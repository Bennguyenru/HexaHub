(ns internal.node-test
  (:require [clojure.test :refer :all]
            [schema.core :as s]
            [plumbing.core :refer [defnk fnk]]
            [dynamo.types :as t :refer [as-schema]]
            [dynamo.node :as n :refer [defnode]]
            [dynamo.project :as p]
            [dynamo.system :as ds]
            [dynamo.system.test-support :refer [with-clean-world]]
            [internal.node :as in :refer [deep-merge]]
            [internal.system :as is]
            [internal.query :as iq]
            [internal.transaction :as it]))

(def a-schema (as-schema {:names [java.lang.String]}))

(def m1 {:cached #{:derived}   :inputs {:simple-number 18 :another [:a] :nested ['v1] :setvalued #{:x}}})
(def m2 {:cached #{:expensive} :inputs {:simple-number 99 :another [:a] :nested ['v3] :setvalued #{:z}}})
(def expected-merge {:cached #{:expensive :derived},
                     :inputs {:simple-number 99
                              :another [:a :a],
                              :nested ['v1 'v3]
                              :setvalued #{:x :z}}})

(def s1           (assoc m1 :declared-type a-schema))
(def s2           (assoc m2 :declared-type a-schema))
(def schema-merge (assoc expected-merge :declared-type a-schema))

(deftest merging-nested-maps
  (is (= expected-merge (deep-merge m1 m2))))

(deftest merging-maps-with-schemas
  (is (= schema-merge (deep-merge s1 s2)))
  (is (:schema (meta (get (deep-merge s1 s2) :declared-type)))))

(defnode SimpleTestNode
  (property foo (t/string :default "FOO!")))

(defnode NodeWithProtocols
  (property foo (t/string :default "the user"))

  clojure.lang.IDeref
  (deref [this] (:foo this))

  t/N2Extent
  (width [this] 800)
  (height [this] 600))

(defnode NodeWithEvents
  (on :mousedown
    (let [nn (ds/add (make-node-with-protocols :_id -1))]
      (ds/set-property {:_id -1} :foo "newly created")
      (ds/set-property self :message-processed true))))

(deftest node-definition
  (testing "properties"
    (is (= [:foo] (-> (make-simple-test-node) :descriptor :properties keys)))
    (is (contains? (make-simple-test-node) :foo)))
  (testing "property defaults"
    (is (= "FOO!" (-> (make-simple-test-node) :foo))))
  (testing "extending nodes with protocols"
    (is (instance? clojure.lang.IDeref (make-node-with-protocols)))
    (is (= "the user" @(make-node-with-protocols)))
    (is (satisfies? t/N2Extent (make-node-with-protocols)))
    (is (= 800 (t/width (make-node-with-protocols))))))

(deftest event-delivery
  (with-clean-world
    (let [evented (ds/transactional (ds/add (make-node-with-events :_id -1)))]
      (is (= :ok (t/process-one-event evented {:type :mousedown})))
      (is (:message-processed (iq/node-by-id world-ref (:_id evented)))))))

(deftest nodes-share-descriptors
  (is (identical? (-> (make-simple-test-node) :descriptor) (-> (make-simple-test-node) :descriptor))))

(defprotocol AProtocol
  (complainer [this]))

(definterface IInterface
  (allGood []))

(defnode MyNode
  AProtocol
  (complainer [this] :owie)
  IInterface
  (allGood [this] :ok))

(deftest node-respects-namespaces
  (testing "node can implement protocols not known/visible to internal.node"
    (is (= :owie (complainer (make-my-node)))))
  (testing "node can implement interface not known/visible to internal.node"
    (is (= :ok (.allGood (make-my-node))))))

(defnode EmptyNode)

(deftest node-intrinsics
  (let [node (make-empty-node)]
    (is (identical? node (t/get-value node nil :self)))))

(defn ^:dynamic production-fn [this g] :defn)
(def ^:dynamic production-val :def)
(defnk production-fn-this [this] this)
(defnk production-fn-g [g] g)
(defnk production-fn-world [world] world)
(defnk production-fn-project [project] project)

(defnode ProductionFunctionsNode
  (output inline-fn      s/Keyword [this g] :fn)
  (output defn-as-symbol s/Keyword production-fn)
  (output def-as-symbol  s/Keyword production-val)
  (output inline-fn-this s/Any     [this g] this)
  (output inline-fn-g    s/Any     [this g] g)
  (output defnk-this     s/Any     production-fn-this)
  (output defnk-g        s/Any     production-fn-g)
  (output defnk-world    s/Any     production-fn-world)
  (output defnk-project  s/Any     production-fn-project))

(deftest production-functions
  (with-clean-world
    (let [project (ds/transactional (ds/add (p/make-project)))
          node    (ds/transactional (ds/in project (ds/add (make-production-functions-node))))
          graph   (is/graph world-ref)]
      (is (= :fn   (t/get-value node graph :inline-fn)))
      (is (= :defn (t/get-value node graph :defn-as-symbol)))
      (is (= :def  (t/get-value node graph :def-as-symbol)))
      (binding [production-fn :dynamic-binding-val]
        (is (= :dynamic-binding-val (t/get-value node graph :defn-as-symbol))))
      (binding [production-val (constantly :dynamic-binding-fn)]
        (is (= :dynamic-binding-fn (t/get-value node graph :def-as-symbol))))
      (is (identical? node (t/get-value node graph :inline-fn-this)))
      (is (identical? node (t/get-value node graph :defnk-this)))
      (is (identical? graph (t/get-value node graph :inline-fn-g)))
      (is (identical? graph (t/get-value node graph :defnk-g)))
      (is (identical? world-ref (t/get-value node graph :defnk-world)))
      (is (= project (t/get-value node graph :defnk-project))))))
