;; Copyright 2020-2022 The Defold Foundation
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

(ns integration.label-test
  (:require [clojure.string :as string]
            [clojure.test :refer :all]
            [clojure.walk :as walk]
            [dynamo.graph :as g]
            [editor.defold-project :as project]
            [editor.game-object :as game-object]
            [editor.gl.pass :as pass]
            [editor.label :as label]
            [editor.math :as math]
            [editor.scene :as scene]
            [editor.workspace :as workspace]
            [integration.test-util :as test-util])
  (:import [editor.types Region]))

(deftest label-validation-test
  (test-util/with-loaded-project
    (let [node-id (project/get-resource-node project "/label/test.label")]
      (doseq [[prop cases] [[:font {"no font" ""
                                    "unknown font" "/fonts/unknown.font"}]
                            [:material {"no material" ""
                                        "unknown material" "/materials/unknown.material"}]]
              [case path] cases]
        (testing case
          (test-util/with-prop [node-id prop (workspace/resolve-workspace-resource workspace path)]
                               (is (g/error? (test-util/prop-error node-id prop)))))))))

(deftest label-aabb-test
  (test-util/with-loaded-project
    (let [node-id (project/get-resource-node project "/label/test.label")]
      (let [aabb (g/node-value node-id :aabb)
            [x y z] (mapv - (math/vecmath->clj (:max aabb)) (math/vecmath->clj (:min aabb)))]
        (is (< 0.0 x))
        (is (< 0.0 y))
        (is (= 0.0 z))))))

(deftest label-scene-test
  (test-util/with-loaded-project
    (let [node-id (project/get-resource-node project "/label/test.label")]
      (let [scene (g/node-value node-id :scene)
            aabb (g/node-value node-id :aabb)]
        (is (= aabb (:aabb scene)))
        (is (= node-id (:node-id scene)))
        (is (= node-id (some-> scene :renderable :select-batch-key)))
        (is (= :blend-mode-alpha (some-> scene :renderable :batch-key :blend-mode)))
        (is (= "Label" (some-> scene :renderable :user-data :text-data :text-layout :lines first)))
        (is (string/includes? (some-> scene :renderable :user-data :material-shader :verts) "gl_Position"))
        (is (string/includes? (some-> scene :renderable :user-data :material-shader :frags) "gl_FragColor"))))))

(defn- get-render-calls-by-pass
  [scene camera selection key-fn]
  (let [scene-render-data (scene/produce-scene-render-data {:scene scene :selection selection :hidden-renderable-args [] :hidden-node-outline-key-paths [] :camera camera})
        render-data scene-render-data
        renderables (:renderables render-data)
        old-render-lines label/render-lines
        old-render-tris label/render-tris]
    (into {}
          (keep (fn [pass]
                  (let [render-args (scene/pass-render-args (Region. 0 100 0 100) camera pass)
                        calls (test-util/with-logged-calls [label/render-lines label/render-tris]
                                (let [patched-renderables (walk/postwalk-replace {old-render-lines label/render-lines
                                                                                  old-render-tris label/render-tris}
                                                                                 renderables)]
                                  (scene/batch-render nil render-args (get patched-renderables pass) key-fn)))]
                    (when (seq calls)
                      [pass calls]))))
          pass/render-passes)))

(defn- map-render-calls
  [transform-calls-fn render-calls-by-pass]
  (into {}
        (map (fn [[pass calls-by-fn]]
               [pass (into {}
                           (map (fn [[fn calls]]
                                  [fn (transform-calls-fn calls)]))
                           calls-by-fn)]))
        render-calls-by-pass))

(deftest label-batch-render-test
  (test-util/with-loaded-project
    (let [make-restore-point! #(test-util/make-graph-reverter (project/graph project))
          add-label-component! (partial test-util/add-embedded-component! app-view (workspace/get-resource-type workspace "label"))
          [go view] (test-util/open-scene-view! project app-view "/game_object/test.go" 128 128)
          render-calls (fn [selection key-fn]
                         (get-render-calls-by-pass
                           (g/node-value go :scene)
                           (g/node-value view :camera)
                           selection
                           key-fn))
          render-call-counts (comp (partial map-render-calls count)
                                   render-calls)]

      (testing "Single label"
        (with-open [_ (make-restore-point!)]
          (add-label-component! go)
          (is (= {pass/outline {label/render-lines 1}
                  pass/transparent {label/render-tris 1}}
                 (render-call-counts #{} :batch-key)))
          (is (= {pass/outline {label/render-lines 1}
                  pass/transparent {label/render-tris 1}}
                 (render-call-counts #{} :select-batch-key)))))

      (testing "Identical labels"
        (with-open [_ (make-restore-point!)]
          (add-label-component! go)
          (add-label-component! go)
          (add-label-component! go)
          (add-label-component! go)
          (is (= {pass/outline {label/render-lines 1}
                  pass/transparent {label/render-tris 1}}
                 (render-call-counts #{} :batch-key)))
          (is (= {pass/outline {label/render-lines 4}
                  pass/transparent {label/render-tris 4}}
                 (render-call-counts #{} :select-batch-key)))))

      (testing "Blend mode differs"
        (with-open [_ (make-restore-point!)]
          (test-util/prop! (add-label-component! go) :blend-mode :blend-mode-add)
          (test-util/prop! (add-label-component! go) :blend-mode :blend-mode-add)
          (test-util/prop! (add-label-component! go) :blend-mode :blend-mode-mult)
          (test-util/prop! (add-label-component! go) :blend-mode :blend-mode-mult)
          (is (= {pass/outline {label/render-lines 1}
                  pass/transparent {label/render-tris 2}}
                 (render-call-counts #{} :batch-key)))))

      (testing "Font differs"
        (with-open [_ (make-restore-point!)]
          (test-util/prop! (add-label-component! go) :font (workspace/find-resource workspace "/fonts/active_menu_item.font"))
          (test-util/prop! (add-label-component! go) :font (workspace/find-resource workspace "/fonts/active_menu_item.font"))
          (test-util/prop! (add-label-component! go) :font (workspace/find-resource workspace "/fonts/big_score.font"))
          (test-util/prop! (add-label-component! go) :font (workspace/find-resource workspace "/fonts/big_score.font"))
          (is (= {pass/outline {label/render-lines 1}
                  pass/transparent {label/render-tris 2}}
                 (render-call-counts #{} :batch-key)))))

      (testing "Material differs"
        (with-open [_ (make-restore-point!)]
          (test-util/prop! (add-label-component! go) :material (workspace/find-resource workspace "/fonts/active_menu_item.material"))
          (test-util/prop! (add-label-component! go) :material (workspace/find-resource workspace "/fonts/active_menu_item.material"))
          (test-util/prop! (add-label-component! go) :material (workspace/find-resource workspace "/fonts/big_score_font.material"))
          (test-util/prop! (add-label-component! go) :material (workspace/find-resource workspace "/fonts/big_score_font.material"))
          (is (= {pass/outline {label/render-lines 1}
                  pass/transparent {label/render-tris 2}}
                 (render-call-counts #{} :batch-key))))))))

(deftest label-scene-test
  (test-util/with-loaded-project
    (let [node-id (project/get-resource-node project "/label/test.label")]
      (test-util/test-uses-assigned-material workspace project node-id
                                             :material
                                             [:renderable :user-data :material-shader]
                                             [:renderable :user-data :gpu-texture]))))

(deftest label-migration-test
  (test-util/with-loaded-project
    (letfn [(verify-component [component-node-id]
              (and (is (g/node-instance? game-object/ReferencedComponent component-node-id))
                   (is (= [2.0 3.0 4.0] (g/node-value component-node-id :scale)))))

            (verify-embedded-component [embedded-component-node-id]
              (and (is (g/node-instance? game-object/EmbeddedComponent embedded-component-node-id))
                   (is (= [3.0 4.0 5.0] (g/node-value embedded-component-node-id :scale)))))]

      (testing "Scale value was moved from LabelDesc to ComponentDesc in game object."
        (let [label-migration-game-object (project/get-resource-node project "/label/label_migration.go")
              embedded-label-component (:node-id (test-util/outline label-migration-game-object [0]))
              label-component (:node-id (test-util/outline label-migration-game-object [1]))]
          (verify-embedded-component embedded-label-component)
          (verify-component label-component)))

      (testing "Scale value was moved from LabelDesc to ComponentDesc in game object embedded inside collection."
        (let [label-migration-collection (project/get-resource-node project "/label/label_migration.collection")
              embedded-label-component (:node-id (test-util/outline label-migration-collection [0 1]))
              label-component (:node-id (test-util/outline label-migration-collection [0 2]))]
          (verify-component label-component)
          (verify-embedded-component embedded-label-component)))

      (testing "Scale value was moved from LabelDesc to ComponentDesc in child game object embedded inside collection."
        (let [label-migration-collection (project/get-resource-node project "/label/label_migration.collection")
              embedded-label-component (:node-id (test-util/outline label-migration-collection [0 0 0]))
              label-component (:node-id (test-util/outline label-migration-collection [0 0 1]))]
          (verify-component label-component)
          (verify-embedded-component embedded-label-component))))))
