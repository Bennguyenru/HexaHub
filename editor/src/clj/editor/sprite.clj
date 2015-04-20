(ns editor.sprite
  (:require [dynamo.buffers :refer :all]
            [dynamo.camera :refer :all]
            [dynamo.file.protobuf :as protobuf]
            [dynamo.geom :as geom]
            [dynamo.gl :as gl]
            [dynamo.gl.shader :as shader]
            [dynamo.gl.vertex :as vtx]
            [dynamo.graph :as g]
            [dynamo.types :as t :refer :all]
            [dynamo.ui :refer :all]
            [editor.project :as project]
            [editor.scene :as scene]
            [editor.workspace :as workspace]
            [internal.render.pass :as pass])
  (:import [com.dynamo.graphics.proto Graphics$Cubemap Graphics$TextureImage Graphics$TextureImage$Image Graphics$TextureImage$Type]
           [com.dynamo.sprite.proto Sprite Sprite$SpriteDesc Sprite$SpriteDesc$BlendMode]
           [com.jogamp.opengl.util.awt TextRenderer]
           [dynamo.types Region Animation Camera Image TexturePacking Rect EngineFormatTexture AABB TextureSetAnimationFrame TextureSetAnimation TextureSet]
           [java.awt.image BufferedImage]
           [java.io PushbackReader]
           [javax.media.opengl GL GL2 GLContext GLDrawableFactory]
           [javax.media.opengl.glu GLU]
           [javax.vecmath Matrix4d Point3d]))

(def sprite-icon "icons/pictures.png")

; Render assets

(vtx/defvertex texture-vtx
  (vec4 position)
  (vec2 texcoord0)
  (vec4 color))

(shader/defshader vertex-shader
  (attribute vec4 position)
  (attribute vec2 texcoord0)
  (attribute vec4 color)
  (varying vec2 var_texcoord0)
  (varying vec4 var_color)
  (defn void main []
    (setq gl_Position (* gl_ModelViewProjectionMatrix position))
    (setq var_texcoord0 texcoord0)
    (setq var_color color)))

(shader/defshader fragment-shader
  (varying vec2 var_texcoord0)
  (uniform sampler2D texture)
  (varying vec4 var_color)
  (defn void main []
    (setq gl_FragColor (* var_color (texture2D texture var_texcoord0.xy)))
    #_(setq gl_FragColor (vec4 var_texcoord0.xy 0 1))
    ))

(def shader (shader/make-shader vertex-shader fragment-shader))

; Rendering

(defn render-sprite [^GL2 gl gpu-texture vertex-binding]
 (gl/with-enabled gl [gpu-texture shader vertex-binding]
   (shader/set-uniform shader gl "texture" 0)
   (gl/gl-draw-arrays gl GL/GL_TRIANGLES 0 6)))

; Vertex generation

(defn anim-uvs [textureset anim]
  (let [frame (first (:frames anim))]
    (if-let [{start :tex-coords-start count :tex-coords-count} frame]
      (mapv #(nth (:tex-coords textureset) %) (range start (+ start count)))
      [[0 0] [0 0]])))

(defn gen-vertex [x y u v color]
  (doall (concat [x y 0 1 u v] color)))

(defn gen-quad [textureset animation]
  (let [x1 (* 0.5 (:width animation))
        y1 (* 0.5 (:height animation))
        x0 (- x1)
        y0 (- y1)
        [[u0 v0] [u1 v1]] (anim-uvs textureset animation)
        color [1 1 1 1]]
    [(gen-vertex x0 y0 u0 v1 color)
     (gen-vertex x1 y0 u1 v1 color)
     (gen-vertex x0 y1 u0 v0 color)
     (gen-vertex x1 y0 u1 v1 color)
     (gen-vertex x1 y1 u1 v0 color)
     (gen-vertex x0 y1 u0 v0 color)]))

(defn gen-vertex-buffer
  [textureset animation]
  (let [vbuf  (->texture-vtx 6)]
    (doseq [vertex (gen-quad textureset animation)]
      (conj! vbuf vertex))
    (persistent! vbuf)))

; Node defs

(g/defnk produce-save-data [self image default-animation material blend-mode]
  {:resource (:resource self)
   :content (protobuf/pb->str
              (.build
                (doto (Sprite$SpriteDesc/newBuilder)
                  (.setTileSet image)
                  (.setDefaultAnimation default-animation)
                  (protobuf/set-if-present :material self)
                  (.setBlendMode (protobuf/val->pb-enum Sprite$SpriteDesc$BlendMode blend-mode)))))})

(g/defnk produce-scene
  [self aabb gpu-texture textureset animation]
  (let [vertex-binding (vtx/use-with (gen-vertex-buffer textureset animation) shader)]
    {:id (g/node-id self)
     :aabb aabb 
     :renderables {pass/transparent [{:world-transform geom/Identity4d
                                      :render-fn (g/fnk [gl]
                                                        (render-sprite gl gpu-texture vertex-binding))}]}}))

(g/defnode SpriteNode
  (inherits project/ResourceNode)

  (property image t/Str)
  (property default-animation t/Str)
  (property material t/Str)
  (property blend-mode t/Any (default :BLEND_MODE_ALPHA))

  (input textureset t/Any)
  (input gpu-texture t/Any)

  (output textureset t/Any (g/fnk [textureset] textureset))
  (output gpu-texture t/Any (g/fnk [gpu-texture] gpu-texture))
  (output animation t/Any (g/fnk [textureset default-animation] (get (:animations textureset) default-animation))) ; TODO - use placeholder animation
  (output aabb AABB (g/fnk [animation] (let [hw (* 0.5 (:width animation))
                                            hh (* 0.5 (:height animation))]
                                        (-> (geom/null-aabb)
                                          (geom/aabb-incorporate (Point3d. (- hw) (- hh) 0))
                                          (geom/aabb-incorporate (Point3d. hw hh 0))))))
  (output outline t/Any (g/fnk [self] {:self self :label "Sprite" :icon sprite-icon}))
  (output save-data t/Any :cached produce-save-data)
  (output scene t/Any :cached produce-scene))

(defn load-sprite [project self input]
  (let [sprite (protobuf/pb->map (protobuf/read-text Sprite$SpriteDesc input))]
    (concat
      (g/set-property self :image (:tile-set sprite))
      (g/set-property self :default-animation (:default-animation sprite))
      (g/set-property self :material (:material sprite))
      (g/set-property self :blend-mode (:blend-mode sprite))
      (let [atlas-node (project/resolve-resource-node self (:tile-set sprite))]
        (if atlas-node
          [(g/connect atlas-node :textureset self :textureset)
           (g/connect atlas-node :gpu-texture self :gpu-texture)]
          [])))))

(defn register-resource-types [workspace]
  (workspace/register-resource-type workspace
                                    :ext "sprite"
                                    :node-type SpriteNode
                                    :load-fn load-sprite
                                    :icon sprite-icon
                                    :view-types [:scene]))
