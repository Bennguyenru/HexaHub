(ns editor.changes-view
  (:require [clojure.java.io :as io]
            [dynamo.graph :as g]
            [editor.core :as core]
            [editor.diff-view :as diff-view]
            [editor.git :as git]
            [editor.handler :as handler]
            [editor.ui :as ui]
            [editor.workspace :as workspace]
            [editor.sync :as sync]
            [service.log :as log])
  (:import [javafx.scene Parent]
           [javafx.scene.control SelectionMode ListView]
           [org.eclipse.jgit.api Git]
           [org.eclipse.jgit.storage.file FileRepositoryBuilder]))

(set! *warn-on-reflection* true)

(def short-status {:add "A" :modify "M" :delete "D" :rename "R"})

(defn refresh! [git list-view]
  (ui/items! list-view (git/unified-status git)))

(defn- status-render [status]
  {:text (format "[%s]%s" ((:change-type status) short-status) (or (:new-path status) (:old-path status)))})

(ui/extend-menu ::changes-menu nil
                [{:label "Diff"
                  :command :diff}
                 {:label "Revert"
                  :icon "icons/site_backup_and_restore.png"
                  :command :revert}])

(handler/defhandler :revert :asset-browser
  (enabled? [selection]
            (pos? (count selection)))
  (run [selection git list-view workspace]
       (doseq [status selection]
         (git/revert git [(or (:new-path status) (:old-path status))]))
       (refresh! git list-view)
       (workspace/resource-sync! workspace)))

(handler/defhandler :diff :asset-browser
  (enabled? [selection]
            (and (= 1 (count selection))
                 (not= :add (:change-type (first selection)))))
  (run [selection ^Git git list-view]
       (let [status (first selection)
             old-name (or (:old-path status) (:new-path status) )
             new-name (or (:new-path status) (:old-path status) )
             work-tree (.getWorkTree (.getRepository git))
             old (String. ^bytes (git/show-file git old-name))
             new (slurp (io/file work-tree new-name))]
         (diff-view/make-diff-viewer old-name old new-name new))))

(handler/defhandler :synchronize :global
  (enabled? [changes-view] true)
  (run [changes-view]
    (let [git   (g/node-value changes-view :git)
          prefs (g/node-value changes-view :prefs)]
      (sync/open-sync-dialog (sync/make-flow git prefs)))))

(ui/extend-menu ::menubar :editor.app-view/open
                [{:label "Synchronize"
                  :id ::synchronize
                  :acc "Shortcut+U"
                  :command :synchronize}])

(g/defnode ChangesView
  (inherits core/Scope)
  (property parent-view g/Any)
  (property git g/Any)
  (property prefs g/Any)

  core/ICreate
  (post-create
   [this basis event]
   (let [{:keys [^Parent parent git workspace]} event
         refresh                      (.lookup parent "#changes-refresh")
         ^ListView list-view          (.lookup parent "#changes")]
     (.setSelectionMode (.getSelectionModel list-view) SelectionMode/MULTIPLE)
     ; TODO: Should we really include both git and list-view in the context. Have to think about this
     (ui/context! list-view :asset-browser {:git git :list-view list-view :workspace workspace} list-view)
     (ui/register-context-menu list-view ::changes-menu)
     (ui/cell-factory! list-view status-render)
     (ui/on-action! refresh (fn [_] (refresh! git list-view)))
     (refresh! git list-view))))

(defn make-changes-view [view-graph workspace prefs parent]
  (let [git     (Git/open (io/file (g/node-value workspace :root)))
        view-id (g/make-node! view-graph ChangesView :parent-view parent :git git :prefs prefs)
        view    (g/node-by-id view-id)]
    ; TODO: try/catch to protect against project without git setup
    ; Show warning/error etc?
    (try
      (core/post-create view (g/now) {:parent parent :git git :workspace workspace})
      view-id
      (catch Exception e
        (log/error :exception e)))))
