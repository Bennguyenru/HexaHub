(ns editor.cubemap
  (:require [dynamo.file.protobuf :as protobuf]
            [dynamo.geom :as geom]
            [dynamo.gl :as gl]
            [dynamo.gl.shader :as shader]
            [dynamo.gl.texture :as texture]
            [dynamo.gl.vertex :as vtx]
            [dynamo.graph :as g]
            [dynamo.types :as t]
            [dynamo.ui :refer :all]
            [editor.project :as project]
            [editor.scene :as scene]
            [editor.workspace :as workspace]
            [internal.render.pass :as pass]
            [schema.core :as s])
  (:import [com.dynamo.graphics.proto Graphics$Cubemap Graphics$TextureImage Graphics$TextureImage$Image Graphics$TextureImage$Type]
           [com.jogamp.opengl.util.awt TextRenderer]
           [dynamo.types Animation Camera Image TexturePacking Rect EngineFormatTexture AABB TextureSetAnimationFrame TextureSetAnimation TextureSet]
           [java.awt.image BufferedImage]
           [javax.media.opengl GL GL2 GLContext GLDrawableFactory]
           [javax.media.opengl.glu GLU]
           [javax.vecmath Matrix4d]))

(def cubemap-icon "icons/layer_raster_3d.png")

(vtx/defvertex normal-vtx
  (vec3 position)
  (vec3 normal))

(def unit-sphere
  (let [lats 16
        longs 32
        vbuf (->normal-vtx (* 6 (* lats longs)))]
    (doseq [face (geom/unit-sphere-pos-nrm lats longs)
            v    face]
      (conj! vbuf v))
    (persistent! vbuf)))

(shader/defshader pos-norm-vert
  (uniform mat4 world)
  (attribute vec3 position)
  (attribute vec3 normal)
  (varying vec3 vWorld)
  (varying vec3 vNormal)
  (defn void main []
    (setq vNormal (normalize (* (mat3 (.xyz (nth world 0)) (.xyz (nth world 1)) (.xyz (nth world 2))) normal)))
    (setq vWorld (.xyz (* world (vec4 position 1.0))))
    (setq gl_Position (* gl_ModelViewProjectionMatrix (vec4 position 1.0)))))

(shader/defshader pos-norm-frag
  (uniform vec3 cameraPosition)
  (uniform samplerCube envMap)
  (varying vec3 vWorld)
  (varying vec3 vNormal)
  (defn void main []
    (setq vec3 camToV (normalize (- vWorld cameraPosition)))
    (setq vec3 refl (reflect camToV vNormal))
      (setq gl_FragColor (textureCube envMap refl))))

(def cubemap-shader (shader/make-shader pos-norm-vert pos-norm-frag))

(defn render-cubemap
  [^GL2 gl world camera gpu-texture vertex-binding]
  (gl/with-enabled gl [gpu-texture cubemap-shader vertex-binding]
    (shader/set-uniform cubemap-shader gl "world" world)
    (shader/set-uniform cubemap-shader gl "cameraPosition" (t/position camera))
    (shader/set-uniform cubemap-shader gl "envMap" 0)
    (gl/gl-enable gl GL/GL_CULL_FACE)
    (gl/gl-cull-face gl GL/GL_BACK)
    (gl/gl-draw-arrays gl GL/GL_TRIANGLES 0 (* 6 (* 16 32)))
    (gl/gl-disable gl GL/GL_CULL_FACE)))

(g/defnk produce-gpu-texture
  [right-img left-img top-img bottom-img front-img back-img]
  (apply texture/image-cubemap-texture [right-img left-img top-img bottom-img front-img back-img]))

(g/defnk produce-save-data [resource right left top bottom front back]
  {:resource resource
   :content (-> (doto (Graphics$Cubemap/newBuilder)
                  (.setRight right)
                  (.setLeft left)
                  (.setTop top)
                  (.setBottom bottom)
                  (.setFront front)
                  (.setBack back))
              (.build)
              (protobuf/pb->str))})

(g/defnk produce-scene
  [self aabb gpu-texture vertex-binding]
  (let [vertex-binding (vtx/use-with unit-sphere cubemap-shader)
        world (Matrix4d. geom/Identity4d)]
    {:id          (g/node-id self)
     :aabb        aabb
     :transform   world
     :renderables {pass/transparent
                   [{:render-fn (g/fnk [gl camera] (render-cubemap gl world camera gpu-texture vertex-binding))}]}}))

(g/defnode CubemapNode
  (inherits project/ResourceNode)
  (inherits scene/SceneNode)

  (property right  t/Str)
  (property left   t/Str)
  (property top    t/Str)
  (property bottom t/Str)
  (property front  t/Str)
  (property back   t/Str)

  (input right-img  BufferedImage)
  (input left-img   BufferedImage)
  (input top-img    BufferedImage)
  (input bottom-img BufferedImage)
  (input front-img  BufferedImage)
  (input back-img   BufferedImage)

  (output gpu-texture s/Any :cached produce-gpu-texture)
  (output save-data   s/Any :cached produce-save-data)
  (output aabb        AABB  :cached (g/fnk [] geom/unit-bounding-box))
  (output scene       s/Any :cached produce-scene))

(defn load-cubemap [project self input]
  (let [cubemap-message (protobuf/pb->map (protobuf/read-text Graphics$Cubemap input))]
    (for [[side input] cubemap-message]
      (if-let [img-node (project/resolve-resource-node self input)]
        [(g/connect img-node :content self (keyword (subs (str side "-img") 1)))
         (g/set-property self side input)]
        (g/set-property self side input)))))

(defn register-resource-types [workspace]
  (workspace/register-resource-type workspace
                                    :ext "cubemap"
                                    :node-type CubemapNode
                                    :load-fn load-cubemap
                                    :icon cubemap-icon
                                    :view-types [:scene]))
