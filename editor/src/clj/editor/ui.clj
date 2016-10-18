(ns editor.ui
  (:require
   [clojure.java.io :as io]
   [clojure.string :as string]
   [dynamo.graph :as g]
   [editor.handler :as handler]
   [editor.jfx :as jfx]
   [editor.progress :as progress]
   [editor.workspace :as workspace]
   [internal.util :as util]
   [service.log :as log]
   [util.profiler :as profiler])
  (:import
   [com.defold.control LongField]
   [com.defold.control ListCell]
   [com.defold.control TreeCell]
   [javafx.animation AnimationTimer Timeline KeyFrame KeyValue]
   [javafx.application Platform]
   [javafx.beans.value ChangeListener ObservableValue]
   [javafx.collections ObservableList ListChangeListener ListChangeListener$Change]
   [javafx.css Styleable]
   [javafx.event ActionEvent EventHandler WeakEventHandler Event]
   [javafx.fxml FXMLLoader]
   [javafx.geometry Orientation]
   [javafx.scene Parent Node Scene Group]
   [javafx.scene.control ButtonBase CheckBox ChoiceBox ColorPicker ComboBox ComboBoxBase Control ContextMenu Separator SeparatorMenuItem Label Labeled ListView ToggleButton TextInputControl TreeView TreeItem Toggle Menu MenuBar MenuItem CheckMenuItem ProgressBar TabPane Tab TextField Tooltip]
   [javafx.scene.input Clipboard KeyCombination ContextMenuEvent MouseEvent DragEvent KeyEvent]
   [javafx.scene.image Image]
   [javafx.scene.layout Region]
   [javafx.scene.layout AnchorPane Pane HBox]
   [javafx.stage DirectoryChooser FileChooser FileChooser$ExtensionFilter]
   [javafx.stage Stage Modality Window]
   [javafx.util Callback Duration StringConverter]))

(set! *warn-on-reflection* true)

;; These two lines initialize JavaFX and OpenGL when we're generating
;; API docs
(import com.sun.javafx.application.PlatformImpl)
(PlatformImpl/startup (constantly nil))

(defonce ^:dynamic *menus* (atom {}))
(defonce ^:dynamic *menu-key-combos* (atom #{}))
(defonce ^:dynamic *main-stage* (atom nil))

(defprotocol Text
  (text ^String [this])
  (text! [this ^String val]))

(defprotocol HasValue
  (value [this])
  (value! [this val]))

(defprotocol HasUserData
  (user-data [this key])
  (user-data! [this key val]))

(defprotocol Editable
  (editable [this])
  (editable! [this val])
  (on-edit! [this fn]))

(def application-icon-image (Image. (io/input-stream (io/resource "logo_blue.png"))))

(defn make-stage
  ^Stage []
  (doto (Stage.)
    (.. getIcons (add application-icon-image))))

; NOTE: This one might change from welcome to actual project window
(defn set-main-stage [main-stage]
  (reset! *main-stage* main-stage))

(defn ^Stage main-stage []
  @*main-stage*)

(defn ^Scene main-scene []
  (.. (main-stage) (getScene)))

(defn ^Node main-root []
  (.. (main-scene) (getRoot)))

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

(defn observe-list
  ([^ObservableList observable listen-fn]
    (observe-list nil observable listen-fn))
  ([^Node node ^ObservableList observable listen-fn]
    (let [listener (reify ListChangeListener
                     (onChanged [this change]
                       (listen-fn observable (into [] (.getList change)))))]
      (.addListener observable listener)
      (when node
        (let [listeners (-> (or (user-data node ::list-listeners) [])
                          (conj listener))]
          (user-data! node ::list-listeners listeners))))))

(defn remove-list-observers
  [^Node node ^ObservableList observable]
  (let [listeners (user-data node ::list-listeners)]
    (user-data! node ::list-listeners [])
    (doseq [^ListChangeListener l listeners]
      (.removeListener observable l))))

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
     (handle [~'this ~event]
       ~@body)))

(defmacro change-listener [observable old-val new-val & body]
  `(reify ChangeListener
     (changed [~'this ~observable ~old-val ~new-val]
       ~@body)))

(defn scene [^Node node]
  (.getScene node))

(defn add-styles! [^Styleable node classes]
  (let [styles (.getStyleClass node)
        existing (into #{} styles)
        new (filter (complement existing) classes)]
    (when-not (empty? new)
      (.addAll styles ^java.util.Collection new))))

(defn add-style! [^Styleable node ^String class]
  (add-styles! node [class]))

(defn remove-styles! [^Styleable node ^java.util.Collection classes]
  (let [styles (.getStyleClass node)
        existing (into #{} styles)
        old (filter existing classes)]
    (when-not (empty? old)
      (.removeAll styles ^java.util.Collection old))))

(defn remove-style! [^Styleable node ^String class]
  (remove-styles! node [class]))

(defn update-tree-cell-style! [^TreeCell cell]
  (let [tree-view (.getTreeView cell)
        expanded-count (.getExpandedItemCount tree-view)
        last-index (- expanded-count 1)
        index (.getIndex cell)]
    (remove-styles! cell ["first-tree-item" "last-tree-item"])
    (when (not (.isEmpty cell))
      (add-styles! cell (remove nil? [(when (= index 0) "first-tree-item")
                                      (when (= index last-index) "last-tree-item")])))))

(defn update-list-cell-style! [^ListCell cell]
  (let [list-view (.getListView cell)
        count (.size (.getItems list-view))
        last-index (- count 1)
        index (.getIndex cell)]
    (remove-styles! cell ["first-list-item" "last-list-item"])
    (when (not (.isEmpty cell))
      (add-styles! cell (remove nil? [(when (= index 0) "first-list-item")
                                      (when (= index last-index) "last-list-item")])))))


(defn restyle-tabs! [^TabPane tab-pane]
  (let [tabs (seq (.getTabs tab-pane))]
    (doseq [tab tabs]
      (remove-styles! tab ["first-tab" "last-tab"]))
    (when-let [first-tab (first tabs)]
      (add-style! first-tab "first-tab"))
    (when-let [last-tab (last tabs)]
      (add-style! last-tab "last-tab"))))

(defn reload-root-styles! []
  (when-let [scene (.getScene ^Stage (main-stage))]
    (let [root ^Parent (.getRoot scene)
          styles (vec (.getStylesheets root))]
      (.forget (com.sun.javafx.css.StyleManager/getInstance) scene)
      (.setAll (.getStylesheets root) ^java.util.Collection styles))))

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

(defn tooltip! [^Control ctrl tip]
  (.setTooltip ctrl (when tip (Tooltip. tip))))

(defn show! [^Stage stage]
  (.show stage))

(defn show-and-wait! [^Stage stage]
  (.showAndWait stage))

(defn request-focus! [^Node node]
  (.requestFocus node))

(defn on-double! [^Node node fn]
  (.setOnMouseClicked node (event-handler e (when (= 2 (.getClickCount ^MouseEvent e))
                                              (fn e)))))

(defn on-mouse! [^Node node fn]
  (.setOnMouseEntered node (when fn (event-handler e (fn :enter e))))
  (.setOnMouseExited node (when fn (event-handler e (fn :exit e))))
  (.setOnMouseMoved node (when fn (event-handler e (fn :move e)))))

(defn on-key! [^Node node key-fn]
  (.setOnKeyPressed node (event-handler e (key-fn (.getCode ^KeyEvent e)))))

(defn on-focus! [^Node node focus-fn]
  (observe (.focusedProperty node)
           (fn [observable old-val got-focus]
             (focus-fn got-focus))))

(defn load-fxml [path]
  (let [root ^Parent (FXMLLoader/load (io/resource path))
        css (io/file "editor.css")]
    (when (and (.exists css) (seq (.getStylesheets root)))
      (.setAll (.getStylesheets root) ^java.util.Collection (vec [(str (.toURI css))])))
    root))

(extend-type Node
  HasUserData
  (user-data [this key] (get (.getUserData this) key))
  (user-data! [this key val] (.setUserData this (assoc (or (.getUserData this) {}) key val)))
  Editable
  (editable [this] (.isDisabled this))
  (editable! [this val] (.setDisable this (not val))))

(extend-type MenuItem
  HasUserData
  (user-data [this key] (get (.getUserData this) key))
  (user-data! [this key val] (.setUserData this (assoc (or (.getUserData this) {}) key val))))

(extend-type Tab
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

(extend-type LongField
  HasValue
  (value [this] (Integer/parseInt (.getText this)))
  (value! [this val] (.setText this (str val))))

(extend-type TextInputControl
  HasValue
  (value [this] (text this))
  (value! [this val] (text! this val))
  Text
  (text [this] (.getText this))
  ; TODO: This is hack to reduce the cpu usage due to bug DEFEDIT-131
  (text! [this val] (when-not (= val (.getText this))
                      (.setText this val)
                      (when (.isFocused this)
                        (.end this)
                        (.selectAll this))))
  Editable
  (editable [this] (.isEditable this))
  (editable! [this val] (.setEditable this val))
  (on-edit! [this f] (observe (.textProperty this) (fn [this old new] (f old new)))))

(extend-type ComboBoxBase
  Editable
  (editable [this] (.isEditable this))
  (editable! [this val] (.setEditable this val))
  (on-edit! [this f] (observe (.valueProperty this) (fn [this old new] (f old new)))))

(extend-type CheckBox
  HasValue
  (value [this] (.isSelected this))
  (value! [this val] (.setSelected this val))
  Editable
  (editable [this] (not (.isDisabled this)))
  (editable! [this val] (.setDisable this (not val)))
  (on-edit! [this f] (observe (.selectedProperty this) (fn [this old new] (f old new)))))

(extend-type ColorPicker
  HasValue
  (value [this] (.getValue this))
  (value! [this val] (.setValue this val)))

(extend-type TextField
  HasAction
  (on-action! [this fn] (.setOnAction this (event-handler e (fn e))))
  Text
  (text [this] (.getText this))
  (text! [this val] (.setText this val)))

(extend-type Labeled
  Text
  (text [this] (.getText this))
  (text! [this val] (.setText this val)))

(extend-type Label
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
      (let [^ListCell this this
            render-data (and object (render-fn object))]
        (proxy-super updateItem object empty)
        (update-list-cell-style! this)
        (if (or (nil? object) empty)
          (do
            (proxy-super setText nil)
            (proxy-super setGraphic nil))
          (do
            (proxy-super setText (:text render-data))
            (when-let [style (:style render-data)]
              (proxy-super setStyle style))
            (when-let [icon (:icon render-data)]
              (proxy-super setGraphic (jfx/get-image-view icon 16)))))
        (proxy-super setTooltip (:tooltip render-data))))))

(defn- make-list-cell-factory [render-fn]
  (reify Callback (call ^ListCell [this view] (make-list-cell render-fn))))

(defn- make-tree-cell [render-fn]
  (let [cell (proxy [TreeCell] []
               (updateItem [resource empty]
                 (let [^TreeCell this this
                       render-data (and resource (render-fn resource))]
                   (proxy-super updateItem resource empty)
                   (update-tree-cell-style! this)
                   (if empty
                     (do
                       (proxy-super setText nil)
                       (proxy-super setGraphic nil))
                     (do
                       (when-let [text (:text render-data)]
                         (proxy-super setText text))
                       (when-let [icon (:icon render-data)]
                         (proxy-super setGraphic (jfx/get-image-view icon 16))))))))]
    cell))

(defn- make-tree-cell-factory [render-fn]
  (reify Callback (call ^TreeCell [this view] (make-tree-cell render-fn))))

(extend-type ButtonBase
  HasAction
  (on-action! [this fn] (.setOnAction this (event-handler e (fn e)))))

(extend-type ColorPicker
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
                      (mapv #(.getValue ^TreeItem %))))

  CollectionView
  (selection [this] (when-let [item ^TreeItem (.getSelectedItem (.getSelectionModel this))]
                      (.getValue item)))
  (cell-factory! [this render-fn]
    (.setCellFactory this (make-tree-cell-factory render-fn))))

(defn selection-roots [^TreeView tree-view path-fn id-fn]
  (let [selection (-> tree-view (.getSelectionModel) (.getSelectedItems))]
    (let [items (into {} (map #(do [(path-fn %) %]) (filter id-fn selection)))
          roots (loop [paths (keys items)
                       roots []]
                  (if-let [path (first paths)]
                    (let [ancestors (filter #(util/seq-starts-with? path %) roots)
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


;; context handling

;; context for TextInputControls so that we can retain default behaviours

(defn make-text-input-control-context
  [control]
  {:name :text-input-control
   :env {:control control}})

(defn- has-selection?
  [^TextInputControl control]
  (not (string/blank? (.getSelectedText control))))

(handler/defhandler :copy :text-input-control
  (enabled? [^TextInputControl control] (has-selection? control))
  (run [^TextInputControl control] (.copy control)))

(handler/defhandler :cut :text-input-control
  (enabled? [^TextInputControl control] (has-selection? control))
  (run [^TextInputControl control] (.cut control)))

(handler/defhandler :paste :text-input-control
  (enabled? [] (.. Clipboard getSystemClipboard hasString))
  (run [^TextInputControl control] (.paste control)))

(handler/defhandler :undo :text-input-control
  (enabled? [^TextInputControl control] (.isUndoable control))
  (run [^TextInputControl control] (.undo control)))

(handler/defhandler :redo :text-input-control
  (enabled? [^TextInputControl control] (.isRedoable control))
  (run [^TextInputControl control] (.redo control)))


(defn context!
  ([^Node node name env selection-provider]
    (context! node name env selection-provider {}))
  ([^Node node name env selection-provider dynamics]
    (user-data! node ::context {:name name
                                :env env
                                :selection-provider selection-provider
                                :dynamics dynamics})))

(defn context
  [^Node node]
  (cond
    (instance? TabPane node)
    (let [^TabPane tab-pane node
          ^Tab tab (.getSelectedItem (.getSelectionModel tab-pane))]
      (or (and tab (.getContent tab) (user-data (.getContent tab) ::context))
          (user-data node ::context)))
    
    (instance? TextInputControl node)
    (make-text-input-control-context node)
    
    :else
    (user-data node ::context)))

(defn complete-context
  [{:keys [selection-provider dynamics] :as context}]
  (cond-> context
    selection-provider
    (assoc-in [:env :selection] (workspace/selection selection-provider))

    dynamics
    (update :env merge (into {} (map (fn [[k [node v]]] [k (g/node-value (get-in context [:env node]) v)]) dynamics)))))

(defn- contexts []
  (let [main-scene (.getScene ^Stage @*main-stage*)
        initial-node (or (.getFocusOwner main-scene) (.getRoot main-scene))]
    (loop [^Node node initial-node
           ctxs []]
      (if-not node
        ctxs
        (if-let [ctx (context node)]
          (recur (.getParent node) (conj ctxs (complete-context ctx)))
          (recur (.getParent node) ctxs))))))

(defn extend-menu [id location menu]
  (swap! *menus* update id (comp distinct concat) (list {:location location :menu menu})))

(defn- collect-menu-extensions []
  (->>
    (flatten (vals @*menus*))
    (filter :location)
    (reduce (fn [acc x] (update-in acc [(:location x)] concat (:menu x))) {})))

(defn- do-realize-menu [menu exts]
  (->> menu
     (map (fn [x] (if (:children x)
                    (update-in x [:children] do-realize-menu exts)
                    x)))
     (mapcat (fn [x] (concat [x] (get exts (:id x)))))))

(defn- realize-menu [id]
  (let [exts (collect-menu-extensions)
        ;; *menus* is a map from id to a list of extensions, extension with location nil effectively root menu
        menu (:menu (first (filter (comp nil? :location) (get @*menus* id))))]
    (do-realize-menu menu exts)))

(defn- make-desc [control menu-id]
  {:control control
   :menu-id menu-id})

(defn- wrap-menu-image [node]
  (doto (Pane.)
    (children! [node])
    (add-style! "menu-image-wrapper")))

(defn- make-submenu [label icon menu-items on-open]
  (when (seq menu-items)
    (let [menu (Menu. label)]
      (when on-open
        (.setOnShowing menu (event-handler e (on-open))))
      (when icon
        (.setGraphic menu (wrap-menu-image (jfx/get-image-view icon 16))))
      (.addAll (.getItems menu) (to-array menu-items))
      menu)))

(defn- make-menu-command [label icon acc user-data command handler-ctx check]
  (let [^MenuItem menu-item (if check
                              (CheckMenuItem. label)
                              (MenuItem. label))
        key-combo           (and acc (KeyCombination/keyCombination acc))]
    (when command
      (.setId menu-item (name command)))
    (when key-combo
      (.setAccelerator menu-item key-combo)
      (swap! *menu-key-combos* conj key-combo))
    (when icon
      (.setGraphic menu-item (wrap-menu-image (jfx/get-image-view icon 16))))
    (.setDisable menu-item (not (handler/enabled? handler-ctx)))
    (.setOnAction menu-item (event-handler event (handler/run handler-ctx)))
    (user-data! menu-item ::menu-user-data user-data)
    menu-item))

(declare make-menu-items)

(defn- make-menu-item [item command-contexts]
  (let [icon (:icon item)
        item-label (:label item)
        on-open (:on-submenu-open item)]
    (if-let [children (:children item)]
      (make-submenu item-label icon (make-menu-items children command-contexts) on-open)
      (if (= item-label :separator)
        (SeparatorMenuItem.)
        (let [command (:command item)
              user-data (:user-data item)
              check (:check item)]
          (when-let [handler-ctx (handler/active command command-contexts user-data)]
            (let [label (or (handler/label handler-ctx) item-label)]
              (if-let [options (handler/options handler-ctx)]
                (make-submenu label icon (make-menu-items options command-contexts) on-open)
                (make-menu-command label icon (:acc item) user-data command handler-ctx check)))))))))

(defn- make-menu-items [menu command-contexts]
  (let [menu-items (->> menu
                        (map (fn [item] (make-menu-item item command-contexts)))
                        (remove nil?))]
    (when-let [head (first menu-items)]
      (add-style! head "first-menu-item"))
    (when-let [tail (last menu-items)]
      (add-style! tail "last-menu-item"))
    menu-items))

(defn- ^ContextMenu make-context-menu [menu-items]
  (let [^ContextMenu context-menu (ContextMenu.)]
    (.addAll (.getItems context-menu) (to-array menu-items))
    context-menu))

(defn register-context-menu [^Control control menu-id]
  (.addEventHandler control ContextMenuEvent/CONTEXT_MENU_REQUESTED
    (event-handler event
                   (when-not (.isConsumed event)
                     (let [cm (make-context-menu (make-menu-items (realize-menu menu-id) (contexts)))]
                       ;; Required for autohide to work when the event originates from the anchor/source control
                       ;; See RT-15160 and Control.java
                       (.setImpl_showRelativeToWindow cm true)
                       (.show cm control (.getScreenX ^ContextMenuEvent event) (.getScreenY ^ContextMenuEvent event))
                       (.consume event))))))

(defn register-tab-context-menu [^Tab tab menu-id]
  (let [cm (make-context-menu (make-menu-items (realize-menu menu-id) (contexts)))]
    (.setImpl_showRelativeToWindow cm true)
    (.setContextMenu tab cm)))

(defn register-menubar [^Scene scene  menubar-id menu-id ]
  ; TODO: See comment below about top-level items. Should be enforced here
 (let [root (.getRoot scene)]
   (if-let [menubar (.lookup root menubar-id)]
     (let [desc (make-desc menubar menu-id)]
       (user-data! root ::menubar desc))
     (log/warn :message (format "menubar %s not found" menubar-id))))
  (.addEventFilter scene KeyEvent/KEY_PRESSED
    (event-handler event
      (when (some (fn [^KeyCombination c] (.match c event)) @*menu-key-combos*)
        (.consume event)))))

(def ^:private invalidate-menus? (atom false))

(defn invalidate-menus! []
  (reset! invalidate-menus? true))

(defn- refresh-menubar [md command-contexts]
 (let [menu (realize-menu (:menu-id md))
       control ^MenuBar (:control md)]
   (when (or
          @invalidate-menus?
          (not= menu (user-data control ::menu))
          (not= command-contexts (user-data control ::command-contexts)))
     (reset! invalidate-menus? false)
     (.clear (.getMenus control))
     ; TODO: We must ensure that top-level element are of type Menu and note MenuItem here, i.e. top-level items with ":children"
     (.addAll (.getMenus control) (to-array (make-menu-items menu command-contexts)))
     (user-data! control ::menu menu)
     (user-data! control ::command-contexts command-contexts))))

(defn- refresh-menu-state [^Menu menu command-contexts]
  (doseq [m (.getItems menu)]
    (cond
      (instance? Menu m)
      (refresh-menu-state m command-contexts)

      (instance? CheckMenuItem m)
      (let [m         ^CheckMenuItem m
            command   (keyword (.getId ^MenuItem m))
            user-data (user-data m ::menu-user-data)
            handler-ctx (handler/active command command-contexts user-data)]
        (doto m
          (.setDisable (not (handler/enabled? handler-ctx)))
          (.setSelected (boolean (handler/state handler-ctx)))))

      (instance? MenuItem m)
      (let [m ^MenuItem m]
        (.setDisable m (not (-> (handler/active (keyword (.getId m))
                                                command-contexts
                                                (user-data m ::menu-user-data))
                              handler/enabled?)))))))

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
     (let [children (doall
                      (for [menu-item menu
                            :let [command (:command menu-item)
                                  user-data (:user-data menu-item)
                                  separator? (= :separator (:label menu-item))
                                  handler-ctx (handler/active command command-contexts user-data)]
                            :when (or separator? handler-ctx)]
                        (let [^Control child (if separator?
                                               (doto (Separator. Orientation/VERTICAL)
                                                 (add-style! "separator"))
                                               (if-let [opts (handler/options handler-ctx)]
                                                 (let [hbox (doto (HBox.)
                                                              (add-style! "cell"))
                                                       cb (doto (ChoiceBox.)
                                                            (.setConverter (proxy [StringConverter] []
                                                                             (fromString [str] (some #{str} (map :label opts)))
                                                                             (toString [v] (:label v)))))]
                                                   (observe (.valueProperty cb) (fn [this old new]
                                                                                  (when new
                                                                                    (some-> (handler/active (:command new) command-contexts (:user-data new))
                                                                                      handler/run))))
                                                   (doseq [opt opts]
                                                     (.add (.getItems cb) opt))
                                                   (.add (.getChildren hbox) (jfx/get-image-view (:icon menu-item) 22.5))
                                                   (.add (.getChildren hbox) cb)
                                                   hbox)
                                                 (let [button (ToggleButton. (or (handler/label handler-ctx) (:label menu-item)))
                                                       icon (:icon menu-item)
                                                       selection-provider (:selection-provider td)]
                                                   (when icon
                                                     (.setGraphic button (jfx/get-image-view icon 22.5)))
                                                   (when command
                                                     (on-action! button (fn [event] (handler/run handler-ctx))))
                                                   button)))]
                          (when command
                            (.setId child (name command)))
                          (user-data! child ::menu-user-data user-data)
                          child)))
           children (if (instance? Separator (last children))
                      (butlast children)
                      children)]
       (doseq [child children]
         (.add (.getChildren control) child))))))

(defn- refresh-toolbar-state [^Pane toolbar command-contexts]
  (let [nodes (.getChildren toolbar)
        ids (map #(.getId ^Node %) nodes)]
    (doseq [^Node n nodes
            :let [user-data (user-data n ::menu-user-data)
                  handler-ctx (handler/active (keyword (.getId n)) command-contexts user-data)]]
      (disable! n (not (handler/enabled? handler-ctx)))
      (when (instance? ToggleButton n)
        (if (handler/state handler-ctx)
          (.setSelected ^Toggle n true)
          (.setSelected ^Toggle n false)))
      (when (instance? HBox n)
        (let [^HBox box n
              state (handler/state handler-ctx)
              ^ChoiceBox cb (.get (.getChildren box) 1)]
          (when (not (.isShowing cb))
            (-> (.getSelectionModel cb)
              (.select state))))))))

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

(defn update-progress-controls! [progress ^ProgressBar bar ^Label label]
  (let [pctg (progress/percentage progress)]
    (.setProgress bar (if (nil? pctg) -1.0 (double pctg)))
    (when label
      (.setText label (progress/description progress)))))

(defn- update-progress!
  [progress]
  (let [root  (.. (main-stage) (getScene) (getRoot))
         tb    (.lookup root "#toolbar-status")
         bar   (.lookup tb ".progress-bar")
         label (.lookup tb ".label")]
    (update-progress-controls! progress bar label)))

(defn default-render-progress! [progress]
  (run-later (update-progress! progress)))

(defn init-progress!
  []
  (update-progress! progress/done))

(defmacro with-progress [bindings & body]
  `(let ~bindings
     (try
       ~@body
       (finally
         ((second ~bindings) progress/done)))))

(defn modal-progress [title total-work worker-fn]
  (run-now
   (let [root             ^Parent (load-fxml "progress.fxml")
         stage            (make-stage)
         scene            (Scene. root)
         title-control    ^Label (.lookup root "#title")
         progress-control ^ProgressBar (.lookup root "#progress")
         message-control  ^Label (.lookup root "#message")
         return           (atom nil)
         render-progress! (fn [progress]
                            (run-later
                             (update-progress-controls! progress progress-control message-control)))]
      (.setText title-control title)
      (.setProgress progress-control 0)
      (.initOwner stage @*main-stage*)
      (.initModality stage Modality/WINDOW_MODAL)
      (.setScene stage scene)
      (future
        (try
          (reset! return (worker-fn render-progress!))
          (catch Throwable e
            (log/error :exception e)
            (reset! return e)))
        (run-later (.close stage)))
      (.showAndWait stage)
      (if (instance? Throwable @return)
          (throw @return)
          @return))))

(def ^:private last-focused-node (atom nil))

(defn disable-ui [disabled]
  (let [scene       (.getScene (main-stage))
        focus-owner (.getFocusOwner scene)
        root        (.getRoot scene)]
    (.setDisable root disabled)
    (when-let [^Node node @last-focused-node]
      (.requestFocus node))
    (reset! last-focused-node focus-owner)))

(defmacro with-disabled-ui [& body]
  `(try
     (run-now (disable-ui true))
     ~@body
     (finally
       (run-now (disable-ui false)))))

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
  (cancel [this])
  (restart [this]))

(extend-type Timeline
  Future
  (cancel [this] (.stop this))
  (restart [this] (.playFromStart this)))

(defn ->future [delay run-fn]
  (let [^EventHandler handler (event-handler e (run-fn))
        ^"[Ljavafx.animation.KeyValue;" values (into-array KeyValue [])]
    ; TODO - fix reflection ctor warning
    (doto (Timeline. 60 (into-array KeyFrame [(KeyFrame. ^Duration (Duration/seconds delay) handler values)]))
      (.play))))

(defn ->timer
  ([name tick-fn]
    (->timer nil name tick-fn))
  ([fps name tick-fn]
   (let [last       (atom (System/nanoTime))
         interval   (when fps
                      (long (* 1e9 (/ 1 (double fps)))))
         last-error (atom nil)]
     {:last  last
      :timer (proxy [AnimationTimer] []
               (handle [now]
                 (profiler/profile "timer" name
                                   (let [delta (- now @last)]
                                     (when (or (nil? interval) (> delta interval))
                                       (try
                                         (tick-fn (* delta 1e-9))
                                         (reset! last-error nil)
                                         (reset! last (- now (if interval
                                                               (- delta interval)
                                                               0)))
                                         (catch Exception e
                                           (let [clj-ex (Throwable->map e)]
                                             (when (not= @last-error clj-ex)
                                               (println clj-ex)
                                               (reset! last-error clj-ex))))))))))})))

(defn timer-start! [timer]
  (.start ^AnimationTimer (:timer timer)))

(defn timer-stop! [timer]
  (.stop ^AnimationTimer (:timer timer)))

(defn anim! [duration anim-fn end-fn]
  (let [duration   (long (* 1e9 duration))
        start      (System/nanoTime)
        end        (+ start (long duration))
        last-error (atom nil)]
    (doto (proxy [AnimationTimer] []
            (handle [now]
              (if (< now end)
                (let [t (/ (double (- now start)) duration)]
                  (try
                    (anim-fn t)
                    (reset! last-error nil)
                    (catch Exception e
                      (let [clj-ex (Throwable->map e)]
                        (when (not= @last-error clj-ex)
                          (println clj-ex)
                          (reset! last-error clj-ex))))))
                (do
                  (end-fn)
                  (.stop ^AnimationTimer this)))))
      (.start))))

(defn anim-stop! [^AnimationTimer anim]
  (.stop anim))

(defn- chain-handler [new-handler-fn ^EventHandler existing-handler]
  (event-handler e
                 (new-handler-fn e)
                 (when existing-handler
                   (.handle existing-handler e))))

(defprotocol CloseRequestable
  (on-closing [this])
  (on-closing! [this f]))

(extend-type javafx.stage.Stage
  CloseRequestable
  (on-closing [this] (.getOnCloseRequest this))
  (on-closing! [this f]
    (.setOnCloseRequest this (chain-handler
                              (fn [^Event e]
                                (when-not (f e)
                                  (.consume e)))
                              (on-closing this)))))

(defprotocol Closeable
  (on-closed [this])
  (on-closed! [this f]))

(extend-protocol Closeable
  javafx.scene.control.Tab
  (on-closed [this] (.getOnClosed this))
  (on-closed! [this f] (.setOnClosed this (chain-handler f (on-closed this))))

  javafx.stage.Stage
  (on-closed [this] (.getOnHidden this))
  (on-closed! [this f] (.setOnHidden this (chain-handler f (on-closed this)))))

(defn timer-stop-on-closed!
  [closeable timer]
  (on-closed! closeable (fn [_]
                         (timer-stop! timer))))
                         
(defn drag-internal? [^DragEvent e]
  (some? (.getGestureSource e)))

(defn parent->stage ^Stage [^Parent parent]
  (.. parent getScene getWindow))
