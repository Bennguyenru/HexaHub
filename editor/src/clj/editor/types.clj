(ns editor.types
  (:require [dynamo.graph :as g])
  (:import [com.dynamo.graphics.proto Graphics$TextureImage$TextureFormat]
           [com.dynamo.tile.proto Tile$Playback]
           [java.awt.image BufferedImage]
           [java.nio ByteBuffer]
           [javafx.scene Parent]
           [javax.vecmath Matrix4d Point3d Quat4d Vector3d Vector4d]))

(set! *warn-on-reflection* true)

;; ----------------------------------------
;; Protocols here help avoid circular dependencies
;; ----------------------------------------

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
(g/deftype Int32   (g/both g/Int (g/pred #(< Integer/MIN_VALUE % Integer/MAX_VALUE) 'int32?)))

(g/deftype Icon    g/Str)

(g/deftype Color   [g/Num])

(g/deftype Vec2    [(g/one g/Num "x")
                    (g/one g/Num "y")])

(g/deftype Vec3    [(g/one g/Num "x")
                    (g/one g/Num "y")
                    (g/one g/Num "z")])

(g/deftype Vec4    [(g/one g/Num "x")
                    (g/one g/Num "y")
                    (g/one g/Num "z")
                    (g/one g/Num "w")])

(defn Point3d->Vec3 [^Point3d p]
  [(.getX p) (.getY p) (.getZ p)])

(def MouseType #{:one-button :three-button})

(def Registry {g/Any g/Any})

(g/s-defrecord Rect
  [path     :- g/Any
   x        :- Int32
   y        :- Int32
   width    :- Int32
   height   :- Int32]
  N2Extent
  (width [this] width)
  (height [this] height))

(g/deftype RectType Rect)

(g/s-defrecord AABB [min max]
  R3Min
  (min-p [this] (.min this))
  R3Max
  (max-p [this] (.max this)))

(g/deftype AABBType AABB)

(defmethod print-method AABB
  [^AABB v ^java.io.Writer w]
  (.write w (str "<AABB \"min: " (.min v) ", max: " (.max v) "\">")))

(g/s-defn ^:always-validate rect :- Rect
  ([x :- g/Num y :- g/Num width :- g/Num height :- g/Num]
    (rect "" (int  x) (int y) (int width) (int height)))
  ([path :- g/Any x :- g/Num y :- g/Num width :- g/Num height :- g/Num]
    (Rect. path (int x) (int y) (int width) (int height))))

(g/s-defrecord Image
  [path     :- g/Any
   contents :- BufferedImage
   width    :- Int32
   height   :- Int32]
  ImageHolder
  (contents [this] contents))

(g/deftype ImageType Image)

(def AnimationPlayback (g/enum :playback-none :playback-once-forward :playback-once-backward
                               :playback-once-pingpong :playback-loop-forward :playback-loop-backward
                               :playback-loop-pingpong))

(g/s-defrecord Animation
  [id              :- g/Str
   images          :- [Image]
   fps             :- Int32
   flip-horizontal :- g/Bool
   flip-vertical   :- g/Bool
   playback        :- AnimationPlayback])

(g/deftype AnimationType Animation)

(g/s-defrecord TexturePacking
  [aabb         :- Rect
   packed-image :- BufferedImage
   coords       :- [Rect]
   sources      :- [Rect]
   animations   :- [Animation]])

(g/deftype TexturePackingType TexturePacking)

(g/s-defrecord Vertices
  [counts   :- [Int32]
   starts   :- [Int32]
   vertices :- [g/Num]])

(g/deftype VerticesType Vertices)

(g/s-defrecord EngineFormatTexture
  [width           :- Int32
   height          :- Int32
   original-width  :- Int32
   original-height :- Int32
   format          :- Graphics$TextureImage$TextureFormat
   data            :- ByteBuffer
   mipmap-sizes    :- [Int32]
   mipmap-offsets  :- [Int32]])

(g/deftype EngineFormatTextureType EngineFormatTexture)

(g/s-defrecord TextureSetAnimationFrame
  [image                :- Image ; TODO: is this necessary?
   vertex-start         :- g/Num
   vertex-count         :- g/Num
   outline-vertex-start :- g/Num
   outline-vertex-count :- g/Num
   tex-coords-start     :- g/Num
   tex-coords-count     :- g/Num])

(g/deftype TextureSetAnimationFrameType TextureSetAnimationFrame)

(g/s-defrecord TextureSetAnimation
  [id              :- g/Str
   width           :- Int32
   height          :- Int32
   fps             :- Int32
   flip-horizontal :- g/Int
   flip-vertical   :- g/Int
   playback        :- AnimationPlayback
   frames          :- [TextureSetAnimationFrame]])

(g/deftype TextureSetAnimationType TextureSetAnimation)

(g/s-defrecord TextureSet
  [animations       :- {g/Str TextureSetAnimation}
   vertices         :- g/Any #_editor.gl.vertex/PersistentVertexBuffer
   outline-vertices :- g/Any #_editor.gl.vertex/PersistentVertexBuffer
   tex-coords       :- g/Any #_editor.gl.vertex/PersistentVertexBuffer])

(defprotocol Pass
  (selection?       [this])
  (model-transform? [this]))

(g/deftype PassType (g/protocol Pass))

(g/s-defrecord Region
  [left   :- g/Num
   right  :- g/Num
   top    :- g/Num
   bottom :- g/Num])

(g/deftype RegionType Region)

(defprotocol Viewport
  (viewport ^Region [this]))

(g/deftype ViewportType (g/protocol Viewport))

(g/s-defrecord Camera
  [type           :- (g/enum :perspective :orthographic)
   position       :- Point3d
   rotation       :- Quat4d
   z-near         :- g/Num
   z-far          :- g/Num
   aspect         :- g/Num
   fov            :- g/Num
   focus-point    :- Vector4d]
  Position
  (position [this] position)
  Rotation
  (rotation [this] rotation))

(g/deftype CameraType Camera)

(g/deftype OutlineCommand
  {:label      g/Str
   :enabled    g/Bool
   :command-fn g/Any
   :context    g/Any})

(g/deftype OutlineItem
  {:label    g/Str
   :icon     Icon
   :node-ref Long
   :commands [(:schema @OutlineCommand)]
   :children [g/Any]})

(g/deftype RunnableType Runnable)
(g/deftype ParentType Parent)
