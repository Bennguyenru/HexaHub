(ns integration.model-test
  (:require [clojure.test :refer :all]
            [dynamo.graph :as g]
            [integration.test-util :as test-util]
            [editor.workspace :as workspace]
            [editor.defold-project :as project]
            [editor.geom :as geom]
            [editor.spine :as spine]
            [editor.types :as types]
            [editor.properties :as properties])
  (:import [javax.vecmath Point3d]))

(deftest aabb
  (test-util/with-loaded-project
    (let [node-id (test-util/resource-node project "/model/test.model")
          aabb (g/node-value node-id :aabb)
          min ^Point3d (types/min-p aabb)
          max ^Point3d (types/max-p aabb)]
      (is (< 10 (.distance max min))))))

(deftest textures
  (test-util/with-loaded-project
    (let [node-id (test-util/resource-node project "/model/test.model")]
      (let [original-texture (first (test-util/prop node-id :textures))
            t [original-texture nil nil]]
        (test-util/prop! node-id :textures t)
        (is (= t (test-util/prop node-id :textures)))
        (let [p (-> [(g/node-value node-id :_properties)]
                  (properties/coalesce)
                  :properties
                  :texture2)]
          (properties/set-values! p [original-texture]))))))

(deftest model-validation
  (test-util/with-loaded-project
    (let [node-id (test-util/resource-node project "/model/test.model")]
      (is (nil? (test-util/prop-error node-id :mesh)))
      (doseq [v [nil (workspace/resolve-workspace-resource workspace "/not_found.dae")]]
        (test-util/with-prop [node-id :mesh v]
          (is (g/error? (test-util/prop-error node-id :mesh)))))
      (is (nil? (test-util/prop-error node-id :material)))
      (doseq [v [nil (workspace/resolve-workspace-resource workspace "/not_found.material")]]
        (test-util/with-prop [node-id :material v]
          (is (g/error? (test-util/prop-error node-id :material))))))))
