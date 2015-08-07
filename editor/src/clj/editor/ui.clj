(ns editor.ui
  (:require [clojure.java.io :as io]
            [editor.handler :as handler]
            [editor.jfx :as jfx]
            [editor.workspace :as workspace]
            [service.log :as log])
  (:import [javafx.application Platform]
           [javafx.animation AnimationTimer Timeline KeyFrame KeyValue]
           [javafx.beans.value ChangeListener ObservableValue]
           [javafx.event ActionEvent EventHandler WeakEventHandler]
           [javafx.fxml FXMLLoader]
           [javafx.scene Parent Node Scene Group]
           [javafx.scene.control ButtonBase ComboBox Control ContextMenu SeparatorMenuItem Label Labeled ListView ListCell ToggleButton TextInputControl TreeView TreeItem Toggle Menu MenuBar MenuItem ProgressBar TextField]
           [javafx.scene.input KeyCombination ContextMenuEvent MouseEvent DragEvent]
           [javafx.scene.layout AnchorPane Pane]
           [javafx.stage DirectoryChooser FileChooser FileChooser$ExtensionFilter]
           [javafx.stage Stage Modality Window]
           [javafx.util Callback Duration]))

;; These two lines initialize JavaFX and OpenGL when we're generating
;; API docs
(import com.sun.javafx.application.PlatformImpl)
(PlatformImpl/startup (constantly nil))

(set! *warn-on-reflection* true)

(defonce ^:dynamic *menus* (atom {}))
(defonce ^:dynamic *main-stage* (atom nil))

; NOTE: This one might change from welcome to actual project window
(defn set-main-stage [main-stage]
  (reset! *main-stage* main-stage))

(defn main-stage []
  @*main-stage*)

(defn choose-file [title ^String ext-descr exts]
  (let [chooser (FileChooser.)
        ext-array (into-array exts)]
    (.setTitle chooser title)
    (.add (.getExtensionFilters chooser) (FileChooser$ExtensionFilter. ext-descr ^"[Ljava.lang.String;" ext-array))
    (let [file (.showOpenDialog chooser nil)]
      (if file (.getAbsolutePath file)))))

(defn choose-directory
  ([title] (choose-directory title @*main-stage*))
  ([title parent]
    (let [chooser (DirectoryChooser.)]
      (.setTitle chooser title)
      (let [file (.showDialog chooser parent)]
        (when file (.getAbsolutePath file))))))

(defn collect-controls [^Parent root keys]
  (let [controls (zipmap (map keyword keys) (map #(.lookup root (str "#" %)) keys))
        missing (->> controls
                  (filter (fn [[k v]] (when (nil? v) k)))
                  (map first))]
    (when (seq missing)
      (throw (Exception. (format "controls %s are missing" missing))))
    controls))

; TODO: Better name?
(defn fill-control [control]
  (AnchorPane/setTopAnchor control 0.0)
  (AnchorPane/setBottomAnchor control 0.0)
  (AnchorPane/setLeftAnchor control 0.0)
  (AnchorPane/setRightAnchor control 0.0))

(defn local-bounds [^Node node]
  (let [b (.getBoundsInLocal node)]
    {:minx (.getMinX b)
     :miny (.getMinY b)
     :maxx (.getMaxX b)
     :maxy (.getMaxY b)
     :width (.getWidth b)
     :height (.getHeight b)}))

(defn observe [^ObservableValue observable listen-fn]
  (.addListener observable (reify ChangeListener
                        (changed [this observable old new]
                          (listen-fn observable old new)))))

(defn do-run-now [f]
  (if (Platform/isFxApplicationThread)
    (f)
    (let [p (promise)]
      (Platform/runLater
        (fn []
          (try
            (deliver p (f))
            (catch Throwable e
              (deliver p e)))))
      (let [val @p]
        (if (instance? Throwable val)
          (throw val)
          val)))))

(defmacro run-now
  [& body]
  `(do-run-now
    (fn [] ~@body)))

(defmacro run-later
  [& body]
  `(Platform/runLater
    (fn [] ~@body)))

(defmacro event-handler [event & body]
  `(reify EventHandler
     (handle [this ~event]
       ~@body)))

(defn scene [^Node node]
  (.getScene node))

(defn add-style! [^Node node ^String class]
  (.add (.getStyleClass node) class))

(defn visible! [^Node node v]
  (.setVisible node v))

(defn managed! [^Node node m]
  (.setManaged node m))

(defn disable! [^Node node d]
  (.setDisable node d))

(defn window [^Scene scene]
  (.getWindow scene))

(defn close! [^Stage window]
  (.close window))

(defn title! [^Stage window t]
  (.setTitle window t))

(defn show! [^Stage stage]
  (.show stage))

(defn show-and-wait! [^Stage stage]
  (.showAndWait stage))

(defn request-focus! [^Node node]
  (.requestFocus node))

(defn on-double! [^Node node fn]
  (.setOnMouseClicked node (event-handler e (when (= 2 (.getClickCount ^MouseEvent e))
                                        (fn e)))))

(defprotocol Text
  (text [this])
  (text! [this val]))

(defprotocol HasUserData
  (user-data [this key])
  (user-data! [this key val]))

(extend-type Node
  HasUserData
  (user-data [this key] (get (.getUserData this) key))
  (user-data! [this key val] (.setUserData this (assoc (or (.getUserData this) {}) key val))))

(extend-type MenuItem
  HasUserData
  (user-data [this key] (get (.getUserData this) key))
  (user-data! [this key val] (.setUserData this (assoc (or (.getUserData this) {}) key val))))

(defprotocol HasAction
  (on-action! [this fn]))

(defprotocol HasChildren
  (children! [this c])
  (add-child! [this c]))

(defprotocol CollectionView
  (selection [this])
  (items [this])
  (items! [this items])
  (cell-factory! [this render-fn]))

(extend-type TextInputControl
  Text
  (text [this] (.getText this))
  ; TODO: This is hack to reduce the cpu usage due to bug DEFEDIT-131
  (text! [this val] (when-not (= val (.getText this))
                      (.setText this val)
                      (when (.isFocused this)
                        (.end this)
                        (.selectAll this)))))

(extend-type TextField
  HasAction
  (on-action! [this fn] (.setOnAction this (event-handler e (fn e)))))

(extend-type Labeled
  Text
  (text [this] (.getText this))
  (text! [this val] (.setText this val)))

(extend-type Pane
  HasChildren
  (children! [this c]
    (doto
      (.getChildren this)
      (.clear)
      (.addAll ^"[Ljavafx.scene.Node;" (into-array Node c))))
  (add-child! [this c]
    (-> this (.getChildren) (.add c))))

(extend-type Group
  HasChildren
  (children! [this c]
    (doto
      (.getChildren this)
      (.clear)
      (.addAll ^"[Ljavafx.scene.Node;" (into-array Node c))))
  (add-child! [this c]
    (-> this (.getChildren) (.add c))))

(defn- make-list-cell [render-fn]
  (proxy [ListCell] []
    (updateItem [object empty]
      (let [this ^ListCell this
            render-data (and object (render-fn object))]
        (proxy-super updateItem (and object (:text render-data)) empty)
        (let [name (or (and (not empty) (:text render-data)) nil)]
          (proxy-super setText name))
        (proxy-super setGraphic (jfx/get-image-view (:icon render-data) 16))))))

(defn- make-list-cell-factory [render-fn]
  (reify Callback (call ^ListCell [this view] (make-list-cell render-fn))))

(extend-type ButtonBase
  HasAction
  (on-action! [this fn] (.setOnAction this (event-handler e (fn e)))))

(extend-type ComboBox
  CollectionView
  (selection [this] (when-let [item (.getSelectedItem (.getSelectionModel this))] [item]))
  (items [this] (.getItems this))
  (items! [this ^java.util.Collection items] (let [l (.getItems this)]
                                               (.clear l)
                                               (.addAll l items)))
  (cell-factory! [this render-fn]
    (.setButtonCell this (make-list-cell render-fn))
    (.setCellFactory this (make-list-cell-factory render-fn))))

(extend-type ListView
  CollectionView
  (selection [this] (when-let [items (.getSelectedItems (.getSelectionModel this))] items))
  (items [this] (.getItems this))
  (items! [this ^java.util.Collection items] (let [l (.getItems this)]
                                               (.clear l)
                                               (.addAll l items)))
  (cell-factory! [this render-fn]
    (.setCellFactory this (make-list-cell-factory render-fn))))

(extend-type TreeView
  workspace/SelectionProvider
  (selection [this] (some->> this
                      (.getSelectionModel)
                      (.getSelectedItems)
                      (filter (comp not nil?))
                      (mapv #(.getValue ^TreeItem %)))))

(defn selection-roots [^TreeView tree-view path-fn id-fn]
  (let [selection (-> tree-view (.getSelectionModel) (.getSelectedItems))]
    (let [items (into {} (map #(do [(path-fn %) %]) (filter id-fn selection)))
          roots (loop [paths (keys items)
                       roots []]
                  (if-let [path (first paths)]
                    (let [ancestors (filter #(= (subvec path 0 (count %)) %) roots)
                          roots (if (empty? ancestors)
                                  (conj roots path)
                                  roots)]
                      (recur (rest paths) roots))
                    roots))]
      (vals (into {} (map #(let [item (items %)]
                            [(id-fn item) item]) roots))))))

(extend-type ListView
  workspace/SelectionProvider
  (selection [this] (some->> this
                      (.getSelectionModel)
                      (.getSelectedItems)
                      (vec))))


(defn context! [^Node node name env selection-provider]
  (user-data! node ::context {:name name
                              :env env
                              :selection-provider selection-provider}))

(defn- contexts []
  (let [main-scene (.getScene ^Stage @*main-stage*)
        initial-node (or (.getFocusOwner main-scene) (.getRoot main-scene))]
    (->>
      (loop [^Node node initial-node
             ctxs []]
        (if-not node
          ctxs
          (recur (.getParent node) (conj ctxs (user-data node ::context)))))
      (remove nil?)
      (map (fn [ctx] (assoc-in ctx [:env :selection] (workspace/selection (:selection-provider ctx))))))))

(defn extend-menu [id location menu]
  (swap! *menus* assoc id {:location location
                           :menu menu}))

(defn- collect-menu-extensions []
  (->>
    (vals @*menus*)
    (filter :location)
    (reduce (fn [acc x] (update-in acc [(:location x)] concat (:menu x))) {})))

(defn- do-realize-menu [menu exts]
  (->> menu
     (map (fn [x] (if (:children x)
                    (update-in x [:children] do-realize-menu exts)
                    x)))
     (mapcat (fn [x] (concat [x] (get exts (:id x)))))))

(defn- realize-menu [id]
  (let [exts (collect-menu-extensions)]
    (do-realize-menu (:menu (get @*menus* id)) exts)))

(defn- make-desc [control menu-id]
  {:control control
   :menu-id menu-id})

(defn- make-submenu [label icon menu-items]
  (when (seq menu-items)
    (let [menu (Menu. label)]
      (when icon
        (.setGraphic menu (jfx/get-image-view icon 16)))
      (.addAll (.getItems menu) (to-array menu-items))
      menu)))

(defn- make-menu-command [label icon acc command command-contexts user-data]
  (let [menu-item (MenuItem. label)]
    (when command
      (.setId menu-item (name command)))
    (when acc
      (.setAccelerator menu-item (KeyCombination/keyCombination acc)))
    (when icon
      (.setGraphic menu-item (jfx/get-image-view icon 16)))
    (.setDisable menu-item (not (handler/enabled? command command-contexts user-data)))
    (.setOnAction menu-item (event-handler event (handler/run command command-contexts user-data)))
    (user-data! menu-item ::menu-user-data user-data)
    menu-item))

(def ^:private make-menu-items nil)

(defn- make-menu-item [item command-contexts]
  (let [icon (:icon item),
        item-label (:label item)]
    (if-let [children (:children item)]
      (make-submenu item-label icon (make-menu-items children command-contexts))
      (if (= item-label :separator)
        (SeparatorMenuItem.)
        (let [command (:command item)
              user-data (:user-data item)]
          (when (handler/active? command command-contexts user-data)
            (let [label (or (handler/label command command-contexts user-data) item-label)]
              (if-let [options (handler/options command command-contexts user-data)]
                (make-submenu label icon (make-menu-items options command-contexts))
                (make-menu-command label icon (:acc item) command command-contexts user-data)))))))))

(defn- make-menu-items [menu command-contexts]
  (->> menu
       (map (fn [item] (make-menu-item item command-contexts)))
       (remove nil?)))

(defn- ^ContextMenu make-context-menu [menu-items]
  (let [^ContextMenu context-menu (ContextMenu.)]
    (.addAll (.getItems context-menu) (to-array menu-items))
    context-menu))

(defn register-context-menu [^Control control menu-id]
  (let [desc (make-desc control menu-id)]
    (.addEventHandler control ContextMenuEvent/CONTEXT_MENU_REQUESTED
      (event-handler event
                     (when-not (.isConsumed event)
                       (let [cm (make-context-menu (make-menu-items (realize-menu menu-id) (contexts)))]
                         ; Required for autohide to work when the event originates from the anchor/source control
                         ; See RT-15160 and Control.java
                         (.setImpl_showRelativeToWindow cm true)
                         (.show cm control (.getScreenX ^ContextMenuEvent event) (.getScreenY ^ContextMenuEvent event))
                         (.consume event)))))))

(defn register-menubar [^Scene scene  menubar-id menu-id ]
  ; TODO: See comment below about top-level items. Should be enforced here
 (let [root (.getRoot scene)]
   (if-let [menubar (.lookup root menubar-id)]
     (let [desc (make-desc menubar menu-id)]
       (user-data! root ::menubar desc))
     (log/warn :message (format "menubar %s not found" menubar-id)))))

(defn- refresh-menubar [md command-contexts]
 (let [menu (realize-menu (:menu-id md))
       control ^MenuBar (:control md)]
   (when-not (and
               (= menu (user-data control ::menu))
               (= command-contexts (user-data control ::command-contexts)))
     (.clear (.getMenus control))
     ; TODO: We must ensure that top-level element are of type Menu and note MenuItem here, i.e. top-level items with ":children"
     (.addAll (.getMenus control) (to-array (make-menu-items menu command-contexts)))
     (user-data! control ::menu menu)
     (user-data! control ::command-contexts command-contexts))))

(defn- refresh-menu-state [^Menu menu command-contexts]
  (doseq [m (.getItems menu)]
    (if (instance? Menu m)
      (refresh-menu-state m command-contexts)
      (.setDisable ^MenuItem m (not (handler/enabled? (keyword (.getId ^MenuItem m)) command-contexts (user-data m ::menu-user-data)))))))

(defn- refresh-menubar-state [^MenuBar menubar command-contexts]
  (doseq [m (.getMenus menubar)]
    (refresh-menu-state m command-contexts)))

(defn register-toolbar [^Scene scene toolbar-id menu-id ]
  (let [root (.getRoot scene)]
    (if-let [toolbar (.lookup root toolbar-id)]
      (let [desc (make-desc toolbar menu-id)]
        (user-data! root ::toolbars (assoc-in (user-data root ::toolbars) [toolbar-id] desc)))
      (log/warn :message (format "toolbar %s not found" toolbar-id)))))

(defn- refresh-toolbar [td command-contexts]
 (let [menu (realize-menu (:menu-id td))
       ^Pane control (:control td)]
   (when-not (and (= menu (user-data control ::menu))
                  (= command-contexts (user-data control ::command-contexts)))
     (.clear (.getChildren control))
     (user-data! control ::menu menu)
     (user-data! control ::command-contexts command-contexts)
     (doseq [menu-item menu
             :let [command (:command menu-item)
                   user-data (:user-data menu-item)]
             :when (handler/active? command command-contexts user-data)]
       (let [button (ToggleButton. (or (handler/label command command-contexts user-data) (:label menu-item)))
             icon (:icon menu-item)
             selection-provider (:selection-provider td)]
         (user-data! button ::menu-user-data user-data)
         (when icon
           (.setGraphic button (jfx/get-image-view icon 22.5)))
         (when command
           (.setId button (name command))
           (.setOnAction button (event-handler event (handler/run command command-contexts user-data))))
         (.add (.getChildren control) button))))))

(defn- refresh-toolbar-state [^Pane toolbar command-contexts]
  (let [nodes (.getChildren toolbar)
        ids (map #(.getId ^Node %) nodes)]
    (doseq [^Node n nodes
            :let [user-data (user-data n ::menu-user-data)]]
      (.setDisable n (not (handler/enabled? (keyword (.getId n)) command-contexts user-data)))
      (when (instance? ToggleButton n)
        (if (handler/state (keyword (.getId n)) command-contexts user-data)
         (.setSelected ^Toggle n true)
         (.setSelected ^Toggle n false))))))

(defn refresh
  ([^Scene scene] (refresh scene (contexts)))
  ([^Scene scene command-contexts]
   (let [root (.getRoot scene)
         toolbar-descs (vals (user-data root ::toolbars))]
     (when-let [md (user-data root ::menubar)]
       (refresh-menubar md command-contexts)
       (refresh-menubar-state (:control md) command-contexts))
     (doseq [td toolbar-descs]
       (refresh-toolbar td command-contexts)
       (refresh-toolbar-state (:control td) command-contexts)))))

(defn modal-progress [title total-work worker-fn]
  (run-now
    (let [root ^Parent (FXMLLoader/load (io/resource "progress.fxml"))
          stage (Stage.)
          scene (Scene. root)
          title-control ^Label (.lookup root "#title")
          progress-control ^ProgressBar (.lookup root "#progress")
          message-control ^Label (.lookup root "#message")
          worked (atom 0)
          return (atom nil)
          report-fn (fn [work msg]
                      (when (> work 0)
                        (swap! worked + work))
                      (run-later
                        (.setText message-control (str msg))
                        (if (> work 0)
                          (.setProgress progress-control (/ @worked (float total-work)))
                          (.setProgress progress-control -1))))]
      (.setText title-control title)
      (.setProgress progress-control 0)
      (.initOwner stage @*main-stage*)
      (.initModality stage Modality/WINDOW_MODAL)
      (.setScene stage scene)
      (future
        (try
          (reset! return (worker-fn report-fn))
          (catch Throwable e
            (reset! return e)))
        (run-later (.close stage)))
      (.showAndWait stage)
      (if (instance? Throwable @return)
          (throw @return)
          @return))))


(defn mouse-type
  []
  :one-button)

(defn- on-ui-thread?
  []
  (Platform/isFxApplicationThread))

(defmacro on-app-thread
  [& body]
  `(if (on-ui-thread?)
     (do ~@body)
     (Platform/runLater
      (bound-fn [] (do ~@body)))))

(defn make-animation-timer
  [f & args]
  (proxy [AnimationTimer] []
    (handle [now] (apply f now args))))

(defn run-wait
  [f]
  (let [result (promise)]
    (on-app-thread
     (deliver result (f)))
    @result))

(defmacro run-safe
  [& body]
  `(Platform/runLater
    (fn [] ~@body)))

(defn handle
  [f]
  (reify EventHandler
    (handle [this event] (f event))))

(defn weak [^EventHandler h]
  (WeakEventHandler. h))

(defprotocol EventRegistration
  (add-listener [this key listener])
  (remove-listener [this key]))

(defprotocol EventSource
  (send-event [this event]))

(defrecord EventBroadcaster [listeners]
  EventRegistration
  (add-listener [this key listener] (swap! listeners assoc key listener))
  (remove-listener [this key] (swap! listeners dissoc key))

  EventSource
  (send-event [this event]
    #_(swt-await
      (doseq [l (vals @listeners)]
       (run-safe
         (l event))))))

(defn make-event-broadcaster [] (EventBroadcaster. (atom {})))

(defmacro defcommand
  "Create a command with the given category and id. Binds
the resulting command to the named variable.

Label should be a human-readable string. It will appear
directly in the UI (unless there is a translation for it.)

If you use the same category-id and command-id more than once,
this will create independent entities that refer to the same underlying
command."
  [name category-id command-id label]
  `(def ^:command ~name [~label ~category-id ~command-id]))

(defmacro defhandler
  "Creates a handler and binds it to the given command.

In the first form, the handler will always be enabled. Upon invocation, it
will call the function bound to fn-var with the
org.eclipse.core.commands.ExecutionEvent and the additional args.

In the second form, enablement-fn will be checked. When it returns a truthy
value, the handler will be enabled. Enablement-fn must have metadata to
identify the evaluation context variables and properties that affect its
return value."
  [name command & body]
  (let [enablement (if (= :enabled-when (first body)) (second body) nil)
        body       (if (= :enabled-when (first body)) (drop 2 body) body)
        fn-var     (first body)
        body       (rest body)]
    `(def ^:handler ~name [~command ~fn-var ~@body])))

(defn tree-item-seq [item]
  (if item
    (tree-seq
      #(not (.isLeaf ^TreeItem %))
      #(seq (.getChildren ^TreeItem %))
      item)
    []))

(defprotocol Future
  (cancel [this]))

(extend-type Timeline
  Future
  (cancel [this] (.stop this)))

(defn ->future [delay run-fn]
  (let [^EventHandler handler (event-handler e (run-fn))]
    (doto (Timeline. 60 (into-array KeyFrame [(KeyFrame. ^Duration (Duration/seconds delay) handler (into-array KeyValue []))]))
      (.play))))

(defn drag-internal? [^DragEvent e]
  (some? (.getGestureSource e)))
