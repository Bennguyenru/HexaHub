(ns integration.asset-browser-test
  (:require [clojure.test :refer :all]
            [clojure.pprint :refer [pprint]]
            [dynamo.graph :as g]
            [dynamo.node :as n]
            [dynamo.system :as ds]
            [dynamo.types :as t]
            [dynamo.system.test-support :refer [with-clean-system]]
            [editor.workspace :as workspace]
            [editor.project :as project]
            [editor.collection :as collection]
            [editor.game-object :as game-object]
            [editor.cubemap :as cubemap]
            [editor.atlas :as atlas]
            [editor.image :as image]
            [editor.scene :as scene]
            [editor.platformer :as platformer]
            [editor.switcher :as switcher]
            [editor.sprite :as sprite]
            [internal.clojure :as clojure])
  (:import [java.io File]
           [javax.imageio ImageIO]))

(def not-nil? (complement nil?))

(def project-path "resources/test_project")

(defn- load-test-workspace [graph]
  (first
    (ds/tx-nodes-added
      (ds/transact
        (g/make-nodes
          graph
          [workspace [workspace/Workspace :root project-path]]
          (collection/register-resource-types workspace)
          (game-object/register-resource-types workspace)
          (cubemap/register-resource-types workspace)
          (image/register-resource-types workspace)
          (atlas/register-resource-types workspace)
          (platformer/register-resource-types workspace)
          (switcher/register-resource-types workspace)
          (sprite/register-resource-types workspace))))))

(deftest workspace-tree
  (testing "The file system can be retrieved as a tree"
    (with-clean-system
      (let [workspace     (load-test-workspace world)
            history-count 0  ; TODO retrieve actual undo-history count
            root          (g/node-value workspace :resource-tree)]
        (is (workspace/url root) "file:/")))))

(deftest asset-browser-search
  (testing "Searching for a resource produces a hit and renders a preview"
    (with-clean-system
      (let [ws-graph world
            proj-graph (ds/attach-graph-with-history (g/make-graph :volatility 1))
            view-graph (ds/attach-graph (g/make-graph :volatility 100))
            workspace (load-test-workspace ws-graph)
            project (first
                      (ds/tx-nodes-added
                        (ds/transact
                          (g/make-nodes
                            proj-graph
                            [project [project/Project :workspace workspace]]
                            (g/connect workspace :resource-list project :resources)
                            (g/connect workspace :resource-types project :resource-types)))))
            resources (g/node-value workspace :resource-list)
            project   (project/load-project project resources)
            #_queries #_["**/atlas_sprite.collection"]
            queries ["**/atlas.atlas" "**/env.cubemap" "**/level1.platformer" "**/level01.switcher" "**/atlas.sprite" "**/atlas_sprite.go" "**/atlas_sprite.collection"]]
        (doseq [query queries
                :let [results (project/find-resources project query)]]
          (is (= 1 (count results)))
          (let [resource-node (get (first results) 1)
                resource-type (project/get-resource-type resource-node)
                setup-view-fn (:setup-view-fn resource-type)
                setup-rendering-fn (:setup-rendering-fn resource-type)]
            (let [view (scene/make-preview-view view-graph 128 128)]
              (ds/transact (setup-view-fn resource-node view))
              (ds/transact (setup-rendering-fn resource-node (g/refresh view)))
              (let [view (g/refresh view)
                    image (g/node-value view :frame)]
                (is (not (nil? image)))
                #_(let [file (File. "test.png")]
                   (ImageIO/write image "png" file))))))))))

#_(asset-browser-search)
