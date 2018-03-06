(ns editor.changes-view
  (:require [clojure.java.io :as io]
            [dynamo.graph :as g]
            [editor.client :as client]
            [editor.core :as core]
            [editor.defold-project :as project]
            [editor.dialogs :as dialogs]
            [editor.diff-view :as diff-view]
            [editor.git :as git]
            [editor.handler :as handler]
            [editor.login :as login]
            [editor.progress :as progress]
            [editor.resource :as resource]
            [editor.resource-watch :as resource-watch]
            [editor.sync :as sync]
            [editor.ui :as ui]
            [editor.vcs-status :as vcs-status]
            [editor.workspace :as workspace]
            [service.log :as log])
  (:import [clojure.lang ExceptionInfo]
           [javafx.scene Parent]
           [javafx.scene.control SelectionMode ListView]
           [org.eclipse.jgit.api Git]
           [java.io File]))

(set! *warn-on-reflection* true)

(defn- refresh-list-view! [list-view unified-status]
  (ui/items! list-view unified-status))

(defn refresh! [changes-view]
  (let [list-view (g/node-value changes-view :list-view)
        unconfigured-git (g/node-value changes-view :unconfigured-git)
        refresh-pending (ui/user-data list-view :refresh-pending)
        schedule-refresh (ref nil)]
    (when unconfigured-git
      (dosync
        (ref-set schedule-refresh (not @refresh-pending))
        (ref-set refresh-pending true))
      (when @schedule-refresh
        (future
          (dosync (ref-set refresh-pending false))
          (let [unified-status (git/unified-status unconfigured-git)]
            (ui/run-later (refresh-list-view! list-view unified-status))))))))

(defn refresh-after-resource-sync! [changes-view diff]
  ;; The call to resource-sync! will refresh the changes view if it detected changes,
  ;; but committing a file from the command line will not actually change the file
  ;; as far as resource-sync! is concerned. To ensure the changed files view reflects
  ;; the current Git state, we explicitly refresh the changes view here if the the
  ;; call to resource-sync! would not have already done so.
  (when (resource-watch/empty-diff? diff)
    (refresh! changes-view)))

(defn resource-sync-after-git-change!
  ([changes-view workspace]
   (resource-sync-after-git-change! changes-view workspace []))
  ([changes-view workspace moved-files]
   (resource-sync-after-git-change! changes-view workspace moved-files progress/null-render-progress!))
  ([changes-view workspace moved-files render-progress!]
   (->> (workspace/resource-sync! workspace moved-files render-progress!)
        (refresh-after-resource-sync! changes-view))))

(ui/extend-menu ::changes-menu nil
                [{:label "Open"
                  :icon "icons/32/Icons_S_14_linkarrow.png"
                  :command :open}
                 {:label "Open As"
                  :icon "icons/32/Icons_S_14_linkarrow.png"
                  :command :open-as}
                 {:label "Show in Asset Browser"
                  :icon "icons/32/Icons_S_14_linkarrow.png"
                  :command :show-in-asset-browser}
                 {:label "Show in Desktop"
                  :icon "icons/32/Icons_S_14_linkarrow.png"
                  :command :show-in-desktop}
                 {:label "Copy Project Path"
                  :command :copy-project-path}
                 {:label "Copy Full Path"
                  :command :copy-full-path}
                 {:label "Referencing Files..."
                  :command :referencing-files}
                 {:label "Dependencies..."
                  :command :dependencies}
                 {:label "Hot Reload"
                  :command :hot-reload}
                 {:label :separator}
                 {:label "View Diff"
                  :icon "icons/32/Icons_S_06_arrowup.png"
                  :command :diff}
                 {:label "Revert"
                  :icon "icons/32/Icons_S_02_Reset.png"
                  :command :revert}])

(defn- path->file [workspace ^String path]
  (File. ^File (workspace/project-path workspace) path))

(handler/defhandler :revert :changes-view
  (enabled? [selection]
            (pos? (count selection)))
  (run [selection git changes-view workspace]
    (let [moved-files (mapv #(vector (path->file workspace (:new-path %)) (path->file workspace (:old-path %))) (filter #(= (:change-type %) :rename) selection))]
      (git/revert git (mapv (fn [status] (or (:new-path status) (:old-path status))) selection))
      (resource-sync-after-git-change! changes-view workspace moved-files))))

(handler/defhandler :diff :changes-view
  (enabled? [selection]
            (git/selection-diffable? selection))
  (run [selection ^Git git]
       (diff-view/present-diff-data (git/selection-diff-data git selection))))

(defn- upload-project! [changes-view workspace project]
  (try
    (let [prefs (g/node-value changes-view :prefs)]
      (when (login/login prefs)
        (let [client (client/make-client prefs)]
          (when-let [user-id (client/user-id client)]
            (try
              (let [project-title (project/project-title project)
                    repo-url (:repository-url (client/new-project client user-id {:name project-title}))
                    proj-path (.getPath (workspace/project-path workspace))
                    creds (git/credentials prefs)]
                (when-let [git (git/init proj-path)]
                  (doto git
                    (git/stage-change! (git/make-add-change "."))
                    (git/commit "Initial commit")
                    (git/config-remote! repo-url)
                    (git/push creds))
                  (do
                    (g/set-property! changes-view :unconfigured-git git))))
              (catch ExceptionInfo e
                (let [message (if (= 403 (:status (ex-data e)))
                                "You are not authorized to create a new project on the dashboard."
                                "Failed to create a new project on the dashboard.")]
                  {:err e
                   :message message})))))))
    (catch Exception e
      {:err e :message "Failed to create a new project on the dashboard."})))

(handler/defhandler :synchronize :global
  (run [changes-view workspace project]
    (if (not (g/node-value changes-view :unconfigured-git))
      (upload-project! changes-view workspace project)
      (let [git   (g/node-value changes-view :git)
            prefs (g/node-value changes-view :prefs)]
        ;; Check if there are locked files below the project folder before proceeding.
        ;; If so, we abort the sync and notify the user, since this could cause problems.
        (loop []
          (if-some [locked-files (not-empty (git/locked-files git))]
            ;; Found locked files below the project. Notify user and offer to retry.
            (when (dialogs/make-confirm-dialog (git/locked-files-error-message locked-files)
                                               {:title "Not Safe to Sync"
                                                :ok-label "Retry"
                                                :cancel-label "Cancel"})
              (recur))

            ;; Found no locked files.
            ;; Save the project before we initiate the sync process. We need to do this because
            ;; the unsaved files may also have changed on the server, and we'll need to merge.
            (do (project/save-all! project)
                (when (login/login prefs)
                  (let [creds (git/credentials prefs)
                        flow (sync/begin-flow! git creds)]
                    (sync/open-sync-dialog flow)
                    (resource-sync-after-git-change! changes-view workspace))))))))))

(ui/extend-menu ::menubar :editor.app-view/open
                [{:label "Synchronize..."
                  :id ::synchronize
                  :command :synchronize}])

(g/defnode ChangesView
  (inherits core/Scope)
  (property list-view g/Any)
  (property unconfigured-git g/Any)
  (output git g/Any (g/fnk [unconfigured-git prefs]
                      (when unconfigured-git
                        (doto unconfigured-git
                          (git/ensure-user-configured! prefs)))))
  (property prefs g/Any))

(defn- status->resource [workspace status]
  (workspace/resolve-workspace-resource workspace (format "/%s" (or (:new-path status)
                                                                    (:old-path status)))))

(defn make-changes-view [view-graph workspace prefs ^Parent parent]
  (let [^ListView list-view (.lookup parent "#changes")
        diff-button         (.lookup parent "#changes-diff")
        revert-button       (.lookup parent "#changes-revert")
        git                 (try (Git/open (io/file (g/node-value workspace :root)))
                                 (catch Exception _))
        view-id             (g/make-node! view-graph ChangesView :list-view list-view :unconfigured-git git :prefs prefs)]
    ; TODO: try/catch to protect against project without git setup
    ; Show warning/error etc?
    (try
      (ui/user-data! list-view :refresh-pending (ref false))
      (.setSelectionMode (.getSelectionModel list-view) SelectionMode/MULTIPLE)
      (ui/context! parent :changes-view {:changes-view view-id :workspace workspace} (ui/->selection-provider list-view)
        {:git [:changes-view :unconfigured-git]}
        {resource/Resource (fn [status] (status->resource workspace status))})
      (ui/register-context-menu list-view ::changes-menu)
      (ui/cell-factory! list-view vcs-status/render)
      (ui/bind-action! diff-button :diff)
      (ui/bind-action! revert-button :revert)
      (ui/disable! diff-button true)
      (ui/disable! revert-button true)
      (ui/bind-double-click! list-view :open)
      (when git (refresh-list-view! list-view (git/unified-status git)))
      (ui/observe-selection list-view
                            (fn [_ _]
                              (ui/refresh-bound-action-enabled! diff-button)
                              (ui/refresh-bound-action-enabled! revert-button)))
      view-id
      (catch Exception e
        (log/error :exception e)))))
