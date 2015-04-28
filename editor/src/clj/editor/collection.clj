(ns editor.collection
  (:require [clojure.pprint :refer [pprint]] 
            [dynamo.buffers :refer :all]
            [dynamo.camera :refer :all]
            [dynamo.file.protobuf :as protobuf]
            [dynamo.geom :as geom]
            [dynamo.graph :as g]
            [dynamo.types :as t :refer :all]
            [dynamo.ui :refer :all]
            [editor.game-object :as game-object]
            [editor.project :as project]
            [editor.scene :as scene]
            [editor.workspace :as workspace]
            [editor.math :as math])
  (:import [com.dynamo.gameobject.proto GameObject GameObject$CollectionDesc GameObject$CollectionInstanceDesc GameObject$InstanceDesc
            GameObject$EmbeddedInstanceDesc]
           [com.dynamo.graphics.proto Graphics$Cubemap Graphics$TextureImage Graphics$TextureImage$Image Graphics$TextureImage$Type]
           [com.jogamp.opengl.util.awt TextRenderer]
           [dynamo.types Region Animation Camera Image TexturePacking Rect EngineFormatTexture AABB TextureSetAnimationFrame TextureSetAnimation TextureSet]
           [java.awt.image BufferedImage]
           [java.io PushbackReader]
           [javax.media.opengl GL GL2 GLContext GLDrawableFactory]
           [javax.media.opengl.glu GLU]
           [javax.vecmath Matrix4d Point3d Quat4d]))

(def collection-icon "icons/bricks.png")

(defn- gen-embed-ddf [id position rotation scale save-data]
  (-> (doto (GameObject$EmbeddedInstanceDesc/newBuilder)
        (.setId id)
        ; TODO children
        (.setData (:content save-data))
        (.setPosition (protobuf/vecmath->pb (Point3d. position)))
        (.setRotation (protobuf/vecmath->pb rotation))
        ; TODO properties
        (.setScale scale))
    (.build)))

(defn- gen-ref-ddf [id position rotation scale save-data]
  (-> (doto (GameObject$InstanceDesc/newBuilder)
        (.setId id)
        ; TODO children
        (.setPrototype (workspace/proj-path (:resource save-data)))
        (.setPosition (protobuf/vecmath->pb (Point3d. position)))
        (.setRotation (protobuf/vecmath->pb rotation))
        ; TODO properties
        (.setScale scale))
    (.build)))

(defn- replace-id-deep [scene new-id]
  (let [new-scene (assoc scene :id new-id)]
    (if (:children scene)
      (assoc new-scene :children (map #(replace-id-deep % new-id) (:children scene)))
      new-scene)))

(g/defnode GameObjectInstanceNode
  (inherits scene/SceneNode)

  (property id t/Str)
  (property path t/Str)
  (property embedded t/Bool (visible false))

  (input source t/Any)
  (input properties t/Any)
  (input outline t/Any)
  (input save-data t/Any)
  (input scene t/Any)

  (output outline t/Any (g/fnk [self id outline] (merge outline {:self self :label id :icon game-object/game-object-icon})))
  (output ddf-message t/Any :cached (g/fnk [id path embedded position rotation scale save-data]
                                           (if embedded (gen-embed-ddf id position rotation scale save-data) (gen-ref-ddf id position rotation scale save-data))))
  (output scene t/Any :cached (g/fnk [self transform scene]
                                     (assoc (replace-id-deep scene (g/node-id self))
                                           :transform transform
                                           :aabb (geom/aabb-transform (:aabb scene) transform)))))

(g/defnk produce-save-data [resource name ref-inst-ddf embed-inst-ddf ref-coll-ddf]
  {:resource resource
   :content (protobuf/pb->str (.build (doto (GameObject$CollectionDesc/newBuilder)
                                        (.setName name)
                                        (.addAllInstances ref-inst-ddf)
                                        (.addAllEmbeddedInstances embed-inst-ddf)
                                        (.addAllCollectionInstances ref-coll-ddf))))})

(g/defnode CollectionNode
  (inherits project/ResourceNode)

  (property name t/Str)

  (input outline [t/Any])
  (input ref-inst-ddf [t/Any])
  (input embed-inst-ddf [t/Any])
  (input ref-coll-ddf [t/Any])
  (input child-scenes [t/Any])

  (output outline t/Any (g/fnk [self outline] {:self self :label "Collection" :icon collection-icon :children outline}))
  (output save-data t/Any :cached produce-save-data)
  (output scene t/Any :cached (g/fnk [self child-scenes]
                                     {:id (g/node-id self)
                                      :children child-scenes
                                      :aabb (reduce geom/aabb-union (geom/null-aabb) (filter #(not (nil? %)) (map :aabb child-scenes)))})))

(g/defnode CollectionInstanceNode
  (inherits scene/SceneNode)

  (property id t/Str)
  (property path t/Str)

  (input source t/Any)
  (input outline t/Any)
  (input save-data t/Any)
  (input scene t/Any)

  (output outline t/Any (g/fnk [self id outline] (merge outline {:self self :label id :icon collection-icon})))
  (output ddf-message t/Any :cached (g/fnk [id path position rotation scale]
                                           (.build (doto (GameObject$CollectionInstanceDesc/newBuilder)
                                                     (.setId id)
                                                     (.setCollection path)
                                                     (.setPosition (protobuf/vecmath->pb (Point3d. position)))
                                                     (.setRotation (protobuf/vecmath->pb rotation))
                                                     (.setScale scale)))))
  (output scene t/Any :cached (g/fnk [self transform scene]
                                     (assoc scene
                                           :id (g/node-id self)
                                           :transform transform
                                           :aabb (geom/aabb-transform (:aabb scene) transform)))))

(defn load-collection [project self input]
  (let [collection (protobuf/pb->map (protobuf/read-text GameObject$CollectionDesc input))
        project-graph (g/node->graph-id project)]
    (concat
      (g/set-property self :name (:name collection))
      (for [game-object (:instances collection)]
        (g/make-nodes project-graph
                      [go-node [GameObjectInstanceNode :id (:id game-object) :path (:prototype game-object)
                                :position (t/Point3d->Vec3 (:position game-object)) :rotation (math/quat->euler (:rotation game-object)) :scale (:scale game-object)]]
                      (g/connect go-node :outline self :outline)
                      (if-let [source-node (project/resolve-resource-node self (:prototype game-object))]
                        [(g/connect go-node :ddf-message self :ref-inst-ddf)
                         (g/connect go-node :scene self :child-scenes)
                         (g/connect source-node :self go-node :source)
                         (g/connect source-node :outline go-node :outline)
                         (g/connect source-node :save-data go-node :save-data)
                         (g/connect source-node :scene go-node :scene)]
                        [])))
      (for [embedded (:embedded-instances collection)]
        (let [resource (project/make-embedded-resource project "go" (:data embedded))]
          (if-let [resource-type (and resource (workspace/resource-type resource))]
            (g/make-nodes project-graph
                          [go-node [GameObjectInstanceNode :id (:id embedded) :embedded true
                                    :position (t/Point3d->Vec3 (:position embedded)) :rotation (math/quat->euler (:rotation embedded)) :scale (:scale embedded)]
                           source-node [(:node-type resource-type) :resource resource :parent project :resource-type resource-type]]
                          (g/connect source-node :self       go-node :source)
                          (g/connect source-node :outline    go-node :outline)
                          (g/connect source-node :save-data  go-node :save-data)
                          (g/connect source-node :scene  go-node :scene)
                          (g/connect go-node   :outline      self    :outline)
                          (g/connect go-node   :ddf-message  self    :embed-inst-ddf)
                          (g/connect go-node   :scene self    :child-scenes))
            (g/make-nodes project-graph
                          [go-node [GameObjectInstanceNode :id (:id embedded) :embedded true]]
                          (g/connect go-node :outline self :outline)))))
      (for [coll-instance (:collection-instances collection)]
        (g/make-nodes project-graph
                      [coll-node [CollectionInstanceNode :id (:id coll-instance) :path (:collection coll-instance)
                                  :position (t/Point3d->Vec3 (:position coll-instance)) :rotation (math/quat->euler (:rotation coll-instance)) :scale (:scale coll-instance)]]
                      (g/connect coll-node :outline self :outline)
                      (if-let [source-node (project/resolve-resource-node self (:collection coll-instance))]
                        [(g/connect coll-node   :ddf-message  self :ref-coll-ddf)
                         (g/connect coll-node   :scene  self :child-scenes)
                         (g/connect source-node :self         coll-node :source)
                         (g/connect source-node :outline      coll-node :outline)
                         (g/connect source-node :save-data    coll-node :save-data)
                         (g/connect source-node :scene    coll-node :scene)]
                        []))))))

(defn register-resource-types [workspace]
  (workspace/register-resource-type workspace
                                    :ext "collection"
                                    :node-type CollectionNode
                                    :load-fn load-collection
                                    :icon collection-icon
                                    :view-types [:scene]
                                    :view-opts {:scene {:grid true}}))
