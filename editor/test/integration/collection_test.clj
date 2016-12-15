(ns integration.collection-test
  (:require [clojure.test :refer :all]
            [dynamo.graph :as g]
            [support.test-support :refer [with-clean-system]]
            [editor.app-view :as app-view]
            [editor.collection :as collection]
            [editor.game-object :as game-object]
            [editor.handler :as handler]
            [editor.defold-project :as project]
            [editor.workspace :as workspace]
            [editor.types :as types]
            [editor.properties :as properties]
            [integration.test-util :as test-util])
  (:import [editor.types Region]
           [java.awt.image BufferedImage]
           [java.io File]
           [javax.imageio ImageIO]
           [javax.vecmath Point3d Matrix4d]))

(deftest hierarchical-outline
  (testing "Hierarchical outline"
           (with-clean-system
             (let [workspace (test-util/setup-workspace! world)
                   project   (test-util/setup-project! workspace)
                   node-id   (test-util/resource-node project "/logic/hierarchy.collection")
                   outline   (g/node-value node-id :node-outline)]
               ; Two game objects under the collection
               (is (= 2 (count (:children outline))))
               ; One component and game object under the game object
               (is (= 2 (count (:children (second (:children outline))))))))))

(deftest hierarchical-scene
  (testing "Hierarchical scene"
           (with-clean-system
             (let [workspace (test-util/setup-workspace! world)
                   project   (test-util/setup-project! workspace)
                   node-id   (test-util/resource-node project "/logic/hierarchy.collection")
                   scene     (g/node-value node-id :scene)]
               ; Two game objects under the collection
               (is (= 2 (count (:children scene))))
               ; One component and game object under the game object
               (is (= 2 (count (:children (first (:children scene))))))))))

(defn- reachable? [source target]
  (contains? (set (g/dependencies (g/now) [source])) target))

(deftest two-instances-are-invalidated
  (with-clean-system
    (let [workspace (test-util/setup-workspace! world)
          project (test-util/setup-project! workspace)
          node-id (test-util/resource-node project "/logic/two_atlas_sprites.collection")
          scene (g/node-value node-id :scene)
          go-id (test-util/resource-node project "/logic/atlas_sprite.go")
          go-scene (g/node-value go-id :scene)
          sprite (get-in go-scene [:children 0 :node-id])]
      (is (reachable? [sprite :scene] [go-id :scene]))
      (is (reachable? [sprite :scene] [(get-in scene [:children 0 :node-id]) :scene]))
      (is (not (reachable? [go-id :scene] [(get-in scene [:children 0 :node-id]) :scene])))
      (is (reachable? [(get-in scene [:children 0 :node-id]) :scene] [node-id :scene])))))

(deftest add-embedded-instance
  (testing "Hierarchical scene"
           (with-clean-system
             (let [[workspace project app-view] (test-util/setup! world)
                   node-id   (test-util/resource-node project "/logic/hierarchy.collection")]
               ; Two game objects under the collection
               (is (= 2 (count (:children (g/node-value node-id :node-outline)))))
               ; Select the collection node
               (app-view/select! app-view [node-id])
               ; Run the add handler
               (test-util/handler-run :add [{:name :workbench :env {:workspace workspace :project project :app-view app-view :selection [node-id]}}] {})
               ; Three game objects under the collection
               (is (= 3 (count (:children (g/node-value node-id :node-outline)))))))))

(deftest empty-go
  (testing "Collection with a single empty game object"
           (with-clean-system
             (let [[workspace project app-view] (test-util/setup! world)
                   node-id   (test-util/resource-node project "/collection/empty_go.collection")
                   zero-aabb (types/->AABB (Point3d. 0 0 0) (Point3d. 0 0 0))
                   outline   (g/node-value node-id :node-outline)
                   scene     (g/node-value node-id :scene)]
               ; Verify outline labels
               (is (= (list "Collection" "go") (map :label (tree-seq :children :children outline))))
               ; Verify AABBs
               (is (every? #(= zero-aabb %) (map :aabb (tree-seq :children :children (g/node-value node-id :scene)))))))))

(deftest unknown-components
  (testing "Load a collection with unknown components"
           (with-clean-system
             (let [[workspace project app-view] (test-util/setup! world)
                   node-id   (test-util/resource-node project "/collection/unknown_components.collection")
                   outline   (g/node-value node-id :node-outline)
                   scene     (g/node-value node-id :scene)
                   zero-aabb (types/->AABB (Point3d. 0 0 0) (Point3d. 0 0 0))]
               ; Verify outline labels
               (is (= (list "Collection" "my_instance" "unknown")
                      (map :label (tree-seq :children :children outline))))
               ; Verify AABBs
               (is (every? #(= zero-aabb %) (map :aabb (tree-seq :children :children (g/node-value node-id :scene)))))))))

(defn- prop [node-id path prop]
  (-> (test-util/outline node-id path)
    :node-id
    (test-util/prop prop)))

(defn- url-prop [node-id path]
  (prop node-id path :url))

(deftest urls
  (testing "Checks URLs at different levels"
           (with-clean-system
             (let [[workspace project app-view] (test-util/setup! world)
                   node-id   (test-util/resource-node project "/collection/sub_sub_props.collection")]
               (is (= "/sub_props" (url-prop node-id [0])))
               (is (= "/sub_props/props" (url-prop node-id [0 0])))
               (is (= "/sub_props/props/props" (url-prop node-id [0 0 0])))
               (is (= "/sub_props/props/props#script" (url-prop node-id [0 0 0 0])))))))

(defn- script-prop [node-id name]
  (let [key (properties/user-name->key name)]
    (test-util/prop node-id key)))

(defn- script-prop! [node-id name v]
  (let [key (properties/user-name->key name)]
    (test-util/prop! (test-util/prop-node-id node-id key) key v)))

(defn- script-prop-clear! [node-id name]
  (let [key (properties/user-name->key name)]
    (test-util/prop-clear! (test-util/prop-node-id node-id key) key)))

(deftest add-script-properties
  (with-clean-system
    (let [[workspace project app-view] (test-util/setup! world)
          coll-id   (test-util/resource-node project "/collection/test.collection")
          go-id     (test-util/resource-node project "/game_object/test.go")
          script-id (test-util/resource-node project "/script/props.script")
          select-fn (fn [node-ids] (app-view/select app-view node-ids))]
      (collection/add-game-object-file coll-id coll-id (test-util/resource workspace "/game_object/test.go") select-fn)
      (is (nil? (test-util/outline coll-id [0 0])))
      (let [inst (first (test-util/selection app-view))]
        (game-object/add-component-file go-id (test-util/resource workspace "/script/props.script") select-fn)
        (let [coll-comp (:node-id (test-util/outline coll-id [0 0]))
              go-comp (:node-id (test-util/outline go-id [0]))]
          (is (= [coll-comp] (g/overrides go-comp)))
          (let [coll-script (ffirst (g/sources-of coll-comp :source-id))
                go-script (ffirst (g/sources-of go-comp :source-id))]
            (is (= [coll-script] (g/overrides go-script)))
            (is (some #{go-script} (g/overrides script-id))))
          (script-prop! go-comp "number" 2.0)
          (is (= 2.0 (script-prop go-comp "number")))
          (is (= 2.0 (script-prop coll-comp "number")))
          (script-prop! coll-comp "number" 3.0)
          (is (= 2.0 (script-prop go-comp "number")))
          (is (= 3.0 (script-prop coll-comp "number")))
          (script-prop-clear! coll-comp "number")
          (is (= 2.0 (script-prop coll-comp "number")))
          (script-prop-clear! go-comp "number")
          (is (= 1.0 (script-prop go-comp "number")))
          (is (= 1.0 (script-prop coll-comp "number")))
          (g/set-property! script-id :code "go.property(\"new_value\", 2.0)\n")
          (is (= 2.0 (script-prop coll-comp "new_value"))))))))

(defn- build-error? [node-id]
  (g/error? (g/node-value node-id :build-targets)))

(deftest validation
  (with-clean-system
    (let [[workspace project app-view] (test-util/setup! world)
          coll-id   (test-util/resource-node project "/collection/props.collection")
          inst-id   (:node-id (test-util/outline coll-id [0]))]
      (testing "game object ref instance"
               (is (not (build-error? coll-id)))
               (is (nil? (test-util/prop-error inst-id :path)))
               (test-util/with-prop [inst-id :path {:resource nil :overrides []}]
                 (is (g/error? (test-util/prop-error inst-id :path)))
                 (is (build-error? coll-id)))
               (let [not-found (workspace/resolve-workspace-resource workspace "/not_found.go")]
                 (test-util/with-prop [inst-id :path {:resource not-found :overrides []}]
                   (is (g/error? (test-util/prop-error inst-id :path)))))
               (test-util/with-prop [inst-id :id "props_embedded"]
                 (is (g/error? (test-util/prop-error inst-id :id)))
                 (is (build-error? coll-id))))
      (testing "game object embedded instance"
               (let [inst-id (:node-id (test-util/outline coll-id [1]))]
                 (is (nil? (test-util/prop-error inst-id :id)))
                 (test-util/with-prop [inst-id :id "props"]
                   (is (g/error? (test-util/prop-error inst-id :id))))))
      (testing "collection ref instance"
               (is (not (build-error? coll-id)))
               (let [res (workspace/resolve-workspace-resource workspace "/collection/test.collection")]
                 (g/transact (collection/add-collection-instance coll-id res "coll" [0 0 0] [0 0 0 1] [1 1 1] []))
                 (let [inst-id (:node-id (test-util/outline coll-id [0]))]
                   (is (nil? (test-util/prop-error inst-id :path)))
                   (test-util/with-prop [inst-id :path {:resource nil :overrides []}]
                     (is (g/error? (test-util/prop-error inst-id :path)))
                     (is (build-error? coll-id)))
                   (let [not-found (workspace/resolve-workspace-resource workspace "/not_found.collection")]
                     (test-util/with-prop [inst-id :path {:resource not-found :overrides []}]
                       (is (g/error? (test-util/prop-error inst-id :path)))))
                   (is (nil? (test-util/prop-error inst-id :id)))
                   (test-util/with-prop [inst-id :id "props"]
                     (is (g/error? (test-util/prop-error inst-id :id)))
                     (is (build-error? coll-id)))))))))
