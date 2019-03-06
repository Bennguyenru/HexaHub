(ns editor.scene
  (:require [clojure.set :as set]
            [dynamo.graph :as g]
            [editor.background :as background]
            [editor.camera :as c]
            [editor.colors :as colors]
            [editor.error-reporting :as error-reporting]
            [editor.geom :as geom]
            [editor.gl :as gl]
            [editor.gl.pass :as pass]
            [editor.graph-util :as gu]
            [editor.grid :as grid]
            [editor.handler :as handler]
            [editor.input :as i]
            [editor.math :as math]
            [editor.properties :as properties]
            [editor.resource :as resource]
            [editor.rulers :as rulers]
            [editor.scene-async :as scene-async]
            [editor.scene-cache :as scene-cache]
            [editor.scene-picking :as scene-picking]
            [editor.scene-selection :as selection]
            [editor.scene-text :as scene-text]
            [editor.scene-tools :as scene-tools]
            [editor.system :as system]
            [editor.types :as types]
            [editor.ui :as ui]
            [editor.view :as view]
            [editor.workspace :as workspace]
            [service.log :as log]
            [util.profiler :as profiler])
  (:import [com.jogamp.opengl GL GL2 GLAutoDrawable GLContext GLOffscreenAutoDrawable]
           [com.jogamp.opengl.glu GLU]
           [com.jogamp.opengl.util GLPixelStorageModes]
           [editor.types AABB Camera Rect Region]
           [java.awt.image BufferedImage]
           [java.lang Runnable Math]
           [java.nio IntBuffer]
           [javafx.embed.swing SwingFXUtils]
           [javafx.geometry HPos VPos]
           [javafx.scene Node Parent]
           [javafx.scene.image ImageView WritableImage]
           [javafx.scene.input KeyCode KeyEvent]
           [javafx.scene.layout AnchorPane Pane]
           [javax.vecmath Matrix4d Point3d Quat4d Vector3d Vector4d]
           [sun.awt.image IntegerComponentRaster]))

(set! *warn-on-reflection* true)

(defn overlay-text [^GL2 gl ^String text x y]
  (scene-text/overlay gl text x y))

(defn- get-resource-name [node-id]
  (let [{:keys [resource] :as resource-node} (and node-id (g/node-by-id node-id))]
    (and resource (resource/resource-name resource))))

(defn- root-causes
  [error]
  (->> (tree-seq :causes :causes error)
       (filter :message)
       (remove :causes)))

(defn- render-error
  [gl render-args renderables nrenderables]
  (when (= pass/overlay (:pass render-args))
    (let [errors (->> renderables
                      (map (comp :error :user-data))
                      (mapcat root-causes)
                      (map #(select-keys % [:_node-id :message]))
                      set)]
      (scene-text/overlay gl "Render error:" 24.0 -22.0)
      (doseq [[n error] (partition 2 (interleave (range) errors))]
        (let [message (format "- %s: %s"
                              (or (get-resource-name (:_node-id error))
                                  "unknown")
                              (:message error))]
          (scene-text/overlay gl message 24.0 (- -22.0 (* 14 (inc n)))))))))

(defn substitute-render-data
  [error]
  [{pass/overlay [{:render-fn render-error
                   :user-data {:error error}
                   :batch-key ::error}]}])

(defn substitute-scene [error]
  {:aabb       (geom/null-aabb)
   :renderable {:render-fn render-error
                :user-data {:error error}
                :batch-key ::error
                :passes    [pass/overlay]}})

;; Avoid recreating the image each frame
(defonce ^:private cached-buf-img-ref (atom nil))

;; Replacement for Screenshot/readToBufferedImage but without expensive y-axis flip.
;; We flip in JavaFX instead
(defn- read-to-buffered-image [^long w ^long h]
  (let [^BufferedImage image (let [^BufferedImage image @cached-buf-img-ref]
                               (when (or (nil? image) (not= (.getWidth image) w) (not= (.getHeight image) h))
                                 (reset! cached-buf-img-ref (BufferedImage. w h BufferedImage/TYPE_INT_ARGB_PRE)))
                               @cached-buf-img-ref)
        glc (GLContext/getCurrent)
        gl (.getGL glc)
        psm (GLPixelStorageModes.)]
   (.setPackAlignment psm gl 1)
   (.glReadPixels gl 0 0 w h GL2/GL_BGRA GL/GL_UNSIGNED_BYTE (IntBuffer/wrap (.getDataStorage ^IntegerComponentRaster (.getRaster image))))
   (.restore psm gl)
   image))

(def outline-color colors/bright-grey)
(def selected-outline-color colors/defold-turquoise)

(defn select-color [pass selected object-color]
  (if (= pass pass/outline)
    (if selected selected-outline-color outline-color)
    object-color))

(defn vp-dims [^Region viewport]
  (types/dimensions viewport))

(defn vp-not-empty? [^Region viewport]
  (not (types/empty-space? viewport)))

(defn z-distance [^Matrix4d view-proj ^Matrix4d world-transform]
  (let [^Matrix4d t (or world-transform geom/Identity4d)
        tmp-v4d (Vector4d.)]
    (.getColumn t 3 tmp-v4d)
    (.transform view-proj tmp-v4d)
    (let [ndc-z (/ (.z tmp-v4d) (.w tmp-v4d))
          wz (min 1.0 (max 0.0 (* (+ ndc-z 1.0) 0.5)))]
      (long (* Integer/MAX_VALUE (max 0.0 wz))))))

(defn- render-key [^Matrix4d view-proj ^Matrix4d world-transform index topmost?]
  [(boolean topmost?)
   (if topmost? Long/MAX_VALUE (- Long/MAX_VALUE (z-distance view-proj world-transform)))
   (or index 0)])

(defn- outline-render-key [^Matrix4d view-proj ^Matrix4d world-transform index topmost? selected?]
  ;; Draw selection outlines on top of other outlines.
  [(boolean selected?)
   (boolean topmost?)
   (if topmost? Long/MAX_VALUE (- Long/MAX_VALUE (z-distance view-proj world-transform)))
   (or index 0)])

(defn gl-viewport [^GL2 gl ^Region viewport]
  (.glViewport gl (.left viewport) (.top viewport) (- (.right viewport) (.left viewport)) (- (.bottom viewport) (.top viewport))))

(defn setup-pass
  [^GL2 gl pass render-args]
  (let [glu (GLU.)]
    (.glMatrixMode gl GL2/GL_PROJECTION)
    (gl/gl-load-matrix-4d gl (:projection render-args))
    (.glMatrixMode gl GL2/GL_MODELVIEW)
    (gl/gl-load-matrix-4d gl (:world-view render-args))
    (pass/prepare-gl pass gl glu)))

(defn- render-nodes
  [^GL2 gl render-args renderables count]
  (when-let [render-fn (:render-fn (first renderables))]
    (try
      (let [shared-world-transform (or (:world-transform (first renderables)) geom/Identity4d) ; rulers apparently don't have world-transform
            shared-render-args (merge render-args
                                      (math/derive-render-transforms shared-world-transform
                                                                     (:view render-args)
                                                                     (:projection render-args)
                                                                     (:texture render-args)))]
        (render-fn gl shared-render-args renderables count))
      (catch Exception e
        (log/error :exception e
                   :pass (:pass render-args)
                   :render-fn render-fn
                   :message "skipping renderable")))))

(defn batch-render [gl render-args renderables key-fn]
  (loop [renderables renderables
         offset 0
         batch-index 0
         batches (transient [])]
    (if-let [renderable (first renderables)]
      (let [first-key (key-fn renderable)
            first-render-fn (:render-fn renderable)
            batch-count (loop [renderables (rest renderables)
                               batch-count 1]
                          (let [renderable (first renderables)
                                key (key-fn renderable)
                                render-fn (:render-fn renderable)
                                break? (or (not= first-render-fn render-fn)
                                           (nil? first-key)
                                           (nil? key)
                                           (not= first-key key))]
                            (if break?
                              batch-count
                              (recur (rest renderables) (inc batch-count)))))]
        (when (> batch-count 0)
          (let [batch (subvec renderables 0 batch-count)]
            (render-nodes gl render-args batch batch-count))
          (let [end (+ offset batch-count)]
            ;; TODO - long conversion should not be necessary?
            (recur (subvec renderables batch-count) (long end) (inc batch-index) (conj! batches [offset end])))))
      (persistent! batches))))

(defn- render-sort [renderables]
  (sort-by :render-key renderables))

(defn pass-render-args [^Region viewport ^Camera camera pass]
  (let [view (if (types/model-transform? pass)
               (c/camera-view-matrix camera)
               geom/Identity4d)
        proj (if (types/model-transform? pass)
               (c/camera-projection-matrix camera)
               (c/region-orthographic-projection-matrix viewport -1.0 1.0)) ; used for background & tile map overlay so no need to use camera settings
        world geom/Identity4d
        texture geom/Identity4d
        transforms (math/derive-render-transforms world view proj texture)]
    (assoc transforms
           :pass pass
           :camera camera
           :viewport viewport)))

(defn- assoc-updatable-states
  [renderables updatable-states]
  (mapv (fn [renderable]
          (if-let [updatable-node-id (get-in renderable [:updatable :node-id])]
            (assoc-in renderable [:updatable :state] (get-in updatable-states [updatable-node-id]))
            renderable))
        renderables))

(defn- picking-render-args [render-args ^Region viewport ^Rect picking-rect]
  (let [{:keys [world view ^Matrix4d projection texture]} render-args
        picking-matrix (c/pick-matrix viewport picking-rect)
        projection' (doto (Matrix4d. picking-matrix) (.mul projection))]
    (merge render-args
           (math/derive-render-transforms world view projection' texture))))

(def render-mode-transitions {:normal :picking-color
                              :picking-color :picking-rect
                              :picking-rect :normal})
(def render-mode-atom (atom :normal))
(def last-picking-rect (atom nil))

(defn render! [^GLContext context renderables updatable-states viewport pass->render-args]
  (let [^GL2 gl (.getGL context)
        render-mode @render-mode-atom]
    (gl/gl-clear gl 0.0 0.0 0.0 1)
    (.glColor4f gl 1.0 1.0 1.0 1.0)
    (gl-viewport gl viewport)
    (doseq [pass (if (= render-mode :normal) pass/render-passes pass/selection-passes)
            :let [pass-render-args (cond-> (pass->render-args pass)
                                     (and (= render-mode :picking-rect)
                                          (some? @last-picking-rect))
                                     (picking-render-args viewport @last-picking-rect))
                  pass-renderables (-> (get renderables pass)
                                       (assoc-updatable-states updatable-states))]]
      (setup-pass gl pass pass-render-args)
      (batch-render gl pass-render-args pass-renderables :batch-key))))

(defn- apply-pass-overrides
  [pass renderable]
  ;; No nested :pass-overrides like {... :pass-overrides {pass/outline {:pass-overrides {...}}}}
  (-> (merge renderable (get-in renderable [:pass-overrides pass]))
      (dissoc :pass-overrides)))

(defn- make-pass-renderables
  []
  (into {} (map #(vector % (transient []))) pass/all-passes))

(defn- persist-pass-renderables!
  [pass-renderables]
  (into {}
        (map (fn [[pass renderables]]
               [pass (persistent! renderables)]))
        pass-renderables))

(defn- update-pass-renderables!
  [pass-renderables passes flattened-renderable]
  (reduce (fn [pass-renderables pass]
            (update pass-renderables pass conj! (apply-pass-overrides pass flattened-renderable)))
          pass-renderables
          passes))

(defn- flatten-scene-renderables! [pass-renderables
                                   scene
                                   selection-set
                                   hidden-renderable-tags
                                   hidden-node-outline-key-paths
                                   view-proj
                                   node-id-path
                                   node-outline-key-path
                                   ^Quat4d parent-world-rotation
                                   ^Vector3d parent-world-scale
                                   ^Matrix4d parent-world-transform
                                   alloc-picking-id!]
  (let [renderable (:renderable scene)
        local-transform ^Matrix4d (:transform scene geom/Identity4d)
        world-transform (doto (Matrix4d. parent-world-transform) (.mul local-transform))
        local-transform-unscaled (doto (Matrix4d. local-transform) (.setScale 1.0))
        local-rotation (doto (Quat4d.) (.set local-transform-unscaled))
        world-translation (math/transform parent-world-transform (math/translation local-transform))
        world-rotation (doto (Quat4d. parent-world-rotation) (.mul local-rotation))
        world-scale (math/multiply-vector parent-world-scale (math/scale local-transform))
        appear-selected? (some? (some selection-set node-id-path)) ; Child nodes appear selected if parent is.
        picking-node-id (or (:picking-node-id scene) (peek node-id-path))
        flat-renderable (-> scene
                            (dissoc :children :renderable)
                            (assoc :node-id-path node-id-path
                                   :node-outline-key-path node-outline-key-path
                                   :picking-node-id picking-node-id
                                   :picking-id (alloc-picking-id! picking-node-id)
                                   :tags (:tags renderable)
                                   :render-fn (:render-fn renderable)
                                   :world-translation world-translation
                                   :world-rotation world-rotation
                                   :world-scale world-scale
                                   :world-transform world-transform
                                   :parent-world-transform parent-world-transform
                                   :selected appear-selected?
                                   :user-data (:user-data renderable)
                                   :batch-key (:batch-key renderable)
                                   :aabb (geom/aabb-transform ^AABB (:aabb scene (geom/null-aabb)) parent-world-transform)
                                   :render-key (render-key view-proj world-transform (:index renderable) (:topmost? renderable))
                                   :pass-overrides {pass/outline {:render-key (outline-render-key view-proj world-transform (:index renderable) (:topmost? renderable) appear-selected?)}}))
        visible? (and (not (contains? hidden-node-outline-key-paths node-outline-key-path))
                      (not-any? (partial contains? hidden-renderable-tags) (:tags flat-renderable)))
        drawn-passes (cond
                       ;; Draw to all passes unless hidden.
                       visible?
                       (:passes renderable)

                       ;; For selected objects, we always draw the outline and
                       ;; selection passes. This way, the visual part is hidden,
                       ;; but the selection highlight and hit test remains until
                       ;; the object is deselected. If we do not render the
                       ;; selection pass, objects can be deselected by clicking
                       ;; within their outlines, and more importantly, the
                       ;; manipulator disappears, since it aligns to selection
                       ;; pass renderables.
                       appear-selected?
                       (filterv #(or (= pass/outline %)
                                     (= pass/selection %))
                                (:passes renderable)))
        pass-renderables (update-pass-renderables! pass-renderables drawn-passes flat-renderable)]
    (reduce (fn [pass-renderables child-scene]
              (let [parent-node-id (:node-id scene)
                    child-node-id (:node-id child-scene)
                    child-node-id-path (if (= parent-node-id child-node-id)
                                         node-id-path
                                         (conj node-id-path child-node-id))
                    child-node-outline-key-path (if (= parent-node-id child-node-id)
                                                  node-outline-key-path
                                                  (conj node-outline-key-path (:node-outline-key child-scene)))]
                (flatten-scene-renderables! pass-renderables
                                            child-scene
                                            selection-set
                                            hidden-renderable-tags
                                            hidden-node-outline-key-paths
                                            view-proj
                                            child-node-id-path
                                            child-node-outline-key-path
                                            world-rotation
                                            world-scale
                                            world-transform
                                            alloc-picking-id!)))
            pass-renderables
            (:children scene))))

;; Picking id's are in the range 1..2^24-1. To more easily distinguish
;; consecutive picking seeds (by color) we shuffle the bits by
;; multiplying by picking-id-multiplier modulo 2^24. picking-id-multiplier is
;; coprime to 2^24 so there will be no collisions. picking-id-inverse
;; can be used to find the original picking-seed if need be.
(def ^:private picking-id-multiplier 0x5b1047)
#_(def picking-id-inverse 0x79977)

(defn- picking-seed->picking-id [picking-seed]
  (assert (<= picking-seed 0x00ffffff))
  (let [picking-id (bit-and (* picking-seed picking-id-multiplier) 0x00ffffff)]
    (assert (<= picking-id 0x00ffffff))
    picking-id))

(defn- alloc-picking-id! [node-id->picking-id-atom node-id]
  (if-let [picking-id (get @node-id->picking-id-atom node-id)]
    picking-id
    (get (swap! node-id->picking-id-atom
                (fn [node-id->picking-id]
                  (let [picking-seed (inc (count node-id->picking-id))
                        picking-id (picking-seed->picking-id picking-seed)]
                    (assoc node-id->picking-id node-id picking-id))))
         node-id)))

(defn- flatten-scene [scene selection-set hidden-renderable-tags hidden-node-outline-key-paths view-proj]
  (let [node-id->picking-id-atom (atom {})
        alloc-picking-id! (partial alloc-picking-id! node-id->picking-id-atom)
        node-id-path []
        node-outline-key-path [(:node-id scene)]
        parent-world-rotation geom/NoRotation
        parent-world-transform geom/Identity4d
        parent-world-scale (Vector3d. 1 1 1)]
    (-> (make-pass-renderables)
        (flatten-scene-renderables! scene
                                    selection-set
                                    hidden-renderable-tags
                                    hidden-node-outline-key-paths
                                    view-proj
                                    node-id-path
                                    node-outline-key-path
                                    parent-world-rotation
                                    parent-world-scale
                                    parent-world-transform
                                    alloc-picking-id!)
        (persist-pass-renderables!))))

(defn- get-selection-pass-renderables-by-node-id
  "Returns a map of renderables that were in a selection pass by their node id.
  If a renderable appears in multiple selection passes, the one from the latter
  pass will be picked. This should be fine, since the flat-renderable added
  by update-pass-renderables! will be the same for all passes."
  [renderables-by-pass]
  (into {}
        (comp (filter (comp types/selection? key))
              (mapcat (fn [[_pass renderables]]
                        (map (fn [renderable]
                               [(:node-id renderable) renderable])
                             renderables))))
        renderables-by-pass))

(g/defnk produce-scene-render-data [scene selection hidden-renderable-tags hidden-node-outline-key-paths camera]
  (let [selection-set (set selection)
        view-proj (c/camera-view-proj-matrix camera)
        scene-renderables-by-pass (flatten-scene scene selection-set hidden-renderable-tags hidden-node-outline-key-paths view-proj)
        selection-pass-renderables-by-node-id (get-selection-pass-renderables-by-node-id scene-renderables-by-pass)
        selected-renderables (into [] (keep selection-pass-renderables-by-node-id) selection)]
    {:renderables scene-renderables-by-pass
     :selected-renderables selected-renderables}))

(g/defnk produce-aux-render-data [aux-renderables hidden-renderable-tags]
  (let [aux-renderables-by-pass (apply merge-with concat aux-renderables)
        filtered-aux-renderables-by-pass (into {}
                                               (map (fn [[pass renderables]]
                                                      [pass (remove #(not-empty (set/intersection hidden-renderable-tags (:tags %))) renderables)]))
                                               aux-renderables-by-pass)]
    {:renderables filtered-aux-renderables-by-pass}))

(g/defnk produce-pass->render-args [^Region viewport camera]
  (into {}
        (map (juxt identity (partial pass-render-args viewport camera)))
        pass/all-passes))

(g/defnk produce-renderables-screen-aabb+picking-node-id [scene-render-data ^Region viewport camera]
  (into []
        (comp
          cat
          (keep (fn [renderable]
                  (when-some [aabb (:aabb renderable)]
                    (when-not (= aabb (geom/null-aabb))
                      (let [picking-node-id (:picking-node-id renderable)
                            corners (geom/aabb->corners aabb)
                            projected-corners (mapv (partial c/camera-project camera viewport) corners)
                            projected-aabb ^AABB (reduce (fn [aabb ^Point3d pt]
                                                           (geom/aabb-incorporate aabb pt))
                                                         (geom/null-aabb)
                                                         projected-corners)
                            aabb-min ^Point3d (.min projected-aabb)
                            aabb-max ^Point3d (.max projected-aabb)
                            aabb-rect (types/rect (.x aabb-min)
                                                  (.y aabb-min)
                                                  (- (.x aabb-max) (.x aabb-min))
                                                  (- (.y aabb-max) (.y aabb-min)))]
                        [aabb-rect picking-node-id]))))))
        (vals (:renderables scene-render-data))))

(g/defnode SceneRenderer
  (input active-view g/NodeID)
  (input scene g/Any :substitute substitute-scene)
  (input selection g/Any)
  (input camera Camera)
  (input aux-renderables pass/RenderData :array :substitute gu/array-subst-remove-errors)
  (input hidden-renderable-tags types/RenderableTags)
  (input hidden-node-outline-key-paths types/NodeOutlineKeyPaths)

  (output viewport Region :abstract)
  (output all-renderables g/Any :abstract)

  (output scene-render-data g/Any :cached produce-scene-render-data)
  (output aux-render-data g/Any :cached produce-aux-render-data)

  (output selected-renderables g/Any :cached (g/fnk [scene-render-data] (:selected-renderables scene-render-data)))
  (output selected-aabb AABB :cached (g/fnk [selected-renderables scene]
                                       (if (empty? selected-renderables)
                                         (:aabb scene)
                                         (reduce geom/aabb-union (geom/null-aabb) (map :aabb selected-renderables)))))
  (output renderables-screen-aabb+picking-node-id g/Any :cached produce-renderables-screen-aabb+picking-node-id)
  (output selected-updatables g/Any :cached (g/fnk [selected-renderables]
                                              (into {}
                                                    (comp (keep :updatable)
                                                          (map (juxt :node-id identity)))
                                                    selected-renderables)))
  (output updatables g/Any :cached (g/fnk [scene-render-data]
                                     ;; Currently updatables are implemented as extra info on the renderables.
                                     ;; The renderable associates an updatable with itself, which contains info
                                     ;; about the updatable. The updatable is identified by a node-id, for example
                                     ;; the id of a ParticleFXNode. In the case of ParticleFX, the same updatable
                                     ;; is also associated with every sub-element of the ParticleFX scene, such
                                     ;; as every emitter and modifier below it. This makes it possible to start
                                     ;; playback of the owning ParticleFX scene while a modifier is selected.
                                     ;; In order to find the owning ParticleFX scene so we can position the
                                     ;; effect in the world, we find the renderable with the shortest node-id-path
                                     ;; for that particular updatable.
                                     ;;
                                     ;; TODO:
                                     ;; We probably want to change how this works to make it possible to have
                                     ;; multiple instances of the same updatable in a scene.
                                     (let [flat-renderables (apply concat (map second (:renderables scene-render-data)))
                                           renderables-by-updatable-node-id (dissoc (group-by (comp :node-id :updatable) flat-renderables) nil)]
                                       (into {}
                                             (map (fn [[updatable-node-id renderables]]
                                                    (let [renderable (first (sort-by (comp count :node-id-path) renderables))
                                                          updatable (:updatable renderable)
                                                          world-transform (:world-transform renderable)
                                                          transformed-updatable (assoc updatable :world-transform world-transform)]
                                                      [updatable-node-id transformed-updatable])))
                                             renderables-by-updatable-node-id))))
  (output pass->render-args g/Any :cached produce-pass->render-args))

;; Scene selection

(defn map-scene [f scene]
  (letfn [(scene-fn [scene]
            (let [children (:children scene)]
              (cond-> scene
                true (f)
                children (update :children (partial mapv scene-fn)))))]
    (scene-fn scene)))

(defn claim-child-scene [old-node-id new-node-id new-node-outline-key child-scene]
  (if (= old-node-id (:node-id child-scene))
    (assoc child-scene :node-id new-node-id :node-outline-key new-node-outline-key)
    (assoc child-scene :picking-node-id new-node-id)))

(defn claim-scene [scene new-node-id new-node-outline-key]
  ;; When scenes reference other resources in the project, we want to treat the
  ;; referenced scene as a group when picking in the scene view. To make this
  ;; happen, the referencing scene claims ownership of the referenced scene and
  ;; its children. Note that sub-elements can still be selected using the
  ;; Outline view should the need arise.
  (let [old-node-id (:node-id scene)
        child-f (partial claim-child-scene old-node-id new-node-id new-node-outline-key)
        children (:children scene)]
    (cond-> (assoc scene :node-id new-node-id :node-outline-key new-node-outline-key)
      children (update :children (partial mapv (partial map-scene child-f))))))

(defn- box-selection? [^Rect picking-rect]
  (or (> (.width picking-rect) selection/min-pick-size)
      (> (.height picking-rect) selection/min-pick-size)))

(defn- picking-rect->clamped-view-rect
  ^Rect [^Region viewport ^Rect picking-rect]
  (let [view-width (.width picking-rect)
        view-height (.height picking-rect)
        view-left (- (.x picking-rect) (/ view-width 2))
        view-right (+ (.x picking-rect) (/ view-width 2))
        view-top (- (.y picking-rect) (/ view-height 2))
        view-bottom (+ (.y picking-rect) (/ view-height 2))
        clamped-left (int (max (.left viewport) (min view-left (.right viewport))))
        clamped-right (int (max (.left viewport) (min view-right (.right viewport))))
        clamped-top (int (max (.top viewport) (min view-top (.bottom viewport))))
        clamped-bottom (int (max (.top viewport) (min view-bottom (.bottom viewport))))]
    (types/rect clamped-left clamped-top (int (- clamped-right clamped-left)) (int (- clamped-bottom clamped-top)))))

(def ^:private picking-drawable-size selection/min-pick-size)
(def ^:private picking-viewport (Region. 0 picking-drawable-size 0 picking-drawable-size))
(def ^:private picking-buf-spiral-indices
  ;; Indices for traversing the picking buffer in a spiral fashion
  ;; from the center starting left then going clockwise.
  ;; Indices work as long as the buffer is square. Even/odd side
  ;; length supported, where an even side puts center towards
  ;; bottom right.
  (let [size (* picking-drawable-size picking-drawable-size)
        side picking-drawable-size
        cx (int (/ side 2))
        cy (int (/ side 2))
        repeats (mapcat (partial repeat 2) (rest (range)))
        left-up-right-down [[-1 0] [0 -1] [1 0] [0 1]]
        deltas (take (dec size) (mapcat repeat repeats (cycle left-up-right-down)))
        coords (reductions (fn [[x y] [dx dy]] [(+ x dx) (+ y dy)]) [cx cy] deltas)]
    (map (fn [[x y]]
           (let [flipped-y (dec (- picking-drawable-size y))]
             (+ x (* flipped-y picking-drawable-size))))
         coords)))

(defn- picking-buf->spiral-seq [^ints buf]
  (assert (= (count buf) (* picking-drawable-size picking-drawable-size)) "picking buf of unexpected size")
  (map (partial aget buf) picking-buf-spiral-indices))

(g/defnk produce-selection [scene-render-data renderables-screen-aabb+picking-node-id ^GLAutoDrawable picking-drawable ^Region viewport pass->render-args ^Rect picking-rect]
  (when (some? picking-rect)
    (cond
      (box-selection? picking-rect)
      (let [view-picking-rect (picking-rect->clamped-view-rect viewport picking-rect)]
        (keep (fn [[aabb-rect picking-node-id]]
                (when (geom/intersect? aabb-rect view-picking-rect)
                  picking-node-id))
              renderables-screen-aabb+picking-node-id))

      (not (geom/rect-empty? picking-rect))
      (gl/with-drawable-as-current picking-drawable
        (gl/gl-clear gl 0.0 0.0 0.0 1.0)
        (gl-viewport gl picking-viewport)
        (let [renderables (:renderables scene-render-data)
              picking-id->picking-node-id (into {}
                                                (comp
                                                  cat
                                                  (map (juxt :picking-id :picking-node-id)))
                                                (vals renderables))
              buf (int-array (* picking-drawable-size picking-drawable-size))]
          (reset! last-picking-rect picking-rect)
          (doseq [pass [pass/opaque-selection pass/selection]]
            (let [pass-render-args (picking-render-args (pass->render-args pass) viewport picking-rect)
                  pass-renderables (get renderables pass)]
              (setup-pass gl pass pass-render-args)
              (batch-render gl pass-render-args pass-renderables :select-batch-key)))
          (.glFlush gl)
          (.glFinish gl)
          ;; Pixels read back are like 0xAARRGGBB
          (.glReadPixels ^GL2 gl 0 0 ^int picking-drawable-size ^int picking-drawable-size
                         GL2/GL_BGRA GL2/GL_UNSIGNED_BYTE (IntBuffer/wrap buf))
          (transduce (comp (map scene-picking/argb->picking-id)
                           (keep picking-id->picking-node-id)
                           (take 1))
                     conj
                     (picking-buf->spiral-seq buf)))))))

(g/defnk produce-tool-renderables [tool-renderables]
  ;; tool-renderables input is a [{pass [renderables]}]
  (let [all-tool-renderables (apply concat (vals (apply merge-with into tool-renderables)))
        tool-renderable-identifiers (into [] (comp (map (juxt :node-id :selection-data)) (distinct)) all-tool-renderables)
        tool-renderable-identifier->picking-id (zipmap tool-renderable-identifiers (map picking-seed->picking-id (rest (range))))]
    (mapv (fn [pass->renderables]
            (into {}
                  (map (fn [[pass renderables]]
                         (let [pickable-renderables (mapv (fn [renderable]
                                                            (let [identifier [(:node-id renderable) (:selection-data renderable)]]
                                                              (assoc renderable :picking-id (tool-renderable-identifier->picking-id identifier))))
                                                          renderables)]
                           [pass pickable-renderables])))
                  pass->renderables))
          tool-renderables)))

(g/defnk produce-tool-selection [tool-renderables ^GLAutoDrawable picking-drawable ^Region viewport pass->render-args ^Rect tool-picking-rect inactive?]
  (when (and (some? tool-picking-rect)
             (not inactive?)
             (not (geom/rect-empty? tool-picking-rect)))
    (gl/with-drawable-as-current picking-drawable
      (gl/gl-clear gl 0.0 0.0 0.0 1.0)
      (gl-viewport gl picking-viewport)
      (let [render-args (picking-render-args (pass->render-args pass/manipulator-selection) viewport tool-picking-rect)
            tool-renderables (apply merge-with into tool-renderables)
            pickable-tool-renderables (get tool-renderables pass/manipulator-selection)
            picking-id->renderable (into {} (map (juxt :picking-id identity)) pickable-tool-renderables)
            buf (int-array (* picking-drawable-size picking-drawable-size))]
        (reset! last-picking-rect tool-picking-rect)
        (setup-pass gl pass/manipulator-selection render-args)
        (batch-render gl render-args pickable-tool-renderables :select-batch-key)
        (.glFlush gl)
        (.glFinish gl)
        ;; Pixels read back are like 0xAARRGGBB
        (.glReadPixels ^GL2 gl 0 0 ^int picking-drawable-size ^int picking-drawable-size
                       GL2/GL_BGRA GL2/GL_UNSIGNED_BYTE (IntBuffer/wrap buf))
        (transduce (comp (map scene-picking/argb->picking-id)
                         (keep picking-id->renderable)
                         (take 1))
                   conj
                   (picking-buf->spiral-seq buf))))))

(g/defnk produce-selected-tool-renderables [tool-selection]
  (apply merge-with concat {} (map #(do {(:node-id %) [(:selection-data %)]}) tool-selection)))

(declare update-image-view!)

(defn merge-render-datas [aux-render-data tool-render-data scene-render-data]
  (let [all-renderables-by-pass (merge-with into
                                            (:renderables aux-render-data)
                                            (:renderables tool-render-data)
                                            (:renderables scene-render-data))
        sorted-renderables-by-pass (into {} (map (fn [[pass renderables]] [pass (vec (render-sort renderables))]) all-renderables-by-pass))]
    {:renderables sorted-renderables-by-pass
     :selected-renderables (:selected-renderables scene-render-data)}))

(g/defnode SceneView
  (inherits view/WorkbenchView)
  (inherits SceneRenderer)

  (property image-view ImageView)
  (property viewport Region (default (g/constantly (types/->Region 0 0 0 0))))
  (property active-updatable-ids g/Any)
  (property play-mode g/Keyword)
  (property drawable GLAutoDrawable)
  (property picking-drawable GLAutoDrawable)
  (property async-copy-state g/Any)
  (property cursor-pos types/Vec2)
  (property tool-picking-rect Rect)
  (property input-action-queue g/Any (default []))
  (property updatable-states g/Any)

  (input input-handlers Runnable :array)
  (input picking-rect Rect)
  (input tool-renderables pass/RenderData :array :substitute substitute-render-data)
  (input active-tool g/Keyword)
  (input manip-space g/Keyword)
  (input updatables g/Any)
  (input selected-updatables g/Any)
  (output inactive? g/Bool (g/fnk [_node-id active-view] (not= _node-id active-view)))
  (output tool-renderables g/Any produce-tool-renderables)
  (output active-tool g/Keyword (gu/passthrough active-tool))
  (output manip-space g/Keyword (gu/passthrough manip-space))
  (output active-updatables g/Any :cached (g/fnk [updatables active-updatable-ids]
                                                 (into [] (keep updatables) active-updatable-ids)))

  (output selection g/Any (gu/passthrough selection))
  (output all-renderables pass/RenderData :cached (g/fnk [aux-render-data tool-render-data scene-render-data]
                                                    (:renderables (merge-render-datas aux-render-data tool-render-data scene-render-data))))
  (output tool-render-data g/Any :cached (g/fnk [tool-renderables inactive?]
                                           (if inactive?
                                             {:renderables []}
                                             {:renderables (reduce (partial merge-with into)
                                                                   {}
                                                                   tool-renderables)})))
  
  (output picking-selection g/Any :cached produce-selection)
  (output tool-selection g/Any :cached produce-tool-selection)
  (output selected-tool-renderables g/Any :cached produce-selected-tool-renderables))

(defn refresh-scene-view! [node-id]
  (g/with-auto-evaluation-context evaluation-context
    (let [image-view (g/node-value node-id :image-view evaluation-context)]
      (when-not (ui/inside-hidden-tab? image-view)
        (let [drawable (g/node-value node-id :drawable evaluation-context)
              async-copy-state-atom (g/node-value node-id :async-copy-state evaluation-context)]
          (when (and (some? drawable) (some? async-copy-state-atom))
            (update-image-view! image-view drawable async-copy-state-atom evaluation-context)))))))

(defn dispose-scene-view! [node-id]
  (when-let [scene (g/node-by-id node-id)]
    (when-let [^GLAutoDrawable drawable (g/node-value node-id :drawable)]
      (gl/with-drawable-as-current drawable
        (scene-cache/drop-context! gl)
        (when-let [async-copy-state-atom (g/node-value node-id :async-copy-state)]
          (scene-async/dispose! @async-copy-state-atom gl))
        (.glFinish gl))
      (.destroy drawable))
    (when-let [^GLAutoDrawable picking-drawable (g/node-value node-id :picking-drawable)]
      (gl/with-drawable-as-current picking-drawable
        (.glFinish gl))
      (.destroy picking-drawable))
    (g/transact
      (concat
        (g/set-property node-id :drawable nil)
        (g/set-property node-id :picking-drawable nil)
        (g/set-property node-id :async-copy-state nil)))))

(defn- screen->world
  ^Vector3d [camera viewport ^Vector3d screen-pos]
  (let [w4 (c/camera-unproject camera viewport (.x screen-pos) (.y screen-pos) (.z screen-pos))]
    (Vector3d. (.x w4) (.y w4) (.z w4))))

(defn- view->camera [view]
  (g/node-feeding-into view :camera))

(defn augment-action [view action]
  (let [x          (:x action)
        y          (:y action)
        screen-pos (Vector3d. x y 0)
        view-graph (g/node-id->graph-id view)
        camera     (g/node-value (view->camera view) :camera)
        viewport   (g/node-value view :viewport)
        world-pos  (Point3d. (screen->world camera viewport screen-pos))
        world-dir  (doto (screen->world camera viewport (doto (Vector3d. screen-pos) (.setZ 1)))
                         (.sub world-pos)
                         (.normalize))]
    (assoc action
           :screen-pos screen-pos
           :world-pos world-pos
           :world-dir world-dir)))

(defn- active-scene-view [app-view]
  (let [view (g/node-value app-view :active-view)]
    (when (and view (g/node-instance? SceneView view))
      view)))

(defn- play-handler [view-id]
  (let [play-mode (g/node-value view-id :play-mode)
        selected-updatable-ids (set (keys (g/node-value view-id :selected-updatables)))
        active-updatable-ids (set (g/node-value view-id :active-updatable-ids))
        new-play-mode (if (= selected-updatable-ids active-updatable-ids)
                        (if (= play-mode :playing) :idle :playing)
                        :playing)]
    (g/transact
      (concat
        (g/set-property view-id :play-mode new-play-mode)
        (g/set-property view-id :active-updatable-ids selected-updatable-ids)))))

(handler/defhandler :scene-play :global
  (active? [app-view] (when-let [view (active-scene-view app-view)]
                        (seq (g/node-value view :updatables))))
  (enabled? [app-view] (when-let [view (active-scene-view app-view)]
                         (let [selected (g/node-value view :selected-updatables)]
                           (not (empty? selected)))))
  (run [app-view] (when-let [view (active-scene-view app-view)]
                    (play-handler view))))

(defn- stop-handler [view-id]
  (g/transact
    (concat
      (g/set-property view-id :play-mode :idle)
      (g/set-property view-id :active-updatable-ids [])
      (g/set-property view-id :updatable-states {}))))

(handler/defhandler :scene-stop :global
  (active? [app-view] (when-let [view (active-scene-view app-view)]
                        (seq (g/node-value view :updatables))))
  (enabled? [app-view] (when-let [view (active-scene-view app-view)]
                         (seq (g/node-value view :active-updatables))))
  (run [app-view] (when-let [view (active-scene-view app-view)]
                    (stop-handler view))))

(defn set-camera! [camera-node start-camera end-camera animate?]
  (if animate?
    (let [duration 0.5]
      (ui/anim! duration
                (fn [^double t]
                  (let [t (- (* t t 3) (* t t t 2))
                        cam (c/interpolate start-camera end-camera t)]
                    (g/transact
                      (g/set-property camera-node :local-camera cam))))
                (fn []
                  (g/transact
                    (g/set-property camera-node :local-camera end-camera)))))
    (g/transact (g/set-property camera-node :local-camera end-camera))))

(defn frame-selection [view animate?]
  (when-let [aabb (g/node-value view :selected-aabb)]
    (let [graph (g/node-id->graph-id view)
          camera (view->camera view)
          viewport (g/node-value view :viewport)
          local-cam (g/node-value camera :local-camera)
          end-camera (c/camera-orthographic-frame-aabb local-cam viewport aabb)]
      (set-camera! camera local-cam end-camera animate?))))

(defn realign-camera [view animate?]
  (when-let [aabb (g/node-value view :selected-aabb)]
    (let [graph (g/node-id->graph-id view)
          camera (view->camera view)
          viewport (g/node-value view :viewport)
          local-cam (g/node-value camera :local-camera)
          end-camera (c/camera-orthographic-realign local-cam viewport aabb)]
      (set-camera! camera local-cam end-camera animate?))))

(handler/defhandler :frame-selection :global
  (active? [app-view] (active-scene-view app-view))
  (enabled? [app-view] (when-let [view (active-scene-view app-view)]
                         (let [selected (g/node-value view :selection)]
                           (not (empty? selected)))))
  (run [app-view] (when-let [view (active-scene-view app-view)]
                    (frame-selection view true))))

(handler/defhandler :realign-camera :global
  (active? [app-view] (active-scene-view app-view))
  (run [app-view] (when-let [view (active-scene-view app-view)]
                    (realign-camera view true))))

(defn- set-manip-space! [app-view manip-space]
  (assert (contains? #{:local :world} manip-space))
  (g/set-property! app-view :manip-space manip-space))

(handler/defhandler :set-manip-space :global
  (enabled? [app-view user-data] (let [active-tool (g/node-value app-view :active-tool)]
                                   (contains? (scene-tools/supported-manip-spaces active-tool)
                                              (:manip-space user-data))))
  (run [app-view user-data] (set-manip-space! app-view (:manip-space user-data)))
  (state [app-view user-data] (= (g/node-value app-view :manip-space) (:manip-space user-data))))

(handler/defhandler :toggle-move-whole-pixels :global
  (active? [app-view] (active-scene-view app-view))
  (state [prefs] (scene-tools/move-whole-pixels? prefs))
  (run [prefs] (scene-tools/set-move-whole-pixels! prefs (not (scene-tools/move-whole-pixels? prefs)))))

(ui/extend-menu ::menubar :editor.app-view/edit-end
                [{:label :separator}
                 {:label "World Space"
                  :command :set-manip-space
                  :user-data {:manip-space :world}
                  :check true}
                 {:label "Local Space"
                  :command :set-manip-space
                  :user-data {:manip-space :local}
                  :check true}
                 {:label :separator}
                 {:label "Move Whole Pixels"
                  :command :toggle-move-whole-pixels
                  :check true}])

(ui/extend-menu ::menubar :editor.app-view/view-end
                [{:label "Toggle Visibility Filters"
                  :command :toggle-visibility-filters}
                 {:label "Toggle Component Guides"
                  :command :toggle-component-guides}
                 {:label "Toggle Grid"
                  :command :toggle-grid}
                 {:label :separator}
                 {:label "Hide Selected Objects"
                  :command :hide-selected}
                 {:label "Hide Unselected Objects"
                  :command :hide-unselected}
                 {:label "Show Selected Objects"
                  :command :show-selected}
                 {:label "Show Last Hidden Objects"
                  :command :show-last-hidden}
                 {:label "Show All Hidden Objects"
                  :command :show-all-hidden}
                 {:label :separator}
                 {:label "Play"
                  :command :scene-play}
                 {:label "Stop"
                  :command :scene-stop}
                 {:label :separator}
                 {:label "Frame Selection"
                  :command :frame-selection}
                 {:label "Realign Camera"
                  :command :realign-camera}])

(defn dispatch-input [input-handlers action user-data]
  (reduce (fn [action [node-id label]]
            (when action
              ((g/node-value node-id label) node-id action user-data)))
          action input-handlers))

(defn- update-updatables
  [updatable-states play-mode active-updatables]
  (let [dt 1/60 ; fixed dt for deterministic playback
        context {:dt (if (= play-mode :playing) dt 0)}]
    (reduce (fn [ret {:keys [update-fn node-id world-transform initial-state]}]
              (let [context (assoc context
                              :world-transform world-transform
                              :node-id node-id)
                    state (get-in updatable-states [node-id] initial-state)]
                (assoc ret node-id (update-fn state context))))
            {}
            active-updatables)))

(defn update-image-view! [^ImageView image-view ^GLAutoDrawable drawable async-copy-state-atom evaluation-context]
  (when-let [view-id (ui/user-data image-view ::view-id)]
    (let [action-queue (g/node-value view-id :input-action-queue evaluation-context)
          tool-user-data (g/node-value view-id :selected-tool-renderables evaluation-context) ; TODO: for what actions do we need selected tool renderables?
          play-mode (g/node-value view-id :play-mode evaluation-context)
          active-updatables (g/node-value view-id :active-updatables evaluation-context)
          updatable-states (g/node-value view-id :updatable-states evaluation-context)
          new-updatable-states (if (seq active-updatables)
                                 (profiler/profile "updatables" -1 (update-updatables updatable-states play-mode active-updatables))
                                 updatable-states)
          renderables-invalidate-counter (g/invalidate-counter view-id :all-renderables evaluation-context)
          last-renderables-invalidate-counter (ui/user-data image-view ::last-renderables-invalidate-counter)
          last-frame-version (ui/user-data image-view ::last-frame-version)
          frame-version (cond-> (or last-frame-version 0)
                          (or (nil? last-renderables-invalidate-counter)
                              (not= last-renderables-invalidate-counter renderables-invalidate-counter)
                              (seq active-updatables))
                          inc)]
      (when (seq action-queue)
        (g/set-property! view-id :input-action-queue []))
      (profiler/profile "input-dispatch" -1
        (let [input-handlers (g/sources-of (:basis evaluation-context) view-id :input-handlers)]
          (doseq [action action-queue]
            (dispatch-input input-handlers action tool-user-data))))
      (when (seq active-updatables)
        (g/set-property! view-id :updatable-states new-updatable-states))
      (profiler/profile "render" -1
        (gl/with-drawable-as-current drawable
          (if (= last-frame-version frame-version)
            (reset! async-copy-state-atom (scene-async/finish-image! @async-copy-state-atom gl))
            (let [renderables (g/node-value view-id :all-renderables evaluation-context)
                  viewport (g/node-value view-id :viewport evaluation-context)
                  pass->render-args (g/node-value view-id :pass->render-args evaluation-context)]
              (render! gl-context renderables new-updatable-states viewport pass->render-args)
              (ui/user-data! image-view ::last-renderables-invalidate-counter renderables-invalidate-counter)
              (ui/user-data! image-view ::last-frame-version frame-version)
              (scene-cache/prune-context! gl)
              (reset! async-copy-state-atom (scene-async/finish-image! (scene-async/begin-read! @async-copy-state-atom gl) gl))))))
      (let [new-image (scene-async/image @async-copy-state-atom)]
        (when-not (identical? (.getImage image-view) new-image)
          (.setImage image-view new-image))))))

(defn- nudge! [scene-node-ids ^double dx ^double dy ^double dz]
  (g/transact
    (for [node-id scene-node-ids
          :let [[^double x ^double y ^double z] (g/node-value node-id :position)]]
      (g/set-property node-id :position [(+ x dx) (+ y dy) (+ z dz)]))))

(declare selection->movable)

(handler/defhandler :up :workbench
  (active? [selection] (selection->movable selection))
  (run [selection] (nudge! (selection->movable selection) 0.0 1.0 0.0)))

(handler/defhandler :down :workbench
  (active? [selection] (selection->movable selection))
  (run [selection] (nudge! (selection->movable selection) 0.0 -1.0 0.0)))

(handler/defhandler :left :workbench
  (active? [selection] (selection->movable selection))
  (run [selection] (nudge! (selection->movable selection) -1.0 0.0 0.0)))

(handler/defhandler :right :workbench
  (active? [selection] (selection->movable selection))
  (run [selection] (nudge! (selection->movable selection) 1.0 0.0 0.0)))

(handler/defhandler :up-major :workbench
  (active? [selection] (selection->movable selection))
  (run [selection] (nudge! (selection->movable selection) 0.0 10.0 0.0)))

(handler/defhandler :down-major :workbench
  (active? [selection] (selection->movable selection))
  (run [selection] (nudge! (selection->movable selection) 0.0 -10.0 0.0)))

(handler/defhandler :left-major :workbench
  (active? [selection] (selection->movable selection))
  (run [selection] (nudge! (selection->movable selection) -10.0 0.0 0.0)))

(handler/defhandler :right-major :workbench
  (active? [selection] (selection->movable selection))
  (run [selection] (nudge! (selection->movable selection) 10.0 0.0 0.0)))

(defn- handle-key-pressed! [^KeyEvent event]
  ;; Only handle bare key events that cannot be bound to handlers here.
  (when (not= ::unhandled
              (if (or (.isAltDown event) (.isMetaDown event) (.isShiftDown event) (.isShortcutDown event))
                ::unhandled
                (condp = (.getCode event)
                  KeyCode/UP (ui/run-command (.getSource event) :up)
                  KeyCode/DOWN (ui/run-command (.getSource event) :down)
                  KeyCode/LEFT (ui/run-command (.getSource event) :left)
                  KeyCode/RIGHT (ui/run-command (.getSource event) :right)
                  ::unhandled)))
    (.consume event)))

(defn register-event-handler! [^Parent parent view-id]
  (let [process-events? (atom true)
        event-handler   (ui/event-handler e
                          (when @process-events?
                            (try
                              (profiler/profile "input-event" -1
                                (let [action (augment-action view-id (i/action-from-jfx e))
                                      x (:x action)
                                      y (:y action)
                                      pos [x y 0.0]
                                      picking-rect (selection/calc-picking-rect pos pos)]
                                  (when (= :mouse-pressed (:type action))
                                    ;; Request focus and consume event to prevent someone else from stealing focus
                                    (.requestFocus parent)
                                    (.consume e))
                                  (g/transact
                                    (concat
                                      (g/set-property view-id :cursor-pos [x y])
                                      (g/set-property view-id :tool-picking-rect picking-rect)
                                      (g/update-property view-id :input-action-queue conj action)))))
                              (catch Throwable error
                                (reset! process-events? false)
                                (error-reporting/report-exception! error)))))]
    (ui/on-mouse! parent (fn [type e] (cond
                                        (= type :exit)
                                        (g/set-property! view-id :cursor-pos nil))))
    (.setOnMousePressed parent event-handler)
    (.setOnMouseReleased parent event-handler)
    (.setOnMouseClicked parent event-handler)
    (.setOnMouseMoved parent event-handler)
    (.setOnMouseDragged parent event-handler)
    (.setOnScroll parent event-handler)
    (.setOnKeyPressed parent (ui/event-handler e
                               (when @process-events?
                                 (handle-key-pressed! e))))))

(defn make-gl-pane! [view-id opts]
  (let [image-view (doto (ImageView.)
                     (.setScaleY -1.0)
                     (.setFocusTraversable true)
                     (.setPreserveRatio false)
                     (.setSmooth false))
        pane (proxy [com.defold.control.Region] []
               (layoutChildren []
                 (let [this ^com.defold.control.Region this
                       width (.getWidth this)
                       height (.getHeight this)]
                   (try
                     (.setFitWidth image-view width)
                     (.setFitHeight image-view height)
                     (proxy-super layoutInArea ^Node image-view 0.0 0.0 width height 0.0 HPos/CENTER VPos/CENTER)
                     (when (and (> width 0) (> height 0))
                       (let [viewport (types/->Region 0 width 0 height)]
                         (g/transact (g/set-property view-id :viewport viewport))
                         (if-let [view-id (ui/user-data image-view ::view-id)]
                           (when-some [drawable ^GLOffscreenAutoDrawable (g/node-value view-id :drawable)]
                             (doto drawable
                               (.setSurfaceSize width height))
                             (let [async-copy-state-atom (g/node-value view-id :async-copy-state)]
                               (reset! async-copy-state-atom (scene-async/request-resize! @async-copy-state-atom width height))))
                           (let [drawable (gl/offscreen-drawable width height)
                                 picking-drawable (gl/offscreen-drawable picking-drawable-size picking-drawable-size)]
                             (ui/user-data! image-view ::view-id view-id)
                             (register-event-handler! this view-id)
                             (ui/on-closed! (:tab opts) (fn [_]
                                                          (ui/kill-event-dispatch! this)
                                                          (dispose-scene-view! view-id)))
                             (g/set-property! view-id :drawable drawable :picking-drawable picking-drawable :async-copy-state (atom (scene-async/make-async-copy-state width height)))
                             (frame-selection view-id false)))))
                     (catch Throwable error
                       (error-reporting/report-exception! error)))
                   (proxy-super layoutChildren))))]
    (.setFocusTraversable pane true)
    (.add (.getChildren pane) image-view)
    (g/set-property! view-id :image-view image-view)
    pane))

(defn- make-scene-view-pane [view-id opts]
  (let [scene-view-pane ^Pane (ui/load-fxml "scene-view.fxml")]
    (ui/fill-control scene-view-pane)
    (ui/with-controls scene-view-pane [^AnchorPane gl-view-anchor-pane]
      (let [gl-pane (make-gl-pane! view-id opts)]
        (ui/fill-control gl-pane)
        (.add (.getChildren scene-view-pane) 0 gl-pane)))
    (when (system/defold-dev?)
      (.setOnKeyPressed scene-view-pane (ui/event-handler event
                                          (let [key-event ^KeyEvent event]
                                            (when (and (.isShortcutDown key-event)
                                                       (= "t" (.getText key-event)))
                                              (swap! render-mode-atom render-mode-transitions)
                                              (g/invalidate-outputs! [[view-id :all-renderables]]))))))
    scene-view-pane))

(defn- make-scene-view [scene-graph ^Parent parent opts]
  (let [view-id (g/make-node! scene-graph SceneView :updatable-states {})
        scene-view-pane (make-scene-view-pane view-id opts)]
    (ui/children! parent [scene-view-pane])
    view-id))

(g/defnk produce-frame [all-renderables ^Region viewport pass->render-args ^GLAutoDrawable drawable]
  (when drawable
    (gl/with-drawable-as-current drawable
      (render! gl-context all-renderables nil viewport pass->render-args)
      (let [[w h] (vp-dims viewport)
            buf-image (read-to-buffered-image w h)]
        (scene-cache/prune-context! gl)
        buf-image))))

(g/defnode PreviewView
  (inherits view/WorkbenchView)
  (inherits SceneRenderer)

  (property width g/Num)
  (property height g/Num)
  (property tool-picking-rect Rect)
  (property cursor-pos types/Vec2)
  (property image-view ImageView)
  (property drawable GLAutoDrawable)
  (property picking-drawable GLAutoDrawable)

  (input input-handlers Runnable :array)
  (input active-tool g/Keyword)
  (input manip-space g/Keyword)

  (input hidden-renderable-tags types/RenderableTags)
  (input hidden-node-outline-key-paths types/NodeOutlineKeyPaths)
  (input updatables g/Any)
  (input selected-updatables g/Any)
  (input picking-rect Rect)
  (input tool-renderables pass/RenderData :array)
  (output tool-renderables g/Any produce-tool-renderables)
  (output inactive? g/Bool (g/constantly false))
  (output active-tool g/Keyword (gu/passthrough active-tool))
  (output manip-space g/Keyword (gu/passthrough manip-space))
  (output viewport Region (g/fnk [width height] (types/->Region 0 width 0 height)))
  (output selection g/Any (gu/passthrough selection))
  (output picking-selection g/Any :cached produce-selection) ; PreviewView is used for click-driven tests, so must support regular selection/picking.
  (output tool-selection g/Any :cached produce-tool-selection)
  (output selected-tool-renderables g/Any :cached produce-selected-tool-renderables)
  (output frame BufferedImage produce-frame)
  (output all-renderables pass/RenderData (g/fnk [scene-render-data] (:renderables (merge-render-datas {} {} scene-render-data))))
  (output image WritableImage :cached (g/fnk [frame] (when frame (SwingFXUtils/toFXImage frame nil)))))

(defn make-preview-view [graph width height]
  (g/make-node! graph PreviewView
                :width width
                :height height
                :drawable (gl/offscreen-drawable width height)
                :picking-drawable (gl/offscreen-drawable picking-drawable-size picking-drawable-size)))

(defmulti attach-grid
  (fn [grid-node-type grid-node-id view-id resource-node camera]
    (:key @grid-node-type)))

(defmethod attach-grid :editor.grid/Grid
  [_ grid-node-id view-id resource-node camera]
  (concat
    (g/connect grid-node-id :renderable view-id      :aux-renderables)
    (g/connect camera       :camera     grid-node-id :camera)))

(defmulti attach-tool-controller
  (fn [tool-node-type tool-node-id view-id resource-node]
    (:key @tool-node-type)))

(defmethod attach-tool-controller :default
  [_ tool-node view-id resource-node])

(defn setup-view [view-id resource-node opts]
  (let [view-graph           (g/node-id->graph-id view-id)
        app-view-id          (:app-view opts)
        select-fn            (:select-fn opts)
        prefs                (:prefs opts)
        grid-type            (cond
                               (true? (:grid opts)) grid/Grid
                               (:grid opts) (:grid opts)
                               :else grid/Grid)
        tool-controller-type (get opts :tool-controller scene-tools/ToolController)]
    (concat
      (g/make-nodes view-graph
                    [background      background/Background
                     selection       [selection/SelectionController :select-fn (fn [selection op-seq]
                                                                                 (g/transact
                                                                                   (concat
                                                                                     (g/operation-sequence op-seq)
                                                                                     (g/operation-label "Select")
                                                                                     (select-fn selection))))]
                     camera          [c/CameraController :local-camera (or (:camera opts) (c/make-camera :orthographic identity {:fov-x 1000 :fov-y 1000}))]
                     grid            grid-type
                     tool-controller [tool-controller-type :prefs prefs]
                     rulers          [rulers/Rulers]]

                    (g/connect resource-node   :scene                         view-id         :scene)

                    (g/connect background      :renderable                    view-id         :aux-renderables)

                    (g/connect camera          :camera                        view-id         :camera)
                    (g/connect camera          :input-handler                 view-id         :input-handlers)
                    (g/connect view-id         :viewport                      camera          :viewport)

                    (g/connect app-view-id     :selected-node-ids             view-id         :selection)
                    (g/connect app-view-id     :active-view                   view-id         :active-view)
                    (g/connect app-view-id     :active-tool                   view-id         :active-tool)
                    (g/connect app-view-id     :manip-space                   view-id         :manip-space)
                    (g/connect app-view-id     :hidden-renderable-tags        view-id         :hidden-renderable-tags)
                    (g/connect app-view-id     :hidden-node-outline-key-paths view-id         :hidden-node-outline-key-paths)

                    (g/connect tool-controller :input-handler                 view-id         :input-handlers)
                    (g/connect tool-controller :renderables                   view-id         :tool-renderables)
                    (g/connect view-id         :active-tool                   tool-controller :active-tool)
                    (g/connect view-id         :manip-space                   tool-controller :manip-space)
                    (g/connect view-id         :viewport                      tool-controller :viewport)
                    (g/connect camera          :camera                        tool-controller :camera)
                    (g/connect view-id         :selected-renderables          tool-controller :selected-renderables)

                    (attach-tool-controller tool-controller-type tool-controller view-id resource-node)

                    (if (:grid opts)
                      (attach-grid grid-type grid view-id resource-node camera)
                      (g/delete-node grid))

                    (g/connect resource-node   :_node-id                      selection       :root-id)
                    (g/connect selection       :renderable                    view-id         :tool-renderables)
                    (g/connect selection       :input-handler                 view-id         :input-handlers)
                    (g/connect selection       :picking-rect                  view-id         :picking-rect)
                    (g/connect view-id         :picking-selection             selection       :picking-selection)
                    (g/connect view-id         :selection                     selection       :selection)

                    (g/connect camera :camera rulers :camera)
                    (g/connect rulers :renderables view-id :aux-renderables)
                    (g/connect view-id :viewport rulers :viewport)
                    (g/connect view-id :cursor-pos rulers :cursor-pos)

                    (when-not (:manual-refresh? opts)
                      (g/connect view-id :_node-id app-view-id :scene-view-ids)))
      (when-let [node-id (:select-node opts)]
        (select-fn [node-id])))))

(defn make-view [graph ^Parent parent resource-node opts]
  (let [view-id (make-scene-view graph parent opts)]
    (g/transact
      (setup-view view-id resource-node opts))
    view-id))

(defn make-preview [graph resource-node opts width height]
  (let [view-id (make-preview-view graph width height)
        opts (-> opts
                 (assoc :manual-refresh? true)
                 (dissoc :grid))]
    (g/transact
      (setup-view view-id resource-node opts))
    (frame-selection view-id false)
    view-id))

(defn dispose-preview [node-id]
  (when-some [^GLAutoDrawable drawable (g/node-value node-id :drawable)]
    (gl/with-drawable-as-current drawable
      (scene-cache/drop-context! gl))
    (.destroy drawable)
    (g/set-property! node-id :drawable nil)))

(defn- focus-view [view-id opts]
  (when-let [image-view ^ImageView (g/node-value view-id :image-view)]
    (.requestFocus image-view)))

(defn register-view-types [workspace]
  (workspace/register-view-type workspace
                                :id :scene
                                :label "Scene"
                                :make-view-fn make-view
                                :make-preview-fn make-preview
                                :dispose-preview-fn dispose-preview
                                :focus-fn focus-view))

(g/defnk produce-transform [position rotation scale]
  (let [position-v3 (doto (Vector3d.) (math/clj->vecmath position))
        rotation-q4 (doto (Quat4d.) (math/clj->vecmath rotation))
        scale-v3 (Vector3d. (double-array scale))]
    (math/->mat4-non-uniform position-v3 rotation-q4 scale-v3)))

(def produce-no-transform-properties (g/constantly #{}))
(def produce-scalable-transform-properties (g/constantly #{:position :rotation :scale}))
(def produce-unscalable-transform-properties (g/constantly #{:position :rotation}))

;; Arbitrarily small value to avoid 0-determinants
(def ^:private ^:const scale-min 0.000001)

(defn- non-zeroify-scale [^double v]
  (if (< (Math/abs v) scale-min) (Math/copySign scale-min v) v))

(g/defnode SceneNode
  (property position types/Vec3 (default [0.0 0.0 0.0])
            (dynamic visible (g/fnk [transform-properties] (contains? transform-properties :position))))
  (property rotation types/Vec4 (default [0.0 0.0 0.0 1.0])
            (dynamic visible (g/fnk [transform-properties] (contains? transform-properties :rotation)))
            (dynamic edit-type (g/constantly (properties/quat->euler))))
  (property scale types/Vec3 (default [1.0 1.0 1.0])
            (dynamic visible (g/fnk [transform-properties] (contains? transform-properties :scale)))
            (set (fn [_evaluation-context self _old-value new-value]
                   (when (some? new-value)
                     (g/set-property self :scale (mapv non-zeroify-scale new-value))))))

  (output transform-properties g/Any :abstract)
  (output transform Matrix4d :cached produce-transform)
  (output scene g/Any :cached (g/fnk [^g/NodeID _node-id ^Matrix4d transform] {:node-id _node-id :transform transform}))
  (output aabb AABB :cached (g/constantly (geom/null-aabb))))

(defmethod scene-tools/manip-movable? ::SceneNode [node-id]
  (contains? (g/node-value node-id :transform-properties) :position))

(defmethod scene-tools/manip-rotatable? ::SceneNode [node-id]
  (contains? (g/node-value node-id :transform-properties) :rotation))

(defmethod scene-tools/manip-scalable? ::SceneNode [node-id]
  (contains? (g/node-value node-id :transform-properties) :scale))

(defmethod scene-tools/manip-move ::SceneNode [evaluation-context node-id delta]
  (let [orig-p ^Vector3d (doto (Vector3d.) (math/clj->vecmath (g/node-value node-id :position evaluation-context)))
        p (math/add-vector orig-p delta)]
    (g/set-property node-id :position (properties/round-vec (math/vecmath->clj p)))))

(defmethod scene-tools/manip-rotate ::SceneNode [evaluation-context node-id delta]
  (let [new-rotation (math/vecmath->clj
                       (doto (Quat4d.)
                         (math/clj->vecmath (g/node-value node-id :rotation evaluation-context))
                         (.mul delta)))]
    ;; Note! The rotation is not rounded here like manip-move and manip-scale.
    ;; As the user-facing property is the euler angles, they are rounded in properties/quat->euler.
    (g/set-property node-id :rotation new-rotation)))

(defmethod scene-tools/manip-scale ::SceneNode [evaluation-context node-id delta]
  (let [s (Vector3d. (double-array (g/node-value node-id :scale evaluation-context)))
        ^Vector3d d delta]
    (.setX s (* (.x s) (.x d)))
    (.setY s (* (.y s) (.y d)))
    (.setZ s (* (.z s) (.z d)))
    (g/set-property node-id :scale (properties/round-vec [(.x s) (.y s) (.z s)]))))

(defn selection->movable [selection]
  (handler/selection->node-ids selection scene-tools/manip-movable?))
