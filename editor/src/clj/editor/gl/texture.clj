(ns editor.gl.texture
  "Functions for creating and using textures"
  (:require [editor.image :as image]
            [editor.gl.protocols :refer [GlBind]]
            [editor.gl :as gl]
            [editor.scene-cache :as scene-cache]
            [dynamo.graph :as g])
  (:import [java.awt.image BufferedImage]
           [java.nio IntBuffer ByteBuffer]
           [javax.media.opengl GL GL2 GL3 GLContext GLProfile]
           [com.jogamp.common.nio Buffers]
           [com.jogamp.opengl.util.texture Texture TextureIO TextureData]
           [com.jogamp.opengl.util.texture.awt AWTTextureIO]))

(set! *warn-on-reflection* true)

(def
  ^{:doc "This map translates Clojure keywords into OpenGL constants.
          You can use the keywords in texture parameter maps."}
  texture-params
  {:base-level   GL2/GL_TEXTURE_BASE_LEVEL
   :border-color GL2/GL_TEXTURE_BORDER_COLOR
   :compare-func GL2/GL_TEXTURE_COMPARE_FUNC
   :compare-mode GL2/GL_TEXTURE_COMPARE_MODE
   :lod-bias     GL2/GL_TEXTURE_LOD_BIAS
   :min-filter   GL/GL_TEXTURE_MIN_FILTER
   :mag-filter   GL/GL_TEXTURE_MAG_FILTER
   :min-lod      GL2/GL_TEXTURE_MIN_LOD
   :max-lod      GL2/GL_TEXTURE_MAX_LOD
   :max-level    GL2/GL_TEXTURE_MAX_LEVEL
   :swizzle-r    GL3/GL_TEXTURE_SWIZZLE_R
   :swizzle-g    GL3/GL_TEXTURE_SWIZZLE_G
   :swizzle-b    GL3/GL_TEXTURE_SWIZZLE_B
   :swizzle-a    GL3/GL_TEXTURE_SWIZZLE_A
   :wrap-s       GL2/GL_TEXTURE_WRAP_S
   :wrap-t       GL2/GL_TEXTURE_WRAP_T
   :wrap-r       GL2/GL_TEXTURE_WRAP_R})

(defn- apply-params
  [gl ^Texture texture params]
  (doseq [[p v] params]
    (if-let [pname (texture-params p)]
      (.setTexParameteri texture gl pname v)
      (if (integer? p)
        (.setTexParameteri texture gl p v)
        (println "WARNING: ignoring unknown texture parameter " p)))))

(defprotocol TextureProxy
  (->texture ^Texture [this ^GL2 gl]))

(defrecord TextureLifecycle [request-id cache-id unit params texture-data]
  TextureProxy
  (->texture [this gl]
    (scene-cache/request-object! cache-id request-id gl texture-data))

  GlBind
  (bind [this gl _]
    (let [tex (->texture this gl)]
      (.glActiveTexture ^GL2 gl unit)
      (.enable tex gl)
      (.bind tex gl)
      (apply-params gl tex params)))

  (unbind [this gl]
    (let [tex (->texture this gl)]
      (.disable tex gl))
    (gl/gl-active-texture ^GL gl GL/GL_TEXTURE0)))

(defn set-params [^TextureLifecycle tlc params]
  (update tlc :params merge params))

(def
  ^{:doc "If you do not supply parameters to `image-texture`, these will be used as defaults."}
  default-image-texture-params
  {:min-filter gl/linear-mipmap-linear
   :mag-filter gl/linear
   :wrap-s     gl/clamp
   :wrap-t     gl/clamp})

(defn- channels->format [^long channels]
  (case channels
    1 GL2/GL_LUMINANCE
    3 GL2/GL_RGB
    4 GL2/GL_RGBA))

(defn- channels->type [^long channels]
  (case channels
    1 GL2/GL_UNSIGNED_BYTE
    3 GL2/GL_UNSIGNED_BYTE
    4 GL2/GL_UNSIGNED_INT_8_8_8_8))

(defn- ->texture-data [width height channels data mipmap]
  (let [internal-format (channels->format channels)
        _ (assert internal-format (format "Invalid channel count %d" channels))
        type (channels->type channels)
        border 0]
    (TextureData. (GLProfile/getGL2GL3) internal-format width height border internal-format type mipmap false false data nil)))

(defn- image->texture-data ^TextureData [img mipmap]
  (let [channels (image/image-color-components img)
        type (case channels
               4 BufferedImage/TYPE_4BYTE_ABGR_PRE
               3 BufferedImage/TYPE_3BYTE_BGR
               1 BufferedImage/TYPE_BYTE_GRAY)
        img (image/image-convert-type img type)
        data (ByteBuffer/wrap (.getData (.getDataBuffer (.getRaster img))))]
    (->texture-data (.getWidth img) (.getHeight img) channels data mipmap)))

(defn image-texture
  "Create an image texture from a BufferedImage. The returned value
supports GlBind and GlEnable. You can use it in do-gl and with-gl-bindings.

If supplied, the params argument must be a map of parameter name to value. Parameter names
can be OpenGL constants (e.g., GL_TEXTURE_WRAP_S) or their keyword equivalents from
`texture-params` (e.g., :wrap-s).

If you supply parameters, then those parameters are used. If you do not supply parameters,
then defaults in `default-image-texture-params` are used.

If supplied, the unit must be an OpenGL texture unit enum. The default is GL_TEXTURE0."
  ([request-id img]
   (image-texture request-id img default-image-texture-params 0))
  ([request-id img params]
   (image-texture request-id img params 0))
  ([request-id ^BufferedImage img params unit-index]
    (assert (< unit-index GL2/GL_MAX_TEXTURE_IMAGE_UNITS) (format "the maximum number of texture units is %d" GL2/GL_MAX_TEXTURE_IMAGE_UNITS))
    (let [texture-data (image->texture-data (or img (:contents image/placeholder-image)) true)
          unit (+ unit-index GL2/GL_TEXTURE0)]
      (->TextureLifecycle request-id ::texture unit params texture-data))))

(defn empty-texture [request-id width height channels params unit-index]
  (let [unit (+ unit-index GL2/GL_TEXTURE0)]
    (->TextureLifecycle request-id ::texture unit params (->texture-data width height channels nil false))))

(def white-pixel (image-texture ::white (-> (image/blank-image 1 1) (image/flood 1.0 1.0 1.0)) (assoc default-image-texture-params
                                                                                             :min-filter GL2/GL_NEAREST
                                                                                             :mag-filter GL2/GL_NEAREST)))

(defn tex-sub-image [^GL2 gl ^TextureLifecycle texture data x y w h channels]
  (let [tex (->texture texture gl)
        data (->texture-data w h channels data false)]
    (.updateSubImage tex gl data 0 x y)))

(def default-cubemap-texture-params
  ^{:doc "If you do not supply parameters to `image-cubemap-texture`, these will be used as defaults."}
  {:min-filter gl/linear
   :mag-filter gl/linear
   :wrap-s     gl/clamp
   :wrap-t     gl/clamp})

(def cubemap-placeholder
  (memoize
    (fn []
      (image/flood (image/blank-image 512 512 BufferedImage/TYPE_3BYTE_BGR) 0.9568 0.0 0.6313))))

(defn- safe-texture
  [x]
  (if (nil? x)
    (cubemap-placeholder)
    (if (= (:contents image/placeholder-image) x)
      (cubemap-placeholder)
      x)))

(defn image-cubemap-texture
  "Create an cubemap texture from six BufferedImages. The returned value
supports GlBind and GlEnable. You can use it in do-gl and with-gl-bindings.

If supplied, the params argument must be a map of parameter name to value. Parameter names
can be OpenGL constants (e.g., GL_TEXTURE_WRAP_S) or their keyword equivalents from
`texture-params` (e.g., :wrap-s).

If you supply parameters, then those parameters are used. If you do not supply parameters,
then defaults in `default-cubemap-texture-params` are used.

If supplied, the unit must be an OpenGL texture unit enum. The default is GL_TEXTURE0"
  ([request-id right left top bottom front back]
   (image-cubemap-texture request-id GL/GL_TEXTURE0 default-cubemap-texture-params right left top bottom front back))
  ([request-id params right left top bottom front back]
   (image-cubemap-texture request-id GL/GL_TEXTURE0 params right left top bottom front back))
  ([request-id unit params right left top bottom front back]
   (->TextureLifecycle request-id ::cubemap-texture unit params (map #(safe-texture %) [right left top bottom front back]))))

(defn- make-texture [^GL2 gl ^TextureData texture-data]
  (Texture. gl texture-data))

(defn- update-texture [^GL2 gl ^Texture texture ^TextureData texture-data]
  (.updateImage texture gl texture-data)
  texture)

(defn- destroy-textures [^GL2 gl textures _]
  (doseq [^Texture texture textures]
    (.destroy texture gl)))

(scene-cache/register-object-cache! ::texture make-texture update-texture destroy-textures)

(def ^:private cubemap-targets
  [GL/GL_TEXTURE_CUBE_MAP_POSITIVE_X
   GL/GL_TEXTURE_CUBE_MAP_NEGATIVE_X
   GL/GL_TEXTURE_CUBE_MAP_POSITIVE_Y
   GL/GL_TEXTURE_CUBE_MAP_NEGATIVE_Y
   GL/GL_TEXTURE_CUBE_MAP_POSITIVE_Z
   GL/GL_TEXTURE_CUBE_MAP_NEGATIVE_Z])

(defn- update-cubemap-texture [^GL2 gl ^Texture texture imgs]
  (doseq [[img target] (mapv vector imgs cubemap-targets)]
    (.updateImage texture gl (image->texture-data img false) target))
  texture)

(defn- make-cubemap-texture [^GL2 gl imgs]
  (let [^Texture texture (TextureIO/newTexture GL/GL_TEXTURE_CUBE_MAP)]
    (update-cubemap-texture gl texture imgs)))

(scene-cache/register-object-cache! ::cubemap-texture make-cubemap-texture update-cubemap-texture destroy-textures)
