(ns editor.properties-view
  (:require [clojure.set :as set]
            [camel-snake-kebab :as camel]
            [dynamo.graph :as g]
            [editor.protobuf :as protobuf]
            [editor.core :as core]
            [editor.dialogs :as dialogs]
            [editor.ui :as ui]
            [editor.jfx :as jfx]
            [editor.types :as types]
            [editor.properties :as properties]
            [editor.workspace :as workspace]
            [editor.resource :as resource])
  (:import [com.defold.editor Start]
           [com.dynamo.proto DdfExtensions]
           [com.google.protobuf ProtocolMessageEnum]
           [com.jogamp.opengl.util.awt Screenshot]
           [javafx.application Platform]
           [javafx.beans.value ChangeListener]
           [javafx.collections FXCollections ObservableList]
           [javafx.embed.swing SwingFXUtils]
           [javafx.event ActionEvent EventHandler]
           [javafx.fxml FXMLLoader]
           [javafx.geometry Insets Pos Point2D]
           [javafx.scene Scene Node Parent]
           [javafx.scene.control Control Button CheckBox ComboBox ColorPicker Label Slider TextField TextInputControl Tooltip TitledPane TextArea TreeItem Menu MenuItem MenuBar Tab ProgressBar]
           [javafx.scene.image Image ImageView WritableImage PixelWriter]
           [javafx.scene.input MouseEvent]
           [javafx.scene.layout Pane AnchorPane GridPane StackPane HBox VBox Priority]
           [javafx.scene.paint Color]
           [javafx.stage Stage FileChooser]
           [javafx.util Callback StringConverter]
           [java.io File]
           [java.nio.file Paths]
           [java.util.prefs Preferences]
           [javax.media.opengl GL GL2 GLContext GLProfile GLDrawableFactory GLCapabilities]
           [com.google.protobuf ProtocolMessageEnum]
           [editor.properties CurveSpread Curve]))

(set! *warn-on-reflection* true)

(def ^:private grid-hgap 4)
(def ^:private grid-vgap 6)

(defn- to-int [s]
  (try
    (Integer/parseInt s)
    (catch Throwable _
      nil)))

(defn- to-double [s]
 (try
   (Double/parseDouble s)
   (catch Throwable _
     nil)))

(declare update-field-message)

(defn- update-text-fn [^TextInputControl text values message read-only?]
  (ui/text! text (str (properties/unify-values values)))
  (update-field-message [text] message)
  (ui/editable! text (not read-only?)))

(defmulti create-property-control! (fn [edit-type _ property-fn] (:type edit-type)))

(defmethod create-property-control! String [_ _ property-fn]
  (let [text         (TextField.)
        update-ui-fn (partial update-text-fn text)
        update-fn    (fn [_]
                       (properties/set-values! (property-fn) (repeat (.getText text))))]
    (ui/on-action! text update-fn)
    (ui/on-focus! text (fn [got-focus] (and (not got-focus) (update-fn _))))
    [text update-ui-fn]))

(defmethod create-property-control! g/Int [_ _ property-fn]
  (let [text         (TextField.)
        update-ui-fn (partial update-text-fn text)
        update-fn    (fn [_]
                       (if-let [v (to-int (.getText text))]
                         (let [property (property-fn)]
                           (properties/set-values! property (repeat v))
                           (update-ui-fn (properties/values property)
                                         (properties/validation-message property)
                                         (properties/read-only? property)))))]
    (ui/on-action! text update-fn)
    (ui/on-focus! text (fn [got-focus] (and (not got-focus) (update-fn _))))
    [text update-ui-fn]))

(defmethod create-property-control! g/Num [_ _ property-fn]
  (let [text         (TextField.)
        update-ui-fn (partial update-text-fn text)
        update-fn    (fn [_] (if-let [v (to-double (.getText text))]
                               (properties/set-values! (property-fn) (repeat v))
                               (update-ui-fn (properties/values (property-fn))
                                             (properties/validation-message (property-fn)))))]
    (ui/on-action! text update-fn)
    (ui/on-focus! text (fn [got-focus] (and (not got-focus) (update-fn _))))
    [text update-ui-fn]))

(defmethod create-property-control! g/Bool [_ _ property-fn]
  (let [check (CheckBox.)
        update-ui-fn (fn [values message read-only?]
                       (let [v (properties/unify-values values)]
                         (if (nil? v)
                           (.setIndeterminate check true)
                           (doto check
                             (.setIndeterminate false)
                             (.setSelected v))))
                       (update-field-message [check] message)
                       (ui/editable! check (not read-only?)))]
    (ui/on-action! check (fn [_] (properties/set-values! (property-fn) (repeat (.isSelected check)))))
    [check update-ui-fn]))

(defn- create-property-component [ctrls]
  (doto (HBox.)
    (ui/add-style! "property-component")
    (ui/children! ctrls)))

(defn- create-multi-textfield! [labels property-fn]
  (let [text-fields  (mapv (fn [l] (TextField.)) labels)
        box          (doto (HBox.)
                       (.setAlignment (Pos/BASELINE_LEFT)))
        update-ui-fn (fn [values message read-only?]
                       (doseq [[^TextInputControl t v] (map-indexed (fn [i t]
                                                                      [t (str (properties/unify-values
                                                                               (map #(nth % i) values)))])
                                                                    text-fields)]
                         (ui/text! t v)
                         (ui/editable! t (not read-only?)))
                       (update-field-message text-fields message))]
    (doseq [[t f] (map-indexed (fn [i t]
                                 [t (fn [_]
                                      (let [v            (to-double (.getText ^TextField t))
                                            current-vals (properties/values (property-fn))]
                                        (if v
                                          (properties/set-values! (property-fn) (mapv #(assoc (vec %) i v) current-vals))
                                          (update-ui-fn current-vals (properties/validation-message (property-fn))))))])
                               text-fields)]
      (ui/on-action! ^TextField t f)
      (ui/on-focus! t (fn [got-focus] (and (not got-focus) (f nil)))))
    (doseq [[t label] (map vector text-fields labels)]
      (HBox/setHgrow ^TextField t Priority/SOMETIMES)
      (.setPrefWidth ^TextField t 60)
      (.add (.getChildren box) (create-property-component [(Label. label) t])))

    [box update-ui-fn]))

(defmethod create-property-control! types/Vec3 [_ _ property-fn]
  (create-multi-textfield! ["X" "Y" "Z"] property-fn))

(defmethod create-property-control! types/Vec4 [_ _ property-fn]
  (create-multi-textfield! ["X" "Y" "Z" "W"] property-fn))

(defn- create-multi-keyed-textfield! [fields property-fn]
  (let [text-fields  (mapv (fn [_] (TextField.)) fields)
        box          (doto (HBox.)
                       (.setAlignment (Pos/BASELINE_LEFT)))
        update-ui-fn (fn [values message read-only?]
                       (doseq [[^TextInputControl t v] (map (fn [f t] [t (str (properties/unify-values (map #(get-in % (:path f)) values)))]) fields text-fields)]
                         (ui/text! t v)
                         (ui/editable! t (not read-only?)))
                       (update-field-message text-fields message))]
    (doseq [[t f] (map (fn [f t]
                         [t (fn [_]
                              (let [v            (to-double (.getText ^TextField t))
                                    current-vals (properties/values (property-fn))]
                                (if v
                                  (properties/set-values! (property-fn) (mapv #(assoc-in % (:path f) v) current-vals))
                                  (update-ui-fn current-vals (properties/validation-message (property-fn))))))])
                       fields text-fields)]
      (ui/on-action! ^TextField t f)
      (ui/on-focus! t (fn [got-focus] (and (not got-focus) (f nil)))))
    (doseq [[t f] (map vector text-fields fields)
            :let  [children (if (:label f)
                              [(Label. (:label f)) t]
                              [t])]]
      (HBox/setHgrow ^TextField t Priority/SOMETIMES)
      (.setPrefWidth ^TextField t 60)
      (-> (.getChildren box)
        (.add (create-property-component children))))
    [box update-ui-fn]))

(defmethod create-property-control! CurveSpread [_ _ property-fn]
  (let [fields [{:label "Value" :path [:points 0 :y]} {:label "Spread" :path [:spread]}]]
    (create-multi-keyed-textfield! fields property-fn)))

(defmethod create-property-control! Curve [_ _ property-fn]
  (let [fields [{:path [:points 0 :y]}]]
    (create-multi-keyed-textfield! fields property-fn)))

(defmethod create-property-control! types/Color [_ _ property-fn]
 (let [color-picker (ColorPicker.)
       update-ui-fn (fn [values message read-only?]
                      (let [v (properties/unify-values values)]
                        (if (nil? v)
                          (.setValue color-picker nil)
                          (let [[r g b a] v]
                            (.setValue color-picker (Color. r g b a)))))
                      (update-field-message [color-picker] message)
                      (ui/editable! color-picker (not read-only?)))]
   (ui/on-action! color-picker (fn [_] (let [^Color c (.getValue color-picker)
                                             v [(.getRed c) (.getGreen c) (.getBlue c) (.getOpacity c)]]
                                         (properties/set-values! (property-fn) (repeat v)))))
   [color-picker update-ui-fn]))

(def ^:private ^:dynamic *programmatic-setting* nil)

(defmethod create-property-control! :choicebox [edit-type _ property-fn]
  (let [options (:options edit-type)
        inv-options (clojure.set/map-invert options)
        converter (proxy [StringConverter] []
                    (toString [value]
                      (get options value (str value)))
                    (fromString [s]
                      (inv-options s)))
        cb (doto (ComboBox.)
             (-> (.getItems) (.addAll (object-array (map first options))))
             (.setConverter converter)
             (ui/cell-factory! (fn [val]  {:text (options val)})))
        update-ui-fn (fn [values message read-only?]
                       (binding [*programmatic-setting* true]
                         (let [value (properties/unify-values values)]
                           (if (contains? options value)
                             (.setValue cb (get options value))
                             (do
                               (.setValue cb nil)
                               (.. cb (getSelectionModel) (clearSelection)))))
                         (update-field-message [cb] message)
                         (ui/editable! cb (not read-only?))))]
    (ui/observe (.valueProperty cb) (fn [observable old-val new-val]
                                      (when-not *programmatic-setting*
                                        (properties/set-values! (property-fn) (repeat new-val)))))
    [cb update-ui-fn]))

(defmethod create-property-control! (g/protocol resource/Resource) [edit-type workspace property-fn]
  (let [box (HBox.)
        button (doto (Button. "...") (ui/add-style! "small-button"))
        text (TextField.)
        from-type (or (:from-type edit-type) identity)
        to-type (or (:to-type edit-type) identity)
        dialog-opts (if (:ext edit-type) {:ext (:ext edit-type)} {})
        update-ui-fn (fn [values message read-only?]
                       (let [val (properties/unify-values (map to-type values))]
                         (ui/text! text (when val (resource/proj-path val))))
                       (update-field-message [text] message)
                       (ui/editable! text (not read-only?))
                       (ui/editable! button (not read-only?)))]
    (ui/add-style! box "composite-property-control-container")
    (ui/on-action! button (fn [_]  (when-let [resource (first (dialogs/make-resource-dialog workspace dialog-opts))]
                                     (properties/set-values! (property-fn) (repeat (from-type resource))))))
    (ui/on-action! text (fn [_] (let [path (ui/text text)
                                      resource (workspace/resolve-workspace-resource workspace path)]
                                  (properties/set-values! (property-fn) (repeat (from-type resource))))))
    (ui/children! box [text button])
    [box update-ui-fn]))

(defmethod create-property-control! :slider [edit-type workspace property-fn]
  (let [box (HBox. 4.0)
        [^TextField textfield tf-update-ui-fn] (create-property-control! {:type g/Num} workspace property-fn)
        min (:min edit-type 0.0)
        max (:max edit-type 1.0)
        val (:value edit-type max)
        precision (:precision edit-type)
        slider (Slider. min max val)
        update-ui-fn (fn [values message read-only?]
                       (tf-update-ui-fn values message read-only?)
                       (binding [*programmatic-setting* true]
                         (if-let [v (properties/unify-values values)]
                           (doto slider
                             (.setDisable false)
                             (.setValue v))
                           (.setDisable slider true))
                         (update-field-message [slider] message)
                         (ui/editable! slider (not read-only?))))]
    (HBox/setHgrow slider Priority/ALWAYS)
    (.setPrefColumnCount textfield (if precision (count (str precision)) 5))
    (ui/observe (.valueChangingProperty slider) (fn [observable old-val new-val]
                                                  (ui/user-data! slider ::op-seq (gensym))))
    (ui/observe (.valueProperty slider) (fn [observable old-val new-val]
                                          (when-not *programmatic-setting*
                                            (let [val (if precision
                                                        (* precision (Math/round (double (/ new-val precision))))
                                                        new-val)]
                                              (properties/set-values! (property-fn) (repeat val) (ui/user-data slider ::op-seq))))))
    (ui/children! box [textfield slider])
    [box update-ui-fn]))

(defmethod create-property-control! :default [_ _ _]
  (let [text (TextField.)
        wrapper (HBox.)
        update-ui-fn (fn [values message read-only?]
                       (ui/text! text (properties/unify-values (map str values)))
                       (update-field-message [wrapper] message)
                       (ui/editable! text (not read-only?)))]
    (HBox/setHgrow text Priority/ALWAYS)
    (ui/children! wrapper [text])
    (.setDisable text true)
    [wrapper update-ui-fn]))

(defn- ^Point2D node-screen-coords [^Node node ^Point2D offset]
  (let [scene (.getScene node)
        window (.getWindow scene)
        window-coords (Point2D. (.getX window) (.getY window))
        scene-coords (Point2D. (.getX scene) (.getY scene))
        node-coords (.localToScene node (.getX offset) (.getY offset))]
    (.add node-coords (.add scene-coords window-coords))))

(def ^:private severity-tooltip-style-map
  {g/FATAL "tooltip-error"
   g/SEVERE "tooltip-error"
   g/WARNING "tooltip-warning"
   g/INFO "tooltip-warning"})

(defn- show-message-tooltip [^Node control]
  (when-let [tip (ui/user-data control ::field-message)]
    ;; FIXME: Hack to position tooltip somewhat below control so .show doesn't immediately trigger an :exit.
    (let [tooltip (Tooltip. (:message tip))
          control-bounds (.getLayoutBounds control)
          tooltip-coords (node-screen-coords control (Point2D. 0.0 (+ (.getHeight control-bounds) 11.0)))]
      (ui/add-style! tooltip (severity-tooltip-style-map (:severity tip)))
      (ui/user-data! control ::tooltip tooltip)
      (.show tooltip control (.getX tooltip-coords) (.getY tooltip-coords)))))

(defn- hide-message-tooltip [control]
  (when-let [tooltip ^Tooltip (ui/user-data control ::tooltip)]
    (ui/user-data! control ::tooltip nil)
    (.hide tooltip)))

(defn- update-message-tooltip [control]
  (when (ui/user-data control ::tooltip)
    (hide-message-tooltip control)
    (show-message-tooltip control)))

(defn- install-tooltip-message [ctrl msg]
  (ui/user-data! ctrl ::field-message msg)
  (ui/on-mouse! ctrl
    (when msg
      (fn [verb e]
        (condp = verb
          :enter (show-message-tooltip ctrl)
          :exit (hide-message-tooltip ctrl)
          :move nil)))))

(def ^:private severity-field-style-map
  {g/FATAL "field-error"
   g/SEVERE "field-error"
   g/WARNING "field-warning"
   g/INFO "field-warning"})

(defn- update-field-message-style [ctrl msg]
  (if msg
    (ui/add-style! ctrl (severity-field-style-map (:severity msg)))
    (ui/remove-styles! ctrl (vals severity-field-style-map))))

(defn- update-field-message [ctrls msg]
  (doseq [ctrl ctrls]
    (install-tooltip-message ctrl msg)
    (update-field-message-style ctrl msg)
    (if msg
      (update-message-tooltip ctrl)
      (hide-message-tooltip ctrl))))

(defn- create-property-label [label]
  (doto (Label. label) (ui/add-style! "property-label")))

(defn- create-properties-row [workspace ^GridPane grid key property row property-fn]
  (let [label (create-property-label (properties/label property))
        [^Node control update-ctrl-fn] (create-property-control! (:edit-type property) workspace (fn [] (property-fn key)))
        reset-btn (doto (Button. "x")
                    (.setVisible (properties/overridden? property))
                    (ui/add-styles! ["clear-button" "small-button"])
                    (ui/on-action! (fn [_]
                                     (properties/clear-override! (property-fn key))
                                     (.requestFocus control))))
        update-ui-fn (fn [property]
                       (let [overridden? (properties/overridden? property)]
                         (if overridden?
                           (ui/add-style! control "overridden")
                           (ui/remove-style! control "overridden"))
                         (.setVisible reset-btn overridden?)
                         (update-ctrl-fn (properties/values property)
                                         (properties/validation-message property)
                                         (properties/read-only? property))))]

    (update-ui-fn property)

    (GridPane/setConstraints label 1 row)
    (GridPane/setConstraints control 2 row)
    (GridPane/setConstraints reset-btn 3 row)

    (.add (.getChildren grid) label)
    (.add (.getChildren grid) control)
    (.add (.getChildren grid) reset-btn)

    [key update-ui-fn]))

(defn- create-properties [workspace grid properties property-fn]
  ; TODO - add multi-selection support for properties view
  (doall (map-indexed (fn [row [key property]] (create-properties-row workspace grid key property row property-fn)) properties)))

(defn- make-grid [parent workspace properties property-fn]
  (let [grid (GridPane.)]
      (.setHgap grid grid-hgap)
      (.setVgap grid grid-vgap)
      (ui/add-child! parent grid)
      (ui/add-style! grid "form")
      (create-properties workspace grid properties property-fn)))

(defn- create-category-label [label]
  (doto (Label. label) (ui/add-style! "property-category")))

(defn- make-pane [parent workspace properties]
  (let [vbox (VBox. (double 10.0))]
      (.setPadding vbox (Insets. 10 10 10 10))
      (ui/user-data! vbox ::properties properties)
      (let [property-fn (fn [key]
                          (let [properties (:properties (ui/user-data vbox ::properties))]
                            (get properties key)))
            display-order (:display-order properties)
            properties (:properties properties)
            generics [nil (mapv (fn [k] [k (get properties k)]) (filter (comp not properties/category?) display-order))]
            categories (mapv (fn [order]
                               [(first order) (mapv (fn [k] [k (get properties k)]) (rest order))])
                             (filter properties/category? display-order))
            update-fns (loop [sections (cons generics categories)
                              result []]
                         (if-let [[category properties] (first sections)]
                           (let [update-fns (if (empty? properties)
                                              []
                                              (do
                                                (when category
                                                  (let [label (create-category-label category)]
                                                    (ui/add-child! vbox label)))
                                                (make-grid vbox workspace properties property-fn)))]
                             (recur (rest sections) (into result update-fns)))
                           result))]
        ; NOTE: Note update-fns is a sequence of [[property-key update-ui-fn] ...]
        (ui/user-data! parent ::update-fns (into {} update-fns)))
      (ui/children! parent [vbox])
      vbox))

(defn- refresh-pane [parent ^Pane pane workspace properties]
  (ui/user-data! pane ::properties properties)
  (let [update-fns (ui/user-data parent ::update-fns)]
    (doseq [[key property] (:properties properties)]
      (when-let [update-ui-fn (get update-fns key)]
        (update-ui-fn property)))))

(defn- properties->template [properties]
  (mapv (fn [[k v]] [k (select-keys v [:edit-type])]) (:properties properties)))

(defn- update-pane [parent id workspace properties]
  ; NOTE: We cache the ui based on the ::template user-data
  (let [properties (properties/coalesce properties)
        template (properties->template properties)
        prev-template (ui/user-data parent ::template)]
    (if (not= template prev-template)
      (let [pane (make-pane parent workspace properties)]
        (ui/user-data! parent ::template template)
        (g/set-property! id :prev-pane pane)
        pane)
      (do
        (let [pane (g/node-value id :prev-pane)]
          (refresh-pane parent pane workspace properties)
          pane)))))

(g/defnode PropertiesView
  (property parent-view Parent)
  (property workspace g/Any)
  (property prev-pane Pane)

  (input selected-node-properties g/Any)

  (output pane Pane :cached (g/fnk [parent-view _node-id workspace selected-node-properties]
                                   (update-pane parent-view _node-id workspace selected-node-properties))))

(defn make-properties-view [workspace project view-graph ^Node parent]
  (let [view-id       (g/make-node! view-graph PropertiesView :parent-view parent :workspace workspace)
        stage         (.. parent getScene getWindow)
        refresh-timer (ui/->timer 10 (fn [now] (g/node-value view-id :pane)))]
    (g/connect! project :selected-node-properties view-id :selected-node-properties)
    (ui/timer-stop-on-close! stage refresh-timer)
    (ui/timer-start! refresh-timer)
    view-id))
