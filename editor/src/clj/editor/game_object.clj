(ns editor.game-object
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [editor.protobuf :as protobuf]
            [dynamo.graph :as g]
            [schema.core :as s]
            [editor.graph-util :as gu]
            [editor.core :as core]
            [editor.dialogs :as dialogs]
            [editor.geom :as geom]
            [editor.handler :as handler]
            [editor.math :as math]
            [editor.defold-project :as project]
            [editor.scene :as scene]
            [editor.types :as types]
            [editor.sound :as sound]
            [editor.script :as script]
            [editor.resource :as resource]
            [editor.workspace :as workspace]
            [editor.properties :as properties]
            [editor.validation :as validation]
            [editor.outline :as outline]
            [editor.util :as util])
  (:import [com.dynamo.gameobject.proto GameObject$PrototypeDesc]
           [com.dynamo.graphics.proto Graphics$Cubemap Graphics$TextureImage Graphics$TextureImage$Image Graphics$TextureImage$Type]
           [com.dynamo.sound.proto Sound$SoundDesc]
           [com.dynamo.proto DdfMath$Point3 DdfMath$Quat]
           [com.jogamp.opengl.util.awt TextRenderer]
           [editor.types Region Animation Camera Image TexturePacking Rect EngineFormatTexture AABB TextureSetAnimationFrame TextureSetAnimation TextureSet]
           [java.awt.image BufferedImage]
           [java.io PushbackReader]
           [com.jogamp.opengl GL GL2 GLContext GLDrawableFactory]
           [com.jogamp.opengl.glu GLU]
           [javax.vecmath Matrix4d Point3d Quat4d Vector3d]
           [org.apache.commons.io FilenameUtils]))

(set! *warn-on-reflection* true)

(def game-object-icon "icons/32/Icons_06-Game-object.png")
(def unknown-icon "icons/32/Icons_29-AT-Unkown.png") ; spelling...

(defn- gen-ref-ddf
  ([id position rotation path]
    (gen-ref-ddf id position rotation path {}))
  ([id position rotation path ddf-properties ddf-property-decls]
    {:id id
     :position position
     :rotation rotation
     :component (resource/resource->proj-path path)
     :properties ddf-properties
     :property-decls ddf-property-decls}))

(defn- gen-embed-ddf [id position rotation save-data]
  {:id id
   :type (or (and (:resource save-data) (:ext (resource/resource-type (:resource save-data))))
             "unknown")
   :position position
   :rotation rotation
   :data (or (:content save-data) "")})

(defn- wrap-if-raw-sound [_node-id target]
  (let [source-path (resource/proj-path (:resource (:resource target)))
        ext (FilenameUtils/getExtension source-path)]
    (if (sound/supported-audio-formats ext)
      (let [workspace (project/workspace (project/get-project _node-id))
            res-type  (workspace/get-resource-type workspace "sound")
            pb        {:sound source-path}
            target    {:node-id  _node-id
                       :resource (workspace/make-build-resource (resource/make-memory-resource workspace res-type (protobuf/map->str Sound$SoundDesc pb)))
                       :build-fn (fn [self basis resource dep-resources user-data]
                                   (let [pb (:pb user-data)
                                         pb (assoc pb :sound (resource/proj-path (second (first dep-resources))))]
                                     {:resource resource :content (protobuf/map->bytes Sound$SoundDesc pb)}))
                       :deps     [target]}]
        target)
      target)))

(defn- source-outline-subst [err]
  ;; TODO: embed error
  {:node-id 0
   :icon ""
   :label ""})

(defn- prop-id-duplicate? [id-counts id]
  (when (> (id-counts id) 1)
    (format "'%s' is in use by another instance" id)))

(g/defnode ComponentNode
  (inherits scene/SceneNode)
  (inherits outline/OutlineNode)

  (property id g/Str
            (dynamic error (g/fnk [_node-id id id-counts]
                                  (or (validation/prop-error :fatal _node-id :id validation/prop-empty? id "Id")
                                      (validation/prop-error :fatal _node-id :id (partial prop-id-duplicate? id-counts) id)))))
  (property url g/Str
            (value (g/fnk [base-url id] (format "%s#%s" (or base-url "") id)))
            (dynamic read-only? (g/always true)))

  (display-order [:id :url :path scene/SceneNode])

  (input source-resource resource/Resource)
  (input source-properties g/Properties :substitute {:properties {}})
  (input scene g/Any)
  (input source-build-targets g/Any)
  (input base-url g/Str)
  (input id-counts g/Any)

  (input source-outline outline/OutlineData :substitute source-outline-subst)

  (output component-id g/IdPair (g/fnk [_node-id id] [id _node-id]))
  (output node-outline outline/OutlineData :cached
    (g/fnk [_node-id node-outline-label id source-outline source-properties]
      (let [source-outline (or source-outline {:icon unknown-icon})
            overridden? (boolean (some (fn [[_ p]] (contains? p :original-value)) (:properties source-properties)))]
        (assoc source-outline :node-id _node-id :label node-outline-label
               :outline-overridden? overridden?))))
  (output node-outline-label g/Str (gu/passthrough id))
  (output ddf-message g/Any :cached (g/fnk [rt-ddf-message] (dissoc rt-ddf-message :property-decls)))
  (output rt-ddf-message g/Any :abstract)
  (output scene g/Any :cached (g/fnk [_node-id transform scene]
                                     (-> scene
                                       (assoc :node-id _node-id
                                              :transform transform
                                              :aabb (geom/aabb-transform (geom/aabb-incorporate (get scene :aabb (geom/null-aabb)) 0 0 0) transform))
                                       (update :node-path (partial cons _node-id)))))
  (output build-resource resource/Resource (g/fnk [source-build-targets] (:resource (first source-build-targets))))
  (output build-targets g/Any :cached (g/fnk [_node-id source-build-targets build-resource rt-ddf-message transform]
                                             (if-let [target (first source-build-targets)]
                                               (let [target (->> (assoc target :resource build-resource)
                                                              (wrap-if-raw-sound _node-id))]
                                                 [(assoc target :instance-data {:resource (:resource target)
                                                                                :instance-msg rt-ddf-message
                                                                                :transform transform})])
                                               [])))
  (output _properties g/Properties :cached (g/fnk [_declared-properties source-properties]
                                                  (merge-with into _declared-properties source-properties))))

(g/defnode EmbeddedComponent
  (inherits ComponentNode)

  (input save-data g/Any :cascade-delete)
  (output rt-ddf-message g/Any :cached (g/fnk [id position rotation save-data]
                                              (gen-embed-ddf id position rotation save-data)))
  (output build-resource resource/Resource (g/fnk [source-resource save-data]
                                                  (some-> source-resource
                                                     (assoc :data (:content save-data))
                                                     workspace/make-build-resource))))

(g/defnode ReferencedComponent
  (inherits ComponentNode)

  (property path g/Any
            (dynamic edit-type (g/fnk [source-resource]
                                      {:type resource/Resource
                                       :ext (some-> source-resource resource/resource-type :ext)
                                       :to-type (fn [v] (:resource v))
                                       :from-type (fn [r] {:resource r :overrides {}})}))
            (value (g/fnk [source-resource ddf-properties]
                          {:resource source-resource
                           :overrides (into {} (map (fn [p] [(properties/user-name->key (:id p)) [(:type p) (properties/str->go-prop (:value p) (:type p))]])
                                                    ddf-properties))}))
            (set (fn [basis self old-value new-value]
                   (concat
                     (if-let [old-source (g/node-value self :source-id {:basis basis})]
                       (g/delete-node old-source)
                       [])
                     (let [new-resource (:resource new-value)
                           resource-type (and new-resource (resource/resource-type new-resource))
                           override? (contains? (:tags resource-type) :overridable-properties)]
                       (if override?
                         (let [project (project/get-project self)]
                           (project/connect-resource-node project new-resource self []
                                                          (fn [comp-node]
                                                            (let [override (g/override basis comp-node {:traverse? (constantly true)})
                                                                  id-mapping (:id-mapping override)
                                                                  or-node (get id-mapping comp-node)
                                                                  comp-props (:properties (g/node-value comp-node :_properties {:basis basis}))]
                                                              (concat
                                                                (:tx-data override)
                                                                (let [outputs (g/output-labels (:node-type (resource/resource-type new-resource)))]
                                                                  (for [[from to] [[:_node-id :source-id]
                                                                                   [:resource :source-resource]
                                                                                   [:node-outline :source-outline]
                                                                                   [:_properties :source-properties]
                                                                                   [:scene :scene]
                                                                                   [:build-targets :source-build-targets]]
                                                                        :when (contains? outputs from)]
                                                                    (g/connect or-node from self to)))
                                                                (for [[label [type value]] (:overrides new-value)]
                                                                  (let [original-type (get-in comp-props [label :type])
                                                                        override-type (script/go-prop-type->property-types type)]
                                                                    (when (= original-type override-type)
                                                                      (g/set-property or-node label value)))))))))
                         (project/resource-setter basis self (:resource old-value) (:resource new-value)
                                                  [:resource :source-resource]
                                                  [:node-outline :source-outline]
                                                  [:user-properties :user-properties]
                                                  [:scene :scene]
                                                  [:build-targets :source-build-targets]))))))
            (dynamic error (g/fnk [_node-id source-resource]
                                  (or (validation/prop-error :info _node-id :path validation/prop-nil? source-resource "Path")
                                      (validation/prop-error :fatal _node-id :path validation/prop-resource-not-exists? source-resource "Path")))))

  (input source-id g/NodeID :cascade-delete)
  (output ddf-properties g/Any :cached
          (g/fnk [source-properties]
                 (let [prop-order (into {} (map-indexed (fn [i k] [k i]) (:display-order source-properties)))]
                   (->> source-properties
                     :properties
                     (filter (fn [[key p]] (contains? p :original-value)))
                     (sort-by (comp prop-order first))
                     (mapv (fn [[key p]]
                             {:id (properties/key->user-name key)
                              :type (:go-prop-type p)
                              :value (properties/go-prop->str (:value p) (:go-prop-type p))}))))))
  (output ddf-property-decls g/Any :cached (g/fnk [ddf-properties] (properties/properties->decls ddf-properties)))
  (output node-outline-label g/Str :cached (g/fnk [id source-resource]
                                                  (format "%s - %s" id (resource/resource->proj-path source-resource))))
  (output rt-ddf-message g/Any :cached (g/fnk [id position rotation source-resource ddf-properties ddf-property-decls]
                                              (gen-ref-ddf id position rotation source-resource ddf-properties ddf-property-decls))))

(g/defnk produce-proto-msg [ref-ddf embed-ddf]
  {:components ref-ddf
   :embedded-components embed-ddf})

(g/defnk produce-save-data [resource proto-msg]
  {:resource resource
   :content (protobuf/map->str GameObject$PrototypeDesc proto-msg)})

(defn- externalize [inst-data resources]
  (map (fn [data]
         (let [{:keys [resource instance-msg transform]} data
               resource (get resources resource)
               instance-msg (dissoc instance-msg :type :data)]
           (merge instance-msg
                  {:component (resource/proj-path resource)})))
       inst-data))

(defn- build-game-object [self basis resource dep-resources user-data]
  (let [instance-msgs (externalize (:instance-data user-data) dep-resources)
        msg {:components instance-msgs}]
    {:resource resource :content (protobuf/map->bytes GameObject$PrototypeDesc msg)}))

(g/defnk produce-build-targets [_node-id resource proto-msg dep-build-targets id-counts]
  (or (let [dup-ids (keep (fn [[id count]] (when (> count 1) id)) id-counts)]
        (when (not-empty dup-ids)
          (g/->error _node-id :build-targets :fatal nil (format "the following ids are not unique: %s" (str/join ", " dup-ids)))))
      [{:node-id _node-id
        :resource (workspace/make-build-resource resource)
        :build-fn build-game-object
        :user-data {:proto-msg proto-msg :instance-data (map :instance-data (flatten dep-build-targets))}
        :deps (flatten dep-build-targets)}]))

(g/defnk produce-scene [_node-id child-scenes]
  {:node-id _node-id
   :aabb (reduce geom/aabb-union (geom/null-aabb) (filter #(not (nil? %)) (map :aabb child-scenes)))
   :children child-scenes})

(defn- attach-component [self-id comp-id ddf-input]
  (concat
    (for [[from to] [[:node-outline :child-outlines]
                     [:_node-id :nodes]
                     [:build-targets :dep-build-targets]
                     [:ddf-message ddf-input]
                     [:component-id :component-id-pairs]
                     [:scene :child-scenes]]]
      (g/connect comp-id from self-id to))
    (for [[from to] [[:base-url :base-url]
                     [:id-counts :id-counts]]]
      (g/connect self-id from comp-id to))))

(defn- attach-ref-component [self-id comp-id]
  (attach-component self-id comp-id :ref-ddf))

(defn- attach-embedded-component [self-id comp-id]
  (attach-component self-id comp-id :embed-ddf))

(g/defnk produce-go-outline [_node-id child-outlines]
  {:node-id _node-id
   :label "Game Object"
   :icon game-object-icon
   :children (vec (sort-by :label util/natural-order child-outlines))
   :child-reqs [{:node-type ReferencedComponent
                 :tx-attach-fn attach-ref-component}
                {:node-type EmbeddedComponent
                 :tx-attach-fn attach-embedded-component}]})

(g/defnode GameObjectNode
  (inherits project/ResourceNode)

  (input ref-ddf g/Any :array)
  (input embed-ddf g/Any :array)
  (input child-scenes g/Any :array)
  (input component-id-pairs g/IdPair :array)
  (input dep-build-targets g/Any :array)
  (input base-url g/Str)

  (output base-url g/Str (gu/passthrough base-url))
  (output node-outline outline/OutlineData :cached produce-go-outline)
  (output proto-msg g/Any :cached produce-proto-msg)
  (output save-data g/Any :cached produce-save-data)
  (output build-targets g/Any :cached produce-build-targets)
  (output scene g/Any :cached produce-scene)
  (output component-ids g/Dict :cached (g/fnk [component-id-pairs] (reduce conj {} component-id-pairs)))
  (output ddf-component-properties g/Any :cached
          (g/fnk [ref-ddf]
                 (reduce (fn [props m]
                           (if (empty? (:properties m))
                             props
                             (conj props (-> m
                                           (select-keys [:id :properties])
                                           (assoc :property-decls (properties/properties->decls (:properties m)))))))
                         [] ref-ddf)))
  (output id-counts g/Any :cached (g/fnk [component-id-pairs]
                                         (reduce (fn [res id]
                                                   (update res id (fn [id] (inc (or id 0)))))
                                                 {} (map first component-id-pairs)))))

(defn- gen-component-id [go-node base]
  (let [ids (map first (g/node-value go-node :component-ids))]
    (loop [postfix 0]
      (let [id (if (= postfix 0) base (str base postfix))]
        (if (empty? (filter #(= id %) ids))
          id
          (recur (inc postfix)))))))

(defn- add-component [self project source-resource id position rotation properties]
  (let [path {:resource source-resource
              :overrides properties}]
    (g/make-nodes (g/node-id->graph-id self)
                  [comp-node [ReferencedComponent :id id :position position :rotation rotation :path path]]
                  (attach-ref-component self comp-node))))

(defn add-component-file [go-id resource]
  (let [project (project/get-project go-id)
        id (gen-component-id go-id (:ext (resource/resource-type resource)))
        op-seq (gensym)
        [comp-node] (g/tx-nodes-added
                      (g/transact
                        (concat
                          (g/operation-label "Add Component")
                          (g/operation-sequence op-seq)
                          (add-component go-id project resource id [0 0 0] [0 0 0 1] {}))))]
    ;; Selection
    (g/transact
      (concat
        (g/operation-sequence op-seq)
        (g/operation-label "Add Component")
        (project/select project [comp-node])))))

(defn add-component-handler [workspace go-id]
  (let [component-exts (map :ext (concat (workspace/get-resource-types workspace :component)
                                         (workspace/get-resource-types workspace :embeddable)))]
    (when-let [resource (first (dialogs/make-resource-dialog workspace {:ext component-exts :title "Select Component File"}))]
      (add-component-file go-id resource))))

(handler/defhandler :add-from-file :global
  (active? [selection] (and (= 1 (count selection)) (g/node-instance? GameObjectNode (first selection))))
  (label [] "Add Component File")
  (run [workspace selection]
       (add-component-handler workspace (first selection))))

(defn- add-embedded-component [self project type data id position rotation select?]
  (let [graph (g/node-id->graph-id self)
        resource (project/make-embedded-resource project type data)]
    (g/make-nodes graph [comp-node [EmbeddedComponent :id id :position position :rotation rotation]]
      (g/connect comp-node :_node-id self :nodes)
      (if select?
        (project/select project [comp-node])
        [])
      (let [tx-data (project/make-resource-node graph project resource true {comp-node [[:resource :source-resource]
                                                                                        [:_properties :source-properties]
                                                                                        [:node-outline :source-outline]
                                                                                        [:save-data :save-data]
                                                                                        [:scene :scene]
                                                                                        [:build-targets :source-build-targets]]
                                                                             self [[:_node-id :nodes]]})]
        (concat
          tx-data
          (if (empty? tx-data)
            []
            (attach-embedded-component self comp-node)))))))

(defn add-embedded-component-handler [user-data]
  (let [self (:_node-id user-data)
        project (project/get-project self)
        component-type (:resource-type user-data)
        template (workspace/template component-type)
        id (gen-component-id self (:ext component-type))]
    (g/transact
     (concat
      (g/operation-label "Add Component")
      (add-embedded-component self project (:ext component-type) template id [0 0 0] [0 0 0 1] true)))))

(defn add-embedded-component-label [user-data]
  (if-not user-data
    "Add Component"
    (let [rt (:resource-type user-data)]
      (or (:label rt) (:ext rt)))))

(defn add-embedded-component-options [self workspace user-data]
  (when (not user-data)
    (->> (remove (comp :non-embeddable :tags) (workspace/get-resource-types workspace :component))
         (map (fn [res-type] {:label (or (:label res-type) (:ext res-type))
                              :icon (:icon res-type)
                              :command :add
                              :user-data {:_node-id self :resource-type res-type}}))
         (sort-by :label)
         vec)))

(handler/defhandler :add :global
  (label [user-data] (add-embedded-component-label user-data))
  (active? [selection] (and (= 1 (count selection)) (g/node-instance? GameObjectNode (first selection))))
  (run [user-data] (add-embedded-component-handler user-data))
  (options [selection user-data]
           (let [self (first selection)
                 workspace (:workspace (g/node-value self :resource))]
             (add-embedded-component-options self workspace user-data))))

(defn load-game-object [project self resource]
  (let [project-graph (g/node-id->graph-id self)
        prototype     (protobuf/read-text GameObject$PrototypeDesc resource)]
    (concat
      (for [component (:components prototype)
            :let [source-resource (workspace/resolve-resource resource (:component component))
                  properties (into {} (map (fn [p] [(properties/user-name->key (:id p)) [(:type p) (properties/str->go-prop (:value p) (:type p))]]) (:properties component)))]]
        (add-component self project source-resource (:id component) (:position component) (:rotation component) properties))
      (for [embedded (:embedded-components prototype)]
        (add-embedded-component self project (:type embedded) (:data embedded) (:id embedded) (:position embedded) (:rotation embedded) false)))))

(defn register-resource-types [workspace]
  (workspace/register-resource-type workspace
                                    :ext "go"
                                    :label "Game Object"
                                    :node-type GameObjectNode
                                    :load-fn load-game-object
                                    :icon game-object-icon
                                    :view-types [:scene :text]
                                    :view-opts {:scene {:grid true}}))
