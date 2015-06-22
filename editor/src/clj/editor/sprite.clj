(ns editor.sprite
  (:require [dynamo.file.protobuf :as protobuf]
            [dynamo.graph :as g]
            [dynamo.types :as t]
            [editor.geom :as geom]
            [editor.gl :as gl]
            [editor.gl.shader :as shader]
            [editor.gl.vertex :as vtx]
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
  (vec3 position)
  (vec2 texcoord0 true))

(shader/defshader vertex-shader
  (attribute vec4 position)
  (attribute vec2 texcoord0)
  (varying vec2 var_texcoord0)
  (defn void main []
    (setq gl_Position (* gl_ModelViewProjectionMatrix position))
    (setq var_texcoord0 texcoord0)))

(shader/defshader fragment-shader
  (varying vec2 var_texcoord0)
  (uniform sampler2D texture)
  (defn void main []
    (setq gl_FragColor (texture2D texture var_texcoord0.xy))))

(def shader (shader/make-shader vertex-shader fragment-shader))

(vtx/defvertex color-vtx
  (vec3 position)
  (vec4 color))

(shader/defshader outline-vertex-shader
  (attribute vec4 position)
  (attribute vec4 color)
  (varying vec4 var_color)
  (defn void main []
    (setq gl_Position (* gl_ModelViewProjectionMatrix position))
    (setq var_color color)))

(shader/defshader outline-fragment-shader
  (varying vec4 var_color)
  (defn void main []
    (setq gl_FragColor var_color)))

(def outline-shader (shader/make-shader outline-vertex-shader outline-fragment-shader))

; Vertex generation

(defn- gen-vertex [^Matrix4d wt ^Point3d pt x y u v]
  (.set pt x y 0)
  (.transform wt pt)
  [(.x pt) (.y pt) (.z pt) u v])

(defn- conj-quad! [vbuf ^Matrix4d wt ^Point3d pt width height anim-uvs]
  (let [x1 (* 0.5 width)
        y1 (* 0.5 height)
        x0 (- x1)
        y0 (- y1)
        [[u0 v0] [u1 v1]] anim-uvs]
    (-> vbuf
      (conj! (gen-vertex wt pt x0 y0 u0 v1))
      (conj! (gen-vertex wt pt x1 y0 u1 v1))
      (conj! (gen-vertex wt pt x0 y1 u0 v0))
      (conj! (gen-vertex wt pt x1 y0 u1 v1))
      (conj! (gen-vertex wt pt x1 y1 u1 v0))
      (conj! (gen-vertex wt pt x0 y1 u0 v0)))))

(defn- gen-vertex-buffer
  [renderables count]
  (let [tmp-point (Point3d.)]
    (loop [renderables renderables
          vbuf (->texture-vtx (* count 6))]
      (if-let [renderable (first renderables)]
        (let [world-transform (:world-transform renderable)
              user-data (:user-data renderable)
              anim-uvs (:anim-uvs user-data)
              anim-width (:anim-width user-data)
              anim-height (:anim-height user-data)]
          (recur (rest renderables) (conj-quad! vbuf world-transform tmp-point anim-width anim-height anim-uvs)))
        (persistent! vbuf)))))

(defn- gen-outline-vertex [^Matrix4d wt ^Point3d pt x y cr cg cb]
  (.set pt x y 0)
  (.transform wt pt)
  [(.x pt) (.y pt) (.z pt) cr cg cb 1])

(defn- conj-outline-quad! [vbuf ^Matrix4d wt ^Point3d pt width height cr cg cb]
  (let [x1 (* 0.5 width)
        y1 (* 0.5 height)
        x0 (- x1)
        y0 (- y1)
        v0 (gen-outline-vertex wt pt x0 y0 cr cg cb)
        v1 (gen-outline-vertex wt pt x1 y0 cr cg cb)
        v2 (gen-outline-vertex wt pt x1 y1 cr cg cb)
        v3 (gen-outline-vertex wt pt x0 y1 cr cg cb)]
    (-> vbuf (conj! v0) (conj! v1) (conj! v1) (conj! v2) (conj! v2) (conj! v3) (conj! v3) (conj! v0))))

(def outline-color (scene/select-color pass/outline false [1.0 1.0 1.0]))
(def selected-outline-color (scene/select-color pass/outline true [1.0 1.0 1.0]))

(defn- gen-outline-vertex-buffer
  [renderables count]
  (let [tmp-point (Point3d.)]
    (loop [renderables renderables
           vbuf (->color-vtx (* count 8))]
      (if-let [renderable (first renderables)]
        (let [color (if (:selected renderable) selected-outline-color outline-color)
              cr (get color 0)
              cg (get color 1)
              cb (get color 2)
              world-transform (:world-transform renderable)
              user-data (:user-data renderable)
              anim-width (:anim-width user-data)
              anim-height (:anim-height user-data)]
          (recur (rest renderables) (conj-outline-quad! vbuf world-transform tmp-point anim-width anim-height cr cg cb)))
        (persistent! vbuf)))))

; Rendering

(defn render-sprites [^GL2 gl render-args renderables count]
  (let [pass (:pass render-args)]
    (cond
      (= pass pass/outline)
      (let [outline-vertex-binding (vtx/use-with (gen-outline-vertex-buffer renderables count) outline-shader)]
        (gl/with-enabled gl [outline-shader outline-vertex-binding]
          (gl/gl-draw-arrays gl GL/GL_LINES 0 (* count 8))))

      (= pass pass/transparent)
      (let [vertex-binding (vtx/use-with (gen-vertex-buffer renderables count) shader)
            user-data (:user-data (first renderables))
            gpu-texture (:gpu-texture user-data)
            blend-mode (:blend-mode user-data)]
        (gl/with-enabled gl [gpu-texture shader vertex-binding]
          (case blend-mode
            :BLEND_MODE_ALPHA (.glBlendFunc gl GL/GL_ONE GL/GL_ONE_MINUS_SRC_ALPHA)
            (:BLEND_MODE_ADD :BLEND_MODE_ADD_ALPHA) (.glBlendFunc gl GL/GL_ONE GL/GL_ONE)
            :BLEND_MODE_MULT (.glBlendFunc gl GL/GL_ZERO GL/GL_SRC_COLOR))
          (shader/set-uniform shader gl "texture" 0)
          (gl/gl-draw-arrays gl GL/GL_TRIANGLES 0 (* count 6))
          (.glBlendFunc gl GL/GL_SRC_ALPHA GL/GL_ONE_MINUS_SRC_ALPHA)))

      (= pass pass/selection)
      (let [vertex-binding (vtx/use-with (gen-vertex-buffer renderables count) shader)]
        (gl/with-enabled gl [shader vertex-binding]
          (gl/gl-draw-arrays gl GL/GL_TRIANGLES 0 (* count 6)))))))

; Node defs

(g/defnk produce-save-data [self image default-animation material blend-mode]
  {:resource (:resource self)
   :content (protobuf/pb->str
              (.build
                (doto (Sprite$SpriteDesc/newBuilder)
                  (.setTileSet (workspace/proj-path image))
                  (.setDefaultAnimation default-animation)
                  (.setMaterial (workspace/proj-path material))
                  (.setBlendMode (protobuf/val->pb-enum Sprite$SpriteDesc$BlendMode blend-mode)))))})

(defn anim-uvs [textureset anim]
  (let [frame (first (:frames anim))]
    (if-let [{start :tex-coords-start count :tex-coords-count} frame]
      (mapv #(get (:tex-coords textureset) %) (range start (+ start count)))
      [[0 0] [0 0]])))

(g/defnk produce-scene
  [node-id aabb gpu-texture textureset animation blend-mode]
  (let [scene {:node-id node-id
               :aabb aabb}]
    (if animation
      (let []
        (assoc scene :renderable {:render-fn render-sprites
                                  :batch-key gpu-texture
                                  :select-batch-key node-id
                                  :user-data {:gpu-texture gpu-texture
                                                     :anim-uvs (anim-uvs textureset animation)
                                                     :anim-width (:width animation 0)
                                                     :anim-height (:height animation 0)
                                                     :blend-mode blend-mode}
                                  :passes [pass/transparent pass/selection pass/outline]}))
     scene)))

(defn- connect-atlas [project self image]
  (if-let [atlas-node (project/get-resource-node project image)]
    (let [outputs (g/outputs atlas-node)]
      (if (every? #(contains? outputs %) [:textureset :gpu-texture])
        [(g/connect atlas-node :textureset self :textureset)
        (g/connect atlas-node :gpu-texture self :gpu-texture)]
        []))
    []))

(defn- disconnect-all [self label]
  (let [sources (g/sources-of self label)]
    (for [[src-node src-label] sources]
      (g/disconnect src-node src-label self label))))

(defn reconnect [transaction graph self label kind labels]
  (when (some #{:image} labels)
    (let [image (:image self)
          project (project/get-project self)]
      (concat
        (disconnect-all self :textureset)
        (disconnect-all self :gpu-texture)
        (connect-atlas project self image)))))

(g/defnode SpriteNode
  (inherits project/ResourceNode)

  (property image (t/protocol workspace/Resource))
  (property default-animation t/Str)
  (property material (t/protocol workspace/Resource))
  (property blend-mode t/Any (default :BLEND_MODE_ALPHA) (tag Sprite$SpriteDesc$BlendMode))

  (trigger reconnect :property-touched #'reconnect)

  (input textureset t/Any)
  (input gpu-texture t/Any)

  (output textureset t/Any (g/fnk [textureset] textureset))
  (output gpu-texture t/Any (g/fnk [gpu-texture] gpu-texture))
  (output animation t/Any (g/fnk [textureset default-animation] (get (:animations textureset) default-animation))) ; TODO - use placeholder animation
  (output aabb AABB (g/fnk [animation] (if animation
                                         (let [hw (* 0.5 (:width animation))
                                               hh (* 0.5 (:height animation))]
                                           (-> (geom/null-aabb)
                                             (geom/aabb-incorporate (Point3d. (- hw) (- hh) 0))
                                             (geom/aabb-incorporate (Point3d. hw hh 0))))
                                         (geom/null-aabb))))
  (output outline t/Any :cached (g/fnk [node-id] {:node-id node-id :label "Sprite" :icon sprite-icon}))
  (output save-data t/Any :cached produce-save-data)
  (output scene t/Any :cached produce-scene))

(defn load-sprite [project self input]
  (let [sprite (protobuf/pb->map (protobuf/read-text Sprite$SpriteDesc input))
        resource (:resource self)
        image (workspace/resolve-resource resource (:tile-set sprite))
        material (workspace/resolve-resource resource (:material sprite))]
    (concat
      (g/set-property self :image image)
      (g/set-property self :default-animation (:default-animation sprite))
      (g/set-property self :material material)
      (g/set-property self :blend-mode (:blend-mode sprite))
      (connect-atlas project self image))))

(defn register-resource-types [workspace]
  (workspace/register-resource-type workspace
                                    :ext "sprite"
                                    :node-type SpriteNode
                                    :load-fn load-sprite
                                    :icon sprite-icon
                                    :view-types [:scene]
                                    :tags #{:component}
                                    :template "templates/template.sprite"))
