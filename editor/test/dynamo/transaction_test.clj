(ns dynamo.transaction-test
  (:require [clojure.test :refer :all]
            [dynamo.graph :as g]
            [support.test-support :refer :all]
            [dynamo.util :refer :all]
            [internal.transaction :as it]
            [plumbing.core :refer [defnk fnk]]))

(defnk upcase-a [a] (.toUpperCase a))

(g/defnode Resource
  (input a String)
  (output b g/Keyword (fnk [] :ok))
  (output c String upcase-a)
  (property d String (default ""))
  (property marker Integer))

(g/defnode Downstream
  (input consumer g/Keyword))

(defn safe+ [x y] (int (or (and x (+ x y)) y)))

(deftest low-level-transactions
  (testing "one node"
    (with-clean-system
      (let [tx-result (g/transact (g/make-node world Resource :d "known value"))]
        (is (= :ok (:status tx-result))))))
  (testing "two connected nodes"
    (with-clean-system
      (let [[id1 id2] (tx-nodes (g/make-node world Resource)
                                (g/make-node world Downstream))
            after                 (:basis (g/transact (it/connect id1 :b id2 :consumer)))]
        (is (= [id1 :b]        (first (g/sources after id2 :consumer))))
        (is (= [id2 :consumer] (first (g/targets after id1 :b)))))))

  (testing "disconnect two singly-connected nodes"
    (with-clean-system
      (let [[id1 id2] (tx-nodes (g/make-node world Resource)
                                (g/make-node world Downstream))
            tx-result             (g/transact (it/connect    id1 :b id2 :consumer))
            tx-result             (g/transact (it/disconnect id1 :b id2 :consumer))
            after                 (:basis tx-result)]
        (is (= :ok (:status tx-result)))
        (is (= [] (g/sources after id2 :consumer)))
        (is (= [] (g/targets after id1 :b))))))

  (testing "simple update"
    (with-clean-system
      (let [[resource] (tx-nodes (g/make-node world Resource :marker (int 0)))
            tx-result  (g/transact (it/update-property resource :marker safe+ [42]))]
        (is (= :ok (:status tx-result)))
        (is (= 42 (g/node-value resource :marker))))))

  (testing "node deletion"
    (with-clean-system
      (let [[resource1 resource2] (tx-nodes (g/make-node world Resource)
                                            (g/make-node world Downstream))]
        (g/transact (it/connect resource1 :b resource2 :consumer))
        (let [tx-result  (g/transact (it/delete-node resource2))
              after      (:basis tx-result)]
          (is (nil?      (g/node-by-id   after resource2)))
          (is (empty?    (g/targets      after resource1 :b)))
          (is (contains? (:nodes-deleted tx-result) resource2))
          (is (empty?    (:nodes-added   tx-result)))))))

  (testing "node deletion in same transaction"
    (with-clean-system
      (let [tx-result (g/transact
                       (g/make-nodes world
                                     [resource Resource]
                                     (g/delete-node resource)))
            [node]    (g/tx-nodes-added tx-result)]
        (is (= :ok (:status tx-result)))
        (is (nil?  node)))))

  (testing "node transformation"
    (with-clean-system
      (let [[id1] (tx-nodes (g/make-node world Resource :marker 99))
            tx-result   (g/transact (it/become id1 (g/construct Downstream)))
            after       (:basis tx-result)]
        (is (= :ok (:status tx-result)))
        (is (= Downstream (g/node-type* after id1)))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"No such" (g/node-value after id1 :marker)))))))

(g/defnode NamedThing
  (property name g/Str))

(g/defnode Person
  (property date-of-birth java.util.Date)

  (input first-name String)
  (input surname String)

  (output friendly-name String (fnk [first-name] first-name))
  (output full-name String (fnk [first-name surname] (str first-name " " surname)))
  (output age java.util.Date (fnk [date-of-birth] date-of-birth)))

(g/defnode Receiver
  (input generic-input g/Any)
  (property touched g/Bool (default false))
  (output passthrough g/Any (fnk [generic-input] generic-input)))

(g/defnode FocalNode
  (input aggregator g/Any :array)
  (output aggregated [g/Any] (fnk [aggregator] aggregator)))

(defn- build-network
  [world]
  (let [nodes {:person            (g/make-node world Person)
               :first-name-cell   (g/make-node world NamedThing)
               :last-name-cell    (g/make-node world NamedThing)
               :greeter           (g/make-node world Receiver)
               :formal-greeter    (g/make-node world Receiver)
               :calculator        (g/make-node world Receiver)
               :multi-node-target (g/make-node world FocalNode)}
        nodes (zipmap (keys nodes) (apply tx-nodes (vals nodes)))]
    (g/transact
     (for [[from from-l to to-l]
           [[:first-name-cell :name          :person            :first-name]
            [:last-name-cell  :name          :person            :surname]
            [:person          :friendly-name :greeter           :generic-input]
            [:person          :full-name     :formal-greeter    :generic-input]
            [:person          :age           :calculator        :generic-input]
            [:person          :full-name     :multi-node-target :aggregator]
            [:formal-greeter  :passthrough   :multi-node-target :aggregator]]]
       (g/connect (from nodes) from-l (to nodes) to-l)))
    nodes))

(defmacro affected-by [& forms]
  `(let [tx-result# (g/transact ~@forms)]
     (set (:outputs-modified tx-result#))))

(defn pairwise [m]
  (for [[k vs] m
        v vs]
    [k v]))

(deftest precise-invalidation
  (with-clean-system
    (let [{:keys [calculator person first-name-cell greeter formal-greeter multi-node-target]} (build-network world)]
      (are [update expected] (= (into #{} (pairwise expected)) (affected-by (apply g/set-property update)))
        [calculator :touched true]                {calculator        #{:_declared-properties :_properties :touched}}
        [person :date-of-birth (java.util.Date.)] {person            #{:_declared-properties :_properties :age :date-of-birth}
                                                   calculator        #{:passthrough}}
        [first-name-cell :name "Sam"]             {first-name-cell   #{:_declared-properties :_properties :name}
                                                   person            #{:full-name :friendly-name}
                                                   greeter           #{:passthrough}
                                                   formal-greeter    #{:passthrough}
                                                   multi-node-target #{:aggregated}}))))


(deftest blanket-invalidation
  (with-clean-system
    (let [{:keys [calculator person first-name-cell greeter formal-greeter multi-node-target]} (build-network world)
          tx-result        (g/transact (g/invalidate person))
          outputs-modified (:outputs-modified tx-result)]
      (doseq [output [:_node-id :_properties :friendly-name :full-name :date-of-birth :age]]
        (is (some #{[person output]} outputs-modified))))))


(g/defnode CachedOutputInvalidation
  (property a-property String (default "a-string"))

  (output ordinary String :cached (fnk [a-property] a-property))
  (output self-dependent String :cached (fnk [ordinary] ordinary)))

(deftest invalidated-properties-noted-by-transaction
  (with-clean-system
    (let [tx-result        (g/transact (g/make-node world CachedOutputInvalidation))
          real-id          (first (g/tx-nodes-added tx-result))
          outputs-modified (:outputs-modified tx-result)]
      (is (some #{real-id} (map first outputs-modified)))
      (is (= #{:_declared-properties :_properties :_node-id :_output-jammers :self-dependent :a-property :ordinary} (into #{} (map second outputs-modified))))
      (let [tx-data          [(it/update-property real-id :a-property (constantly "new-value") [])]
            tx-result        (g/transact tx-data)
            outputs-modified (:outputs-modified tx-result)]
        (is (some #{real-id} (map first outputs-modified)))
        (is (= #{:_declared-properties :_properties :a-property :ordinary :self-dependent} (into #{} (map second outputs-modified))))))))

(g/defnode CachedValueNode
  (output cached-output g/Str :cached (fnk [] "an-output-value")))

(defn cache-peek
  [system node-id output]
  (some-> system :cache deref (get [node-id output])))

;; TODO - move this to an integration test group
(deftest values-of-a-deleted-node-are-removed-from-cache
  (with-clean-system
    (let [[node-id]  (tx-nodes (g/make-node world CachedValueNode))]
      (is (= "an-output-value" (g/node-value node-id :cached-output)))
      (let [cached-value (cache-peek system node-id :cached-output)]
        (is (= "an-output-value" cached-value))
        (g/transact (g/delete-node node-id))
        (is (nil? (cache-peek system node-id :cached-output)))))))

(g/defnode OriginalNode
  (output original-output g/Str :cached (fnk [] "original-output-value")))

(g/defnode ReplacementNode
  (output original-output g/Str (fnk [] "original-value-replaced"))
  (output additional-output g/Str :cached (fnk [] "new-output-added")))

(deftest become-interacts-with-caching
  (testing "newly uncacheable values are evicted"
    (with-clean-system
      (let [[node-id]      (tx-nodes (g/make-node world OriginalNode))
            expected-value (g/node-value node-id :original-output)]
        (is (not (nil? expected-value)))
        (is (= expected-value (cache-peek system node-id :original-output)))
        (let [tx-result (g/transact (g/become node-id (g/construct ReplacementNode)))]
          (yield)
          (is (nil? (cache-peek system node-id :original-output)))))))

  (testing "newly cacheable values are indeed cached"
    (with-clean-system
      (let [[node-id]    (tx-nodes (g/make-node world OriginalNode))
            tx-result    (g/transact (g/become node-id (g/construct ReplacementNode)))
            cached-value (g/node-value node-id :additional-output)]
        (yield)
        (is (= cached-value (cache-peek system node-id :additional-output)))))))

(g/defnode Container
  (input nodes g/Any :array :cascade-delete))

(deftest double-deletion-is-safe
  (testing "delete scope first"
    (with-clean-system
      (let [[outer inner] (tx-nodes (g/make-node world Container) (g/make-node world Resource))]
        (g/transact (g/connect inner :_node-id outer :nodes))
        (is (= :ok (:status (g/transact
                             (concat
                              (g/delete-node outer)
                              (g/delete-node inner)))))))))

  (testing "delete inner node first"
    (with-clean-system
      (let [[outer inner] (tx-nodes (g/make-node world Container) (g/make-node world Resource))]
        (g/transact (g/connect inner :_node-id outer :nodes))

        (is (= :ok (:status (g/transact
                             (concat
                              (g/delete-node inner)
                              (g/delete-node outer))))))))))

(defn- exists? [node-id] (not (nil? (g/node-by-id (g/now) node-id))))

(g/defnode CascadingContainer
  (property a-property g/Str (default ""))
  (input attachments g/Any :cascade-delete))

(deftest cascading-delete
  (testing "delete container, one cascade connected by one output"
    (with-clean-system
      (let [[container resource] (tx-nodes (g/make-node world CascadingContainer) (g/make-node world Resource))]
        (g/transact
         (g/connect resource :b container :attachments))
        (is (= :ok (:status (g/transact (g/delete-node container)))))
        (is (not (exists? container)))
        (is (not (exists? resource))))))

  (testing "delete container, one cascade connected by multiple outputs"
    (with-clean-system
      (let [[container resource] (tx-nodes (g/make-node world CascadingContainer) (g/make-node world Resource))]
        (g/transact
         [(g/connect resource :b container :attachments)
          (g/connect resource :c container :attachments)])
        (is (= :ok (:status (g/transact (g/delete-node container)))))
        (is (not (exists? container)))
        (is (not (exists? resource))))))

  (testing "delete container, one cascade connected by a property"
    (with-clean-system
      (let [[container resource] (tx-nodes (g/make-node world CascadingContainer) (g/make-node world Resource))]
        (g/transact
         (g/connect resource :d container :attachments))
        (is (= :ok (:status (g/transact (g/delete-node container)))))
        (is (not (exists? container)))
        (is (not (exists? resource))))))

  (testing "delete container, two cascades"
    (with-clean-system
      (let [[container resource1 resource2] (tx-nodes (g/make-node world CascadingContainer)
                                                      (g/make-node world Resource)
                                                      (g/make-node world Resource))]
        (g/transact
         [(g/connect resource1 :d container :attachments)
          (g/connect resource2 :d container :attachments)])
        (is (= :ok (:status (g/transact (g/delete-node container)))))
        (is (not (exists? container)))
        (is (not (exists? resource1)))
        (is (not (exists? resource2))))))

  (testing "delete container, daisy chain of deletes"
    (with-clean-system
      (let [[container middle1 middle2 resource] (tx-nodes (g/make-node world CascadingContainer)
                                                           (g/make-node world CascadingContainer)
                                                           (g/make-node world CascadingContainer)
                                                           (g/make-node world Resource))]
        (g/transact
         [(g/connect resource  :d          middle2   :attachments)
          (g/connect middle2   :a-property middle1   :attachments)
          (g/connect middle1   :a-property container :attachments)])
        (is (= :ok (:status (g/transact (g/delete-node container)))))
        (is (not (exists? container)))
        (is (not (exists? middle1)))
        (is (not (exists? middle2)))
        (is (not (exists? resource)))))))

(g/defnode MyNode
  (property a-property g/Str))

(deftest make-nodes-complains-about-missing-properties
  (with-clean-system
    (is (thrown? AssertionError
                 (eval `(dynamo.graph/make-nodes ~world [new-node# [MyNode :no-such-property 1]]))))))

(deftest construct-complains-about-missing-properties
  (with-clean-system
    (is (thrown? AssertionError
                 (g/construct MyNode :_node-id 1 :no-such-property 1)))))
