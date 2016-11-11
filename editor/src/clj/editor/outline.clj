(ns editor.outline
  (:require [dynamo.graph :as g]
            [editor.core :as core]
            [schema.core :as s]
            [editor.resource :as resource]
            [editor.workspace :as workspace]
            [service.log :as log])
  (:import [editor.resource FileResource ZipResource]))

(set! *warn-on-reflection* true)

(defprotocol ItemIterator
  (value [this])
  (parent [this]))

(defn- req-satisfied? [req node]
  (g/node-instance*? (:node-type req) node))

(defn- find-req [node reqs]
  (when reqs
    (first (filter #(req-satisfied? % node) reqs))))

(defn- serialize
  [fragment]
  (g/write-graph fragment (core/write-handlers)))

(defn- match-reqs [target-item root-nodes]
  (let [all-reqs (:child-reqs target-item)]
    (loop [root-nodes root-nodes
           matched-reqs []]
      (if-let [node (first root-nodes)]
        (if-let [match (find-req node all-reqs)]
          (recur (rest root-nodes) (conj matched-reqs match))
          nil)
        matched-reqs))))

(defn- find-target-item [item-iterator root-nodes]
  (if item-iterator
    (let [item (value item-iterator)]
      (if-let [reqs (match-reqs item root-nodes)]
        [item reqs]
        (recur (parent item-iterator) root-nodes)))
    nil))

(g/deftype OutlineData {:node-id                              s/Int
                        :label                                s/Str
                        :icon                                 s/Str
                        (s/optional-key :children)            [s/Any]
                        (s/optional-key :child-reqs)          [s/Any]
                        (s/optional-key :outline-overridden?) s/Bool
                        s/Keyword                             s/Any})

(g/defnode OutlineNode
  (input source-outline OutlineData)
  (input child-outlines OutlineData :array)

  (output node-outline OutlineData :abstract)
  (output outline-overridden? g/Bool :cached (g/fnk [_overridden-properties child-outlines]
                                                    (boolean
                                                      (or (not (empty? _overridden-properties))
                                                          (some :outline-overridden child-outlines))))))

(defn- default-copy-traverse [basis [src-node src-label tgt-node tgt-label]]
  (and (g/node-instance? OutlineNode tgt-node)
    (or (= :child-outlines tgt-label)
      (= :source-outline tgt-label))
    (not (and (g/node-instance? basis resource/ResourceNode src-node)
      (some? (resource/path (g/node-value src-node :resource {:basis basis})))))))

(defn copy
  ([src-item-iterators]
    (copy src-item-iterators default-copy-traverse))
  ([src-item-iterators traverse?]
    (let [root-ids (mapv #(:node-id (value %)) src-item-iterators)
          fragment (g/copy root-ids {:traverse? traverse?})]
      (serialize fragment))))

(defn- read-only? [item-it]
  (:read-only (value item-it) false))

(defn- root? [item-iterator]
  (nil? (parent item-iterator)))

(defn- override? [item-it]
  (-> item-it
    value
    :node-id
    g/override-original
    some?))

(defn delete? [item-iterators]
  (and (not-any? read-only? item-iterators)
       (not-any? root? item-iterators)
       (not-any? override? item-iterators)))

(defn cut? [src-item-iterators]
  (and (delete? src-item-iterators)
    (loop [src-item-iterators src-item-iterators]
     (if-let [item-it (first src-item-iterators)]
       (let [root-nodes [(g/node-by-id (:node-id (value item-it)))]
             parent (parent item-it)]
         (if (find-target-item parent root-nodes)
           (recur (rest src-item-iterators))
           false))
       true))))

(defn cut!
  ([src-item-iterators]
    (cut! src-item-iterators []))
  ([src-item-iterators extra-tx-data]
    (let [data     (copy src-item-iterators)
          root-ids (mapv #(:node-id (value %)) src-item-iterators)]
      (g/transact
        (concat
          (g/operation-label "Cut")
          (for [id root-ids]
            (g/delete-node id))
          extra-tx-data))
      data)))

(defn- deserialize
  [text]
  (g/read-graph text (core/read-handlers)))

(defn- paste [graph fragment]
  (g/paste graph (deserialize fragment) {}))

(defn- root-nodes [paste-data]
  (let [nodes (into {} (map #(let [n (:node %)] [(:_node-id n) n]) (filter #(= (:type %) :create-node) (:tx-data paste-data))))]
    (mapv (partial get nodes) (:root-node-ids paste-data))))

(defn- build-tx-data [item reqs paste-data]
  (let [target (:node-id item)]
    (concat
      (:tx-data paste-data)
      (for [[node req] (map vector (:root-node-ids paste-data) reqs)]
        (if-let [tx-attach-fn (:tx-attach-fn req)]
          (tx-attach-fn target node)
          [])))))

(defn paste! [graph item-iterator data select-fn]
  (let [paste-data (paste graph data)
        root-nodes (root-nodes paste-data)]
    (when-let [[item reqs] (find-target-item item-iterator root-nodes)]
      (g/transact
        (concat
          (g/operation-label "Paste")
          (build-tx-data item reqs paste-data)
          (select-fn (mapv :_node-id root-nodes)))))))

(defn paste? [graph item-iterator data]
  (try
    (let [paste-data (paste graph data)
         root-nodes (root-nodes paste-data)]
     (some? (find-target-item item-iterator root-nodes)))
    (catch Exception e
      (log/warn :exception e)
      ; TODO - ignore
      false)))

(defn drag? [graph item-iterators]
  (delete? item-iterators))

(defn- descendant? [src-item item-iterator]
  (if item-iterator
    (if (= src-item (value item-iterator))
      true
      (recur src-item (parent item-iterator)))
    false))

(defn drop? [graph src-item-iterators item-iterator data]
  (and
    ; target is not parent of source
    (let [tgt (value item-iterator)]
      (not (reduce (fn [parent? it] (or parent? (= (value (parent it)) tgt))) false src-item-iterators)))
    ; src is not descendant of target
    (not
      (reduce (fn [desc? it] (or desc?
                                 (descendant? (value it) item-iterator)))
              false src-item-iterators))
    ; pasting is allowed
    (paste? graph item-iterator data)))

(defn drop! [graph src-item-iterators item-iterator data select-fn]
  (when (drop? graph src-item-iterators item-iterator data)
    (let [paste-data (paste graph data)
          root-nodes (root-nodes paste-data)]
      (when-let [[item reqs] (find-target-item item-iterator root-nodes)]
        (let [op-seq (gensym)]
          (g/transact
            (concat
              (g/operation-label "Drop")
              (g/operation-sequence op-seq)
              (for [it src-item-iterators]
                (g/delete-node (:node-id (value it))))))
          (g/transact
            (concat
              (g/operation-label "Drop")
              (g/operation-sequence op-seq)
              (build-tx-data item reqs paste-data)
              (select-fn (mapv :_node-id root-nodes)))))))))

(defn resolve-id [id ids]
  (let [ids (set ids)]
    (if (ids id)
      (let [prefix id]
        (loop [suffix ""
               index 1]
          (let [id (str prefix suffix)]
            (if (contains? ids id)
              (recur (str index) (inc index))
              id))))
      id)))
