(ns integration.font-test
  (:require [clojure.test :refer :all]
            [clojure.string :as s]
            [dynamo.graph :as g]
            [support.test-support :refer [with-clean-system]]
            [integration.test-util :as test-util]
            [editor.workspace :as workspace]
            [editor.font :as font]
            [editor.defold-project :as project]))

(defn- prop [node-id label]
  (get-in (g/node-value node-id :_properties) [:properties label :value]))

(defn- prop! [node-id label val]
  (g/transact (g/set-property node-id label val)))

(deftest load-material-render-data
  (with-clean-system
    (let [workspace (test-util/setup-workspace! world)
          project   (test-util/setup-project! workspace)
          node-id   (test-util/resource-node project "/fonts/score.font")
          scene (g/node-value node-id :scene)]
      (is (not (nil? scene))))))

(deftest text-measure
  (with-clean-system
    (let [workspace (test-util/setup-workspace! world)
          project   (test-util/setup-project! workspace)
          node-id   (test-util/resource-node project "/fonts/score.font")
          font-map (g/node-value node-id :font-map)]
      (let [[w h] (font/measure font-map "test")]
        (is (> w 0))
        (is (> h 0))
        (let [[w' h'] (font/measure font-map "test\ntest")]
          (is (= w' w))
          (is (> h' h))
          (let [[w'' h''] (font/measure font-map "test test test" true w 0 1)]
            (is (= w'' w'))
            (is (> h'' h')))
          (let [[w'' h''] (font/measure font-map "test test test" true w 0.1 1.1)]
            (is (> w'' w'))
            (is (> h'' h'))))))))

(deftest preview-text
  (with-clean-system
    (let [workspace (test-util/setup-workspace! world)
          project   (test-util/setup-project! workspace)
          node-id   (test-util/resource-node project "/fonts/score.font")
          font-map (g/node-value node-id :font-map)
          pre-text (g/node-value node-id :preview-text)
          no-break (s/replace pre-text " " "")
          [w h] (font/measure font-map pre-text true (:cache-width font-map) 0 1)
          [ew eh] (font/measure font-map no-break true (:cache-width font-map) 0 1)]
      (is (.contains pre-text " "))
      (is (not (.contains no-break " ")))
      (is (< w ew))
      (is (< eh h)))))

(deftest validation
  (with-clean-system
    (let [workspace (test-util/setup-workspace! world)
          project   (test-util/setup-project! workspace)
          node-id   (test-util/resource-node project "/fonts/score.font")]
      (is (nil? (test-util/prop-error node-id :font)))
      (is (nil? (test-util/prop-error node-id :material)))
      (test-util/with-prop [node-id :font nil]
        (is (g/error-fatal? (test-util/prop-error node-id :font))))
      (test-util/with-prop [node-id :font (workspace/resolve-workspace-resource workspace "/not_found.ttf")]
        (is (g/error-fatal? (test-util/prop-error node-id :font))))
      (test-util/with-prop [node-id :material nil]
        (is (g/error-fatal? (test-util/prop-error node-id :material))))
      (test-util/with-prop [node-id :material (workspace/resolve-workspace-resource workspace "/not_found.material")]
        (is (g/error-fatal? (test-util/prop-error node-id :material))))
      (doseq [p [:size :alpha :outline-alpha :outline-width :shadow-alpha :shadow-blur :cache-width :cache-height]]
        (test-util/with-prop [node-id p -1]
          (is (g/error-fatal? (test-util/prop-error node-id p))))))))
