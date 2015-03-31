(ns internal.value-test
  (:require [clojure.core.async :as a]
            [clojure.test :refer :all]
            [dynamo.graph :as g]
            [dynamo.node :as n]
            [dynamo.system :as ds]
            [dynamo.system.test-support :refer :all]
            [dynamo.types :as t]
            [internal.graph :as ig]
            [internal.node :as in]
            [internal.system :as is]
            [internal.transaction :as it]))

(def ^:dynamic *calls*)

(defn tally [node fn-symbol]
  (swap! *calls* update-in [(g/node-id node) fn-symbol] (fnil inc 0)))

(defn get-tally [node fn-symbol]
  (get-in @*calls* [(g/node-id node) fn-symbol] 0))

(defmacro expect-call-when [node fn-symbol & body]
  `(let [calls-before# (get-tally ~node ~fn-symbol)]
     ~@body
     (is (= (inc calls-before#) (get-tally ~node ~fn-symbol)))))

(defmacro expect-no-call-when [node fn-symbol & body]
  `(let [calls-before# (get-tally ~node ~fn-symbol)]
     ~@body
     (is (= calls-before# (get-tally ~node ~fn-symbol)))))

(g/defnk produce-simple-value
  [this scalar]
  (tally this 'produce-simple-value)
  scalar)

(g/defnode UncachedOutput
  (property scalar t/Str)
  (output uncached-value String produce-simple-value))

(defn compute-expensive-value
  [node g]
  (tally node 'compute-expensive-value)
  "this took a long time to produce")

(g/defnode CachedOutputNoInputs
  (output expensive-value String :cached
    (fn [node g]
      (tally node 'compute-expensive-value)
      "this took a long time to produce"))
  (input operand String))

(g/defnode UpdatesExpensiveValue
  (output expensive-value String :cached
    (fn [node g]
      (tally node 'compute-expensive-value)
      "this took a long time to produce")))

(g/defnode SecondaryCachedValue
  (output another-value String :cached
    (fn [node g]
      "this is distinct from the other outputs")))

(g/defnk compute-derived-value
  [this first-name last-name]
  (tally this 'compute-derived-value)
  (str first-name " " last-name))

(g/defnk passthrough-first-name
  [this first-name]
  (tally this 'passthrough-first-name)
  first-name)

(g/defnode CachedOutputFromInputs
  (input first-name String)
  (input last-name  String)

  (output nickname String :cached passthrough-first-name)
  (output derived-value String :cached compute-derived-value))

(g/defnode CacheTestNode
  (inherits UncachedOutput)
  (inherits CachedOutputNoInputs)
  (inherits CachedOutputFromInputs)
  (inherits UpdatesExpensiveValue)
  (inherits SecondaryCachedValue))

(defn build-sample-project
  [world]
  (let [nodes (tx-nodes
               (g/make-node world CacheTestNode :scalar "Jane")
               (g/make-node world CacheTestNode :scalar "Doe")
               (g/make-node world CacheTestNode)
               (g/make-node world CacheTestNode))
        [name1 name2 combiner expensive]  nodes]
    (ds/transact
     (concat
      (g/connect name1 :uncached-value combiner :first-name)
      (g/connect name2 :uncached-value combiner :last-name)
      (g/connect name1 :uncached-value expensive :operand)))
    nodes))

(defn with-function-counts
  [f]
  (binding [*calls* (atom {})]
    (f)))

(use-fixtures :each with-function-counts)

(deftest project-cache
  (with-clean-system
    (let [[name1 name2 combiner expensive] (build-sample-project world)]
      (testing "uncached values are unaffected"
        (is (= "Jane" (g/node-value name1 :uncached-value)))))))

(deftest caching-avoids-computation
  (testing "cached values are only computed once"
    (with-clean-system
      (let [[name1 name2 combiner expensive] (build-sample-project world)]
        (is (= "Jane Doe" (g/node-value combiner :derived-value)))
        (expect-no-call-when combiner 'compute-derived-value
                             (doseq [x (range 100)]
                               (g/node-value combiner :derived-value))))))

  (testing "modifying inputs invalidates the cached value"
    (with-clean-system
      (let [[name1 name2 combiner expensive] (build-sample-project world)]
        (is (= "Jane Doe" (g/node-value combiner :derived-value)))
        (expect-call-when combiner 'compute-derived-value
                          (ds/transact (it/update-property name1 :scalar (constantly "John") []))
                          (is (= "John Doe" (g/node-value combiner :derived-value)))))))

  (testing "transmogrifying a node invalidates its cached value"
    (with-clean-system
      (let [[name1 name2 combiner expensive] (build-sample-project world)]
        (is (= "Jane Doe" (g/node-value combiner :derived-value)))
        (expect-call-when combiner 'compute-derived-value
                          (ds/transact (it/become name1 (n/construct CacheTestNode)))
                          (is (= "Jane Doe" (g/node-value combiner :derived-value)))))))

  (testing "cached values are distinct"
    (with-clean-system
      (let [[name1 name2 combiner expensive] (build-sample-project world)]
        (is (= "this is distinct from the other outputs" (g/node-value combiner :another-value)))
        (is (not= (g/node-value combiner :another-value) (g/node-value combiner :expensive-value))))))

  (testing "cache invalidation only hits dependent outputs"
    (with-clean-system
      (let [[name1 name2 combiner expensive] (build-sample-project world)]
        (is (= "Jane" (g/node-value combiner :nickname)))
        (expect-call-when combiner 'passthrough-first-name
                          (ds/transact (it/update-property name1 :scalar (constantly "Mark") []))
                          (is (= "Mark" (g/node-value combiner :nickname))))
        (expect-no-call-when combiner 'passthrough-first-name
                             (ds/transact (it/update-property name2 :scalar (constantly "Brandenburg") []))
                             (is (= "Mark" (g/node-value combiner :nickname)))
                             (is (= "Mark Brandenburg" (g/node-value combiner :derived-value))))))))

(g/defnk produce-input-from-node
  [overridden]
  overridden)

(g/defnk derive-value-from-inputs
  [an-input]
  an-input)

(g/defnode OverrideValueNode
  (input overridden t/Str)
  (output output t/Str produce-input-from-node)
  (output foo    t/Str derive-value-from-inputs))

(defn build-override-project
  [world]
  (let [nodes (tx-nodes
               (g/make-node world OverrideValueNode)
               (g/make-node world CacheTestNode :scalar "Jane"))
        [override jane]  nodes]
    (ds/transact
     (g/connect jane :uncached-value override :overridden))
    nodes))

(deftest invalid-resource-values
  (with-clean-system
    (let [[override jane] (build-override-project world)]
      (testing "requesting a non-existent label throws"
        (is (thrown? clojure.lang.ExceptionInfo (g/node-value override :aint-no-thang)))))))

(deftest update-sees-in-transaction-value
  (with-clean-system
    (let [[node]            (tx-nodes (g/make-node world OverrideValueNode :name "a project" :int-prop 0))
          after-transaction (ds/transact
                             (concat
                              (g/update-property node :int-prop inc)
                              (g/update-property node :int-prop inc)
                              (g/update-property node :int-prop inc)
                              (g/update-property node :int-prop inc)))]
      (is (= 4 (:int-prop (g/refresh node)))))))

(defn- cache-peek [cache node-id label]
  (get @cache [node-id label]))

(defn- cached? [cache node-id label]
  (not (nil? (cache-peek cache node-id label))))

(g/defnode SelfCounter
  (property call-counter t/Int (default 0))

  (output plus-1 t/Int :cached
          (g/fnk [self call-counter]
                 (ds/transact (g/update-property self :call-counter inc))
                 (inc call-counter))))

(deftest intermediate-uncached-values-are-not-cached
  (with-clean-system
    (let [[node]       (tx-nodes (g/make-node world SelfCounter :first-call true))
          node-id      (:_id node)]
      (g/node-value node :plus-1)
      (is (cached? cache node-id :plus-1))
      (is (not (cached? cache node-id :self)))
      (is (not (cached? cache node-id :call-counter))))))

(g/defnode Source
  (property constant t/Keyword))

(g/defnode ValuePrecedence
  (property overloaded-output-input-property t/Keyword (default :property))
  (input    overloaded-output-input-property t/Keyword)
  (output   overloaded-output-input-property t/Keyword (g/fnk [] :output))

  (input    overloaded-input-property t/Keyword)
  (output   overloaded-input-property t/Keyword (g/fnk [overloaded-input-property] overloaded-input-property))

  (property the-property t/Keyword (default :property))

  (output   output-using-overloaded-output-input-property t/Keyword (g/fnk [overloaded-output-input-property] overloaded-output-input-property))

  (input    eponymous t/Keyword)
  (output   eponymous t/Keyword (g/fnk [eponymous] eponymous)))


(deftest node-value-precedence
  (with-clean-system
    (let [[node s1] (tx-nodes (g/make-node world ValuePrecedence)
                              (g/make-node world Source :constant :input))]
      (ds/transact
       (concat
        (g/connect s1 :constant node :overloaded-output-input-property)
        (g/connect s1 :constant node :overloaded-input-property)
        (g/connect s1 :constant node :eponymous)))
      (is (= :output   (g/node-value node :overloaded-output-input-property)))
      (is (= :input    (g/node-value node :overloaded-input-property)))
      (is (= :property (g/node-value node :the-property)))
      (is (= :output   (g/node-value node :output-using-overloaded-output-input-property)))
      (is (= :input    (g/node-value node :eponymous))))))
