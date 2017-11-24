(ns integration.script-properties-test
  (:require [clojure.test :refer :all]
            [clojure.string :as string]
            [dynamo.graph :as g]
            [integration.test-util :as tu]
            [editor.workspace :as workspace]
            [editor.defold-project :as project]
            [editor.properties :as properties]))

(defn- component [go-id id]
  (let [comps (->> (g/node-value go-id :node-outline)
                :children
                (map (fn [v] [(-> (:label v)
                                (string/split #" ")
                                first) (:node-id v)]))
                (into {}))]
    (comps id)))

(defmacro with-source [script-id source & body]
  `(let [orig# (tu/code-editor-source ~script-id)]
     (tu/code-editor-source! ~script-id ~source)
     ~@body
     (tu/code-editor-source! ~script-id orig#)))

(defn- prop [node-id prop-name]
  (let [key (properties/user-name->key prop-name)]
    (tu/prop (tu/prop-node-id node-id key) key)))

(defn- read-only? [node-id prop-name]
  (tu/prop-read-only? node-id (properties/user-name->key prop-name)))

(defn- overridden? [node-id prop-name]
  (let [key (properties/user-name->key prop-name)]
    (tu/prop-overridden? (tu/prop-node-id node-id key) key)))

(defn- prop! [node-id prop-name value]
  (let [key (properties/user-name->key prop-name)]
    (tu/prop! (tu/prop-node-id node-id key) key value)))

(defn- clear! [node-id prop-name]
  (let [key (properties/user-name->key prop-name)]
    (tu/prop-clear! (tu/prop-node-id node-id key) key)))

(defn- perform-script-properties-source-test! []
  (tu/with-loaded-project
    (let [script-id (tu/resource-node project "/script/props.script")]
      (testing "reading values"
               (is (= 1.0 (prop script-id "number")))
               (is (read-only? script-id "number")))
      (testing "broken prop defs" ;; string vals are not supported
               (with-source script-id "go.property(\"number\", \"my_string\")\n"
                 (is (nil? (prop script-id "number"))))))))

(deftest script-properties-source
  (with-bindings {#'tu/use-new-code-editor? false}
    (perform-script-properties-source-test!))
  (with-bindings {#'tu/use-new-code-editor? true}
    (perform-script-properties-source-test!)))

(defn- perform-script-properties-component-test! []
  (tu/with-loaded-project
    (let [go-id (tu/resource-node project "/game_object/props.go")
          script-c (component go-id "script")]
      (is (= 2.0 (prop script-c "number")))
      (is (overridden? script-c "number"))
      (prop! script-c "number" 3.0)
      (is (= 3.0 (prop script-c "number")))
      (is (overridden? script-c "number"))
      (clear! script-c "number")
      (is (= 1.0 (prop script-c "number")))
      (is (not (overridden? script-c "number"))))))

(deftest script-properties-component
  (with-bindings {#'tu/use-new-code-editor? false}
    (perform-script-properties-component-test!))
  (with-bindings {#'tu/use-new-code-editor? true}
    (perform-script-properties-component-test!)))

(defn- perform-script-properties-broken-component-test! []
  (tu/with-loaded-project
    (let [go-id (tu/resource-node project "/game_object/type_faulty_props.go")
          script-c (component go-id "script")]
      (is (not (overridden? script-c "number")))
      (is (= 1.0 (prop script-c "number")))
      (prop! script-c "number" 3.0)
      (is (overridden? script-c "number"))
      (is (= 3.0 (prop script-c "number")))
      (clear! script-c "number")
      (is (not (overridden? script-c "number")))
      (is (= 1.0 (prop script-c "number"))))))

(deftest script-properties-broken-component
  (with-bindings {#'tu/use-new-code-editor? false}
    (perform-script-properties-broken-component-test!))
  (with-bindings {#'tu/use-new-code-editor? true}
    (perform-script-properties-broken-component-test!)))

(defn- perform-script-properties-collection-test! []
  (tu/with-loaded-project
    (doseq [[resource paths val] [["/collection/props.collection" [[0 0] [1 0]] 3.0]
                                  ["/collection/sub_props.collection" [[0 0 0]] 4.0]
                                  ["/collection/sub_sub_props.collection" [[0 0 0 0]] 5.0]]
            path paths]
      (let [coll-id (tu/resource-node project resource)]
        (let [outline (tu/outline coll-id path)
              script-c (:node-id outline)]
          (is (:outline-overridden? outline))
          (is (= val (prop script-c "number")))
          (is (overridden? script-c "number")))))))

(deftest script-properties-collection
  (with-bindings {#'tu/use-new-code-editor? false}
    (perform-script-properties-collection-test!))
  (with-bindings {#'tu/use-new-code-editor? true}
    (perform-script-properties-collection-test!)))

(defn- perform-script-properties-broken-collection-test! []
  (tu/with-loaded-project
    ;; [0 0] instance script, bad collection level override, fallback to instance override = 2.0
    ;; [1 0] embedded instance script, bad collection level override, fallback to script setting = 1.0
    ;; [2 0] type faulty instance script, bad collection level override, fallback to script setting = 1.0
    ;; [3 0] type faulty instance script, proper collection-level override = 3.0
    (doseq [[resource path-vals] [["/collection/type_faulty_props.collection" [[[0 0] false 2.0] [[1 0] false 1.0] [[2 0] false 1.0]] [[3 0] true 3.0]]]
            [path overriden val] path-vals]
      (let [coll-id (tu/resource-node project resource)]
        (let [outline (tu/outline coll-id path)
              script-c (:node-id outline)]
          (is (= overriden (:outline-overridden? outline)))
          (is (= val (prop script-c "number")))
          (is (= overriden (overridden? script-c "number"))))))))

(deftest script-properties-broken-collection
  (with-bindings {#'tu/use-new-code-editor? false}
    (perform-script-properties-broken-collection-test!))
  (with-bindings {#'tu/use-new-code-editor? true}
    (perform-script-properties-broken-collection-test!)))
