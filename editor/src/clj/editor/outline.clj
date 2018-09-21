(ns editor.outline
  (:require [clojure.set :as set]
            [dynamo.graph :as g]
            [editor.core :as core]
            [schema.core :as s]
            [editor.resource :as resource]
            [editor.util :as util]
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

(defn- match-reqs [root-nodes target-item]
  (when-let [all-reqs (:child-reqs target-item)]
    (loop [root-nodes root-nodes
           matched-reqs []]
      (if-let [node (first root-nodes)]
        (if-let [match (find-req node all-reqs)]
          (recur (rest root-nodes) (conj matched-reqs match))
          nil)
        (when (seq matched-reqs)
          [target-item matched-reqs])))))

(defn- find-target-item [item-iterator root-nodes]
  (if item-iterator
    (->> item-iterator
      (iterate parent)
      (take-while some?)
      (mapcat #(let [item (value %)] [item (:alt-outline item)]))
      (some (partial match-reqs root-nodes)))
    nil))

(g/deftype OutlineData {:node-id                              s/Int
                        :node-outline-key                     (s/maybe s/Str)
                        :label                                s/Str
                        :icon                                 s/Str
                        (s/optional-key :link)                (s/maybe (s/pred resource/openable-resource?))
                        (s/optional-key :children)            [s/Any]
                        (s/optional-key :child-reqs)          [s/Any]
                        (s/optional-key :outline-error?)      s/Bool
                        (s/optional-key :outline-overridden?) s/Bool
                        (s/optional-key :outline-reference?)  s/Bool
                        s/Keyword                             s/Any})

(g/defnode OutlineNode
  (input source-outline OutlineData)
  (input child-outlines OutlineData :array)
  (output node-outline OutlineData :abstract))

(defn- outline-attachments
  [node-id]
  (let [{:keys [children]} (g/node-value node-id :node-outline)
        child-ids (map :node-id children)]
    (into (mapv (fn [child-id]
                  [node-id child-id]) child-ids)
          (mapcat outline-attachments child-ids))))

(defn- add-attachments
  [fragment root-ids]
  (let [{:keys [node-id->serial-id]} fragment
        original-attachments (into []
                                   (mapcat outline-attachments)
                                   root-ids)
        tx-attach-arcs (into #{}
                             (mapcat (fn [[parent-id child-id]]
                                       (let [parent-outline (g/node-value parent-id :node-outline)
                                             child-node (g/node-by-id child-id)
                                             [item [req]] (or (match-reqs [child-node] parent-outline)
                                                              (match-reqs [child-node] (:alt-outline parent-outline)))]
                                         (when-some [tx-attach-fn (:tx-attach-fn req)]
                                           (let [target-id (g/override-root (:node-id item))
                                                 tx-data (tx-attach-fn target-id child-id)]
                                             (keep (fn [tx-step]
                                                     (when (= :connect (:type tx-step))
                                                       (let [src-serial-id (node-id->serial-id (:source-id tx-step))
                                                             tgt-serial-id (node-id->serial-id (:target-id tx-step))]
                                                         (when (and src-serial-id tgt-serial-id)
                                                           [src-serial-id (:source-label tx-step) tgt-serial-id (:target-label tx-step)]))))
                                                   (flatten tx-data)))))))
                             original-attachments)

        attachments (into []
                          (keep (fn [[parent-id child-id]]
                                  (let [parent-serial-id (node-id->serial-id parent-id)
                                        child-serial-id (node-id->serial-id child-id)]
                                    (when (and parent-serial-id child-serial-id)
                                      [parent-serial-id child-serial-id]))))
                          original-attachments)]
    (-> fragment
        (assoc :attachments attachments)
        (update :arcs (partial filterv #(not (contains? tx-attach-arcs %)))))))

(defn- default-copy-traverse [basis [src-node src-label tgt-node tgt-label]]
  (and (g/node-instance? OutlineNode tgt-node)
       (or (= :child-outlines tgt-label)
           (= :source-outline tgt-label))
       (not (and (g/node-instance? basis resource/ResourceNode src-node)
                 (some? (resource/path (g/node-value src-node :resource (g/make-evaluation-context {:basis basis}))))))))

(defn copy
  ([src-item-iterators]
    (copy src-item-iterators default-copy-traverse))
  ([src-item-iterators traverse?]
    (let [root-ids (mapv #(:node-id (value %)) src-item-iterators)
          fragment (-> (g/copy root-ids {:traverse? traverse?})
                       (add-attachments root-ids))]
      (serialize fragment))))

(defn- read-only? [item-it]
  (:read-only (value item-it) false))

(defn- root? [item-iterator]
  (nil? (parent item-iterator)))

(defn delete? [item-iterators]
  (and (not-any? read-only? item-iterators)
       (not-any? root? item-iterators)))

(defn cut? [src-item-iterators]
  (and (delete? src-item-iterators)
    (loop [src-item-iterators src-item-iterators
           common-node-types nil]
     (if-let [item-it (first src-item-iterators)]
       (let [root-nodes [(g/node-by-id (:node-id (value item-it)))]
             parent (parent item-it)
             [_ reqs] (find-target-item parent root-nodes)
             node-types (set (map :node-type reqs))
             common-node-types (if common-node-types
                                 (set/intersection common-node-types node-types)
                                 node-types)]
         (if (and reqs (seq common-node-types))
           (recur (rest src-item-iterators) common-node-types)
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
            (g/delete-node (g/override-root id)))
          extra-tx-data))
      data)))

(defn- deserialize
  [text]
  (g/read-graph text (core/read-handlers)))

(defn- paste [graph fragment]
  (g/paste graph fragment {}))

(defn- nodes-by-id
  [paste-data]
  (into {}
        (comp (filter #(= (:type %) :create-node))
              (map :node)
              (map (juxt :_node-id identity)))
        (:tx-data paste-data)))

(defn- root-nodes [paste-data]
  (let [id->node (nodes-by-id paste-data)]
    (mapv (partial get id->node) (:root-node-ids paste-data))))

(defn- attach-pasted-nodes!
  [op-label op-seq item reqs root-node-ids]
  (assert (= (count reqs) (count root-node-ids)))
  (let [target (g/override-root (:node-id item))]
    ;; The tx-attach-fn will often look at the scope and assign unique names for
    ;; the pasted nodes. Performing individual transactions here means they will
    ;; get to see the names of the nodes that were pasted alongside them.
    (dorun
      (map (fn [node req]
             (when-some [tx-attach-fn (:tx-attach-fn req)]
               (g/transact
                 (concat
                   (g/operation-label op-label)
                   (g/operation-sequence op-seq)
                   (tx-attach-fn target node)))))
           root-node-ids
           reqs))))

(defn- do-paste!
  [op-label op-seq paste-data attachments item reqs select-fn]
  (let [serial-id->node-id (:serial-id->node-id paste-data)
        id->node (nodes-by-id paste-data)
        root-nodes (root-nodes paste-data)]
    (g/transact
      (concat
        (g/operation-label op-label)
        (g/operation-sequence op-seq)
        (:tx-data paste-data)))
    (attach-pasted-nodes! op-label op-seq item reqs (:root-node-ids paste-data))
    (doseq [[parent-serial-id child-serial-id] attachments]
      (let [parent-id (serial-id->node-id parent-serial-id)
            parent-outline (g/node-value parent-id :node-outline)
            child-id (serial-id->node-id child-serial-id)
            child-node (id->node child-id)
            [item reqs] (or (match-reqs [child-node] parent-outline)
                            (match-reqs [child-node] (:alt-outline parent-outline)))]
        (attach-pasted-nodes! op-label op-seq item reqs [child-id])))
    (when select-fn
      (g/transact
        (concat
          (g/operation-label op-label)
          (g/operation-sequence op-seq)
          (select-fn (mapv :_node-id root-nodes)))))))

(defn- paste-target [graph item-iterator data]
  (let [paste-data (paste graph (deserialize data))
        root-nodes (root-nodes paste-data)]
    (find-target-item item-iterator root-nodes)))

(defn paste! [graph item-iterator data select-fn]
  (let [fragment (deserialize data)
        paste-data (paste graph fragment)
        root-nodes (root-nodes paste-data)]
    (when-let [[item reqs] (find-target-item item-iterator root-nodes)]
      (do-paste! "Paste" (gensym) paste-data (:attachments fragment) item reqs select-fn))))

(defn paste? [graph item-iterator data]
  (try
    (some? (paste-target graph item-iterator data))
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
    ; src is not descendant of target
    (not
      (reduce (fn [desc? it] (or desc?
                                 (descendant? (value it) item-iterator)))
              false src-item-iterators))
    ; pasting is allowed
    (when-let [[tgt _] (paste-target graph item-iterator data)]
      (not (reduce (fn [parent? it]
                     (let [parent-item (value (parent it))]
                       (or parent? (= parent-item tgt) (= (:alt-outline parent-item) tgt)))) false src-item-iterators)))))

(defn drop! [graph src-item-iterators item-iterator data select-fn]
  (when (drop? graph src-item-iterators item-iterator data)
    (let [fragment (deserialize data)
          paste-data (paste graph fragment)
          root-nodes (root-nodes paste-data)]
      (when-let [[item reqs] (find-target-item item-iterator root-nodes)]
        (let [op-seq (gensym)]
          (g/transact
            (concat
              (g/operation-label "Drop")
              (g/operation-sequence op-seq)
              (for [it src-item-iterators]
                (g/delete-node (g/override-root (:node-id (value it)))))))
          (do-paste! "Drop" op-seq paste-data (:attachments fragment) item reqs select-fn))))))

(defn- ids->lookup [ids]
  (if (or (set? ids) (map? ids))
    ids
    (set ids)))

(defn- lookup-insert [lookup id]
  (cond (set? lookup) (conj lookup id)
        (map? lookup) (assoc lookup id id)
        :else (throw (ex-info (str "Unsupported lookup " (type lookup))
                              {:id id
                               :lookup lookup}))))

(defn- trim-digits
  ^String [^String id]
  (loop [index (.length id)]
    (if (zero? index)
      ""
      (if (Character/isDigit (.charAt id (unchecked-dec index)))
        (recur (unchecked-dec index))
        (subs id 0 index)))))

(defn resolve-id [id ids]
  (let [ids (ids->lookup ids)]
    (if (ids id)
      (let [prefix (trim-digits id)]
        (loop [suffix ""
               index 1]
          (let [id (str prefix suffix)]
            (if (contains? ids id)
              (recur (str index) (inc index))
              id))))
      id)))

(defn resolve-ids [wanted-ids taken-ids]
  (first (reduce (fn [[resolved-ids taken-ids] wanted-id]
                   (let [id (resolve-id wanted-id taken-ids)]
                     [(conj resolved-ids id) (lookup-insert taken-ids id)]))
                 [[] (ids->lookup taken-ids)]
                 wanted-ids)))

(defn natural-sort [items]
  (->> items (sort-by :label util/natural-order) vec))

(defn next-node-outline-key-index [parent-id]
  (inc (transduce (keep :node-outline-key-index)
                  max
                  -1
                  (:children (g/node-value parent-id :node-outline)))))
