(ns editor.app-view
  (:require [clojure.java.io :as io]
            [dynamo.graph :as g]
            [editor.dialogs :as dialogs]
            [editor.engine :as engine]
            [editor.handler :as handler]
            [editor.jfx :as jfx]
            [editor.login :as login]
            [editor.defold-project :as project]
            [editor.github :as github]
            [editor.prefs :as prefs]
            [editor.prefs-dialog :as prefs-dialog]
            [editor.progress :as progress]
            [editor.ui :as ui]
            [editor.workspace :as workspace]
            [editor.resource :as resource]
            [editor.graph-util :as gu]
            [util.profiler :as profiler]
            [util.http-server :as http-server])
  (:import [com.defold.editor EditorApplication]
           [com.defold.editor Start]
           [java.awt Desktop]
           [javafx.application Platform]
           [javafx.beans.value ChangeListener]
           [javafx.collections FXCollections ObservableList ListChangeListener]
           [javafx.embed.swing SwingFXUtils]
           [javafx.event ActionEvent Event EventHandler]
           [javafx.fxml FXMLLoader]
           [javafx.geometry Insets]
           [javafx.scene Scene Node Parent]
           [javafx.scene.control Button ColorPicker Label TextField TitledPane TextArea TreeItem Menu MenuItem MenuBar TabPane Tab ProgressBar Tooltip SplitPane]
           [javafx.scene.image Image ImageView WritableImage PixelWriter]
           [javafx.scene.input MouseEvent]
           [javafx.scene.layout AnchorPane GridPane StackPane HBox Priority]
           [javafx.scene.paint Color]
           [javafx.stage Stage FileChooser WindowEvent]
           [javafx.util Callback]
           [java.io File ByteArrayOutputStream]
           [java.net URI]
           [java.nio.file Paths]
           [java.util.prefs Preferences]
           [com.jogamp.opengl GL GL2 GLContext GLProfile GLDrawableFactory GLCapabilities]))

(set! *warn-on-reflection* true)

(g/defnode AppView
  (property stage Stage)
  (property tab-pane TabPane)
  (property auto-pulls g/Any)
  (property active-tool g/Keyword)

  (input outline g/Any)

  (output active-tab Tab :cached (g/fnk [^TabPane tab-pane] (-> tab-pane (.getSelectionModel) (.getSelectedItem))))
  (output active-outline g/Any :cached (gu/passthrough outline))
  (output active-resource resource/Resource :cached (g/fnk [^Tab active-tab]
                                                           (when active-tab
                                                             (ui/user-data active-tab ::resource))))
  (output active-view g/NodeID :cached (g/fnk [^Tab active-tab]
                                              (when active-tab
                                                (ui/user-data active-tab ::view))))
  (output open-resources g/Any :cached (g/fnk [^TabPane tab-pane] (map (fn [^Tab tab] (ui/user-data tab ::resource)) (.getTabs tab-pane)))))

(defn- invalidate [node label]
  (g/invalidate! [[node label]]))

(defn- disconnect-sources [target-node target-label]
  (for [[source-node source-label] (g/sources-of target-node target-label)]
    (g/disconnect source-node source-label target-node target-label)))

(defn- replace-connection [source-node source-label target-node target-label]
  (concat
    (disconnect-sources target-node target-label)
    (if (and source-node (contains? (-> source-node g/node-type* g/output-labels) source-label))
      (g/connect source-node source-label target-node target-label)
      [])))

(defn- on-selected-tab-changed [app-view resource-node]
  (g/transact
    (replace-connection resource-node :node-outline app-view :outline))
  (invalidate app-view :active-tab))

(defn- on-tabs-changed [app-view]
  (invalidate app-view :open-resources))

(handler/defhandler :move-tool :global
  (enabled? [app-view] true)
  (run [app-view] (g/transact (g/set-property app-view :active-tool :move)))
  (state [app-view] (= (g/node-value app-view :active-tool) :move)))

(handler/defhandler :scale-tool :global
  (enabled? [app-view] true)
  (run [app-view] (g/transact (g/set-property app-view :active-tool :scale)))
  (state [app-view]  (= (g/node-value app-view :active-tool) :scale)))

(handler/defhandler :rotate-tool :global
  (enabled? [app-view] true)
  (run [app-view] (g/transact (g/set-property app-view :active-tool :rotate)))
  (state [app-view]  (= (g/node-value app-view :active-tool) :rotate)))

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
                  :command :scale-tool}])

(def ^:const prefs-window-dimensions "window-dimensions")
(def ^:const prefs-split-positions "split-positions")

(handler/defhandler :quit :global
  (enabled? [] true)
  (run [project ^Stage main-stage]
    (.fireEvent main-stage (WindowEvent. main-stage WindowEvent/WINDOW_CLOSE_REQUEST))))

(defn store-window-dimensions [^Stage stage prefs]
  (let [dims    {:x      (.getX stage)
                 :y      (.getY stage)
                 :width  (.getWidth stage)
                 :height (.getHeight stage)}]
    (prefs/set-prefs prefs prefs-window-dimensions dims)))
  
(defn restore-window-dimensions [^Stage stage prefs]
  (when-let [dims (prefs/get-prefs prefs prefs-window-dimensions nil)]
    (.setX stage (:x dims))
    (.setY stage (:y dims))
    (.setWidth stage (:width dims))
    (.setHeight stage (:height dims))))

(defn- splits [^Stage stage]
  (let [root (.getRoot (.getScene stage))]
    [(.lookup root "#main-split")
     (.lookup root "#center-split")
     (.lookup root "#right-split")
     (.lookup root "#assets-split")]))

(defn store-split-positions [^Stage stage prefs]
  (let [div-pos (map (fn [^SplitPane sp] (.getDividerPositions sp)) (splits stage))]
    (prefs/set-prefs prefs prefs-split-positions div-pos)))

(defn restore-split-positions [^Stage stage prefs]
  (when-let [div-pos (prefs/get-prefs prefs prefs-split-positions nil)]
    (doall (map (fn [^SplitPane sp pos]
                  (when (and sp pos)
                    (.setDividerPositions sp (into-array Double/TYPE pos))))
                (splits stage) div-pos))))

(handler/defhandler :new :global
  (run [] (prn "NEW NOW!")))

(handler/defhandler :open :global
  (run [] (when-let [file-name (ui/choose-file "Open Project" "Project Files" ["*.project"])]
            (EditorApplication/openEditor (into-array String [file-name])))))

(handler/defhandler :logout :global
  (run [prefs] (login/logout prefs)))

(handler/defhandler :preferences :global
  (run [prefs] (prefs-dialog/open-prefs prefs)))

(defn- remove-tab [^TabPane tab-pane ^Tab tab]
  (.remove (.getTabs tab-pane) tab)
  ;; TODO: Workaround as there's currently no API to close tabs programatically with identical semantics to close manually
  ;; See http://stackoverflow.com/questions/17047000/javafx-closing-a-tab-in-tabpane-dynamically
  (Event/fireEvent tab (Event. Tab/CLOSED_EVENT)))

(defn- collect-resources [{:keys [children] :as resource}]
  (if (empty? children)
    #{resource}
    (set (concat [resource] (mapcat collect-resources children)))))

(defn remove-resource-tab [^TabPane tab-pane resource]
  (let [tabs      (.getTabs tab-pane)
        resources (collect-resources resource)]
    (doseq [tab (filter #(contains? resources (ui/user-data % ::resource)) tabs)]
      (remove-tab tab-pane tab))))

(defn- get-tabs [app-view]
  (let [tab-pane ^TabPane (g/node-value app-view :tab-pane)]
    (.getTabs tab-pane)))

(handler/defhandler :hot-reload :global
  (enabled? [app-view]
            (g/node-value app-view :active-resource))
  (run [project app-view prefs build-errors-view]
    (when-let [resource (g/node-value app-view :active-resource)]
      (let [build (project/build-and-save-project project build-errors-view)]
        (when (and (future? build) @build)
            (engine/reload-resource (:url (project/get-selected-target prefs)) resource))))))

(handler/defhandler :close :global
  (enabled? [app-view] (not-empty (get-tabs app-view)))
  (run [app-view]
    (let [tab-pane ^TabPane (g/node-value app-view :tab-pane)]
      (when-let [tab (-> tab-pane (.getSelectionModel) (.getSelectedItem))]
        (remove-tab tab-pane tab)))))

(handler/defhandler :close-other :global
  (enabled? [app-view] (not-empty (get-tabs app-view)))
  (run [app-view]
    (let [tab-pane ^TabPane (g/node-value app-view :tab-pane)]
      (when-let [selected-tab (-> tab-pane (.getSelectionModel) (.getSelectedItem))]
        (doseq [tab (.getTabs tab-pane)]
          (when (not= tab selected-tab)
            (remove-tab tab-pane tab)))))))

(handler/defhandler :close-all :global
  (enabled? [app-view] (not-empty (get-tabs app-view)))
  (run [app-view]
    (let [tab-pane ^TabPane (g/node-value app-view :tab-pane)]
      (doseq [tab (.getTabs tab-pane)]
        (remove-tab tab-pane tab)))))

(defn make-about-dialog []
  (let [root ^Parent (ui/load-fxml "about.fxml")
        stage (ui/make-stage)
        scene (Scene. root)
        controls (ui/collect-controls root ["version" "sha1"])]
    (ui/text! (:version controls) (str "Version: " (System/getProperty "defold.version" "NO VERSION")))
    (ui/text! (:sha1 controls) (format "(%s)" (System/getProperty "defold.sha1" "NO SHA1")))
    (ui/title! stage "About")
    (.setScene stage scene)
    (ui/show! stage)))

(handler/defhandler :documentation :global
  (run [] (.browse (Desktop/getDesktop) (URI. "http://www.defold.com/learn/"))))

(handler/defhandler :report-issue :global
  (run [] (.browse (Desktop/getDesktop) (github/new-issue-link))))

(handler/defhandler :report-praise :global
  (run [] (.browse (Desktop/getDesktop) (github/new-praise-link))))

(handler/defhandler :about :global
  (run [] (make-about-dialog)))

(handler/defhandler :reload-stylesheet :global
  (run [] (ui/reload-root-styles!)))

(ui/extend-menu ::menubar nil
                [{:label "File"
                  :id ::file
                  :children [{:label "New"
                              :id ::new
                              :acc "Shortcut+N"
                              :command :new}
                             {:label "Open..."
                              :id ::open
                              :acc "Shortcut+O"
                              :command :open}
                             {:label :separator}
                             {:label "Open Asset"
                              :acc "Shift+Shortcut+R"
                              :command :open-asset}
                             {:label "Search in Files"
                              :acc "Shift+Shortcut+F"
                              :command :search-in-files}
                             {:label :separator}
                             {:label "Hot Reload"
                              :acc "Shortcut+R"
                              :command :hot-reload}
                             {:label "Close"
                              :acc "Shortcut+W"
                              :command :close}
                             {:label "Close All"
                              :acc "Shift+Shortcut+W"
                              :command :close-all}
                             {:label "Close Others"
                              :command :close-other}
                             {:label :separator}
                             {:label "Logout"
                              :command :logout}
                             {:label "Preferences..."
                              :command :preferences
                              :acc "Shortcut+,"}
                             {:label "Quit"
                              :acc "Shortcut+Q"
                              :command :quit}]}
                 {:label "Edit"
                  :id ::edit
                  :children [{:label "Undo"
                              :acc "Shortcut+Z"
                              :icon "icons/undo.png"
                              :command :undo}
                             {:label "Redo"
                              :acc "Shift+Shortcut+Z"
                              :icon "icons/redo.png"
                              :command :redo}
                             {:label :separator}
                             {:label "Copy"
                              :acc "Shortcut+C"
                              :command :copy}
                             {:label "Cut"
                              :acc "Shortcut+X"
                              :command :cut}
                             {:label "Paste"
                              :acc "Shortcut+V"
                              :command :paste}
                             {:label "Delete"
                              :acc "Shortcut+BACKSPACE"
                              :icon_ "icons/redo.png"
                              :command :delete}
                             {:label :separator}
                             {:label "Move Up"
                              :acc "Alt+UP"
                              :command :move-up}
                             {:label "Move Down"
                              :acc "Alt+DOWN"
                              :command :move-down}]}
                 {:label "Help"
                  :children [{:label "Profiler"
                              :children [{:label "Measure"
                                          :command :profile
                                          :acc "Shortcut+P"}
                                         {:label "Measure and Show"
                                          :command :profile-show
                                          :acc "Shift+Shortcut+P"}]}
                             {:label "Reload Stylesheet"
                              :acc "F5"
                              :command :reload-stylesheet}
                             {:label "Documentation"
                              :command :documentation}
                             {:label "Report Issue"
                              :command :report-issue}
                             {:label "Report Praise"
                              :command :report-praise}
                             {:label "About"
                              :command :about}]}])

(ui/extend-menu ::tab-menu nil
                [{:label "Hot Reload"
                  :acc "Shortcut+R"
                  :command :hot-reload}
                 {:label "Close"
                  :acc "Shortcut+W"
                  :command :close}
                 {:label "Close Others"
                  :command :close-other}
                 {:label "Close All"
                  :acc "Shift+Shortcut+W"
                  :command :close-all}])

(defrecord DummySelectionProvider []
  workspace/SelectionProvider
  (selection [this] []))

(defn- make-title
  ([] "Defold Editor 2.0")
  ([project-title] (str (make-title) " - " project-title)))

(defn- refresh-ui! [^Stage stage project]
  (ui/refresh (.getScene stage))
  (let [settings      (g/node-value project :settings)
        project-title (settings ["project" "title"])
        new-title     (make-title project-title)]
    (when (not= (.getTitle stage) new-title)
      (.setTitle stage new-title))))

(defn- refresh-views! [app-view]
  (let [auto-pulls (g/node-value app-view :auto-pulls)]
    (doseq [[node label] auto-pulls]
      (profiler/profile "view" (:name @(g/node-type* node))
                        (g/node-value node label)))))

;; Here only because reports indicate that isDesktopSupported does
;; some kind of initialization behind the scenes on Linux:
;; http://stackoverflow.com/questions/23176624/javafx-freeze-on-desktop-openfile-desktop-browseuri
(def desktop-supported? (delay (Desktop/isDesktopSupported)))

(defn make-app-view [view-graph project-graph project ^Stage stage ^MenuBar menu-bar ^TabPane tab-pane prefs]
  (.setUseSystemMenuBar menu-bar true)
  (.setTitle stage (make-title))
  (force desktop-supported?)
  (let [app-view (first (g/tx-nodes-added (g/transact (g/make-node view-graph AppView :stage stage :tab-pane tab-pane :active-tool :move))))]
    (-> tab-pane
      (.getSelectionModel)
      (.selectedItemProperty)
      (.addListener
        (reify ChangeListener
          (changed [this observable old-val new-val]
            (on-selected-tab-changed app-view (when new-val (ui/user-data ^Tab new-val ::resource-node)))))))
    (-> tab-pane
      (.getTabs)
      (.addListener
        (reify ListChangeListener
          (onChanged [this change]
            (ui/restyle-tabs! tab-pane)
            (on-tabs-changed app-view)))))

    (ui/register-toolbar (.getScene stage) "#toolbar" :toolbar)
    (ui/register-menubar (.getScene stage) "#menu-bar" ::menubar)

    (let [refresh-timers [(ui/->timer 3 "refresh-ui" (fn [dt] (refresh-ui! stage project)))
                          (ui/->timer 13 "refresh-views" (fn [dt] (refresh-views! app-view)))]]
      (doseq [timer refresh-timers]
        (ui/timer-stop-on-closed! stage timer)
        (ui/timer-start! timer)))
    app-view))

(defn- create-new-tab [app-view workspace project resource resource-node
                       resource-type view-type make-view-fn ^ObservableList tabs opts]
  (let [parent     (AnchorPane.)
        tab        (doto (Tab. (resource/resource-name resource))
                     (.setContent parent)
                     (ui/user-data! ::resource resource)
                     (ui/user-data! ::resource-node resource-node)
                     (ui/user-data! ::view-type view-type))
        _          (.add tabs tab)
        view-graph (g/make-graph! :history false :volatility 2)
        opts       (merge opts
                          (get (:view-opts resource-type) (:id view-type))
                          {:app-view  app-view
                           :project   project
                           :workspace workspace
                           :tab       tab})
        view       (make-view-fn view-graph parent resource-node opts)]
    (ui/user-data! tab ::view view)
    (.setGraphic tab (jfx/get-image-view (:icon resource-type "icons/cog.png") 16))
    (ui/register-tab-context-menu tab ::tab-menu)
    (let [close-handler (.getOnClosed tab)]
      (.setOnClosed tab (ui/event-handler
                         event
                         (g/delete-graph! view-graph)
                         (when close-handler
                           (.handle close-handler event)))))
    tab))

(defn open-resource
  ([app-view workspace project resource]
   (open-resource app-view workspace project resource {}))
  ([app-view workspace project resource opts]
   (let [resource-type (resource/resource-type resource)
         view-type     (or (:selected-view-type opts)
                           (first (:view-types resource-type))
                           (workspace/get-view-type workspace :text))]
     (if-let [make-view-fn (:make-view-fn view-type)]
       (let [resource-node     (project/get-resource-node project resource)
             ^TabPane tab-pane (g/node-value app-view :tab-pane)
             tabs              (.getTabs tab-pane)
             tab               (or (first (filter #(and (= resource (ui/user-data % ::resource))
                                                        (= view-type (ui/user-data % ::view-type)))
                                                  tabs))
                                   (create-new-tab app-view workspace project resource resource-node
                                                   resource-type view-type make-view-fn tabs opts))]
         (.select (.getSelectionModel tab-pane) tab)
         (when-let [focus (:focus-fn view-type)]
           (focus (ui/user-data tab ::view) opts))
         (project/select! project [resource-node]))
       (let [path (resource/abs-path resource)]
         (try
           (.open (Desktop/getDesktop) (File. path))
           (catch Exception _
             (dialogs/make-alert-dialog (str "Unable to open external editor for " path)))))))))

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
                                         opts (assoc ((:id view-type) (:view-opts resource-type))
                                                     :app-view app-view
                                                     :project project
                                                     :workspace workspace)
                                         preview (make-preview-fn view-graph resource-node opts 256 256)]
                                     (.setImage image-view ^Image (g/node-value preview :image))
                                     (ui/user-data! image-view :graph view-graph))))))
          (.setOnHiding (ui/event-handler
                          e
                          (let [image-view ^ImageView (.getGraphic tooltip)]
                            (when-let [graph (ui/user-data image-view :graph)]
                              (g/delete-graph! graph))))))))))

(defn- make-resource-dialog [workspace project app-view]
  (when-let [resource (first (dialogs/make-resource-dialog workspace {:tooltip-gen (partial gen-tooltip workspace project app-view)}))]
    (open-resource app-view workspace project resource)))

(handler/defhandler :open-asset :global
  (run [workspace project app-view] (make-resource-dialog workspace project app-view)))

(defn- make-search-in-files-dialog [workspace project app-view]
  (let [[resource opts] (dialogs/make-search-in-files-dialog
                         workspace
                         (fn [exts term]
                           (project/search-in-files project exts term)))]
    (when resource
      (open-resource app-view workspace project resource opts))))

(handler/defhandler :search-in-files :global
  (run [workspace project app-view] (make-search-in-files-dialog workspace project app-view)))

(defn- fetch-libraries [workspace project prefs]
  (future
    (ui/with-disabled-ui
      (ui/with-progress [render-fn ui/default-render-progress!]
        (let [library-urls (project/project-dependencies project)]
          (workspace/fetch-libraries! workspace library-urls render-fn (partial login/login prefs)))))))

(handler/defhandler :fetch-libraries :global
  (run [workspace project prefs] (fetch-libraries workspace project prefs)))
