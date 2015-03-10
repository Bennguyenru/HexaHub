(ns dynamo.types
  "Schema and type definitions. Refer to Prismatic's schema.core for s/* definitions."
  (:require [schema.core :as s]
            [schema.macros :as sm])
  (:import [java.awt.image BufferedImage]
           [java.nio ByteBuffer]
           [com.dynamo.graphics.proto Graphics$TextureImage$TextureFormat]
           [com.dynamo.tile.proto Tile$Playback]
           [javax.vecmath Matrix4d Point3d Quat4d Vector3d Vector4d]))

; ----------------------------------------
; Protocols here help avoid circular dependencies
; ----------------------------------------
(defprotocol IDisposable
  (dispose [this] "Clean up a value, including thread-jumping as needed"))

(defn disposable? [x] (satisfies? IDisposable x))

(defprotocol Condition
  (signal [this] "Notify a deferred action of something"))

(defprotocol Cancelable
  (cancel [this] "Cancel a thing."))

(defprotocol NamingContext
  (lookup [this nm] "Locate a value by name"))

(defprotocol FileContainer
  (node-for-path [this path] "Create a new node from a path within the container. `path` must be a ProjectPath."))

(defprotocol NodeType
  (supertypes           [this])
  (interfaces           [this])
  (protocols            [this])
  (method-impls         [this])
  (triggers             [this])
  (transforms'          [this])
  (transform-types'     [this])
  (properties'          [this])
  (inputs'              [this])
  (injectable-inputs'   [this])
  (outputs'             [this])
  (cached-outputs'      [this])
  (event-handlers'      [this])
  (output-dependencies' [this]))

(sm/defrecord NodeRef [world-ref node-id]
  clojure.lang.IDeref
  (deref [this] (get-in (:graph @world-ref) [:nodes node-id])))

(defmethod print-method NodeRef
  [^NodeRef v ^java.io.Writer w]
  (.write w (str "<NodeRef@" (:node-id v) ">")))

(defn node-ref [node] (NodeRef. (:world-ref node) (:_id node)))

(defprotocol Node
  (node-type           [this]        "Return the node type that created this node.")
  (transforms          [this]        "temporary")
  (transform-types     [this]        "temporary")
  (properties          [this]        "Produce a description of properties supported by this node.")
  (inputs              [this]        "Return a set of labels for the allowed inputs of the node.")
  (injectable-inputs   [this]        "temporary")
  (input-types         [this]        "Return a map from input label to schema of the value type allowed for the input")
  (outputs             [this]        "Return a set of labels for the outputs of this node.")
  (cached-outputs      [this]        "Return a set of labels for the outputs of this node which are cached. This must be a subset of 'outputs'.")
  (output-dependencies [this]        "Return a map of labels for the inputs and properties to outputs that depend on them."))

(defprotocol MessageTarget
  (process-one-event [this event]))

(defprotocol R3Min
  (min-p ^Point3d  [this]))

(defprotocol R3Max
  (max-p ^Point3d  [this]))

(defprotocol Rotation
  (rotation ^Quat4d [this]))

(defprotocol Translation
  (translation ^Vector4d [this]))

(defprotocol Position
  (position ^Point3d [this]))

(defprotocol ImageHolder
  (contents ^BufferedImage [this]))

(defprotocol N2Extent
  (width ^Integer [this])
  (height ^Integer [this]))

(defprotocol Frame
  (frame [this] "Notified one frame after being altered."))

(defprotocol PathManipulation
  (^String           extension         [this]         "Returns the extension represented by this path.")
  (^PathManipulation replace-extension [this new-ext] "Returns a new path with the desired extension.")
  (^String           local-path        [this]         "Returns a string representation of the path and extension.")
  (^String           local-name        [this]         "Returns the last segment of the path"))

; ----------------------------------------
; Functions to create basic value types
; ----------------------------------------
(defn apply-if-fn [f & args]
  (if (fn? f)
    (apply f args)
    f))

(defn var-get-recursive [var-or-value]
  (if (var? var-or-value)
    (recur (var-get var-or-value))
    var-or-value))

(defprotocol PropertyType
  (property-value-type    [this]   "Prismatic schema for property value type")
  (property-default-value [this])
  (property-validate      [this v] "Returns a possibly-empty seq of messages.")
  (property-valid-value?  [this v] "If valid, returns nil. If invalid, returns seq of Marker")
  (property-visible       [this]   "If true, this property appears in the UI")
  (property-tags          [this]))

(defn property-type? [x] (satisfies? PropertyType x))

(def Properties {s/Keyword {:value s/Any :type (s/protocol PropertyType)}})

(defprotocol Marker
  "Annotates a location with extra information."
  (marker-kind     [this] "Keyword denoting the marker's intent")
  (marker-status   [this] "Keyword denoting the status. Predefined statuses are :error, :information, :ok")
  (marker-location [this] "Map that must include :node-ref, may include other keys added by the marker's creator.")
  (marker-message  [this] "Human readable string"))

(def Int32   (s/pred #(instance? java.lang.Integer %) 'int32?))
(def Icon    s/Str)

(def Color   [s/Num])
(def Vec3    [(s/one s/Num "x")
              (s/one s/Num "y")
              (s/one s/Num "z")])

(def MouseType (s/enum :one-button :three-button))

(def Registry {s/Any s/Any})

(sm/defrecord Rect
  [path     :- s/Any
   x        :- Int32
   y        :- Int32
   width    :- Int32
   height   :- Int32]
  N2Extent
  (width [this] width)
  (height [this] height))

(sm/defrecord AABB [min max]
  R3Min
  (min-p [this] (.min this))
  R3Max
  (max-p [this] (.max this)))

(defmethod print-method AABB
  [^AABB v ^java.io.Writer w]
  (.write w (str "<AABB \"min: " (.min v) ", max: " (.max v) "\">")))

(sm/defn ^:always-validate rect :- Rect
  ([x :- s/Num y :- s/Num width :- s/Num height :- s/Num]
    (rect "" (int  x) (int y) (int width) (int height)))
  ([path :- s/Any x :- s/Num y :- s/Num width :- s/Num height :- s/Num]
    (Rect. path (int x) (int y) (int width) (int height))))

(sm/defrecord Image
  [path     :- s/Any
   contents :- BufferedImage
   width    :- Int32
   height   :- Int32]
  ImageHolder
  (contents [this] contents))

(def AnimationPlayback (s/enum :PLAYBACK_NONE :PLAYBACK_ONCE_FORWARD :PLAYBACK_ONCE_BACKWARD
                               :PLAYBACK_ONCE_PINGPONG :PLAYBACK_LOOP_FORWARD :PLAYBACK_LOOP_BACKWARD
                               :PLAYBACK_LOOP_PINGPONG))

(sm/defrecord Animation
  [id              :- s/Str
   images          :- [Image]
   fps             :- Int32
   flip-horizontal :- s/Int
   flip-vertical   :- s/Int
   playback        :- AnimationPlayback])

(sm/defrecord TexturePacking
  [aabb         :- Rect
   packed-image :- BufferedImage
   coords       :- [Rect]
   sources      :- [Rect]
   animations   :- [Animation]])

(sm/defrecord Vertices
  [counts   :- [Int32]
   starts   :- [Int32]
   vertices :- [s/Num]])

(sm/defrecord EngineFormatTexture
  [width           :- Int32
   height          :- Int32
   original-width  :- Int32
   original-height :- Int32
   format          :- Graphics$TextureImage$TextureFormat
   data            :- ByteBuffer
   mipmap-sizes    :- [Int32]
   mipmap-offsets  :- [Int32]])

(sm/defrecord TextureSetAnimationFrame
  [image                :- Image ; TODO: is this necessary?
   vertex-start         :- s/Num
   vertex-count         :- s/Num
   outline-vertex-start :- s/Num
   outline-vertex-count :- s/Num
   tex-coords-start     :- s/Num
   tex-coords-count     :- s/Num])

(sm/defrecord TextureSetAnimation
  [id              :- s/Str
   width           :- Int32
   height          :- Int32
   fps             :- Int32
   flip-horizontal :- s/Int
   flip-vertical   :- s/Int
   playback        :- AnimationPlayback
   frames          :- [TextureSetAnimationFrame]])

(sm/defrecord TextureSet
  [animations       :- {s/Str TextureSetAnimation}
   vertices         :- s/Any #_dynamo.gl.vertex/PersistentVertexBuffer
   outline-vertices :- s/Any #_dynamo.gl.vertex/PersistentVertexBuffer
   tex-coords       :- s/Any #_dynamo.gl.vertex/PersistentVertexBuffer])

(defprotocol Pass
  (selection?       [this])
  (model-transform? [this]))

(def RenderData {(s/required-key Pass) s/Any})

(sm/defrecord Region
  [left   :- s/Num
   right  :- s/Num
   top    :- s/Num
   bottom :- s/Num])

(defprotocol Viewport
  (viewport ^Region [this]))

(sm/defrecord Camera
  [type           :- (s/enum :perspective :orthographic)
   position       :- Point3d
   rotation       :- Quat4d
   z-near         :- s/Num
   z-far          :- s/Num
   aspect         :- s/Num
   fov            :- s/Num
   focus-point    :- Vector4d]
  Position
  (position [this] position)
  Rotation
  (rotation [this] rotation))

(def OutlineCommand
  {:label      s/Str
   :enabled    s/Bool
   :command-fn s/Any
   :context    s/Any})

(def OutlineItem
  {:label    s/Str
   :icon     Icon
   :node-ref NodeRef
   :commands [OutlineCommand]
   :children [(s/recursive #'OutlineItem)]})

; ----------------------------------------
; Type compatibility and inference
; ----------------------------------------
(defn- check-single-type
  [out in]
  (or
   (= s/Any in)
   (= out in)
   (and (class? in) (class? out) (.isAssignableFrom ^Class in out))))

(defn compatible?
  [output-schema input-schema expect-collection?]
  (let [out-t-pl? (coll? output-schema)
        in-t-pl?  (coll? input-schema)]
    (or
     (= s/Any input-schema)
     (and expect-collection? (= [s/Any] input-schema))
     (and expect-collection? in-t-pl? (check-single-type output-schema (first input-schema)))
     (and (not expect-collection?) (check-single-type output-schema input-schema))
     (and (not expect-collection?) in-t-pl? out-t-pl? (check-single-type (first output-schema) (first input-schema))))))

(doseq [[v doc]
       {#'Icon                 "*schema* - schema for the representation of an Icon as s/Str"
        #'Pass                 "value for a rendering pass"
        #'selection?           "Replies true when the pass is used during pick render."
        #'model-transform?     "Replies true when the pass should apply the node transforms to the current model-view matrix. (Will be true in most cases, false for overlays.)"}]
  (alter-meta! v assoc :doc doc))
