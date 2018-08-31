(ns editor.sprite
  (:require [clojure.string :as str]
            [editor.protobuf :as protobuf]
            [dynamo.graph :as g]
            [editor.graph-util :as gu]
            [editor.geom :as geom]
            [editor.gl :as gl]
            [editor.gl.shader :as shader]
            [editor.gl.texture :as texture]
            [editor.gl.vertex :as vtx]
            [editor.defold-project :as project]
            [editor.material :as material]
            [editor.properties :as properties]
            [editor.scene :as scene]
            [editor.workspace :as workspace]
            [editor.validation :as validation]
            [editor.pipeline :as pipeline]
            [editor.resource :as resource]
            [editor.resource-node :as resource-node]
            [editor.texture-set :as texture-set]
            [editor.gl.pass :as pass]
            [editor.types :as types])
  (:import [com.dynamo.graphics.proto Graphics$Cubemap Graphics$TextureImage Graphics$TextureImage$Image Graphics$TextureImage$Type]
           [com.dynamo.sprite.proto Sprite$SpriteDesc Sprite$SpriteDesc$BlendMode]
           [com.jogamp.opengl.util.awt TextRenderer]
           [editor.types Region Animation Camera Image TexturePacking Rect EngineFormatTexture AABB TextureSetAnimationFrame TextureSetAnimation TextureSet]
           [editor.gl.shader ShaderLifecycle]
           [java.awt.image BufferedImage]
           [java.io PushbackReader]
           [com.jogamp.opengl GL GL2 GLContext GLDrawableFactory]
           [com.jogamp.opengl.glu GLU]
           [javax.vecmath Matrix4d Point3d]))

(set! *warn-on-reflection* true)

(def sprite-icon "icons/32/Icons_14-Sprite.png")

; Render assets

(vtx/defvertex texture-vtx
  (vec4 position)
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

; TODO - macro of this
(def shader (shader/make-shader ::shader vertex-shader fragment-shader))

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

; TODO - macro of this
(def outline-shader (shader/make-shader ::outline-shader outline-vertex-shader outline-fragment-shader))

(defn- conj-animation-data!
  [vbuf animation frame-index world-transform]
  (reduce conj! vbuf (texture-set/vertex-data (get-in animation [:frames frame-index]) world-transform)))

(defn- gen-vertex-buffer
  [renderables count]
  (persistent! (reduce (fn [vbuf {:keys [world-transform updatable user-data]}]
                         (let [{:keys [animation]} user-data
                               frame (get-in updatable [:state :frame] 0)]
                           (conj-animation-data! vbuf animation frame world-transform)))
                       (->texture-vtx (* count 6))
                       renderables)))

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
              anim-width (-> user-data :animation :width)
              anim-height (-> user-data :animation :height)]
          (recur (rest renderables) (conj-outline-quad! vbuf world-transform tmp-point anim-width anim-height cr cg cb)))
        (persistent! vbuf)))))

; Rendering

(defn render-sprites [^GL2 gl render-args renderables count]
  (let [pass (:pass render-args)]
    (condp = pass
      pass/transparent
      (let [user-data (:user-data (first renderables))
            shader (:shader user-data)
            vertex-binding (vtx/use-with ::sprite-trans (gen-vertex-buffer renderables count) shader)
            gpu-texture (:gpu-texture user-data)
            blend-mode (:blend-mode user-data)]
        (gl/with-gl-bindings gl render-args [gpu-texture shader vertex-binding]
          (gl/set-blend-mode gl blend-mode)
          (gl/gl-draw-arrays gl GL/GL_TRIANGLES 0 (* count 6))
          (.glBlendFunc gl GL/GL_SRC_ALPHA GL/GL_ONE_MINUS_SRC_ALPHA)))

      pass/selection
      (let [vertex-binding (vtx/use-with ::sprite-selection (gen-vertex-buffer renderables count) shader)]
        (gl/with-gl-bindings gl render-args [shader vertex-binding]
          (gl/gl-draw-arrays gl GL/GL_TRIANGLES 0 (* count 6)))))))

(defn- render-sprite-outlines [^GL2 gl render-args renderables count]
  (assert (= pass/outline (:pass render-args)))
  (let [outline-vertex-binding (vtx/use-with ::sprite-outline (gen-outline-vertex-buffer renderables count) outline-shader)]
    (gl/with-gl-bindings gl render-args [outline-shader outline-vertex-binding]
      (gl/gl-draw-arrays gl GL/GL_LINES 0 (* count 8)))))

; Node defs

(g/defnk produce-save-value [image default-animation material blend-mode]
  {:tile-set (resource/resource->proj-path image)
   :default-animation default-animation
   :material (resource/resource->proj-path material)
   :blend-mode blend-mode})

(g/defnk produce-scene
  [_node-id aabb gpu-texture material-shader animation blend-mode]
  (cond-> {:node-id _node-id :aabb aabb}
    (seq (:frames animation))
    (assoc :renderable {:render-fn render-sprites
                        :batch-key [gpu-texture blend-mode material-shader]
                        :select-batch-key _node-id
                        :tags #{:sprite}
                        :user-data {:gpu-texture gpu-texture
                                    :shader material-shader
                                    :animation animation
                                    :blend-mode blend-mode}
                        :passes [pass/transparent pass/selection]})
    true
    (assoc :children [{:node-id _node-id
                       :aabb aabb
                       :renderable {:render-fn render-sprite-outlines
                                    :batch-key [outline-shader]
                                    :tags #{:sprite :outline}
                                    :select-batch-key _node-id
                                    :user-data {:animation animation}
                                    :passes [pass/outline]}}])

    (< 1 (count (:frames animation)))
    (assoc :updatable (texture-set/make-animation-updatable _node-id "Sprite" animation))))

(g/defnk produce-build-targets [_node-id resource image anim-ids default-animation material blend-mode dep-build-targets]
  (or (when-let [errors (->> [(validation/prop-error :fatal _node-id :image validation/prop-nil? image "Image")
                              (validation/prop-error :fatal _node-id :material validation/prop-nil? material "Material")
                              (validation/prop-error :fatal _node-id :default-animation validation/prop-nil? default-animation "Default Animation")
                              (validation/prop-error :fatal _node-id :default-animation validation/prop-anim-missing? default-animation anim-ids)]
                             (remove nil?)
                             (seq))]
        (g/error-aggregate errors))
      [(pipeline/make-protobuf-build-target resource dep-build-targets
                                            Sprite$SpriteDesc
                                            {:tile-set          image
                                             :default-animation default-animation
                                             :material          material
                                             :blend-mode        blend-mode}
                                            [:tile-set :material])]))

(defn- sort-anim-ids
  [anim-ids]
  (sort-by str/lower-case anim-ids))

(g/defnode SpriteNode
  (inherits resource-node/ResourceNode)

  (property image resource/Resource
            (value (gu/passthrough image-resource))
            (set (fn [evaluation-context self old-value new-value]
                   (project/resource-setter evaluation-context self old-value new-value
                                            [:resource :image-resource]
                                            [:anim-data :anim-data]
                                            [:anim-ids :anim-ids]
                                            [:gpu-texture :gpu-texture]
                                            [:build-targets :dep-build-targets])))
            (dynamic error (g/fnk [_node-id image anim-data]
                                  (or (validation/prop-error :info _node-id :image validation/prop-nil? image "Image")
                                      (validation/prop-error :fatal _node-id :image validation/prop-resource-not-exists? image "Image"))))
            (dynamic edit-type (g/constantly
                                 {:type resource/Resource
                                  :ext ["atlas" "tilesource"]})))

  (property default-animation g/Str
            (dynamic error (g/fnk [_node-id image anim-ids default-animation]
                                  (when image
                                    (or (validation/prop-error :fatal _node-id :default-animation validation/prop-empty? default-animation "Default Animation")
                                        (validation/prop-error :fatal _node-id :default-animation validation/prop-anim-missing? default-animation anim-ids)))))
            (dynamic edit-type (g/fnk [anim-ids] (properties/->choicebox anim-ids))))

  (property material resource/Resource
            (value (gu/passthrough material-resource))
            (set (fn [evaluation-context self old-value new-value]
                   (project/resource-setter evaluation-context self old-value new-value
                                            [:resource :material-resource]
                                            [:shader :material-shader]
                                            [:samplers :material-samplers]
                                            [:build-targets :dep-build-targets])))
            (dynamic edit-type (g/constantly {:type resource/Resource :ext #{"material"}}))
            (dynamic error (g/fnk [_node-id material]
                                  (or (validation/prop-error :fatal _node-id :material validation/prop-nil? material "Material")
                                      (validation/prop-error :fatal _node-id :material validation/prop-resource-not-exists? material "Material")))))

  (property blend-mode g/Any (default :blend-mode-alpha)
            (dynamic tip (validation/blend-mode-tip blend-mode Sprite$SpriteDesc$BlendMode))
            (dynamic edit-type (g/constantly (properties/->pb-choicebox Sprite$SpriteDesc$BlendMode))))

  (input image-resource resource/Resource)
  (input anim-data g/Any :substitute (fn [v] (assoc v :user-data "the Image has internal errors")))
  (input anim-ids g/Any)
  (input gpu-texture g/Any)
  (input dep-build-targets g/Any :array)

  (input material-resource resource/Resource)
  (input material-shader ShaderLifecycle)
  (input material-samplers g/Any)
  (input default-tex-params g/Any)

  (output tex-params g/Any (g/fnk [material-samplers material-shader default-tex-params]
                             (or (some-> material-samplers first material/sampler->tex-params)
                                 default-tex-params)))
  (output gpu-texture g/Any (g/fnk [gpu-texture tex-params] (texture/set-params gpu-texture tex-params)))
  (output animation g/Any (g/fnk [anim-data default-animation] (get anim-data default-animation))) ; TODO - use placeholder animation
  (output aabb AABB (g/fnk [animation] (if animation
                                         (let [hw (* 0.5 (:width animation))
                                               hh (* 0.5 (:height animation))]
                                           (-> (geom/null-aabb)
                                             (geom/aabb-incorporate (Point3d. (- hw) (- hh) 0))
                                             (geom/aabb-incorporate (Point3d. hw hh 0))))
                                         (geom/null-aabb))))
  (output save-value g/Any produce-save-value)
  (output scene g/Any :cached produce-scene)
  (output build-targets g/Any :cached produce-build-targets))

(defn load-sprite [project self resource sprite]
  (let [image    (workspace/resolve-resource resource (:tile-set sprite))
        material (workspace/resolve-resource resource (:material sprite))]
    (concat
      (g/connect project :default-tex-params self :default-tex-params)
      (g/set-property self :image image)
      (g/set-property self :default-animation (:default-animation sprite))
      (g/set-property self :material material)
      (g/set-property self :blend-mode (:blend-mode sprite)))))

(defn register-resource-types [workspace]
  (resource-node/register-ddf-resource-type workspace
    :ext "sprite"
    :node-type SpriteNode
    :ddf-type Sprite$SpriteDesc
    :load-fn load-sprite
    :icon sprite-icon
    :view-types [:scene :text]
    :tags #{:component}
    :tag-opts {:component {:transform-properties #{:position :rotation}}}
    :label "Sprite"))
