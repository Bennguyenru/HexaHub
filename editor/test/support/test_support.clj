(ns support.test-support
  (:require [dynamo.graph :as g]
            [internal.system :as is]))

(defmacro with-clean-system
  [& forms]
  (let [configuration  (if (map? (first forms)) (first forms) {})
        forms          (if (map? (first forms)) (next forms)  forms)]
    `(let [~'system      (is/make-system ~configuration)
           ~'cache       (:cache ~'system)
           ~'world       (first (keys (is/graphs ~'system)))]
       (binding [g/*the-system* (atom ~'system)]
         ~@forms))))

(defn tx-nodes [& txs]
  (g/tx-nodes-added (g/transact txs)))

(defn tempfile
  ^java.io.File [prefix suffix auto-delete?]
  (let [f (java.io.File/createTempFile prefix suffix)]
    (when auto-delete?
      (.deleteOnExit f))
    f))

(defn array= [a b]
  (and
    (= (class a) (class b))
    (= (count a) (count b))
    (every? true? (map = a b))))

(defn yield
  "Give up the thread just long enough for a context switch"
  []
  (Thread/sleep 1))

(defn undo-stack
  [graph]
  (is/undo-stack (is/graph-history @g/*the-system* graph)))

(defn redo-stack
  [graph]
  (is/redo-stack (is/graph-history @g/*the-system* graph)))
