(ns integration.save-test
  (:require [clojure.test :refer :all]
            [clojure.data :as data]
            [dynamo.graph :as g]
            [support.test-support :refer [with-clean-system]]
            [editor.defold-project :as project]
            [editor.workspace :as workspace]
            [editor.protobuf :as protobuf]
            [editor.resource :as resource]
            [editor.asset-browser :as asset-browser]
            [integration.test-util :as test-util])
  (:import [java.io StringReader File]
           [com.dynamo.gameobject.proto GameObject$PrototypeDesc GameObject$CollectionDesc]
           [com.dynamo.gui.proto Gui$SceneDesc]
           [com.dynamo.model.proto Model$ModelDesc]
           [com.dynamo.particle.proto Particle$ParticleFX]
           [com.dynamo.spine.proto Spine$SpineSceneDesc Spine$SpineModelDesc Spine$SpineModelDesc$BlendMode]
           [com.dynamo.tile.proto Tile$TileSet]))

(def ^:private ext->proto {"go" GameObject$PrototypeDesc
                           "collection" GameObject$CollectionDesc
                           "gui" Gui$SceneDesc
                           "model" Model$ModelDesc
                           "particlefx" Particle$ParticleFX
                           "spinescene" Spine$SpineSceneDesc
                           "spinemodel" Spine$SpineModelDesc
                           "tilesource" Tile$TileSet})

(deftest save-all
  (let [queries ["**/env.cubemap"
                 "**/switcher.atlas"
                 "**/atlas_sprite.collection"
                 "**/props.collection"
                 "**/sub_props.collection"
                 "**/sub_sub_props.collection"
                 "**/go_with_scale3.collection"
                 "**/atlas_sprite.go"
                 "**/atlas.sprite"
                 "**/props.go"
                 "game.project"
                 "**/super_scene.gui"
                 "**/scene.gui"
                 "**/fireworks_big.particlefx"
                 "**/new.collisionobject"
                 "**/three_shapes.collisionobject"
                 "**/tile_map_collision_shape.collisionobject"
                 "**/spineboy.spinescene"
                 "**/spineboy.spinemodel"
                 "**/new.factory"
                 "**/with_prototype.factory"
                 "**/new.sound"
                 "**/tink.sound"
                 "**/new.camera"
                 "**/non_default.camera"
                 "**/new.tilemap"
                 "**/with_layers.tilemap"
                 "**/test.model"
                 "**/empty_mesh.model"]]
    (with-clean-system
      (let [workspace (test-util/setup-workspace! world)
            project   (test-util/setup-project! workspace)
            save-data (group-by :resource (project/save-data project))]
        (doseq [query queries]
          (testing (format "Saving %s" query)
                   (let [[resource _] (first (project/find-resources project query))
                        save (first (get save-data resource))
                        file (slurp resource)
                        pb-class (-> resource resource/resource-type :ext ext->proto)]
                    (if pb-class
                      (let [pb-save (protobuf/read-text pb-class (StringReader. (:content save)))
                            pb-disk (protobuf/read-text pb-class resource)
                            path []
                            [disk save both] (data/diff (get-in pb-disk path) (get-in pb-save path))]
                        (is (nil? disk))
                        (is (nil? save)))
                      (is (= file (:content save)))))))))))

(deftest save-all-literal-equality
  (let [paths [#_"/collection/embedded_instances.collection"
               #_"/game_object/embedded_components.go"
               #_"/editor1/level.tilesource"
               "/editor1/ice.tilesource"]]
    (with-clean-system
      (let [workspace (test-util/setup-workspace! world)
            project   (test-util/setup-project! workspace)
            save-data (group-by :resource (project/save-data project))]
        (doseq [path paths]
          (testing (format "Saving %s" path)
            (let [node-id (test-util/resource-node project path)
                  resource (g/node-value node-id :resource)
                  save (first (get save-data resource))
                  file (slurp resource)
                  pb-class (-> resource resource/resource-type :ext ext->proto)]
              #_(when (and pb-class (not= file (:content save)))
                 (let [pb-save (protobuf/read-text pb-class (StringReader. (:content save)))
                       pb-disk (protobuf/read-text pb-class resource)
                       path []
                       [disk save both] (data/diff (get-in pb-disk path) (get-in pb-save path))]
                   (is (nil? disk))
                   (is (nil? save))))
              #_(is (= file (:content save)))
              (prn (subs file 0 400))
              (prn (subs (:content save) 0 400)))))))))

(save-all-literal-equality)

(defn- setup-scratch
  [ws-graph]
  (let [workspace (test-util/setup-scratch-workspace! ws-graph test-util/project-path)
        project (test-util/setup-project! workspace)]
    [workspace project]))

(deftest save-after-delete []
  (with-clean-system
    (let [[workspace project] (setup-scratch world)
          atlas-id (test-util/resource-node project "/switcher/switcher.atlas")]
      (asset-browser/delete [(g/node-value atlas-id :resource)]
                            (fn [resources]
                              (let [nodes (keep #(project/get-resource-node project %) resources)]
                                (when (not-empty nodes)
                                  (g/transact
                                    (for [n nodes]
                                      (g/delete-node n)))))))
      (is (not (g/error? (project/save-data project)))))))

(deftest save-after-external-delete []
  (with-clean-system
    (let [[workspace project] (setup-scratch world)
          atlas-id (test-util/resource-node project "/switcher/switcher.atlas")
          path (resource/abs-path (g/node-value atlas-id :resource))]
      (doto (File. path)
        (.delete))
      (workspace/resource-sync! workspace)
      (is (not (g/error? (project/save-data project)))))))

(deftest save-after-rename []
  (with-clean-system
    (let [[workspace project] (setup-scratch world)
          atlas-id (test-util/resource-node project "/switcher/switcher.atlas")]
      (asset-browser/rename (g/node-value atlas-id :resource) "/switcher/switcher2.atlas")
      (is (not (g/error? (project/save-data project)))))))
