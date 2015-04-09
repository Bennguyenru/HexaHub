(ns editor.scene
  (:require [dynamo.background :as background]
            [dynamo.geom :as geom]
            [dynamo.gl :as gl]
            [dynamo.graph :as g]
            [dynamo.grid :as grid]
            [dynamo.types :as t]
            [dynamo.types :refer [IDisposable dispose]]
            [editor.camera :as c]
            [editor.core :as core]
            [editor.input :as i]
            [editor.workspace :as workspace]
            [internal.render.pass :as pass]
            [service.log :as log])
  (:import [com.defold.editor Start UIUtil]
           [com.jogamp.opengl.util.awt TextRenderer Screenshot]
           [dynamo.types Camera AABB Region]
           [java.awt Font]
           [java.awt.image BufferedImage]
           [javafx.animation AnimationTimer]
           [javafx.application Platform]
           [javafx.beans.value ChangeListener]
           [javafx.collections FXCollections ObservableList]
           [javafx.embed.swing SwingFXUtils]
           [javafx.event ActionEvent EventHandler]
           [javafx.geometry BoundingBox]
           [javafx.scene Scene Node Parent]
           [javafx.scene.control Tab]
           [javafx.scene.image Image ImageView WritableImage PixelWriter]
           [javafx.scene.input MouseEvent]
           [javafx.scene.layout AnchorPane Pane]
           [java.lang Runnable]
           [javax.media.opengl GL GL2 GLContext GLProfile GLAutoDrawable GLOffscreenAutoDrawable GLDrawableFactory GLCapabilities]
           [javax.media.opengl.glu GLU]
           [javax.vecmath Point3d Matrix4d Vector4d Matrix3d Vector3d]))

(set! *warn-on-reflection* true)

(def PASS_SHIFT        32)
(def INDEX_SHIFT       (+ PASS_SHIFT 4))
(def MANIPULATOR_SHIFT 62)

(defn vp-dims [^Region viewport]
  (let [w (- (.right viewport) (.left viewport))
        h (- (.bottom viewport) (.top viewport))]
    [w h]))

(defn vp-not-empty? [^Region viewport]
  (let [[w h] (vp-dims viewport)]
    (and (> w 0) (> h 0))))

(defn z-distance [camera viewport obj]
  (let [p (->> (Point3d.)
            (geom/world-space obj)
            (c/camera-project camera viewport))]
    (long (* Integer/MAX_VALUE (.z p)))))

(defn render-key [camera viewport obj]
  (+ (z-distance camera viewport obj)
     (bit-shift-left (or (:index obj) 0)        INDEX_SHIFT)
     (bit-shift-left (or (:manipulator? obj) 0) MANIPULATOR_SHIFT)))

(defn gl-viewport [^GL2 gl viewport]
  (.glViewport gl (:left viewport) (:top viewport) (- (:right viewport) (:left viewport)) (- (:bottom viewport) (:top viewport))))

(defn setup-pass
  ([context gl glu pass camera ^Region viewport]
    (setup-pass context gl glu pass camera viewport nil))
  ([context ^GL2 gl ^GLU glu pass camera ^Region viewport pick-rect]
    (.glMatrixMode gl GL2/GL_PROJECTION)
      (.glLoadIdentity gl)
      (when pick-rect
        (gl/glu-pick-matrix glu pick-rect viewport))
      (if (t/model-transform? pass)
        (gl/gl-mult-matrix-4d gl (c/camera-projection-matrix camera))
        (gl/glu-ortho glu viewport))
      (.glMatrixMode gl GL2/GL_MODELVIEW)
      (.glLoadIdentity gl)
      (when (t/model-transform? pass)
        (gl/gl-load-matrix-4d gl (c/camera-view-matrix camera)))
      (pass/prepare-gl pass gl glu)))

(defn render-node
  [^GLContext context ^GL2 gl ^GLU glu ^TextRenderer text-renderer pass renderable]
  (gl/gl-push-matrix
    gl
    (when (t/model-transform? pass)
      (gl/gl-mult-matrix-4d gl (:world-transform renderable)))
    (try
      (when (:render-fn renderable)
        ((:render-fn renderable) context gl glu text-renderer))
      (catch Exception e
        (log/error :exception e
                   :pass pass
                   :render-fn (:render-fn renderable)
                   :message "skipping renderable")))))

(defrecord TextRendererRef [^TextRenderer text-renderer ^GLContext context]
  clojure.lang.IDeref
  (deref [this] text-renderer)
  IDisposable
  (dispose [this]
    (prn "disposing text-renderer")
    (when context (.makeCurrent context))
    (.dispose text-renderer)
    (when context (.release context))))

(defmethod print-method TextRendererRef
  [^TextRendererRef v ^java.io.Writer w]
  (.write w (str "<TextRendererRef@" (:text-renderer v) ">")))

(g/defnk produce-drawable [self ^Region viewport]
  (when (vp-not-empty? viewport)
    (let [[w h]   (vp-dims viewport)
          profile (GLProfile/getDefault)
          factory (GLDrawableFactory/getFactory profile)
          caps    (GLCapabilities. profile)]
      (.setOnscreen caps false)
      (.setPBuffer caps true)
      (.setDoubleBuffered caps false)
      (let [^GLOffscreenAutoDrawable drawable (:gl-drawable self)
            drawable (if drawable
                       (do (.setSize drawable w h) drawable)
                       (.createOffscreenAutoDrawable factory nil caps nil w h nil))]
        (g/transact (g/set-property self :gl-drawable drawable))
        drawable))))

(g/defnk produce-frame [^Region viewport ^GLAutoDrawable drawable camera ^TextRendererRef text-renderer renderables]
  (when (and drawable (vp-not-empty? viewport))
    (let [^GLContext context (.getContext drawable)]
      (.makeCurrent context)
      (let [gl ^GL2 (.getGL context)
            glu ^GLU (GLU.)]
        (try
          (.glClearColor gl 0.0 0.0 0.0 1.0)
          (gl/gl-clear gl 0.0 0.0 0.0 1)
          (.glColor4f gl 1.0 1.0 1.0 1.0)
          (gl-viewport gl viewport)
          (when-let [renderables (apply merge-with (fn [a b] (reverse (sort-by #(render-key camera viewport %) (concat a b)))) renderables)]
            (doseq [pass pass/render-passes]
              (setup-pass context gl glu pass camera viewport)
              (doseq [node (get renderables pass)]
                (render-node context gl glu @text-renderer pass node))))
          (let [[w h] (vp-dims viewport)
                  buf-image (Screenshot/readToBufferedImage w h)]
              (.release context)
              buf-image))))))

(g/defnode SceneRenderer
  (property name t/Keyword (default :renderer))
  (property gl-drawable GLAutoDrawable)

  (input viewport Region)
  (input camera Camera)
  (input renderables [t/RenderData])

  (output drawable GLAutoDrawable :cached produce-drawable)
  (output text-renderer TextRendererRef :cached (g/fnk [^GLAutoDrawable drawable] (->TextRendererRef (gl/text-renderer Font/SANS_SERIF Font/BOLD 12) (if drawable (.getContext drawable) nil))))
  (output frame BufferedImage :cached produce-frame) ; TODO cache when the cache bug is fixed
  )

(defn dispatch-input [input-handlers action]
  (reduce (fn [action input-handler]
            (let [node (first input-handler)
                  label (second input-handler)]
              (when action
                ((g/node-value node label) node action))))
          action input-handlers))

(g/defnode SceneView
  (inherits core/Scope)

  (property image-view ImageView)
  (property viewport Region (default (t/->Region 0 0 0 0)))
  (property repainter AnimationTimer)
  (property visible t/Bool (default true))

  (input frame BufferedImage)
  (input input-handlers [Runnable])

;;  (output viewport Region (g/fnk [viewport] viewport))
  (output image WritableImage :cached (g/fnk [frame ^ImageView image-view] (when frame (SwingFXUtils/toFXImage frame (.getImage image-view)))))

  (trigger stop-animation :deleted (fn [tx graph self label trigger]
                                     (.stop ^AnimationTimer (:repainter self))
                                     nil))
  t/IDisposable
  (dispose [self]
           (prn "Disposing SceneEditor")
           (when-let [^GLAutoDrawable drawable (:gl-drawable self)]
             (.destroy drawable))
           ))

(defn make-scene-view [scene-graph ^Parent parent]
  (let [image-view (ImageView.)]
    (.add (.getChildren ^Pane parent) image-view)
    (let [view (first (g/tx-nodes-added (g/transact (g/make-node scene-graph SceneView :image-view image-view))))]
      (let [self-ref (g/node-id view)
            event-handler (reify EventHandler (handle [this e]
                                                (let [now (g/now)
                                                      self (g/node-by-id now self-ref)]
                                                  (dispatch-input (g/sources-of now self :input-handlers) (i/action-from-jfx e)))))
            change-listener (reify ChangeListener (changed [this observable old-val new-val]
                                                    (let [bb ^BoundingBox new-val
                                                          w (- (.getMaxX bb) (.getMinX bb))
                                                          h (- (.getMaxY bb) (.getMinY bb))]
                                                      (g/transact (g/set-property self-ref :viewport (t/->Region 0 w 0 h))))))]
        (.setOnMousePressed parent event-handler)
        (.setOnMouseReleased parent event-handler)
        (.setOnMouseClicked parent event-handler)
        (.setOnMouseMoved parent event-handler)
        (.setOnMouseDragged parent event-handler)
        (.setOnScroll parent event-handler)
        (.addListener (.boundsInParentProperty (.getParent parent)) change-listener)
        (let [repainter (proxy [AnimationTimer] []
                          (handle [now]
                            (let [self                  (g/node-by-id (g/now) self-ref)
                                  image-view ^ImageView (:image-view self)
                                  visible               (:visible self)]
                              (when (and visible)
                                (let [image (g/node-value self :image)]
                                  (when (not= image (.getImage image-view)) (.setImage image-view image)))))))]
          (g/transact (g/set-property view :repainter repainter))
          (.start repainter)))
      (g/refresh view))))

(g/defnode PreviewView
  (inherits core/Scope)

  (property width t/Num)
  (property height t/Num)
  (input frame BufferedImage)
  (input input-handlers [Runnable])
  (output image WritableImage :cached (g/fnk [frame] (when frame (SwingFXUtils/toFXImage frame nil))))
  (output viewport Region (g/fnk [width height] (t/->Region 0 width 0 height))))

(defn make-preview-view [graph width height]
  (first (g/tx-nodes-added (g/transact (g/make-node graph PreviewView :width width :height height)))))

(defn setup-view [view & kvs]
  (let [opts (into {} (map vec (partition 2 kvs)))
        view-graph (g/node->graph-id view)]
    (g/make-nodes view-graph
                  [renderer   SceneRenderer
                   background background/Gradient
                   camera     [c/CameraController :camera (or (:camera opts) (c/make-camera :orthographic)) :reframe true]]
                  (g/update-property camera  :movements-enabled disj :tumble) ; TODO - pass in to constructor

                  ; Needed for scopes
                  (g/connect renderer :self view :nodes)
                  (g/connect camera :self view :nodes)

                  (g/connect background      :renderable    renderer        :renderables)
                  (g/connect camera          :camera        renderer        :camera)
                  (g/connect camera          :input-handler view            :input-handlers)
                  (g/connect view            :viewport      camera          :viewport)
                  (g/connect view            :viewport      renderer        :viewport)
                  (g/connect renderer        :frame         view            :frame)

                  (when (:grid opts)
                    (let [grid (first (g/tx-nodes-added (g/transact (g/make-node view-graph grid/Grid))))]
                      (g/connect grid   :renderable renderer :renderables)
                      (g/connect camera :camera     grid     :camera))))))

(defn make-view [graph ^Parent parent view-fns resource-node]
  (let [view               (make-scene-view graph parent)
        setup-view-fn      (:setup-view-fn view-fns)
        setup-rendering-fn (:setup-rendering-fn view-fns)]
    (g/transact (setup-view-fn resource-node view))
    (g/transact (setup-rendering-fn resource-node (g/refresh view)))
    (g/refresh view)))

(defn make-preview [graph view-fns resource-node width height]
  (let [view               (make-preview-view graph width height)
        setup-view-fn      (:setup-view-fn view-fns)
        setup-rendering-fn (:setup-rendering-fn view-fns)]
    (g/transact (setup-view-fn resource-node view))
    (g/transact (setup-rendering-fn resource-node (g/refresh view)))
    (g/refresh view)))

(defn register-view-types [workspace]
                               (workspace/register-view-type workspace
                                                             :id :scene
                                                             :make-view-fn make-view
                                                             :make-preview-fn make-preview))
