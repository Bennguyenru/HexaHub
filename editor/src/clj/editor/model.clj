;; Copyright 2020-2023 The Defold Foundation
;; Copyright 2014-2020 King
;; Copyright 2009-2014 Ragnar Svensson, Christian Murray
;; Licensed under the Defold License version 1.0 (the "License"); you may not use
;; this file except in compliance with the License.
;;
;; You may obtain a copy of the License, together with FAQs at
;; https://www.defold.com/license
;;
;; Unless required by applicable law or agreed to in writing, software distributed
;; under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
;; CONDITIONS OF ANY KIND, either express or implied. See the License for the
;; specific language governing permissions and limitations under the License.

(ns editor.model
  (:require [clojure.string :as str]
            [dynamo.graph :as g]
            [editor.animation-set :as animation-set]
            [editor.build-target :as bt]
            [editor.defold-project :as project]
            [editor.geom :as geom]
            [editor.gl.pass :as pass]
            [editor.graph-util :as gu]
            [editor.image :as image]
            [editor.material :as material]
            [editor.model-scene :as model-scene]
            [editor.properties :as properties]
            [editor.protobuf :as protobuf]
            [editor.resource :as resource]
            [editor.resource-node :as resource-node]
            [editor.rig :as rig]
            [editor.validation :as validation]
            [editor.workspace :as workspace]
            [internal.util :as util]
            [schema.core :as s]
            [util.digest :as digest])
  (:import [com.dynamo.gamesys.proto ModelProto$Model ModelProto$ModelDesc]
           [editor.gl.shader ShaderLifecycle]
           [editor.types AABB]))

(set! *warn-on-reflection* true)

(def ^:private model-icon "icons/32/Icons_22-Model.png")

(g/defnk produce-animation-set-build-target-single [_node-id resource animations-resource animation-set]
  (let [is-single-anim (and (not (empty? animation-set))
                            (not (animation-set/is-animation-set? animations-resource)))]
    (when is-single-anim
      (rig/make-animation-set-build-target (resource/workspace resource) _node-id animation-set))))

(g/defnk produce-animation-ids [_node-id resource animations-resource animation-set-info animation-set animation-ids]
  (let [is-single-anim (or (empty? animation-set)
                           (not (animation-set/is-animation-set? animations-resource)))]
    (if is-single-anim
      (if animations-resource
        animation-ids
        [])
      (:animation-ids animation-set-info))))

(g/defnk produce-pb-msg [name mesh materials skeleton animations default-animation]
  (cond-> {:mesh (resource/resource->proj-path mesh)
           :materials (mapv
                        (fn [material]
                          (-> material
                              (update :material resource/resource->proj-path)
                              (update :textures
                                      (fn [textures]
                                        (into []
                                              (keep (fn [texture]
                                                      (when-let [resource (:texture texture)]
                                                        (assoc texture :texture (resource/proj-path resource)))))
                                              textures)))))
                        materials)
           :skeleton (resource/resource->proj-path skeleton)
           :animations (resource/resource->proj-path animations)
           :default-animation default-animation}
          (not (str/blank? name))
          (assoc :name name)))

(defn- build-pb [resource dep-resources {:keys [pb] :as user-data}]
  (let [pb (reduce-kv
             (fn [acc path res]
               (assoc-in acc path (resource/resource->proj-path (get dep-resources res))))
             pb
             (:dep-resources user-data))]
    {:resource resource :content (protobuf/map->bytes ModelProto$Model pb)}))

(defn- prop-resource-error [nil-severity _node-id prop-kw prop-value prop-name]
  (or (validation/prop-error nil-severity _node-id prop-kw validation/prop-nil? prop-value prop-name)
      (validation/prop-error :fatal _node-id prop-kw validation/prop-resource-not-exists? prop-value prop-name)))

(defn- res-fields->resources [pb-msg deps-by-source fields]
  (letfn [(fill-from-key-path [acc source acc-path key-path-index key-path]
            (let [end (= key-path-index (count key-path))]
              (if end
                (let [dep (get deps-by-source source ::not-found)]
                  (if (identical? dep ::not-found)
                    acc
                    (assoc! acc acc-path dep)))
                (let [k (key-path key-path-index)
                      v (source k)
                      acc-path (conj acc-path k)]
                  (if (vector? v)
                    (reduce
                      (fn [acc i]
                        (let [item (v i)
                              acc-path (conj acc-path i)]
                          (fill-from-key-path acc item acc-path (inc key-path-index) key-path)))
                      acc
                      (range (count v)))
                    (fill-from-key-path acc v acc-path (inc key-path-index) key-path))))))]
    (persistent!
      (reduce
        (fn [acc field]
          (let [key-path (if (vector? field) field [field])]
            (fill-from-key-path acc pb-msg [] 0 key-path)))
        (transient {})
        fields))))

(defn- validate-default-animation [_node-id default-animation animation-ids]
  (when (not (str/blank? default-animation))
    (validation/prop-error :fatal _node-id :default-animation validation/prop-member-of? default-animation (set animation-ids)
                           (format "Animation '%s' does not exist" default-animation))))

(g/defnk produce-build-targets [_node-id resource pb-msg dep-build-targets default-animation animation-ids animation-set-build-target animation-set-build-target-single mesh-set-build-target materials skeleton-build-target animations mesh skeleton]
  (or (some->> (into [(prop-resource-error :fatal _node-id :mesh mesh "Mesh")
                      (validation/prop-error :fatal _node-id :skeleton validation/prop-resource-not-exists? skeleton "Skeleton")
                      (validation/prop-error :fatal _node-id :animations validation/prop-resource-not-exists? animations "Animations")
                      (validate-default-animation _node-id default-animation animation-ids)
                      (validation/prop-error :fatal _node-id :materials validation/prop-empty? (:materials pb-msg) "Materials")]
                     (map (fn [{:keys [name material]}]
                            (validation/prop-error
                              :fatal _node-id
                              :materials validation/prop-resource-missing?
                              material name)))
                     materials)
               (filterv identity)
               not-empty
               g/error-aggregate)
      (let [workspace (resource/workspace resource)
            animation-set-build-target (if (nil? animation-set-build-target-single) animation-set-build-target animation-set-build-target-single)
            rig-scene-type (workspace/get-resource-type workspace "rigscene")
            rig-scene-pseudo-data (digest/string->sha1-hex (str/join (map #(-> % :resource :resource :data) [animation-set-build-target mesh-set-build-target skeleton-build-target])))
            rig-scene-resource (resource/make-memory-resource workspace rig-scene-type rig-scene-pseudo-data)
            rig-scene-dep-build-targets {:animation-set animation-set-build-target
                                         :mesh-set mesh-set-build-target
                                         :skeleton skeleton-build-target}
            rig-scene-pb-msg {:texture-set ""} ; Set in the ModelProto$Model message. Other field values taken from build targets.
            rig-scene-additional-resource-keys []
            rig-scene-build-targets (rig/make-rig-scene-build-targets _node-id rig-scene-resource rig-scene-pb-msg dep-build-targets rig-scene-additional-resource-keys rig-scene-dep-build-targets)
            pb-msg (select-keys pb-msg [:materials :default-animation])
            dep-build-targets (into rig-scene-build-targets (flatten dep-build-targets))
            deps-by-source (into {}
                                 (map #(let [res (:resource %)]
                                         [(resource/proj-path (:resource res)) res]))
                                 dep-build-targets)
            dep-resources (res-fields->resources pb-msg deps-by-source
                                                 [:rig-scene
                                                  [:materials :material]
                                                  [:materials :textures :texture]])]
        [(bt/with-content-hash
           {:node-id _node-id
            :resource (workspace/make-build-resource resource)
            :build-fn build-pb
            :user-data {:pb pb-msg
                        :dep-resources dep-resources}
            :deps dep-build-targets})])))

(g/defnk produce-gpu-textures [_node-id samplers texture-binding-infos :as m]
  (let [sampler-name->gpu-texture-generator (into {}
                                                  (keep (fn [{:keys [sampler-name gpu-texture-generator]}]
                                                          (when gpu-texture-generator
                                                            [sampler-name gpu-texture-generator])))
                                                  texture-binding-infos)]
    (into {}
          (keep-indexed
            (fn [unit-index {:keys [name] :as sampler}]
              (when-let [{tex-fn :f tex-args :args} (sampler-name->gpu-texture-generator name)]
                (let [request-id [_node-id unit-index]
                      params     (material/sampler->tex-params sampler)
                      texture    (tex-fn tex-args request-id params unit-index)]
                  [name texture]))))
          samplers)))

(g/defnk produce-scene [_node-id scene mesh-material-ids scene-infos :as m]
  (let [name->scene-info (into {}
                               (map (juxt :name identity))
                               scene-infos)
        material-index->scene-info (into {}
                                         (keep-indexed
                                           (fn [i name]
                                             (when-let [info (name->scene-info name)]
                                               [i info])))
                                         mesh-material-ids)]
    (if (not scene)
      {:aabb geom/empty-bounding-box
       :renderable {:passes [pass/selection]}}
      (let [{:keys [renderable aabb]} scene
            material-index->meshes (->> renderable :user-data :meshes (group-by :material-index))]
        {:aabb aabb
         :renderable {:passes [pass/selection]}
         :children
         (into (:children scene [])
               (keep (fn [[material-index meshes]]
                       (when-let [{:keys [shader vertex-space gpu-textures]}
                                  ;; If we have no material associated with the index,
                                  ;; we mirror the engine behavior by picking the first one:
                                  ;; https://github.com/defold/defold/blob/a265a1714dc892eea285d54eae61d0846b48899d/engine/gamesys/src/gamesys/resources/res_model.cpp#L234-L238
                                  (or (material-index->scene-info material-index)
                                      (first scene-infos))]
                         {:node-id _node-id
                          :aabb aabb
                          :renderable (-> renderable
                                          (dissoc :children)
                                          (assoc-in [:user-data :shader] shader)
                                          (assoc-in [:user-data :vertex-space] vertex-space)
                                          (assoc-in [:user-data :textures] gpu-textures)
                                          (assoc-in [:user-data :meshes] meshes)
                                          (update :batch-key
                                                  (fn [old-key]
                                                    ;; We can only batch-render models that use
                                                    ;; :vertex-space-world. In :vertex-space-local
                                                    ;; we must supply individual transforms for
                                                    ;; each model instance in the shader uniforms.
                                                    (when (= :vertex-space-world vertex-space)
                                                      [old-key shader gpu-textures]))))})))
               material-index->meshes)}))))

(g/defnk produce-bones [skeleton-bones animations-bones]
  (or animations-bones skeleton-bones))

(def TTexture
  {:sampler s/Str
   :texture (s/maybe (s/protocol resource/Resource))})
(g/deftype Textures [TTexture])
(g/deftype Materials
  [{:name s/Str
    :material (s/maybe (s/protocol resource/Resource))
    :textures [TTexture]}])

(g/defnode MaterialBinding
  (input dep-build-targets g/Any :array)
  (input shader ShaderLifecycle)
  (input vertex-space g/Keyword)
  (input samplers g/Any)

  (property name g/Str (default ""))
  (property material resource/Resource
            (value (gu/passthrough material-resource))
            (set (fn [evaluation-context self old-value new-value]
                   (concat
                     (when old-value
                       (g/delete-node (g/node-value self :source-id evaluation-context)))
                     (when new-value
                       (let [{:keys [tx-data node-id]} (project/connect-resource-node
                                                         evaluation-context
                                                         (project/get-project (:basis evaluation-context) self)
                                                         new-value
                                                         self [])]
                         (concat
                           tx-data
                           (g/override
                             node-id {}
                             (fn [_evaluation-context id-mapping]
                               (let [override-material-node (get id-mapping node-id)]
                                 (for [[from-label to-label] [[:_node-id :source-id]
                                                              [:texture-binding-infos :texture-binding-infos]
                                                              [:resource :material-resource]
                                                              [:model-dep-build-targets :dep-build-targets]
                                                              [:build-targets :dep-build-targets]
                                                              [:samplers :samplers]
                                                              [:shader :shader]
                                                              [:vertex-space :vertex-space]]]
                                   (g/connect override-material-node from-label self to-label))))))))))))
  (input source-id g/NodeID :cascade-delete)
  ;; related to material
  (input material-resource resource/Resource)
  (input texture-binding-infos g/Any)

  (property textures Textures
            (set (fn [evaluation-context self _old-value new-value]
                   (let [texture-binding-infos (g/node-value self :texture-binding-infos evaluation-context)]
                     (when-not (g/error-value? texture-binding-infos)
                       (let [material-sampler-names (mapv :sampler-name texture-binding-infos)
                             model-sampler-names (mapv :sampler new-value)
                             renames (:renamed (util/order-agnostic-diff-by-identity model-sampler-names material-sampler-names))
                             material-sampler-name+order->node-id (util/multi-index texture-binding-infos :sampler-name :_node-id)
                             model-sampler-name+order->resource (util/multi-index new-value :sampler :texture)]
                         (for [[model-sampler-name+order resource] model-sampler-name+order->resource
                               :let [node-id (or (material-sampler-name+order->node-id model-sampler-name+order)
                                                 (material-sampler-name+order->node-id (get renames model-sampler-name+order)))]
                               :when node-id]
                           (g/set-property node-id :image-resource resource))))))))
  ;; related to textures
  (output gpu-textures g/Any :cached produce-gpu-textures)
  (output dep-build-targets g/Any (gu/passthrough dep-build-targets))

  (output scene-info g/Any (g/fnk [shader vertex-space gpu-textures name :as info] info))
  (output material-binding-info g/Any (g/fnk [_node-id name material ^:try texture-binding-infos :as info]
                                        (if (g/error-value? texture-binding-infos)
                                          (assoc info :texture-binding-infos [])
                                          info))))

(defn- update-materials [evaluation-context self old-value new-value]
  (when-let [{:keys [added removed changed renamed]} (util/order-agnostic-diff-by-map-key old-value new-value :name)]
    (let [old-material-binding-infos (g/node-value self :material-binding-infos evaluation-context)
          old-material-binding-name+order->info (util/multi-index-by-map-key old-material-binding-infos :name)]
      (concat
        (for [[[name] {:keys [material textures]}] added]
          (g/make-nodes (g/node-id->graph-id self) [binding [MaterialBinding :name name]]
            ;; We need to ensure material is applied first, so we don't lose the textures
            ;; since model sampler names are matched against the material-defined samplers
            (g/set-property binding :material material)
            (g/set-property binding :textures textures)
            (g/connect binding :_node-id self :nodes)
            (g/connect binding :dep-build-targets self :dep-build-targets)
            (g/connect binding :scene-info self :scene-infos)
            (g/connect binding :material-binding-info self :material-binding-infos)))
        (for [[old-material-binding-name+order [_ {:keys [material textures]}]] changed
              :let [binding (:_node-id (old-material-binding-name+order->info old-material-binding-name+order))]
              tx [(g/set-property binding :material material)
                  (g/set-property binding :textures textures)]]
          tx)
        (for [[old-material-binding-name+order [new-material-binding-name]] renamed]
          (g/set-property (:_node-id (old-material-binding-name+order->info old-material-binding-name+order))
            :name new-material-binding-name))
        (for [[material-binding-name+order] removed]
          (g/delete-node (:_node-id (old-material-binding-name+order->info material-binding-name+order))))))))

(defn- add-material [model-node-id material-name material-resource]
  (g/update-property model-node-id :materials conj {:name material-name
                                                    :material material-resource
                                                    :textures []}))

(defn- remove-material [model-node-id material-name]
  (g/update-property model-node-id
                     :materials
                     (fn [materials]
                       (filterv #(not= material-name (:name %)) materials))))

(def ^:private fake-resource
  (reify resource/Resource
    (children [_])
    (ext [_] "")
    (resource-type [_])
    (source-type [_])
    (exists? [_] false)
    (read-only? [_] true)
    (path [_] "")
    (abs-path [_] "")
    (proj-path [_] "")
    (resource-name [_] "")
    (workspace [_])
    (resource-hash [_])
    (openable? [_] false)
    (editable? [_] false)))

(g/defnk produce-model-properties [_node-id _declared-properties material-binding-infos mesh-material-ids]
  (let [model-node-id _node-id
        mesh-id? (set mesh-material-ids)
        proto-ids (into #{} (map :name) material-binding-infos)
        new-props (-> []
                      ;; existing materials / textures:
                      (into
                        (mapcat
                          (fn [{:keys [_node-id material name texture-binding-infos]}]
                            (let [should-be-deleted (not (mesh-id? name))]
                              (into [[(keyword (str "__material__" name))
                                      (cond-> {:node-id _node-id
                                               :label name
                                               :type resource/Resource
                                               :value (cond-> material should-be-deleted (or fake-resource))
                                               :error (or
                                                        (when should-be-deleted
                                                          (g/->error _node-id :materials :warning material
                                                                     (format "'%s' is not defined in the mesh. Clear the field to delete it."
                                                                             name)))
                                                        (prop-resource-error :fatal _node-id :materials material "Material"))
                                               :prop-kw :material
                                               :edit-type {:type resource/Resource
                                                           :ext "material"
                                                           :clear-fn (fn [_ _]
                                                                       (remove-material model-node-id name))}}
                                              should-be-deleted
                                              (assoc :original-value fake-resource))]]
                                    (map (fn [{:keys [_node-id sampler-name image-resource]}]
                                           [(keyword (str "__sampler__" sampler-name "__" name))
                                            {:node-id _node-id
                                             :label sampler-name
                                             :type resource/Resource
                                             :value image-resource
                                             :prop-kw :image-resource
                                             :edit-type {:type resource/Resource
                                                         :ext (conj image/exts "cubemap")}}]))
                                    texture-binding-infos))))
                        material-binding-infos)
                      ;; required materials that are not defined:
                      (into
                        (comp
                          (remove proto-ids)
                          (map (fn [id-to-add]
                                 [(keyword (str "__material__" id-to-add))
                                  {:node-id _node-id
                                   :label id-to-add
                                   :value nil
                                   :type resource/Resource
                                   :error (prop-resource-error :fatal _node-id :material nil "Material")
                                   :edit-type {:type resource/Resource
                                               :ext "material"
                                               :set-fn (fn [_evaluation-context id _old new]
                                                         (add-material id id-to-add new))}}])))
                        mesh-material-ids))]
    (-> _declared-properties
        (update :properties into new-props)
        (update :display-order into (map first) new-props))))

(g/defnode ModelNode
  (inherits resource-node/ResourceNode)

  (property name g/Str (dynamic visible (g/constantly false)))
  (property mesh resource/Resource
            (value (gu/passthrough mesh-resource))
            (set (fn [evaluation-context self old-value new-value]
                   (project/resource-setter evaluation-context self old-value new-value
                                            [:resource :mesh-resource]
                                            [:aabb :aabb]
                                            [:mesh-set-build-target :mesh-set-build-target]
                                            [:material-ids :mesh-material-ids]
                                            [:scene :scene])))
            (dynamic error (g/fnk [_node-id mesh]
                                  (prop-resource-error :fatal _node-id :mesh mesh "Mesh")))
            (dynamic edit-type (g/constantly {:type resource/Resource
                                              :ext model-scene/model-file-types})))
  (property materials Materials
            (value (gu/passthrough materials-value))
            (set (fn [evaluation-context self old-value new-value]
                   (update-materials evaluation-context self old-value new-value)))
            (dynamic visible (g/constantly false)))
  (input material-binding-infos g/Any :array)
  (output materials-value Materials :cached
          (g/fnk [material-binding-infos]
            (mapv
              (fn [{:keys [name material texture-binding-infos]}]
                {:name name
                 :material material
                 :textures (mapv
                             (fn [{:keys [sampler-name image-resource]}]
                               {:sampler sampler-name
                                :texture image-resource})
                             texture-binding-infos)})
              material-binding-infos)))
  (input scene g/Any)
  (input scene-infos g/Any :array)
  (property skeleton resource/Resource
            (value (gu/passthrough skeleton-resource))
            (set (fn [evaluation-context self old-value new-value]
                   (project/resource-setter evaluation-context self old-value new-value
                                            [:resource :skeleton-resource]
                                            [:bones :skeleton-bones]
                                            [:skeleton-build-target :skeleton-build-target])))
            (dynamic error (g/fnk [_node-id skeleton]
                                  (validation/prop-error :fatal _node-id :skeleton validation/prop-resource-not-exists? skeleton "Skeleton")))
            (dynamic edit-type (g/constantly {:type resource/Resource
                                              :ext model-scene/model-file-types})))
  (property animations resource/Resource
            (value (gu/passthrough animations-resource))
            (set (fn [evaluation-context self old-value new-value]
                   (project/resource-setter evaluation-context self old-value new-value
                                            [:resource :animations-resource]
                                            [:bones :animations-bones]
                                            [:animation-ids :animation-ids]
                                            [:animation-info :animation-infos]
                                            [:animation-set-build-target :animation-set-build-target])))
            (dynamic error (g/fnk [_node-id animations]
                                  (validation/prop-error :fatal _node-id :animations validation/prop-resource-not-exists? animations "Animations")))
            (dynamic edit-type (g/constantly {:type resource/Resource
                                              :ext model-scene/animation-file-types})))
  (property default-animation g/Str
            (dynamic error (g/fnk [_node-id default-animation animation-ids]
                                  (validate-default-animation _node-id default-animation animation-ids)))
            (dynamic edit-type (g/fnk [animation-ids]
                                      (properties/->choicebox (into [""] animation-ids)))))

  (input mesh-resource resource/Resource)
  (input mesh-set-build-target g/Any)
  (input mesh-material-ids g/Any)

  (input skeleton-resource resource/Resource)
  (input skeleton-build-target g/Any)
  (input animations-resource resource/Resource)
  (input animation-set-build-target g/Any)
  (input dep-build-targets g/Any :array)

  (input skeleton-bones g/Any)
  (input animations-bones g/Any)

  (input animation-infos g/Any :array)
  (input animation-ids g/Any)
  (input aabb AABB)

  (output bones g/Any produce-bones)

  (output animation-resources g/Any (g/fnk [animations-resource] [animations-resource]))

  (output animation-info g/Any :cached animation-set/produce-animation-info)
  (output animation-set-info g/Any :cached animation-set/produce-animation-set-info)
  (output animation-set g/Any :cached animation-set/produce-animation-set)
  (output animation-ids g/Any :cached produce-animation-ids)

  ; if we're referencing a single animation file
  (output animation-set-build-target-single g/Any :cached produce-animation-set-build-target-single)

  (output pb-msg g/Any :cached produce-pb-msg)
  (output save-value g/Any (gu/passthrough pb-msg))
  (output build-targets g/Any :cached produce-build-targets)

  (output scene g/Any :cached produce-scene)

  (output aabb AABB (gu/passthrough aabb))
  (output _properties g/Properties :cached produce-model-properties))

(defn load-model [_project self resource {:keys [name default-animation mesh skeleton animations materials]}]
  (g/set-property self
    :name name
    :default-animation default-animation
    :mesh (workspace/resolve-resource resource mesh)
    :materials (mapv (fn [{:keys [material textures] :as material-desc}]
                       (assoc material-desc
                         :material (workspace/resolve-resource resource material)
                         :textures (mapv (fn [{:keys [texture] :as texture-desc}]
                                           (assoc texture-desc :texture (workspace/resolve-resource resource texture)))
                                         textures)))
                     materials)
    :skeleton (workspace/resolve-resource resource skeleton)
    :animations (workspace/resolve-resource resource animations)))

(defn- sanitize-model [{:keys [material textures materials] :as pb}]
  (-> pb
      (dissoc :material :textures)
      (cond-> (and (zero? (count materials))
                   (or (pos? (count material))
                       (pos? (count textures))))
              (assoc :materials [{:name "default"
                                  :material material
                                  :textures (into []
                                                  (map-indexed
                                                    (fn [i tex-name]
                                                      {:sampler (str "tex" i)
                                                       :texture tex-name}))
                                                  textures)}]))))

(defn register-resource-types [workspace]
  (resource-node/register-ddf-resource-type workspace
    :ext "model"
    :label "Model"
    :node-type ModelNode
    :ddf-type ModelProto$ModelDesc
    :load-fn load-model
    :sanitize-fn sanitize-model
    :icon model-icon
    :view-types [:scene :text]
    :tags #{:component}
    :tag-opts {:component {:transform-properties #{:position :rotation}}}))
