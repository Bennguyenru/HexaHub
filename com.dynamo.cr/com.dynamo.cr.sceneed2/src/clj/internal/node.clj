(ns internal.node
  (:require [clojure.set :refer [rename-keys union]]
            [internal.graph.lgraph :as lg]
            [internal.graph.dgraph :as dg]
            [camel-snake-kebab :refer [->kebab-case]]))

(def ^:private ^java.util.concurrent.atomic.AtomicInteger
     nextid (java.util.concurrent.atomic.AtomicInteger. 1000000))

(defn tempid [] (- (.getAndIncrement nextid)))

(defn node-inputs [v] (into #{} (keys (:inputs v))))
(defn node-outputs [v] (into #{} (keys (:transforms v))))

(defn get-input [])
(defn refresh-inputs [g n i])

(defprotocol Node
  (value [this g output default] "Produce the value for the named output. Supplies nil if the output cannot be produced.")
  (properties [this] "Produce a description of properties supported by this node."))

(defn is-schema? [vals] (some :schema (map meta vals)))

(defn deep-merge
  "Recursively merges maps. If keys are not maps, the last value wins."
  [& vals]
  (cond
    (is-schema? vals)         (last vals)
    (every? map? vals)        (apply merge-with deep-merge vals)
    (every? set? vals)        (apply union vals)
    (every? sequential? vals) (apply concat vals)
    :else                     (last vals)))

(defn merge-behaviors [behaviors]
  (apply deep-merge
         (map #(cond
                 (symbol? %) (var-get (resolve %))
                 (var? %)    (var-get (resolve %))
                 (map? %)    %
                 :else       (throw (ex-info (str "Unacceptable behavior " %) :argument %)))
              behaviors)))

(defn- property-symbols [behavior]
  (map (comp symbol name) (keys (:properties behavior))))

(defn state-vector [behavior]
  (into [] (list* 'inputs 'transforms '_id (property-symbols behavior))))

(defn generate-type [name behavior]
  (let [t 'this#
        g 'g#
        o 'output#
        d 'default#]
    (list 'defrecord name (state-vector behavior)
          'internal.node/Node
          `(value [~t ~g ~o ~d]
                  (cond
                    ~@(mapcat (fn [x] [(list '= o x) (list 'prn x)]) (keys (:outputs behavior)))
                    :else ~d))
          `(properties [~t]
                       ~(:properties behavior)))))

(defn wire-up [selection input new-node output]
  (let [g           (first selection)
        target-node (first (second selection))
        g-new       (lg/add-labeled-node g (node-inputs new-node) (node-outputs new-node) new-node)
        new-node-id (dg/last-node g-new)]
      (-> g
        (lg/connect new-node-id output target-node input)
        (refresh-inputs target-node input))))

(defn unwire [selection input source]
  (let [g           (first selection)
        target-node (first (second selection))
        g-new       (dg/for-graph g [l (lg/source-labels g target-node :input source)]
                                  (lg/disconnect g source l target-node input))]
    (refresh-inputs g target-node input)))

(defn input-mutators [input]
  (let [adder (symbol (str "add-to-" (name input)))
        remover (symbol (str "remove-from-" (name input)))]
    (list
      `(defn ~adder [~'selection ~'new-node ~'output]
         (wire-up ~'selection ~input ~'new-node ~'output))
      `(defn ~remover [~'selection ~'new-node]
         (unwire ~'selection ~input ~'new-node)))))

(defn defaults [behavior]
  (reduce-kv (fn [m k v] (if (:default v) (assoc m k (:default v)) m))
             behavior (:properties behavior)))

(defn- print-md-table
  "Prints a collection of maps in a textual table suitable for converting
   to GMD-flavored markdown. Prints table headings
   ks, and then a line of output for each row, corresponding to the keys
   in ks. If ks are not specified, use the keys of the first item in rows.

   Cribbed from `clojure.pprint/print-table`."
  ([ks rows]
     (when (seq rows)
       (let [widths (map
                     (fn [k]
                       (apply max (count (str k)) (map #(count (str (get % k))) rows)))
                     ks)
             spacers (map #(apply str (repeat % "-")) widths)
             fmts (map #(str "%" % "s") widths)
             fmt-row (fn [leader divider trailer row]
                       (str leader
                            (apply str (interpose divider
                                                  (for [[col fmt] (map vector (map #(get row %) ks) fmts)]
                                                    (format fmt (str col)))))
                            trailer))]
         (println)
         (println (fmt-row "| " " | " " |" (zipmap ks ks)))
         (println (fmt-row "|-" "-|-" "-|" (zipmap ks spacers)))
         (doseq [row rows]
           (println (fmt-row "| " " | " " |" row))))))
  ([rows] (print-md-table (keys (first rows)) rows)))

(defn describe-properties [behavior]
  (with-out-str (print-md-table ["Name" "Type" "Default"]
                            (reduce-kv
                              (fn [rows k v]
                                  (conj rows (assoc (rename-keys v {:schema "Type" :default "Default"}) "Name" k)))
                              []
                              (:properties behavior)))))

(defn generate-constructor [nm behavior]
  (let [ctor             (symbol (str 'make- (->kebab-case (str nm))))
        record-ctor      (symbol (str 'map-> nm))]
    `(defn ~ctor
       ~(str "Constructor for " nm ", using default values for any property not in property-values.\nThe properties on " nm " are:\n"
             (describe-properties behavior))
       [& {:as ~'property-values}]
       (~record-ctor (merge {:_id (tempid)} ~(defaults behavior) ~'property-values)))))
