(ns internal.graph.generator
  "test.check generator to create a randomly populated graph"
  (:require [clojure.test.check :as tc]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [clojure.test :refer :all]
            [internal.graph :as g]
            [internal.graph.types :as gt]))

(def min-node-count 80)
(def max-node-count 100)
(def node-deletion-factor 5)
(def min-arc-count  10)
(def max-arc-count  50)
(def arc-deletion-factor 5)

(def labels (gen/elements [:t :u :v :w :x :y :z]))

(defrecord FakeNode [_id ins outs]
  gt/Node
  (node-id [this] _id)
  (inputs  [this] ins)
  (outputs [this] outs))

(defn node [ins outs] (FakeNode. nil ins outs))

(defn pair
  [m n]
  (gen/tuple (gen/elements m) (gen/elements n)))

(defn map-nodes
  [f nodes]
  (for [[idsym n] nodes
        l         (f n)]
    [idsym l]))

(defn gen-arcs
  [nodes]
  (pair (map-nodes gt/outputs nodes) (map-nodes gt/inputs nodes)))

(def gen-label
  (gen/not-empty
   (gen/vector labels)))

(defn gen-nodes
  [max-id]
  (gen/fmap (fn [[in out]] (node (into #{} in) (into #{} out)))
            (gen/tuple
             gen-label
             gen-label)))

(defn node-bindings
  [gsym nodes]
  (into []
        (mapcat
         (fn [[sym node]]
           `[[~gsym ~sym] (g/claim-id ~gsym)
             ~gsym        (g/add-node ~gsym ~sym (assoc ~node :_id ~sym))])
         nodes)))

(defn remove-nodes
  [dead-nodes]
  (for [n dead-nodes]
    `(g/remove-node ~n)))

(defn- populate-arcs
  [new-arcs]
  (mapcat (fn [a]
            `[(g/connect-target ~@(flatten a))])
          new-arcs))

(defn- remove-arcs
  [dead-arcs]
  (mapcat (fn [a]
            `[(g/disconnect-target ~@(flatten a))])
          dead-arcs))

(defn subselect
  [coll fraction]
  (gen/not-empty
   (gen/vector
    (gen/elements coll)
    (/ (count coll) fraction))))

(defn s
  [g]
  (first (gen/sample g 1)))

(defn random-graph-sexps
  []
  (let [nodes      (s (gen/vector (gen-nodes max-node-count) min-node-count max-node-count))
        nodes      (zipmap (repeatedly (count nodes) gensym) nodes)
        dead-nodes (s (subselect (keys nodes) node-deletion-factor))
        arcs       (s (gen/vector (gen-arcs nodes) min-arc-count max-arc-count))
        dead-arcs  (s (subselect arcs arc-deletion-factor))]
    `(fn []
       (let [~'g (g/empty-graph)]
         (let [~@(node-bindings 'g nodes)]
           (-> ~'g
               ~@(populate-arcs  arcs)
               ~@(remove-nodes   dead-nodes)
               ~@(remove-arcs    dead-arcs)))))))

(defn make-random-graph-builder
  []
  (eval (random-graph-sexps)))


(comment

  (def builder (make-random-graph-builder))

  (builder)

  (= (builder) (builder))
  ;; => true

  (= ((make-random-graph-builder)) ((make-random-graph-builder)))
  ;; => false
)
