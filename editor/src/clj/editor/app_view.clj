(ns editor.app-view
  (:require [clojure.java.io :as io]
            [clojure.string :as string]
            [clojure.set :as set]
            [dynamo.graph :as g]
            [editor.bundle :as bundle]
            [editor.bundle-dialog :as bundle-dialog]
            [editor.changes-view :as changes-view]
            [editor.console :as console]
            [editor.debug-view :as debug-view]
            [editor.dialogs :as dialogs]
            [editor.engine :as engine]
            [editor.fs :as fs]
            [editor.handler :as handler]
            [editor.jfx :as jfx]
            [editor.login :as login]
            [editor.defold-project :as project]
            [editor.github :as github]
            [editor.engine.build-errors :as engine-build-errors]
            [editor.error-reporting :as error-reporting]
            [editor.pipeline.bob :as bob]
            [editor.placeholder-resource :as placeholder-resource]
            [editor.prefs :as prefs]
            [editor.prefs-dialog :as prefs-dialog]
            [editor.progress :as progress]
            [editor.ui :as ui]
            [editor.workspace :as workspace]
            [editor.resource :as resource]
            [editor.graph-util :as gu]
            [editor.util :as util]
            [editor.keymap :as keymap]
            [editor.search-results-view :as search-results-view]
            [editor.targets :as targets]
            [editor.build-errors-view :as build-errors-view]
            [editor.hot-reload :as hot-reload]
            [editor.url :as url]
            [editor.view :as view]
            [editor.system :as system]
            [service.log :as log]
            [internal.util :refer [first-where]]
            [util.profiler :as profiler]
            [util.http-server :as http-server]
            [editor.scene :as scene]
            [editor.live-update-settings :as live-update-settings]
            [editor.disk :as disk]
            [editor.disk-availability :as disk-availability]
            [editor.scene-visibility :as scene-visibility]
            [editor.scene-cache :as scene-cache])
  (:import [com.defold.control TabPaneBehavior]
           [com.defold.editor Editor EditorApplication]
           [com.defold.editor Start]
           [java.net URI Socket]
           [java.util Collection]
           [javafx.application Platform]
           [javafx.beans.value ChangeListener]
           [javafx.collections FXCollections ObservableList ListChangeListener]
           [javafx.embed.swing SwingFXUtils]
           [javafx.event ActionEvent Event EventHandler]
           [javafx.fxml FXMLLoader]
           [javafx.geometry Insets Pos]
           [javafx.scene Scene Node Parent]
           [javafx.scene.control Label MenuBar SplitPane Tab TabPane TabPane$TabClosingPolicy ProgressBar Tooltip]
           [javafx.scene.image Image ImageView WritableImage PixelWriter]
           [javafx.scene.input Clipboard ClipboardContent KeyEvent]
           [javafx.scene.layout AnchorPane HBox StackPane]
           [javafx.scene.shape Ellipse SVGPath]
           [javafx.stage Screen Stage FileChooser WindowEvent]
           [javafx.util Callback]
           [java.io InputStream File IOException BufferedReader]
           [java.util.prefs Preferences]
           [com.jogamp.opengl GL GL2 GLContext GLProfile GLDrawableFactory GLCapabilities]))

(set! *warn-on-reflection* true)

(defn- fire-tab-closed-event! [^Tab tab]
  ;; TODO: Workaround as there's currently no API to close tabs programatically with identical semantics to close manually
  ;; See http://stackoverflow.com/questions/17047000/javafx-closing-a-tab-in-tabpane-dynamically
  (Event/fireEvent tab (Event. Tab/CLOSED_EVENT)))

(defn- remove-tab! [^TabPane tab-pane ^Tab tab]
  (fire-tab-closed-event! tab)
  (.remove (.getTabs tab-pane) tab))

(defn remove-invalid-tabs! [tab-panes open-views]
  (let [invalid-tab? (fn [tab] (nil? (get open-views (ui/user-data tab ::view))))
        closed-tabs-by-tab-pane (into []
                                      (keep (fn [^TabPane tab-pane]
                                              (when-some [closed-tabs (not-empty (filterv invalid-tab? (.getTabs tab-pane)))]
                                                [tab-pane closed-tabs])))
                                      tab-panes)]
    ;; We must remove all invalid tabs from a TabPane in one go to ensure
    ;; the selected tab change event does not trigger onto an invalid tab.
    (when (seq closed-tabs-by-tab-pane)
      (doseq [[^TabPane tab-pane ^Collection closed-tabs] closed-tabs-by-tab-pane]
        (doseq [tab closed-tabs]
          (fire-tab-closed-event! tab))
        (.removeAll (.getTabs tab-pane) closed-tabs)))))

(g/defnode AppView
  (property stage Stage)
  (property editor-tabs-split SplitPane)
  (property active-tab-pane TabPane)
  (property tool-tab-pane TabPane)
  (property auto-pulls g/Any)
  (property active-tool g/Keyword)
  (property manip-space g/Keyword)

  (property visibility-filters-enabled g/Any)
  (property hidden-renderable-tags g/Any)

  (input open-views g/Any :array)
  (input open-dirty-views g/Any :array)
  (input scene-view-ids g/Any :array)
  (input outline g/Any)
  (input project-id g/NodeID)
  (input selected-node-ids-by-resource-node g/Any)
  (input selected-node-properties-by-resource-node g/Any)
  (input sub-selections-by-resource-node g/Any)
  (input debugger-execution-locations g/Any)

  (output open-views g/Any :cached (g/fnk [open-views] (into {} open-views)))
  (output open-dirty-views g/Any :cached (g/fnk [open-dirty-views] (into #{} (keep #(when (second %) (first %))) open-dirty-views)))
  (output active-tab Tab (g/fnk [^TabPane active-tab-pane] (some-> active-tab-pane ui/selected-tab)))
  (output active-outline g/Any (gu/passthrough outline))
  (output active-view g/NodeID (g/fnk [^Tab active-tab]
                                   (when active-tab
                                     (ui/user-data active-tab ::view))))
  (output active-view-info g/Any (g/fnk [^Tab active-tab]
                                        (when active-tab
                                          {:view-id (ui/user-data active-tab ::view)
                                           :view-type (ui/user-data active-tab ::view-type)})))

  (output effective-hidden-renderable-tags g/Any :cached (g/fnk [visibility-filters-enabled hidden-renderable-tags]
                                                                (if visibility-filters-enabled
                                                                  hidden-renderable-tags
                                                                  (set/intersection hidden-renderable-tags #{:outline :grid}))))
  
  (output active-resource-node g/NodeID :cached (g/fnk [active-view open-views] (:resource-node (get open-views active-view))))
  (output active-resource resource/Resource :cached (g/fnk [active-view open-views] (:resource (get open-views active-view))))
  (output open-resource-nodes g/Any :cached (g/fnk [open-views] (->> open-views vals (map :resource-node))))
  (output selected-node-ids g/Any (g/fnk [selected-node-ids-by-resource-node active-resource-node]
                                    (get selected-node-ids-by-resource-node active-resource-node)))
  (output selected-node-properties g/Any (g/fnk [selected-node-properties-by-resource-node active-resource-node]
                                           (get selected-node-properties-by-resource-node active-resource-node)))
  (output sub-selection g/Any (g/fnk [sub-selections-by-resource-node active-resource-node]
                                (get sub-selections-by-resource-node active-resource-node)))
  (output refresh-tab-panes g/Any :cached (g/fnk [^SplitPane editor-tabs-split open-views open-dirty-views]
                                            (let [tab-panes (.getItems editor-tabs-split)]
                                              (doseq [^TabPane tab-pane tab-panes
                                                      ^Tab tab (.getTabs tab-pane)
                                                      :let [view (ui/user-data tab ::view)
                                                            resource-name (resource/resource-name (:resource (get open-views view)))
                                                            title (if (contains? open-dirty-views view)
                                                                    (str "*" resource-name)
                                                                    resource-name)]]
                                                (ui/text! tab title)))))
  (output keymap g/Any :cached (g/fnk []
                                 (keymap/make-keymap keymap/default-key-bindings {:valid-command? (set (handler/available-commands))})))
  (output debugger-execution-locations g/Any (gu/passthrough debugger-execution-locations)))

(defn- selection->openable-resources [selection]
  (when-let [resources (handler/adapt-every selection resource/Resource)]
    (filterv resource/openable-resource? resources)))

(defn- selection->single-openable-resource [selection]
  (when-let [r (handler/adapt-single selection resource/Resource)]
    (when (resource/openable-resource? r)
      r)))

(defn- selection->single-resource [selection]
  (handler/adapt-single selection resource/Resource))

(defn- context-resource-file [app-view selection]
  (or (selection->single-openable-resource selection)
      (g/node-value app-view :active-resource)))

(defn- context-resource [app-view selection]
  (or (selection->single-resource selection)
      (g/node-value app-view :active-resource)))

(defn- disconnect-sources [target-node target-label]
  (for [[source-node source-label] (g/sources-of target-node target-label)]
    (g/disconnect source-node source-label target-node target-label)))

(defn- replace-connection [source-node source-label target-node target-label]
  (concat
    (disconnect-sources target-node target-label)
    (if (and source-node (contains? (-> source-node g/node-type* g/output-labels) source-label))
      (g/connect source-node source-label target-node target-label)
      [])))

(defn- on-selected-tab-changed! [app-view app-scene resource-node]
  (g/transact
    (replace-connection resource-node :node-outline app-view :outline))
  (g/invalidate-outputs! [[app-view :active-tab]])
  (ui/user-data! app-scene ::ui/refresh-requested? true))

(handler/defhandler :move-tool :workbench
  (enabled? [app-view] true)
  (run [app-view] (g/transact (g/set-property app-view :active-tool :move)))
  (state [app-view] (= (g/node-value app-view :active-tool) :move)))

(handler/defhandler :scale-tool :workbench
  (enabled? [app-view] true)
  (run [app-view] (g/transact (g/set-property app-view :active-tool :scale)))
  (state [app-view]  (= (g/node-value app-view :active-tool) :scale)))

(handler/defhandler :rotate-tool :workbench
  (enabled? [app-view] true)
  (run [app-view] (g/transact (g/set-property app-view :active-tool :rotate)))
  (state [app-view]  (= (g/node-value app-view :active-tool) :rotate)))

(handler/defhandler :show-visibility-settings :workbench
  (run [app-view]
    (when-let [btn (some-> ^TabPane (g/node-value app-view :active-tab-pane)
                           (ui/selected-tab)
                           (.. getContent (lookup "#show-visibility-settings")))]
      (scene-visibility/show-visibility-settings btn app-view)))
  (state [app-view]
    (when-let [btn (some-> ^TabPane (g/node-value app-view :active-tab-pane)
                           (ui/selected-tab)
                           (.. getContent (lookup "#show-visibility-settings")))]
      ;; TODO: We have no mechanism for updating the style nor icon on
      ;; on the toolbar button. For now we piggyback on the state
      ;; update polling to set a style when the filters are active.
      (let [visibility-filters-enabled? (g/node-value app-view :visibility-filters-enabled)
            hidden-renderable-tags (g/node-value app-view :hidden-renderable-tags)
            filters-active? (and visibility-filters-enabled? (some scene-visibility/toggleable-tags hidden-renderable-tags))]
        (if filters-active?
          (ui/add-style! btn "filters-active")
          (ui/remove-style! btn "filters-active")))
      (scene-visibility/settings-visible? btn))))

(def ^:private eye-icon-template (ui/load-svg-path "scene/images/eye_icon_eye_arrow.svg"))

(defn- make-visibility-settings-graphic []
  (doto (StackPane.)
    (ui/children! [(doto (SVGPath.)
                     (.setId "eye-icon")
                     (.setContent (.getContent ^SVGPath eye-icon-template)))
                   (doto (Ellipse. 3.0 3.0)
                     (.setId "active-indicator"))])))

(ui/extend-menu :toolbar nil
                [{:id :select
                  :icon "icons/45/Icons_T_01_Select.png"
                  :command :select-tool}
                 {:id :move
                  :icon "icons/45/Icons_T_02_Move.png"
                  :command :move-tool}
                 {:id :rotate
                  :icon "icons/45/Icons_T_03_Rotate.png"
                  :command :rotate-tool}
                 {:id :scale
                  :icon "icons/45/Icons_T_04_Scale.png"
                  :command :scale-tool}
                 {:id :visibility-settings
                  :graphic-fn make-visibility-settings-graphic
                  :command :show-visibility-settings}])

(def ^:const prefs-window-dimensions "window-dimensions")
(def ^:const prefs-split-positions "split-positions")

(handler/defhandler :quit :global
  (enabled? [] true)
  (run []
    (let [^Stage main-stage (ui/main-stage)]
      (.fireEvent main-stage (WindowEvent. main-stage WindowEvent/WINDOW_CLOSE_REQUEST)))))

(defn store-window-dimensions [^Stage stage prefs]
  (let [dims    {:x           (.getX stage)
                 :y           (.getY stage)
                 :width       (.getWidth stage)
                 :height      (.getHeight stage)
                 :maximized   (.isMaximized stage)
                 :full-screen (.isFullScreen stage)}]
    (prefs/set-prefs prefs prefs-window-dimensions dims)))

(defn restore-window-dimensions [^Stage stage prefs]
  (when-let [dims (prefs/get-prefs prefs prefs-window-dimensions nil)]
    (let [{:keys [x y width height maximized full-screen]} dims]
      (when (and (number? x) (number? y) (number? width) (number? height))
        (when-let [screens (seq (Screen/getScreensForRectangle x y width height))]
          (doto stage
            (.setX x)
            (.setY y))
          ;; Maximized and setWidth/setHeight in combination triggers a bug on macOS where the window becomes invisible
          (when (and (not maximized) (not full-screen))
            (doto stage
              (.setWidth width)
              (.setHeight height)))))
      (when maximized
        (.setMaximized stage true))
      (when full-screen
        (.setFullScreen stage true)))))

(def ^:private legacy-split-ids ["main-split"
                                 "center-split"
                                 "right-split"
                                 "assets-split"])

(def ^:private split-ids ["main-split"
                          "workbench-split"
                          "center-split"
                          "right-split"
                          "assets-split"])

(defn store-split-positions! [^Stage stage prefs]
  (let [^Node root (.getRoot (.getScene stage))
        controls (ui/collect-controls root split-ids)
        div-pos (into {} (map (fn [[id ^SplitPane sp]] [id (.getDividerPositions sp)]) controls))]
    (prefs/set-prefs prefs prefs-split-positions div-pos)))

(defn restore-split-positions! [^Stage stage prefs]
  (when-let [div-pos (prefs/get-prefs prefs prefs-split-positions nil)]
    (let [^Node root (.getRoot (.getScene stage))
          controls (ui/collect-controls root split-ids)
          div-pos (if (vector? div-pos)
                    (zipmap (map keyword legacy-split-ids) div-pos)
                    div-pos)]
      (doseq [[k pos] div-pos
              :let [^SplitPane sp (get controls k)]
              :when sp]
        (.setDividerPositions sp (into-array Double/TYPE pos))
        (.layout sp)))))

(handler/defhandler :open-project :global
  (run [] (when-let [file-name (some-> (ui/choose-file {:title "Open Project"
                                                        :filters [{:description "Project Files"
                                                                   :exts ["*.project"]}]})
                                       (.getAbsolutePath))]
            (EditorApplication/openEditor (into-array String [file-name])))))

(handler/defhandler :logout :global
  (enabled? [prefs] (login/has-token? prefs))
  (run [prefs] (login/logout prefs)))

(handler/defhandler :preferences :global
  (run [workspace prefs]
    (prefs-dialog/open-prefs prefs)
    (workspace/update-build-settings! workspace prefs)))

(defn- collect-resources [{:keys [children] :as resource}]
  (if (empty? children)
    #{resource}
    (set (concat [resource] (mapcat collect-resources children)))))

(defn- get-active-tabs [app-view]
  (let [tab-pane ^TabPane (g/node-value app-view :active-tab-pane)]
    (.getTabs tab-pane)))

(defn- make-render-build-error
  [build-errors-view]
  (fn [errors] (build-errors-view/update-build-errors build-errors-view errors)))

(defn- make-clear-build-errors
  [build-errors-view]
  (fn [] (build-errors-view/clear-build-errors build-errors-view)))

(def ^:private remote-log-pump-thread (atom nil))
(def ^:private console-stream (atom nil))

(defn- reset-remote-log-pump-thread! [^Thread new]
  (when-let [old ^Thread @remote-log-pump-thread]
    (.interrupt old))
  (reset! remote-log-pump-thread new))

(defn- start-log-pump! [log-stream sink-fn]
  (doto (Thread. (fn []
                   (try
                     (let [this (Thread/currentThread)]
                       (with-open [buffered-reader ^BufferedReader (io/reader log-stream :encoding "UTF-8")]
                         (loop []
                           (when-not (.isInterrupted this)
                             (when-let [line (.readLine buffered-reader)] ; line of text or nil if eof reached
                               (sink-fn line)
                               (recur))))))
                     (catch IOException e
                       ;; Losing the log connection is ok and even expected
                       nil)
                     (catch InterruptedException _
                       ;; Losing the log connection is ok and even expected
                       nil))))
    (.start)))

(defn- local-url [target web-server]
  (format "http://%s:%s%s" (:local-address target) (http-server/port web-server) hot-reload/url-prefix))


(def ^:private app-task-progress
  {:main (ref progress/done)
   :build (ref progress/done)
   :resource-sync (ref progress/done)
   :save-all (ref progress/done)
   :fetch-libraries (ref progress/done)})

(def ^:private app-task-ui-priority
  [:save-all :resource-sync :fetch-libraries :build :main])

(def ^:private render-task-progress-ui-inflight (ref false))

(defn- render-task-progress-ui! []
  (let [task-progress-snapshot (ref nil)]
    (dosync
      (ref-set render-task-progress-ui-inflight false)
      (ref-set task-progress-snapshot
               (into {} (map (juxt first (comp deref second))) app-task-progress)))
    (let [status-bar (.. (ui/main-stage) (getScene) (getRoot) (lookup "#status-bar"))
          [key progress] (first (filter (comp (complement progress/done?) second)
                                        (map (juxt identity @task-progress-snapshot)
                                             app-task-ui-priority)))
          show-progress-hbox? (boolean (and (not= key :main)
                                            progress
                                            (not (progress/done? progress))))]
      (ui/with-controls status-bar [progress-bar progress-hbox progress-percentage-label status-label]
        (ui/render-progress-message!
          (if key progress (@task-progress-snapshot :main))
          status-label)

        ;; The bottom right of the status bar can show either the progress-hbox
        ;; or the update-available-label, or both. The progress-hbox will cover
        ;; the update-available-label if both are visible.
        (if-not show-progress-hbox?
          (ui/visible! progress-hbox false)
          (do
            (ui/visible! progress-hbox true)
            (ui/render-progress-bar! progress progress-bar)
            (ui/render-progress-percentage! progress progress-percentage-label)))))))

(defn- render-task-progress! [key progress]
  (let [schedule-render-task-progress-ui (ref false)]
    (dosync
      (ref-set (get app-task-progress key) progress)
      (ref-set schedule-render-task-progress-ui (not @render-task-progress-ui-inflight))
      (ref-set render-task-progress-ui-inflight true))
    (when @schedule-render-task-progress-ui
      (ui/run-later (render-task-progress-ui!)))))

(defn make-render-task-progress [key]
  (assert (contains? app-task-progress key))
  (progress/throttle-render-progress
    (fn [progress] (render-task-progress! key progress))))

(defn render-main-task-progress! [progress]
  (render-task-progress! :main progress))

(defn- report-build-launch-progress! [message]
  (render-main-task-progress! (progress/make message)))

(defn clear-build-launch-progress! []
  (render-main-task-progress! progress/done))

(defn- launch-engine! [project prefs debug?]
  (try
    (report-build-launch-progress! "Launching engine...")
    (let [launched-target (->> (engine/launch! project prefs debug?)
                               (targets/add-launched-target!)
                               (targets/select-target! prefs))]
      (report-build-launch-progress! (format "Launched %s" (targets/target-message-label launched-target)))
      launched-target)
    (catch Exception e
      (targets/kill-launched-targets!)
      (report-build-launch-progress! "Launch failed")
      (throw e))))

(defn- reset-console-stream! [stream]
  (reset! console-stream stream)
  (console/clear-console!))

(defn- make-remote-log-sink [log-stream]
  (fn [line]
    (when (= @console-stream log-stream)
      (console/append-console-line! line))))

(defn- make-launched-log-sink [launched-target]
  (let [initial-output (atom "")]
    (fn [line]
      (when (< (count @initial-output) 5000)
        (swap! initial-output str line "\n")
        (when-let [target-info (engine/parse-launched-target-info @initial-output)]
          (targets/update-launched-target! launched-target target-info)))
      (when (= @console-stream (:log-stream launched-target))
        (console/append-console-line! line)))))

(defn- reboot-engine! [target web-server debug?]
  (try
    (report-build-launch-progress! (format "Rebooting %s..." (targets/target-message-label target)))
    (engine/reboot! target (local-url target web-server) debug?)
    (report-build-launch-progress! (format "Rebooted %s" (targets/target-message-label target)))
    target
    (catch Exception e
      (report-build-launch-progress! "Reboot failed")
      (throw e))))

(def ^:private build-in-progress? (atom false))

(defn- launch-built-project! [project prefs web-server debug? render-error!]
  (let [selected-target (targets/selected-target prefs)]
    (try
      (cond
        (not selected-target)
        (do (targets/kill-launched-targets!)
            (let [launched-target (launch-engine! project prefs debug?)
                  log-stream      (:log-stream launched-target)]
              (reset-console-stream! log-stream)
              (reset-remote-log-pump-thread! nil)
              (start-log-pump! log-stream (make-launched-log-sink launched-target))
              launched-target))

        (not (targets/controllable-target? selected-target))
        (do
          (assert (targets/launched-target? selected-target))
          (targets/kill-launched-targets!)
          (let [launched-target (launch-engine! project prefs debug?)
                log-stream      (:log-stream launched-target)]
            (reset-console-stream! log-stream)
            (reset-remote-log-pump-thread! nil)
            (start-log-pump! log-stream (make-launched-log-sink launched-target))
            launched-target))

        (and (targets/controllable-target? selected-target) (targets/remote-target? selected-target))
        (do
          (let [log-stream (engine/get-log-service-stream selected-target)]
            (reset-console-stream! log-stream)
            (reset-remote-log-pump-thread! (start-log-pump! log-stream (make-remote-log-sink log-stream)))
            (reboot-engine! selected-target web-server debug?)))

        :else
        (do
          (assert (and (targets/controllable-target? selected-target) (targets/launched-target? selected-target)))
          (reset-console-stream! (:log-stream selected-target))
          (reset-remote-log-pump-thread! nil)
          ;; Launched target log pump already
          ;; running to keep engine process
          ;; from halting because stdout/err is
          ;; not consumed.
          (reboot-engine! selected-target web-server debug?)))
      (catch Exception e
        (log/warn :exception e)
        (when-not (engine-build-errors/handle-build-error! render-error! project e)
          (dialogs/make-alert-dialog (format "Launching %s failed: \n%s"
                                             (if (some? selected-target)
                                               (targets/target-message-label selected-target)
                                               "New Local Engine")
                                             (.getMessage e))))))))

(defn- async-build! [project {:keys [debug?] :or {debug? false} :as opts} old-artifact-map result-fn]
  (let [render-build-progress! (make-render-task-progress :build)
        local-system           (g/clone-system)]
    (assert (not @build-in-progress?))
    (reset! build-in-progress? true)
    (future
      (try
        (g/with-system local-system
          (let [evaluation-context  (g/make-evaluation-context)
                extra-build-targets (when debug?
                                      (debug-view/build-targets project evaluation-context))
                build-results       (ui/with-progress [_ render-build-progress!]
                                      (project/build-project! project evaluation-context extra-build-targets old-artifact-map render-build-progress!))]
            (ui/run-later
              (try
                (g/update-cache-from-evaluation-context! evaluation-context)
                (when result-fn (result-fn build-results))
                (finally
                  (reset! build-in-progress? false))))
            build-results))
        (catch Throwable t
          (reset! build-in-progress? false)
          (error-reporting/report-exception! t))))))

(defn- handle-build-results! [workspace build-errors-view build-results]
  (let [{:keys [error artifacts artifact-map etags]} build-results]
    (if (some? error)
      (do
        (build-errors-view/update-build-errors build-errors-view error)
        nil)
      (do
        (workspace/artifact-map! workspace artifact-map)
        (workspace/etags! workspace etags)
        (build-errors-view/clear-build-errors build-errors-view)
        build-results))))


(handler/defhandler :build :global
  (enabled? [] (not @build-in-progress?))
  (run [project workspace prefs web-server build-errors-view console-view debug-view]
    (debug-view/detach! debug-view)
    (async-build! project {:debug? false} (workspace/artifact-map workspace)
                  (fn [build-results]
                    (when (handle-build-results! workspace build-errors-view build-results)
                      (console/show! console-view)
                      (launch-built-project! project prefs web-server false (make-render-build-error build-errors-view)))))))

(defn- debugging-supported?
  [project]
  (if (project/shared-script-state? project)
    true
    (do (dialogs/make-alert-dialog "This project cannot be used with the debugger because it is configured to disable shared script state.

If you do not specifically require different script states, consider changing the script.shared_state property in game.project.")
        false)))

(handler/defhandler :start-debugger :global
  (enabled? [debug-view]
            (and (not @build-in-progress?)
                 (nil? (debug-view/current-session debug-view))))
  (run [project workspace prefs web-server build-errors-view console-view debug-view]
    (when (debugging-supported? project)
      (async-build! project {:debug? true} (workspace/artifact-map workspace)
                    (fn [build-results]
                      (when (handle-build-results! workspace build-errors-view build-results)
                        (when-let [target (launch-built-project! project prefs web-server true (make-render-build-error build-errors-view))]
                          (when (nil? (debug-view/current-session debug-view))
                            (debug-view/start-debugger! debug-view project (:address target "localhost"))))))))))

(handler/defhandler :attach-debugger :global
  (enabled? [debug-view prefs]
            (and (not @build-in-progress?)
                 (nil? (debug-view/current-session debug-view))
                 (let [target (targets/selected-target prefs)]
                   (and target (targets/controllable-target? target)))))
  (run [project workspace build-errors-view debug-view prefs]
    (debug-view/detach! debug-view)
    (when (debugging-supported? project)
      (async-build! project {:debug? true} (workspace/artifact-map workspace)
                    (fn [build-results]
                      (when (handle-build-results! workspace build-errors-view build-results)
                        (let [target (targets/selected-target prefs)]
                          (when (targets/controllable-target? target)
                            (debug-view/attach! debug-view project target (:artifacts build-results))))))))))

(handler/defhandler :rebuild :global
  (enabled? [] (not @build-in-progress?))
  (run [workspace project prefs web-server build-errors-view console-view debug-view]
    (debug-view/detach! debug-view)
    (workspace/reset-cache! workspace)
    (async-build! project {:debug? false} (workspace/artifact-map workspace)
                  (fn [build-results]
                    (when (handle-build-results! workspace build-errors-view build-results)
                      (console/show! console-view)
                      (launch-built-project! project prefs web-server false (make-render-build-error build-errors-view)))))))

(handler/defhandler :build-html5 :global
  (run [project prefs web-server build-errors-view changes-view]
    (let [clear-errors! (make-clear-build-errors build-errors-view)
          render-build-error! (make-render-build-error build-errors-view)
          render-reload-progress! (make-render-task-progress :resource-sync)
          render-save-progress! (make-render-task-progress :save-all)
          render-build-progress! (make-render-task-progress :build)
          bob-args (bob/build-html5-bob-args project prefs)]
      (clear-errors!)
      (console/clear-console!)
      (disk/async-bob-build! render-reload-progress! render-save-progress! render-build-progress!
                             render-build-error! bob/build-html5-bob-commands bob-args project changes-view
                             (fn [successful?]
                               (when successful?
                                 (ui/open-url (format "http://localhost:%d%s/index.html" (http-server/port web-server) bob/html5-url-prefix))))))))


(handler/defhandler :hot-reload :global
  (enabled? [app-view debug-view selection prefs]
            (when-let [resource (context-resource-file app-view selection)]
              (and (resource/exists? resource)
                   (some-> (targets/selected-target prefs)
                           (targets/controllable-target?))
                   (not (debug-view/suspended? debug-view)))))
  (run [project app-view prefs build-errors-view selection]
    (when-let [resource (context-resource-file app-view selection)]
      (ui/->future 0.01
                   (fn []
                     (let [evaluation-context (g/make-evaluation-context)
                           workspace (project/workspace project)
                           old-artifact-map (workspace/artifact-map workspace)
                           {:keys [error artifacts artifact-map etags]} (project/build-project! project
                                                                                                evaluation-context
                                                                                                nil
                                                                                                old-artifact-map
                                                                                                (make-render-task-progress :build))]
                       (g/update-cache-from-evaluation-context! evaluation-context)
                       (if (some? error)
                         (build-errors-view/update-build-errors build-errors-view error)
                         (do
                           (workspace/artifact-map! workspace artifact-map)
                           (workspace/etags! workspace etags)
                           (try
                             (build-errors-view/clear-build-errors build-errors-view)
                             (engine/reload-resource (targets/selected-target prefs) resource)
                             (catch Exception e
                               (dialogs/make-alert-dialog (format "Failed to reload resource on '%s':\n%s" (targets/target-message-label (targets/selected-target prefs)) (.getMessage e)))))))))))))

(handler/defhandler :close :global
  (enabled? [app-view] (not-empty (get-active-tabs app-view)))
  (run [app-view]
    (let [tab-pane (g/node-value app-view :active-tab-pane)]
      (when-let [tab (ui/selected-tab tab-pane)]
        (remove-tab! tab-pane tab)))))

(handler/defhandler :close-other :global
  (enabled? [app-view] (not-empty (next (get-active-tabs app-view))))
  (run [app-view]
    (let [tab-pane ^TabPane (g/node-value app-view :active-tab-pane)]
      (when-let [selected-tab (ui/selected-tab tab-pane)]
        (doseq [tab (.getTabs tab-pane)]
          (when (not= tab selected-tab)
            (remove-tab! tab-pane tab)))))))

(handler/defhandler :close-all :global
  (enabled? [app-view] (not-empty (get-active-tabs app-view)))
  (run [app-view]
    (let [tab-pane ^TabPane (g/node-value app-view :active-tab-pane)]
      (doseq [tab (.getTabs tab-pane)]
        (remove-tab! tab-pane tab)))))

(defn- editor-tab-pane
  "Returns the editor TabPane that is above the Node in the scene hierarchy, or
  nil if the Node does not reside under an editor TabPane."
  ^TabPane [node]
  (when-some [parent-tab-pane (ui/parent-tab-pane node)]
    (when (= "editor-tabs-split" (some-> (ui/tab-pane-parent parent-tab-pane) (.getId)))
      parent-tab-pane)))

(declare ^:private configure-editor-tab-pane!)

(defn- find-other-tab-pane
  ^TabPane [^SplitPane editor-tabs-split ^TabPane current-tab-pane]
  (first-where #(not (identical? current-tab-pane %))
               (.getItems editor-tabs-split)))

(defn- add-other-tab-pane!
  ^TabPane [^SplitPane editor-tabs-split app-view]
  (let [tab-panes (.getItems editor-tabs-split)
        app-stage ^Stage (g/node-value app-view :stage)
        app-scene (.getScene app-stage)
        new-tab-pane (TabPane.)]
    (assert (= 1 (count tab-panes)))
    (.add tab-panes new-tab-pane)
    (configure-editor-tab-pane! new-tab-pane app-scene app-view)
    new-tab-pane))

(defn open-tab-count
  ^long [app-view]
  (let [editor-tabs-split ^SplitPane (g/node-value app-view :editor-tabs-split)]
    (loop [tab-panes (.getItems editor-tabs-split)
           tab-count 0]
      (if-some [^TabPane tab-pane (first tab-panes)]
        (recur (next tab-panes)
               (+ tab-count (.size (.getTabs tab-pane))))
        tab-count))))

(defn open-tab-pane-count
  ^long [app-view]
  (let [editor-tabs-split ^SplitPane (g/node-value app-view :editor-tabs-split)]
    (.size (.getItems editor-tabs-split))))

(handler/defhandler :move-tab :global
  (enabled? [app-view] (< 1 (open-tab-count app-view)))
  (run [app-view user-data]
       (let [editor-tabs-split ^SplitPane (g/node-value app-view :editor-tabs-split)
             source-tab-pane ^TabPane (g/node-value app-view :active-tab-pane)
             selected-tab (ui/selected-tab source-tab-pane)
             dest-tab-pane (or (find-other-tab-pane editor-tabs-split source-tab-pane)
                               (add-other-tab-pane! editor-tabs-split app-view))]
         (.remove (.getTabs source-tab-pane) selected-tab)
         (.add (.getTabs dest-tab-pane) selected-tab)
         (.select (.getSelectionModel dest-tab-pane) selected-tab)
         (.requestFocus dest-tab-pane))))

(handler/defhandler :swap-tabs :global
  (enabled? [app-view] (< 1 (open-tab-pane-count app-view)))
  (run [app-view user-data]
       (let [editor-tabs-split ^SplitPane (g/node-value app-view :editor-tabs-split)
             active-tab-pane ^TabPane (g/node-value app-view :active-tab-pane)
             other-tab-pane (find-other-tab-pane editor-tabs-split active-tab-pane)
             active-tab-pane-selection (.getSelectionModel active-tab-pane)
             other-tab-pane-selection (.getSelectionModel other-tab-pane)
             active-tab-index (.getSelectedIndex active-tab-pane-selection)
             other-tab-index (.getSelectedIndex other-tab-pane-selection)
             active-tabs (.getTabs active-tab-pane)
             other-tabs (.getTabs other-tab-pane)
             active-tab (.get active-tabs active-tab-index)
             other-tab (.get other-tabs other-tab-index)]
         (.set active-tabs active-tab-index other-tab)
         (.set other-tabs other-tab-index active-tab)
         (.select active-tab-pane-selection other-tab)
         (.select other-tab-pane-selection active-tab)
         (.requestFocus other-tab-pane))))

(handler/defhandler :join-tab-panes :global
  (enabled? [app-view] (< 1 (open-tab-pane-count app-view)))
  (run [app-view user-data]
       (let [editor-tabs-split ^SplitPane (g/node-value app-view :editor-tabs-split)
             active-tab-pane ^TabPane (g/node-value app-view :active-tab-pane)
             selected-tab (ui/selected-tab active-tab-pane)
             tab-panes (.getItems editor-tabs-split)
             first-tab-pane ^TabPane (.get tab-panes 0)
             second-tab-pane ^TabPane (.get tab-panes 1)
             first-tabs (.getTabs first-tab-pane)
             second-tabs (.getTabs second-tab-pane)
             moved-tabs (vec second-tabs)]
         (.clear second-tabs)
         (.addAll first-tabs ^Collection moved-tabs)
         (.select (.getSelectionModel first-tab-pane) selected-tab)
         (.requestFocus first-tab-pane))))

(defn make-about-dialog []
  (let [root ^Parent (ui/load-fxml "about.fxml")
        stage (ui/make-dialog-stage)
        scene (Scene. root)
        controls (ui/collect-controls root ["version" "channel" "editor-sha1" "engine-sha1" "time"])]
    (ui/text! (:version controls) (str "Version: " (System/getProperty "defold.version" "NO VERSION")))
    (ui/text! (:channel controls) (str "Channel: " (or (system/defold-channel) "No channel")))
    (ui/text! (:editor-sha1 controls) (or (system/defold-editor-sha1) "No editor sha1"))
    (ui/text! (:engine-sha1 controls) (or (system/defold-engine-sha1) "No engine sha1"))
    (ui/text! (:time controls) (or (system/defold-build-time) "No build time"))
    (ui/title! stage "About")
    (.setScene stage scene)
    (ui/show! stage)))

(handler/defhandler :documentation :global
  (run [] (ui/open-url "https://www.defold.com/learn/")))

(handler/defhandler :support-forum :global
  (run [] (ui/open-url "https://forum.defold.com/")))

(handler/defhandler :report-issue :global
  (run [] (ui/open-url (github/new-issue-link))))

(handler/defhandler :report-praise :global
  (run [] (ui/open-url (github/new-praise-link))))

(handler/defhandler :show-logs :global
  (run [] (ui/open-file (.toFile (Editor/getLogDirectory)))))

(handler/defhandler :about :global
  (run [] (make-about-dialog)))

(handler/defhandler :reload-stylesheet :global
  (run [] (ui/reload-root-styles!)))

(ui/extend-menu ::menubar nil
                [{:label "File"
                  :id ::file
                  :children [{:label "New..."
                              :id ::new
                              :command :new-file}
                             {:label "Open"
                              :id ::open
                              :command :open}
                             {:label "Synchronize..."
                              :id ::synchronize
                              :command :synchronize}
                             {:label "Save All"
                              :id ::save-all
                              :command :save-all}
                             {:label :separator}
                             {:label "Open Assets..."
                              :command :open-asset}
                             {:label "Search in Files..."
                              :command :search-in-files}
                             {:label :separator}
                             {:label "Close"
                              :command :close}
                             {:label "Close All"
                              :command :close-all}
                             {:label "Close Others"
                              :command :close-other}
                             {:label :separator}
                             {:label "Referencing Files..."
                              :command :referencing-files}
                             {:label "Dependencies..."
                              :command :dependencies}
                             {:label "Hot Reload"
                              :command :hot-reload}
                             {:label :separator}
                             {:label "Logout"
                              :command :logout}
                             {:label "Preferences..."
                              :command :preferences}
                             {:label "Quit"
                              :command :quit}]}
                 {:label "Edit"
                  :id ::edit
                  :children [{:label "Undo"
                              :icon "icons/undo.png"
                              :command :undo}
                             {:label "Redo"
                              :icon "icons/redo.png"
                              :command :redo}
                             {:label :separator}
                             {:label "Cut"
                              :command :cut}
                             {:label "Copy"
                              :command :copy}
                             {:label "Paste"
                              :command :paste}
                             {:label "Select All"
                              :command :select-all}
                             {:label "Delete"
                              :icon "icons/32/Icons_M_06_trash.png"
                              :command :delete}
                             {:label :separator}
                             {:label "Move Up"
                              :command :move-up}
                             {:label "Move Down"
                              :command :move-down}
                             {:label :separator
                              :id ::edit-end}]}
                 {:label "View"
                  :id ::view
                  :children [{:label "Show Console"
                              :command :show-console}
                             {:label "Show Curve Editor"
                              :command :show-curve-editor}
                             {:label "Show Build Errors"
                              :command :show-build-errors}
                             {:label "Show Search Results"
                              :command :show-search-results}
                             {:label :separator
                              :id ::view-end}]}
                 {:label "Help"
                  :children [{:label "Profiler"
                              :children [{:label "Measure"
                                          :command :profile}
                                         {:label "Measure and Show"
                                          :command :profile-show}]}
                             {:label "Reload Stylesheet"
                              :command :reload-stylesheet}
                             {:label "Documentation"
                              :command :documentation}
                             {:label "Support Forum"
                              :command :support-forum}
                             {:label "Report Issue"
                              :command :report-issue}
                             {:label "Report Praise"
                              :command :report-praise}
                             {:label "Show Logs"
                              :command :show-logs}
                             {:label "About"
                              :command :about}]}])

(ui/extend-menu ::tab-menu nil
                [{:label "Close"
                  :command :close}
                 {:label "Close Others"
                  :command :close-other}
                 {:label "Close All"
                  :command :close-all}
                 {:label :separator}
                 {:label "Move to Other Tab Pane"
                  :command :move-tab}
                 {:label "Swap With Other Tab Pane"
                  :command :swap-tabs}
                 {:label "Join Tab Panes"
                  :command :join-tab-panes}
                 {:label :separator}
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
                  :command :hot-reload}])

(defrecord SelectionProvider [app-view]
  handler/SelectionProvider
  (selection [this] (g/node-value app-view :selected-node-ids))
  (succeeding-selection [this] [])
  (alt-selection [this] []))

(defn ->selection-provider [app-view] (SelectionProvider. app-view))

(defn- update-selection [s open-resource-nodes active-resource-node selection-value]
  (->> (assoc s active-resource-node selection-value)
    (filter (comp (set open-resource-nodes) first))
    (into {})))

(defn select
  ([app-view node-ids]
    (select app-view (g/node-value app-view :active-resource-node) node-ids))
  ([app-view resource-node node-ids]
    (let [project-id (g/node-value app-view :project-id)
          open-resource-nodes (g/node-value app-view :open-resource-nodes)]
      (project/select project-id resource-node node-ids open-resource-nodes))))

(defn select!
  ([app-view node-ids]
    (select! app-view node-ids (gensym)))
  ([app-view node-ids op-seq]
    (g/transact
      (concat
        (g/operation-sequence op-seq)
        (g/operation-label "Select")
        (select app-view node-ids)))))

(defn sub-select!
  ([app-view sub-selection]
    (sub-select! app-view sub-selection (gensym)))
  ([app-view sub-selection op-seq]
    (let [project-id (g/node-value app-view :project-id)
          active-resource-node (g/node-value app-view :active-resource-node)
          open-resource-nodes (g/node-value app-view :open-resource-nodes)]
      (g/transact
        (concat
          (g/operation-sequence op-seq)
          (g/operation-label "Select")
          (project/sub-select project-id active-resource-node sub-selection open-resource-nodes))))))

(defn- make-title
  ([] "Defold Editor 2.0")
  ([project-title] (str (make-title) " - " project-title)))

(defn- refresh-app-title! [^Stage stage project]
  (let [settings      (g/node-value project :settings)
        project-title (settings ["project" "title"])
        new-title     (make-title project-title)]
    (when (not= (.getTitle stage) new-title)
      (.setTitle stage new-title))))

(defn- refresh-menus-and-toolbars! [app-view ^Scene scene]
  (let [keymap (g/node-value app-view :keymap)
        command->shortcut (keymap/command->shortcut keymap)]
    (ui/user-data! scene :command->shortcut command->shortcut)
    (ui/refresh scene)))

(defn- refresh-views! [app-view]
  (let [auto-pulls (g/node-value app-view :auto-pulls)]
    (doseq [[node label] auto-pulls]
      (profiler/profile "view" (:name @(g/node-type* node))
        (g/node-value node label)))))

(defn- refresh-scene-views! [app-view]
  (profiler/begin-frame)
  (doseq [view-id (g/node-value app-view :scene-view-ids)]
    (try
      (scene/refresh-scene-view! view-id)
      (catch Throwable error
        (error-reporting/report-exception! error))))
  (scene-cache/prune-context! nil))

(defn- dispose-scene-views! [app-view]
  (doseq [view-id (g/node-value app-view :scene-view-ids)]
    (try
      (scene/dispose-scene-view! view-id)
      (catch Throwable error
        (error-reporting/report-exception! error))))
  (scene-cache/drop-context! nil))

(defn- tab->resource-node [^Tab tab]
  (some-> tab
    (ui/user-data ::view)
    (g/node-value :view-data)
    second
    :resource-node))

(defn- configure-editor-tab-pane! [^TabPane tab-pane ^Scene app-scene app-view]
  (.setTabClosingPolicy tab-pane TabPane$TabClosingPolicy/ALL_TABS)
  (-> tab-pane
      (.getSelectionModel)
      (.selectedItemProperty)
      (.addListener
        (reify ChangeListener
          (changed [_this _observable _old-val new-val]
            (on-selected-tab-changed! app-view app-scene (tab->resource-node new-val))))))
  (-> tab-pane
      (.getTabs)
      (.addListener
        (reify ListChangeListener
          (onChanged [_this _change]
            ;; Check if we've ended up with an empty TabPane.
            ;; Unless we are the only one left, we should get rid of it to make room for the other TabPane.
            (when (empty? (.getTabs tab-pane))
              (let [editor-tabs-split ^SplitPane (ui/tab-pane-parent tab-pane)
                    tab-panes (.getItems editor-tabs-split)]
                (when (< 1 (count tab-panes))
                  (.remove tab-panes tab-pane)
                  (.requestFocus ^TabPane (.get tab-panes 0)))))))))

  (ui/register-tab-pane-context-menu tab-pane ::tab-menu)

  ;; Workaround for JavaFX bug: https://bugs.openjdk.java.net/browse/JDK-8167282
  ;; Consume key events that would select non-existing tabs in case we have none.
  (.addEventFilter tab-pane KeyEvent/KEY_PRESSED (ui/event-handler event
                                                   (when (and (empty? (.getTabs tab-pane))
                                                              (TabPaneBehavior/isTraversalEvent event))
                                                     (.consume event)))))

(defn- handle-focus-owner-change! [app-view app-scene new-focus-owner]
  (let [old-editor-tab-pane (g/node-value app-view :active-tab-pane)
        new-editor-tab-pane (editor-tab-pane new-focus-owner)]
    (when (and (some? new-editor-tab-pane)
               (not (identical? old-editor-tab-pane new-editor-tab-pane)))
      (let [selected-tab (ui/selected-tab new-editor-tab-pane)
            resource-node (tab->resource-node selected-tab)]
        (ui/add-style! old-editor-tab-pane "inactive")
        (ui/remove-style! new-editor-tab-pane "inactive")
        (g/set-property! app-view :active-tab-pane new-editor-tab-pane)
        (on-selected-tab-changed! app-view app-scene resource-node)))))

(defn make-app-view [view-graph project ^Stage stage ^MenuBar menu-bar ^SplitPane editor-tabs-split ^TabPane tool-tab-pane]
  (let [app-scene (.getScene stage)]
    (ui/disable-menu-alt-key-mnemonic! menu-bar)
    (.setUseSystemMenuBar menu-bar true)
    (.setTitle stage (make-title))
    (let [editor-tab-pane (TabPane.)
          app-view (first (g/tx-nodes-added (g/transact (g/make-node view-graph AppView
                                                                     :stage stage
                                                                     :editor-tabs-split editor-tabs-split
                                                                     :active-tab-pane editor-tab-pane
                                                                     :tool-tab-pane tool-tab-pane
                                                                     :active-tool :move
                                                                     :manip-space :world
                                                                     :visibility-filters-enabled true
                                                                     :hidden-renderable-tags #{}))))]
      (.add (.getItems editor-tabs-split) editor-tab-pane)
      (configure-editor-tab-pane! editor-tab-pane app-scene app-view)
      (ui/observe (.focusOwnerProperty app-scene)
                  (fn [_ _ new-focus-owner]
                    (handle-focus-owner-change! app-view app-scene new-focus-owner)))

      (ui/register-menubar app-scene menu-bar ::menubar)

      (keymap/install-key-bindings! (.getScene stage) (g/node-value app-view :keymap))

      (let [refresh-tick (java.util.concurrent.atomic.AtomicInteger. 0)
            refresh-timer (ui/->timer "refresh-app-view"
                                      (fn [_ _]
                                        (when-not (ui/ui-disabled?)
                                          (let [refresh-requested? (ui/user-data app-scene ::ui/refresh-requested?)
                                                tick (.getAndIncrement refresh-tick)]
                                            (when refresh-requested?
                                              (ui/user-data! app-scene ::ui/refresh-requested? false))
                                            (when (or refresh-requested? (zero? (mod tick 20)))
                                              (refresh-menus-and-toolbars! app-view app-scene))
                                            (when (or refresh-requested? (zero? (mod tick 5)))
                                              (refresh-views! app-view))
                                            (refresh-scene-views! app-view)
                                            (refresh-app-title! stage project)))))]
        (ui/timer-stop-on-closed! stage refresh-timer)
        (ui/timer-start! refresh-timer))
      (ui/on-closed! stage (fn [_] (dispose-scene-views! app-view)))
      app-view)))

(defn- make-tab! [app-view prefs workspace project resource resource-node
                  resource-type view-type make-view-fn ^ObservableList tabs opts]
  (let [parent     (AnchorPane.)
        tab        (doto (Tab. (resource/resource-name resource))
                     (.setContent parent)
                     (.setTooltip (Tooltip. (or (resource/proj-path resource) "unknown")))
                     (ui/user-data! ::view-type view-type))
        view-graph (g/make-graph! :history false :volatility 2)
        select-fn  (partial select app-view)
        opts       (merge opts
                          (get (:view-opts resource-type) (:id view-type))
                          {:app-view  app-view
                           :select-fn select-fn
                           :prefs     prefs
                           :project   project
                           :workspace workspace
                           :tab       tab})
        view       (make-view-fn view-graph parent resource-node opts)]
    (assert (g/node-instance? view/WorkbenchView view))
    (g/transact
      (concat
        (view/connect-resource-node view resource-node)
        (g/connect view :view-data app-view :open-views)
        (g/connect view :view-dirty? app-view :open-dirty-views)))
    (ui/user-data! tab ::view view)
    (.add tabs tab)
    (g/transact
      (select app-view resource-node [resource-node]))
    (.setGraphic tab (jfx/get-image-view (or (:icon resource-type) "icons/64/Icons_29-AT-Unknown.png") 16))
    (.addAll (.getStyleClass tab) ^Collection (resource/style-classes resource))
    (ui/register-tab-toolbar tab "#toolbar" :toolbar)
    (let [close-handler (.getOnClosed tab)]
      (.setOnClosed tab (ui/event-handler
                         event
                         (doto tab
                           (ui/user-data! ::view-type nil)
                           (ui/user-data! ::view nil))
                         (g/delete-graph! view-graph)
                         (when close-handler
                           (.handle close-handler event)))))
    tab))

(defn- substitute-args [tmpl args]
  (reduce (fn [tmpl [key val]]
            (string/replace tmpl (format "{%s}" (name key)) (str val)))
    tmpl args))

(defn- defective-resource-node? [resource-node]
  (let [value (g/node-value resource-node :valid-node-id+resource)]
    (and (g/error? value)
         (g/error-fatal? value))))

(defn open-resource
  ([app-view prefs workspace project resource]
   (open-resource app-view prefs workspace project resource {}))
  ([app-view prefs workspace project resource opts]
   (let [resource-type  (resource/resource-type resource)
         resource-node  (or (project/get-resource-node project resource)
                            (throw (ex-info (format "No resource node found for resource '%s'" (resource/proj-path resource))
                                            {})))
         text-view-type (workspace/get-view-type workspace :text)
         view-type      (or (:selected-view-type opts)
                            (if (nil? resource-type)
                              (placeholder-resource/view-type workspace)
                              (first (:view-types resource-type)))
                            text-view-type)]
     (if (defective-resource-node? resource-node)
       (do (dialogs/make-alert-dialog (format "Unable to open '%s', since it appears damaged." (resource/proj-path resource)))
           false)
       (if-let [custom-editor (and (#{:code :text} (:id view-type))
                                   (let [ed-pref (some->
                                                   (prefs/get-prefs prefs "code-custom-editor" "")
                                                   string/trim)]
                                     (and (not (string/blank? ed-pref)) ed-pref)))]
         (let [arg-tmpl (string/trim (if (:line opts) (prefs/get-prefs prefs "code-open-file-at-line" "{file}:{line}") (prefs/get-prefs prefs "code-open-file" "{file}")))
               arg-sub (cond-> {:file (resource/abs-path resource)}
                               (:line opts) (assoc :line (:line opts)))
               args (->> (string/split arg-tmpl #" ")
                         (map #(substitute-args % arg-sub)))]
           (doto (ProcessBuilder. ^java.util.List (cons custom-editor args))
             (.directory (workspace/project-path workspace))
             (.start))
           false)
         (if (contains? view-type :make-view-fn)
           (let [^SplitPane editor-tabs-split (g/node-value app-view :editor-tabs-split)
                 tab-panes (.getItems editor-tabs-split)
                 open-tabs (mapcat #(.getTabs ^TabPane %) tab-panes)
                 view-type (if (g/node-value resource-node :editable?) view-type text-view-type)
                 make-view-fn (:make-view-fn view-type)
                 ^Tab tab (or (some #(when (and (= (tab->resource-node %) resource-node)
                                                (= view-type (ui/user-data % ::view-type)))
                                       %)
                                    open-tabs)
                              (let [^TabPane active-tab-pane (g/node-value app-view :active-tab-pane)
                                    active-tab-pane-tabs (.getTabs active-tab-pane)]
                                (make-tab! app-view prefs workspace project resource resource-node
                                           resource-type view-type make-view-fn active-tab-pane-tabs opts)))]
             (.select (.getSelectionModel (.getTabPane tab)) tab)
             (when-let [focus (:focus-fn view-type)]
               (ui/run-later
                 ;; We run-later so javafx has time to squeeze in a
                 ;; layout pass. The focus function of some views
                 ;; needs proper width + height (f.i. code view for
                 ;; scrolling to selected line).
                 (focus (ui/user-data tab ::view) opts)))
             true)
           (let [^String path (or (resource/abs-path resource)
                                  (resource/temp-path resource))
                 ^File f (File. path)]
             (ui/open-file f (fn [msg]
                               (let [lines [(format "Could not open '%s'." (.getName f))
                                            "This can happen if the file type is not mapped to an application in your OS."
                                            "Underlying error from the OS:"
                                            msg]]
                                 (ui/run-later (dialogs/make-alert-dialog (string/join "\n" lines))))))
             false)))))))

(handler/defhandler :open :global
  (active? [selection user-data] (:resources user-data (not-empty (selection->openable-resources selection))))
  (enabled? [selection user-data] (some resource/exists? (:resources user-data (selection->openable-resources selection))))
  (run [selection app-view prefs workspace project user-data]
       (doseq [resource (filter resource/exists? (:resources user-data (selection->openable-resources selection)))]
         (open-resource app-view prefs workspace project resource))))

(handler/defhandler :open-as :global
  (active? [selection] (selection->single-openable-resource selection))
  (enabled? [selection user-data] (resource/exists? (selection->single-openable-resource selection)))
  (run [selection app-view prefs workspace project user-data]
       (let [resource (selection->single-openable-resource selection)]
         (open-resource app-view prefs workspace project resource (when-let [view-type (:selected-view-type user-data)]
                                                                    {:selected-view-type view-type}))))
  (options [workspace selection user-data]
           (when-not user-data
             (let [resource (selection->single-openable-resource selection)
                   resource-type (resource/resource-type resource)]
               (map (fn [vt]
                      {:label     (or (:label vt) "External Editor")
                       :command   :open-as
                       :user-data {:selected-view-type vt}})
                    (:view-types resource-type))))))

(handler/defhandler :synchronize :global
  (enabled? [] (disk-availability/available?))
  (run [changes-view project workspace]
       (let [render-reload-progress! (make-render-task-progress :resource-sync)
             render-save-progress! (make-render-task-progress :save-all)]
         (if (changes-view/project-is-git-repo? changes-view)

           ;; The project is a Git repo. Assume the project is hosted by us.
           ;; Check if there are locked files below the project folder before proceeding.
           ;; If so, we abort the sync and notify the user, since this could cause problems.
           (when (changes-view/ensure-no-locked-files! changes-view)
             (disk/async-save! render-reload-progress! render-save-progress! project changes-view
                               (fn [successful?]
                                 (when successful?
                                   (when (changes-view/regular-sync! changes-view)
                                     (disk/async-reload! render-reload-progress! workspace [] changes-view))))))

           ;; The project is not a Git repo. Offer to push it to our servers.
           (disk/async-save! render-reload-progress! render-save-progress! project changes-view
                             (fn [successful?]
                               (when successful?
                                 (changes-view/first-sync! changes-view project))))))))

(handler/defhandler :save-all :global
  (enabled? [] (not (bob/build-in-progress?)))
  (run [app-view changes-view project]
       (let [render-reload-progress! (make-render-task-progress :resource-sync)
             render-save-progress! (make-render-task-progress :save-all)]
         (disk/async-save! render-reload-progress! render-save-progress! project changes-view))))

(handler/defhandler :show-in-desktop :global
  (active? [app-view selection] (context-resource app-view selection))
  (enabled? [app-view selection] (when-let [r (context-resource app-view selection)]
                                   (and (resource/abs-path r)
                                        (resource/exists? r))))
  (run [app-view selection] (when-let [r (context-resource app-view selection)]
                              (let [f (File. (resource/abs-path r))]
                                (ui/open-file (fs/to-folder f))))))

(handler/defhandler :referencing-files :global
  (active? [app-view selection] (context-resource-file app-view selection))
  (enabled? [app-view selection] (when-let [r (context-resource-file app-view selection)]
                                   (and (resource/abs-path r)
                                        (resource/exists? r))))
  (run [selection app-view prefs workspace project] (when-let [r (context-resource-file app-view selection)]
                                                      (doseq [resource (dialogs/make-resource-dialog workspace project {:title "Referencing Files" :selection :multiple :ok-label "Open" :filter (format "refs:%s" (resource/proj-path r))})]
                                                        (open-resource app-view prefs workspace project resource)))))

(handler/defhandler :dependencies :global
  (active? [app-view selection] (context-resource-file app-view selection))
  (enabled? [app-view selection] (when-let [r (context-resource-file app-view selection)]
                                   (and (resource/abs-path r)
                                        (resource/exists? r))))
  (run [selection app-view prefs workspace project] (when-let [r (context-resource-file app-view selection)]
                                                      (doseq [resource (dialogs/make-resource-dialog workspace project {:title "Dependencies" :selection :multiple :ok-label "Open" :filter (format "deps:%s" (resource/proj-path r))})]
                                                        (open-resource app-view prefs workspace project resource)))))

(defn- select-tool-tab! [app-view tab-id]
  (let [^TabPane tool-tab-pane (g/node-value app-view :tool-tab-pane)
        tabs (.getTabs tool-tab-pane)
        tab-index (first (keep-indexed (fn [i ^Tab tab] (when (= tab-id (.getId tab)) i)) tabs))]
    (if (some? tab-index)
      (.select (.getSelectionModel tool-tab-pane) ^long tab-index)
      (throw (ex-info (str "Tab id not found: " tab-id)
                      {:tab-id tab-id
                       :tab-ids (mapv #(.getId ^Tab %) tabs)})))))

(handler/defhandler :show-console :global
  (run [app-view] (select-tool-tab! app-view "console-tab")))

(handler/defhandler :show-curve-editor :global
  (run [app-view] (select-tool-tab! app-view "curve-editor-tab")))

(handler/defhandler :show-build-errors :global
  (run [app-view] (select-tool-tab! app-view "build-errors-tab")))

(handler/defhandler :show-search-results :global
  (run [app-view] (select-tool-tab! app-view "search-results-tab")))

(defn- put-on-clipboard!
  [s]
  (doto (Clipboard/getSystemClipboard)
    (.setContent (doto (ClipboardContent.)
                   (.putString s)))))

(handler/defhandler :copy-project-path :global
  (active? [app-view selection] (context-resource-file app-view selection))
  (enabled? [app-view selection] (when-let [r (context-resource-file app-view selection)]
                                   (and (resource/proj-path r)
                                        (resource/exists? r))))
  (run [selection app-view]
    (when-let [r (context-resource-file app-view selection)]
      (put-on-clipboard! (resource/proj-path r)))))

(handler/defhandler :copy-full-path :global
  (active? [app-view selection] (context-resource-file app-view selection))
  (enabled? [app-view selection] (when-let [r (context-resource-file app-view selection)]
                                   (and (resource/abs-path r)
                                        (resource/exists? r))))
  (run [selection app-view]
    (when-let [r (context-resource-file app-view selection)]
      (put-on-clipboard! (resource/abs-path r)))))

(defn- gen-tooltip [workspace project app-view resource]
  (let [resource-type (resource/resource-type resource)
        view-type (or (first (:view-types resource-type)) (workspace/get-view-type workspace :text))]
    (when-let [make-preview-fn (:make-preview-fn view-type)]
      (let [tooltip (Tooltip.)]
        (doto tooltip
          (.setGraphic (doto (ImageView.)
                         (.setScaleY -1.0)))
          (.setOnShowing (ui/event-handler
                           e
                           (let [image-view ^ImageView (.getGraphic tooltip)]
                             (when-not (.getImage image-view)
                               (let [resource-node (project/get-resource-node project resource)
                                     view-graph (g/make-graph! :history false :volatility 2)
                                     select-fn (partial select app-view)
                                     opts (assoc ((:id view-type) (:view-opts resource-type))
                                            :app-view app-view
                                            :select-fn select-fn
                                            :project project
                                            :workspace workspace)
                                     preview (make-preview-fn view-graph resource-node opts 256 256)]
                                 (.setImage image-view ^Image (g/node-value preview :image))
                                 (when-some [dispose-preview-fn (:dispose-preview-fn view-type)]
                                   (dispose-preview-fn preview))
                                 (g/delete-graph! view-graph)))))))))))

(defn- query-and-open! [workspace project app-view prefs term]
  (doseq [resource (dialogs/make-resource-dialog workspace project
                                                 (cond-> {:title "Open Assets"
                                                          :accept-fn resource/editable-resource?
                                                          :selection :multiple
                                                          :ok-label "Open"
                                                          :tooltip-gen (partial gen-tooltip workspace project app-view)}
                                                   (some? term)
                                                   (assoc :filter term)))]
    (open-resource app-view prefs workspace project resource)))

(handler/defhandler :select-items :global
  (run [user-data] (dialogs/make-select-list-dialog (:items user-data) (:options user-data))))

(defn- get-view-text-selection [{:keys [view-id view-type]}]
  (when-let [text-selection-fn (:text-selection-fn view-type)]
    (text-selection-fn view-id)))

(handler/defhandler :open-asset :global
  (run [workspace project app-view prefs]
    (let [term (get-view-text-selection (g/node-value app-view :active-view-info))]
      (query-and-open! workspace project app-view prefs term))))

(handler/defhandler :search-in-files :global
  (run [project app-view search-results-view]
    (when-let [term (get-view-text-selection (g/node-value app-view :active-view-info))]
      (search-results-view/set-search-term! term))
    (search-results-view/show-search-in-files-dialog! search-results-view project)))

(defn- bundle! [changes-view build-errors-view project prefs platform bundle-options]
  (console/clear-console!)
  (let [output-directory ^File (:output-directory bundle-options)
        clear-errors! (make-clear-build-errors build-errors-view)
        render-build-error! (make-render-build-error build-errors-view)
        render-reload-progress! (make-render-task-progress :resource-sync)
        render-save-progress! (make-render-task-progress :save-all)
        render-build-progress! (make-render-task-progress :build)
        bob-args (bob/bundle-bob-args prefs platform bundle-options)]
    (clear-errors!)
    (disk/async-bob-build! render-reload-progress! render-save-progress! render-build-progress!
                           render-build-error! bob/bundle-bob-commands bob-args project changes-view
                           (fn [successful?]
                             (when successful?
                               (if (some-> output-directory .isDirectory)
                                 (ui/open-file output-directory)
                                 (dialogs/make-alert-dialog "Failed to bundle project. Please fix build errors and try again.")))))))

(handler/defhandler :bundle :global
  (run [user-data workspace project prefs app-view changes-view build-errors-view]
       (let [owner-window (g/node-value app-view :stage)
             platform (:platform user-data)
             bundle! (partial bundle! changes-view build-errors-view project prefs platform)]
         (bundle-dialog/show-bundle-dialog! workspace platform prefs owner-window bundle!))))

(defn- fetch-libraries [workspace project prefs changes-view]
  (let [library-uris (project/project-dependencies project)
        hosts (into #{} (map url/strip-path) library-uris)]
    (if-let [first-unreachable-host (first-where (complement url/reachable?) hosts)]
      (dialogs/make-alert-dialog (string/join "\n" ["Fetch was aborted because the following host could not be reached:"
                                                    (str "\u00A0\u00A0\u2022\u00A0" first-unreachable-host) ; "  * " (NO-BREAK SPACE, NO-BREAK SPACE, BULLET, NO-BREAK SPACE)
                                                    ""
                                                    "Please verify internet connection and try again."]))
      (future
        (error-reporting/catch-all!
          (ui/with-progress [render-fetch-progress! (make-render-task-progress :fetch-libraries)]
            (when (workspace/dependencies-reachable? library-uris (partial login/login prefs))
              (let [lib-states (workspace/fetch-and-validate-libraries workspace library-uris render-fetch-progress!)
                    render-install-progress! (make-render-task-progress :resource-sync)]
                (render-install-progress! (progress/make "Installing updated libraries..."))
                (ui/run-later
                  (workspace/install-validated-libraries! workspace library-uris lib-states)
                  (disk/async-reload! render-install-progress! workspace [] changes-view))))))))))

(handler/defhandler :fetch-libraries :global
  (enabled? [] (disk-availability/available?))
  (run [workspace project prefs changes-view] (fetch-libraries workspace project prefs changes-view)))

(defn- create-and-open-live-update-settings! [app-view changes-view prefs project]
  (let [workspace (project/workspace project)
        project-path (workspace/project-path workspace)
        settings-file (io/file project-path "liveupdate.settings")
        render-reload-progress! (make-render-task-progress :resource-sync)]
    (spit settings-file "[liveupdate]\n")
    (disk/async-reload! render-reload-progress! workspace [] changes-view
                        (fn [successful?]
                          (when successful?
                            (when-some [created-resource (workspace/find-resource workspace "/liveupdate.settings")]
                              (open-resource app-view prefs workspace project created-resource)))))))

(handler/defhandler :live-update-settings :global
  (enabled? [] (disk-availability/available?))
  (run [app-view changes-view prefs workspace project]
       (if-some [existing-resource (workspace/find-resource workspace (live-update-settings/get-live-update-settings-path project))]
         (open-resource app-view prefs workspace project existing-resource)
         (create-and-open-live-update-settings! app-view changes-view prefs project))))

(handler/defhandler :sign-ios-app :global
  (active? [] (util/is-mac-os?))
  (run [workspace project prefs build-errors-view]
    (build-errors-view/clear-build-errors build-errors-view)
    (let [result (bundle/make-sign-dialog workspace prefs project)]
      (when-let [error (:error result)]
        (if (engine-build-errors/handle-build-error! (make-render-build-error build-errors-view) project error)
          (dialogs/make-alert-dialog "Failed to build ipa with Native Extensions. Please fix build errors and try again.")
          (do (error-reporting/report-exception! error)
              (when-let [message (:message result)]
                (dialogs/make-alert-dialog message))))))))
