(ns dynamo.graph
  "Main api for graph and node"
  (:require [clojure.set :as set]
            [clojure.tools.macro :as ctm]
            [cognitect.transit :as transit]
            [dynamo.util :as util]
            [internal.cache :as c]
            [internal.disposal :as dispose]
            [internal.graph :as ig]
            [internal.graph.types :as gt]
            [internal.node :as in]
            [internal.property :as ip]
            [internal.system :as is]
            [internal.transaction :as it]
            [plumbing.core :as pc]
            [potemkin.namespaces :as namespaces]
            [schema.core :as s])
  (:import [internal.graph.types Arc]
           [java.io ByteArrayOutputStream StringBufferInputStream]))

(namespaces/import-vars [plumbing.core defnk fnk])

(namespaces/import-vars [internal.graph.types NodeID node-id->graph-id node->graph-id node-by-property sources targets connected? dependencies Node node-id node-type produce-value NodeType supertypes interfaces protocols method-impls triggers transforms transform-types internal-properties properties externs declared-inputs injectable-inputs declared-outputs cached-outputs input-dependencies input-cardinality substitute-for input-type output-type input-labels output-labels property-labels property-display-order error? error])

(namespaces/import-vars [internal.node has-input? has-output? has-property? merge-display-order])

(namespaces/import-vars [schema.core Any Bool Inst Int Keyword Num Regex Schema Str Symbol Uuid both check enum protocol maybe fn-schema one optional-key pred recursive required-key validate])

(namespaces/import-vars [internal.graph.types IDisposable dispose disposable?])

(namespaces/import-vars [internal.graph.types always PropertyType property-value-type property-default-value property-validate property-valid-value? property-tags property-type? Properties])

(namespaces/import-vars [internal.graph arc type-compatible? node-by-id-at node-ids])

(namespaces/import-macro schema.core/defn      s-defn)
(namespaces/import-macro schema.core/defrecord s-defrecord)

(let [graph-id ^java.util.concurrent.atomic.AtomicInteger (java.util.concurrent.atomic.AtomicInteger. 0)]
  (defn next-graph-id [] (.getAndIncrement graph-id)))

(declare node-value)

;; ---------------------------------------------------------------------------
;; State handling
;; ---------------------------------------------------------------------------

;; Only marked dynamic so tests can rebind. Should never be rebound "for real".
(defonce ^:dynamic *the-system* (atom nil))

(def ^:dynamic *tps-debug* nil)

(namespaces/import-vars [internal.system system-cache cache-disposal-queue deleted-disposal-queue])

(defn now
  "The basis at the current point in time"
  []
  (is/basis @*the-system*))

(defn node-by-id
  "Returns a node given its id. If the basis is provided, it returns the value of the node using that basis.
   Otherwise, it uses the current basis."
  ([node-id]
   (let [graph-id (node-id->graph-id node-id)]
     (ig/node (is/graph @*the-system* graph-id) node-id)))
  ([basis node-id]
   (ig/node-by-id-at basis node-id)))

(defn node-type*
  "Return the node-type given a node-id.  Uses the current basis if not provided."
  ([node-id]
   (node-type* (now) node-id))
  ([basis node-id]
   (gt/node-type (ig/node-by-id-at basis node-id))))

(defn cache "The system cache of node values"
  []
  (is/system-cache @*the-system*))

(defn graph "Given a graph id, returns the particular graph in the system at the current point in time"
  [graph-id]
  (is/graph @*the-system* graph-id))

(defn- has-history? [sys graph-id] (not (nil? (is/graph-history sys graph-id))))
(def ^:private meaningful-change? contains?)

(defn- remember-change
  [sys graph-id before after outputs-modified]
  (alter (is/graph-history sys graph-id) is/merge-or-push-history (is/graph-ref sys graph-id) before after outputs-modified))

(defn- merge-graphs
  [sys basis post-tx-graphs significantly-modified-graphs outputs-modified]
  (let [outputs-modified (group-by #(node-id->graph-id (first %)) outputs-modified)]
    (doseq [[graph-id graph] post-tx-graphs]
      (let [start-tx    (:tx-id graph -1)
            sidereal-tx (is/graph-time sys graph-id)]
        (if (< start-tx sidereal-tx)
          ;; graph was modified concurrently by a different transaction.
          (throw (ex-info "Concurrent modification of graph"
                          {:_gid graph-id :start-tx start-tx :sidereal-tx sidereal-tx}))
          (let [gref   (is/graph-ref sys graph-id)
                before @gref
                after  (update-in graph [:tx-id] util/safe-inc)
                after  (if (not (meaningful-change? significantly-modified-graphs graph-id))
                         (assoc after :tx-sequence-label (:tx-sequence-label before))
                         after)]
            (when (and (has-history? sys graph-id) (meaningful-change? significantly-modified-graphs graph-id))
              (remember-change sys graph-id before after (outputs-modified graph-id)))
            (ref-set gref after)))))))

(when *tps-debug*
  (def tps-counter (agent (long-array 3 0)))

  (defn tick [^longs tps-counts now]
    (let [last-report-time (aget tps-counts 1)
          transaction-count (inc (aget tps-counts 0))]
      (aset-long tps-counts 0 transaction-count)
      (when (> now (+ last-report-time 1000000000))
        (let [elapsed-time (/ (- now last-report-time) 1000000000.00)]
         (do (println "TPS" (/ transaction-count elapsed-time))))
        (aset-long tps-counts 1 now)
        (aset-long tps-counts 0 0)))
    tps-counts))

(defn transact
  "Provides a way to run a transaction against the graph system.  It takes a list of transaction steps.

  Example:

      (g/transact
         [(g/connect n1 output-name n :xs)
         (g/connect n2 output-name n :xs)])

  It returns the transaction result, (tx-result),  which is a map containing keys about the transaction.
  Transaction result-keys:
  `[:status :basis :graphs-modified :nodes-added :nodes-modified :nodes-deleted :outputs-modified :properties-modified :label :sequence-label]`
  "
  [txs]
  (when *tps-debug*
    (send-off tps-counter tick (System/nanoTime)))
  (let [basis     (ig/multigraph-basis (util/map-vals deref (is/graphs @*the-system*)))
        tx-result (it/transact* (it/new-transaction-context basis txs))]
    (when (= :ok (:status tx-result))
      (dosync
       (merge-graphs @*the-system* basis (get-in tx-result [:basis :graphs]) (:graphs-modified tx-result) (:outputs-modified tx-result))
       (c/cache-invalidate (is/system-cache @*the-system*) (:outputs-modified tx-result)))
      (doseq [d (vals (:nodes-deleted tx-result))]
        (is/dispose! @*the-system* d)))
    tx-result))

;; ---------------------------------------------------------------------------
;; Using transaction values
;; ---------------------------------------------------------------------------
(defn tx-nodes-added
 "Returns a list of the node-ids added given a result from a transaction, (tx-result)."
  [transaction]
  (:nodes-added transaction))

(defn is-modified?
  "Returns a boolean if a node, or node and output, was modified as a result of a transaction given a tx-result."
  ([transaction node-id]
    (boolean (contains? (:outputs-modified transaction) node-id)))
  ([transaction node-id output]
    (boolean (get-in transaction [:outputs-modified node-id output]))))

(defn is-added? [transaction node-id]
  "Returns a boolean if a node was added as a result of a transaction given a tx-result and node."
  (contains? (:nodes-added transaction) node-id))

(defn is-deleted? [transaction node-id]
  "Returns a boolean if a node was delete as a result of a transaction given a tx-result and node."
  (contains? (:nodes-deleted transaction) node-id))

(defn outputs-modified
  "Returns the pairs of node-id and label of the outputs that were modified for a node as the result of a transaction given a tx-result and node"
  [transaction node-id]
  (get-in transaction [:outputs-modified node-id]))

(defn transaction-basis
  "Returns the final basis from the result of a transaction given a tx-result"
  [transaction]
  (:basis transaction))

(defn pre-transaction-basis
  "Returns the original, statrting basis from the result of a transaction given a tx-result"
  [transaction]
  (:original-basis transaction))

;; ---------------------------------------------------------------------------
;; Intrinsics
;; ---------------------------------------------------------------------------
(def node-intrinsics
  [(list 'property '_id `s/Int)
   (list 'property '_output-jammers `{s/Keyword s/Any})
   (list 'output '_node-id `NodeID `(pc/fnk [~'this] (gt/node-id ~'this)))])

;; ---------------------------------------------------------------------------
;; Definition
;; ---------------------------------------------------------------------------
(defmacro defproperty [name value-type & body-forms]
  "Defines a property that can later be referred to in a `defnode`

   Example:

      (defproperty StringWithDefault g/Str (default \"foo\"))

      (defnode TestNode
        (property a-prop StringWithDefault))"

  (apply ip/def-property-type-descriptor name value-type body-forms))

(declare become)

(defn construct
  "Creates an instance of a node. The node-type must have been
  previously defined via `defnode`.

  The node's properties will all have their default values. The caller
  may pass key/value pairs to override properties.

  A node that has been constructed is not connected to anything and it
  doesn't exist in any graph yet.

  Example:
  (defnode GravityModifier
    (property acceleration Int (default 32))

  (construct GravityModifier :acceleration 16)"
  [node-type & {:as args}]
  (assert (::ctor node-type))
  (let [args-without-properties (set/difference (util/key-set args) (util/key-set (merge (internal-properties node-type) (properties node-type))))]
    (assert (empty? args-without-properties) (str "You have given values for properties " args-without-properties ", but those don't exist on nodes of type " (:name node-type))))
  ((::ctor node-type) args))

(defmacro defnode
  "Given a name and a specification of behaviors, creates a node,
   and attendant functions.

  Allowed clauses are:

  (inherits _symbol_)

  Compose the behavior from the named node type

  (input _symbol_ _schema_ [:array]? [:inject]?)

  Define an input with the name, whose values must match the schema.

  If the :inject flag is present, then this input is available for
  dependency injection.

  If the :array flag is present, then this input can have multiple
  outputs connected to it. Without the :array flag, this input can
  only have one incoming connection from an output.

  (property _symbol_ _property-type_ & _options_)

  Define a property with schema and, possibly, default value and
  constraints.  Property type and options have the same syntax as for
  `dynamo.graph/defproperty`.

  (output _symbol_ _type_ (:cached)? _producer_)

  Define an output to produce values of type. The ':cached' flag is
  optional. _producer_ may be a var that names an fn, or fnk.  It may
  also be a function tail as [arglist] + forms.

  Values produced on an output with the :cached flag will be cached in
  memory until the node is affected by some change in inputs or
  properties. At that time, the cached value will be sent for
  disposal.

  Example (from [[editors.atlas]]):

    (defnode TextureCompiler
      (input    textureset TextureSet)
      (property texture-filename Str (default \"\"))
      (output   texturec Any compile-texturec)))

    (defnode TextureSetCompiler
      (input    textureset TextureSet)
      (property textureset-filename Str (default \"\"))
      (output   texturesetc Any compile-texturesetc)))

    (defnode AtlasCompiler
      (inherit TextureCompiler)
      (inherit TextureSetCompiler))

  This will produce a record `AtlasCompiler`. `defnode` merges the
  behaviors appropriately.


  A trigger may be invoked during transaction execution, when a node of
  the type is touched by the transaction. _symbol_ is a label for the
  trigger. Triggers are inherited, colliding labels are overwritten by
  the descendant.

  (trigger _symbol_ _type_ _action_)

  _type_ is a keyword, one of:

    :added             - The node was added in this transaction.
    :input-connections - One or more inputs to the node were connected to or disconnected from
    :property-touched  - One or more properties on the node were changed.
    :deleted           - The node was deleted in this transaction.

  For :added and :deleted triggers, _action_ is a function of five
  arguments:

    1. The current transaction context.
    2. The new graph as it has been modified during the transaction
    3. The node ID
    4. The label, as a keyword
    5. The trigger type

  The :input-connections and :property-touched triggers each have an
  additional argument, which is a collection of
  labels. For :input-connections, those are the inputs that were
  affected. For :property-touched, those are the properties that were
  modified.

  The trigger returns a collection of additional transaction
  steps. These effects will be applied within the current transaction.

  It is allowed for a trigger to cause changes that activate more
  triggers, up to a limit.  Triggers should not be used for timed
  actions or automatic counters. So they will only cascade until the
  limit `internal.transaction/maximum-retrigger-count` is reached.

    A node may also implement protocols or interfaces, using a syntax
  identical to `deftype` or `defrecord`. A node may implement any
  number of such protocols.

  Every node always implements dynamo.graph/Node."
  [symb & body]
  (let [[symb forms] (ctm/name-with-attributes symb body)
        record-name  (in/classname-for symb)
        ctor-name    (symbol (str 'map-> record-name))]
    `(do
       (declare ~ctor-name ~symb)
       (let [description#    ~(in/node-type-forms symb (concat node-intrinsics forms))
             replacing#      (if-let [x# (and (resolve '~symb) (var-get (resolve '~symb)))]
                               (when (satisfies? NodeType x#) x#))
             all-graphs#     (util/map-vals deref (is/graphs @*the-system*))
             to-be-replaced# (when (and all-graphs# replacing#)
                               (filterv #(= replacing# (node-type %)) (mapcat ig/node-values (vals all-graphs#))))
             ctor#           (fn [args#] (~ctor-name (merge (in/defaults ~symb) args#)))]
         (def ~symb (in/make-node-type (assoc description# :dynamo.graph/ctor ctor#)))
         (in/declare-node-value-function-names '~symb ~symb)
         (in/define-node-record  '~record-name '~symb ~symb)
         (in/define-node-value-functions '~record-name '~symb ~symb)
         (in/define-print-method '~record-name '~symb ~symb)
         (when (< 0 (count to-be-replaced#))
           (transact
            (mapcat (fn [r#]
                      (let [new# (construct ~symb)]
                        (become r# new#)))
                    (mapv node-id to-be-replaced#))))
         (var ~symb)))))

;; ---------------------------------------------------------------------------
;; Transactions
;; ---------------------------------------------------------------------------
(defmacro make-nodes
  "Create a number of nodes in a graph, binding them to local names
   to wire up connections. The resulting code will return a collection
   of transaction steps, including the steps to construct nodes from the
   bindings.

   If the right side of the binding is a node type, it is used directly. If it
   is a vector, it is treated as a node type followed by initial property values.

  Example:

  (make-nodes view [render     AtlasRender
                   scene      scene/SceneRenderer
                   background background/Gradient
                   camera     [c/CameraController :camera (c/make-orthographic)]]
     (g/connect background   :renderable scene :renderables)
     (g/connect atlas-render :renderable scene :renderables))"
  [graph-id binding-expr & body-exprs]
  (assert (vector? binding-expr) "make-nodes requires a vector for its binding")
  (assert (even? (count binding-expr)) "make-nodes requires an even number of forms in binding vector")
  (let [locals (take-nth 2 binding-expr)
        ctors  (take-nth 2 (next binding-expr))
        ids    (repeat (count locals) `(internal.system/next-node-id @*the-system* ~graph-id))]
    `(let [~@(interleave locals ids)]
       (concat
        ~@(map
           (fn [ctor id]
             (list `it/new-node
                   (if (sequential? ctor)
                     `(construct ~@ctor :_id ~id)
                     `(construct  ~ctor :_id ~id))))
           ctors locals)
        ~@body-exprs))))

(defn operation-label
  "Set a human-readable label to describe the current transaction."
  [label]
  (it/label label))

(defn operation-sequence
  "Set a machine-readable label. Successive transactions with the same
  label will be coalesced into a single undo point."
  [label]
  (it/sequence-label label))

(defn make-node
 "Returns the transaction step for creating a new node.
  Needs to be executed within a transact to actually create the node on a graph.

  Example:

  `(transact (make-node world SimpleTestNode))`"
  [graph-id node-type & args]
  (it/new-node (apply construct node-type :_id (is/next-node-id @*the-system* graph-id) args)))

(defn make-node!
  "Creates the transaction step and runs it in a transaction, returning the resulting node.

  Example:

  `(make-node! world SimpleTestNode)`"
  [graph-id node-type & args]
  (first (tx-nodes-added (transact (apply make-node graph-id node-type args)))))

(defn delete-node
 "Returns the transaction step for deleting a node.
  Needs to be executed within a transact to actually create the node on a graph.

  Example:

  `(transact (delete-node node-id))`"
  [node-id]
  (it/delete-node node-id))

(defn delete-node!
  "Creates the transaction step for deleting a node and runs it in a transaction.
  It returns the transaction results, tx-result

  Example:

  `(delete-node! node-id)`"

  [node-id]
  (transact (delete-node node-id)))

(defn connect
  "Make a connection from an output of the source node to an input on the target node.
   Takes effect when a transaction is applied.

  Example:

  `(transact (connect content-node :scalar view-node :first-name))`"
  [source-node-id source-label target-node-id target-label]
  (it/connect source-node-id source-label target-node-id target-label))

(defn connect!
 "Creates the transaction step to make a connection from an output of the source node to an input on the target node
  and applies it in a transaction

  Example:

  `(connect! content-node :scalar view-node :first-name)`"
  [source-node-id source-label target-node-id target-label]
  (transact (connect source-node-id source-label target-node-id target-label)))

(defn disconnect
  "Creates the transaction step to remove a connection from an output of the source node to the input on the target node.
  Note that there might still be connections between the two nodes,
  from other outputs to other inputs.  Takes effect when a transaction
  is applied with transact.

  Example:

  (`transact (disconnect aux-node :scalar view-node :last-name))`"
  [source-node-id source-label target-node-id target-label]
  (it/disconnect source-node-id source-label target-node-id target-label))

(defn disconnect!
 "Creates the transaction step to remove a connection from an output of the source node to the input on the target node.
  It also applies it in transaction, returning the transaction result, (tx-result).
  Note that there might still be connections between the two nodes,
  from other outputs to other inputs.

  Example:

  `(disconnect aux-node :scalar view-node :last-name)`"
  [source-node-id source-label target-node-id target-label]
  (transact (disconnect source-node-id source-label target-node-id target-label)))

(defn become
  "Creates the transaction step to turn one kind of node into another, in a transaction. All properties and their values
   will be carried over from source-node to new-node. The resulting node will still have
   the same node-id.

  Example:

  `(transact (become counter (construct StringSource))`

   Any input or output connections to labels that exist on both
  source-node and new-node will continue to exist. Any connections to
  labels that don't exist on new-node will be disconnected in the same
  transaction."
  [node-id new-node]
  (it/become node-id new-node))

(defn become!
  "Creates the transaction step to turn one kind of node into another and applies it in a transaction. All properties and their values
   will be carried over from source-node to new-node. The resulting node will still have
   the same node-id.  Returns the transaction-result, (tx-result).

  Example:

  `(become! counter (construct StringSource)`

   Any input or output connections to labels that exist on both
  source-node and new-node will continue to exist. Any connections to
  labels that don't exist on new-node will be disconnected in the same
  transaction."
  [source-node new-node]
  (transact (become source-node new-node)))

(defn set-property
  "Creates the transaction step to assign a value to a node's property (or properties) value(s).  It will take effect when the transaction
  is applies in a transact.

  Example:

  `(transact (set-property root-id :touched 1))`"
  [node-id & kvs]
  (mapcat
   (fn [[p v]]
     (it/update-property node-id p (constantly v) []))
   (partition-all 2 kvs)))

(defn set-property!
  "Creates the transaction step to assign a value to a node's property (or properties) value(s) and applies it in a transaction.
  It returns the result of the transaction, (tx-result).

  Example:

  `(set-property! root-id :touched 1)`"
  [node-id & kvs]
  (transact (apply set-property node-id kvs)))

(defn update-property
  "Create the transaction step to apply a function to a node's property in a transaction. The
  function f will be invoked as if by (apply f current-value args).  It will take effect when the transaction is
  applied in a transact.

  Example:

  `(transact (g/update-property node-id :int-prop inc))`"
  [node-id p f & args]
  (it/update-property node-id p f args))

(defn update-property!
  "Create the transaction step to apply a function to a node's property in a transaction. Then it applies the transaction.
   The function f will be invoked as if by (apply f current-value args).  The transaction results, (tx-result), are returned.

  Example:

  `g/update-property! node-id :int-prop inc)`"
  [node-id p f & args]
  (transact (apply update-property node-id p f args)))

(defn set-graph-value
 "Create the transaction step to attach a named value to a graph. It will take effect when the transaction is
  applied in a transact.

  Example:

  `(transact (set-graph-value 0 :string-value \"A String\"))`"
  [graph-id k v]
  (it/update-graph graph-id assoc [k v]))

(defn set-graph-value!
  "Create the transaction step to attach a named value to a graph and applies the transaction.
  Returns the transaction result, (tx-result).

  Example:

  (set-graph-value! 0 :string-value \"A String\")"
  [graph-id k v]
  (transact (set-graph-value graph-id k v)))

(defn invalidate
 "Creates the transaction step to invalidate all the outputs of the node.  It will take effect when the transaciton is
  applied in a transact.

  Example:

  `(transact (invalidate node-id))`"
  [node-id]
  (it/invalidate node-id))

(defn invalidate!
 "Creates the transaction step to invalidate all the outputs of the node and applies the transaction.

  Example:

  `(invalidate! node-id)`"
  [node-id]
  (transact (invalidate node-id)))

(defn mark-defective
  "Creates the transaction step to mark a node as _defective_.
  This means that all the outputs of the node will be replace by the defective value.
  It will take effect when the transaction is applied in a transact.

  Example:

  `(transact (mark-defective node-id (g/error \"Resource Not Found\")))`"
  [node-id defective-value]
  (let [node-type (node-type* node-id)
        outputs   (keys (gt/transforms node-type))
        externs   (gt/externs node-type)]
    (list
     (set-property node-id :_output-jammers
                   (zipmap (remove externs outputs)
                           (repeat (always defective-value))))
     (invalidate node-id))))

(defn mark-defective!
  "Creates the transaction step to mark a node as _defective_.
  This means that all the outputs of the node will be replace by the defective value.
  It will take effect when the transaction is applied in a transact.

  Example:

  `(mark-defective! node-id (g/error \"Resource Not Found\"))`"
  [node-id defective-value]
  (transact (mark-defective node-id defective-value)))

;; ---------------------------------------------------------------------------
;; Values
;; ---------------------------------------------------------------------------
(defn node-value
  "Pull a value from a node's output, identified by `label`.
  The value may be cached or it may be computed on demand. This is
  transparent to the caller.

  This uses the \"current\" value of the node and its output.
  That means the caller will receive a value consistent with the most
  recently committed transaction.

  The label must exist as a defined transform on the node, or an
  AssertionError will result.

  Example:

  `(node-value node-id :chained-output)`"
  ([node-id label]
   (in/node-value (now) (cache) node-id label))
  ([basis node-id label]
   (in/node-value basis (cache) node-id label))
  ([basis cache node-id label]
   (when (instance? Node node-id)
     (try (throw (ex-info "Pass node IDs instead of node objects!" {}))
          (catch Exception e (.printStackTrace e))))
   (in/node-value basis cache node-id label)))

(defn graph-value
  "Returns the graph from the system given a graph-id and key.  It returns the graph at the point in time of the bais, if provided.
  If the basis is not provided, it will take it from the current point of time in the system.

  Example:

  `(graph-value (node->graph-id view) :renderer)`"
  ([graph-id k]
   (graph-value (now) graph-id k))
  ([basis graph-id k]
   (get-in basis [:graphs graph-id k])))

;; ---------------------------------------------------------------------------
;; Interrogating the Graph
;; ---------------------------------------------------------------------------
(defn- arcs->tuples
  [arcs]
  (util/project arcs [:source :sourceLabel :target :targetLabel]))

(defn inputs
  "Return the inputs to this node. Returns a collection like
  [[source-id output target-id input] [source-id output target-id input]...].

  If there are no inputs connected, returns an empty collection."
  ([node-id]       (inputs (now) node-id))
  ([basis node-id] (arcs->tuples (gt/arcs-by-tail basis node-id))))

(defn outputs
  "Return the outputs from this node. Returns a collection like
  [[source-id output target-id input] [source-id output target-id input]...].

  If there are no outputs connected, returns an empty collection."
  ([node-id]       (outputs (now) node-id))
  ([basis node-id] (arcs->tuples (gt/arcs-by-head basis node-id))))

(defn node-feeding-into
  "Find the one-and-only node ID that sources this input on this node.
   Should you use this on an input label with multiple connections,
   the result is undefined."
  ([node-id label]
   (node-feeding-into (now) node-id label))
  ([basis node-id label]
   (ffirst (sources basis node-id label))))

(defn sources-of
  "Find the [node-id label] pairs for all connections into the given
  node's input label. The result is a sequence of pairs."
  ([node-id label]
   (sources-of (now) node-id label))
  ([basis node-id label]
   (gt/sources basis node-id label)))

(defn invalidate!
  "Invalidate the given outputs and _everything_ that could be
  affected by them. Outputs are specified as pairs of [node-id label]
  for both the argument and return value."
  ([outputs]
   (invalidate! (now) outputs))
  ([basis outputs]
    (c/cache-invalidate (cache) (dependencies basis outputs))))

(defn node-instance?
  "Returns true if the node is a member of a given type, including
   supertypes."
  [type node-id]
  (let [node-ty    (node-type* node-id)
        supertypes (supertypes node-ty)
        all-types  (into #{node-ty} supertypes)]
    (all-types type)))


;; ---------------------------------------------------------------------------
;; Support for serialization, copy & paste, and drag & drop
;; ---------------------------------------------------------------------------
(defrecord Endpoint [node-id label])

(def ^:private node-type-writer
  (transit/write-handler
   (constantly "node-type")
   (fn [t] (select-keys t [:name]))))

(def ^:private write-handlers
  (merge (transit/record-write-handlers Endpoint)
         {internal.node.NodeTypeImpl node-type-writer}))

(def ^:private node-type-reader
  (transit/read-handler
   (fn [{:keys [name]}]
     (var-get (resolve (symbol name))))))

(def ^:private read-handlers
  (merge (transit/record-read-handlers Endpoint)
         {"node-type" node-type-reader}))

(defn read-graph
  "Read a graph fragment from a string. Returns a fragment suitable
  for pasting."
  ([s] (read-graph s {}))
  ([s extra-handlers]
   (let [handlers (merge read-handlers extra-handlers)
         reader   (transit/reader (StringBufferInputStream. s) :json {:handlers handlers})]
     (transit/read reader))))

(defn write-graph
  "Return a serialized string representation of the graph fragment."
  ([fragment]
   (write-graph fragment {}))
  ([fragment extra-handlers]
   (let [handlers (merge write-handlers extra-handlers)
         out      (ByteArrayOutputStream. 4096)
         writer   (transit/writer out :json {:handlers handlers})]
     (transit/write writer fragment)
     (.toString out))))

(defn- all-sources [basis node-id]
  (gt/arcs-by-tail basis node-id))

(defn- in-same-graph? [graph-id node-id]
  (= graph-id (node-id->graph-id node-id)))

(defn- predecessors [basis ^Arc arc]
  (let [sid (.source arc)
        graph-id (node-id->graph-id sid)
        all-arcs (mapcat #(all-sources basis (.target ^Arc %)) (gt/arcs-by-tail basis sid))]
    (filterv #(in-same-graph? graph-id (.source ^Arc %)) all-arcs)))

(defn- input-traverse [basis pred root-ids]
  (ig/pre-traverse basis (into [] (mapcat #(all-sources basis %) root-ids)) pred))

(defn- serialize-node [node]
  (let [all-node-properties    (select-keys node (keys (-> node gt/node-type gt/properties)))
        properties-without-fns (util/filterm (comp not fn? val) all-node-properties)]
    {:serial-id (gt/node-id node)
     :node-type (gt/node-type node)
     :properties properties-without-fns}))

(defn- default-write-handler [node label]
  (Endpoint. (gt/node-id node) label))

(defn- default-read-handler [id-dictionary endpoint]
  [(get id-dictionary (:node-id endpoint)) (:label endpoint)])

(defn- lookup-handler [handlers node not-found]
  (let [all-types (conj (supertypes (node-type node)) (node-type node))]
    (or (some #(get handlers %) all-types) not-found)))

(defn- serialize-arc [basis write-handlers arc]
  (let [[pid plabel]  (gt/head arc)
        [cid clabel]  (gt/tail arc)
        pnode         (ig/node-by-id-at basis pid)
        cnode         (ig/node-by-id-at basis cid)
        write-handler (lookup-handler write-handlers pnode default-write-handler)]
   [(write-handler pnode plabel) (default-write-handler cnode clabel)]))

(defn- guard-arc [f g]
  (util/guard
   (fn [basis ^Arc arc]
     (f (.source arc)))
   g))

(defn copy
  "Given a vector of root ids, and an options map that can contain a `:continue?` predicate and a `write-handlers` map, returns a copy graph fragment that can be serialized or pasted.  Works on the current basis, if a basis is not provided.

  Example:

  `(g/copy root-ids {:continue? (comp not resource?)
                    :write-handlers {ResourceNode make-reference}})`"
  ([root-ids opts]
   (copy (now) root-ids opts))
  ([basis root-ids {:keys [continue? write-handlers] :or {continue? (constantly true)} :as opts}]
   (let [fragment-arcs     (input-traverse basis (guard-arc continue? predecessors) root-ids)
         fragment-node-ids (into #{} (concat (map #(.target %) fragment-arcs) (map #(.source %) fragment-arcs)))
         fragment-nodes    (map #(ig/node-by-id-at basis %) (filter continue? fragment-node-ids))
         root-nodes        (map #(ig/node-by-id-at basis %) root-ids)]
     {:roots root-ids
      :nodes (mapv serialize-node (into #{} (concat root-nodes fragment-nodes)))
      :arcs  (mapv #(serialize-arc basis write-handlers %) fragment-arcs)})))

(defn- deserialize-node
  [graph-id {:keys [node-type properties] :as node-spec}]
  (apply make-node graph-id node-type (mapcat identity properties)))

(defn- deserialize-arc
  [id-dictionary read-handlers [source target]]
  (let [read-handler            (get read-handlers (class source) (partial default-read-handler id-dictionary))
        [real-src-id src-label] (read-handler source)
        [real-tgt-id tgt-label] (default-read-handler id-dictionary target)]
    (assert real-src-id (str "Don't know how to resolve " (pr-str source) " to a node. You might need to put a :read-handler on the call to dynamo.graph/paste"))
    (connect real-src-id src-label real-tgt-id tgt-label)))

(defn paste
  "Given a `graph-id` and graph fragment from copying, provides the transaction data to create the nodes on the graph and connect all the new nodes together with the same arcs in the fragment.  It will take effect when it is applied with a transact.

  Example:

  `(g/paste (graph project) fragment {:read-handlers {ResourceReference (partial resolve-reference workspace project)}})`
"
  ([graph-id fragment opts]
   (paste (now) graph-id fragment opts))
  ([basis graph-id fragment {:keys [read-handlers] :as opts}]
   (let [node-txs      (vec (mapcat #(deserialize-node graph-id %) (:nodes fragment)))
         nodes         (map (comp :_id :node) node-txs)
         id-dictionary (zipmap (map :serial-id (:nodes fragment)) nodes)
         connect-txs   (mapcat #(deserialize-arc id-dictionary read-handlers %) (:arcs fragment))]
     {:root-node-ids (map #(get id-dictionary %) (:roots fragment))
      :nodes         nodes
      :tx-data       (into node-txs connect-txs)})))

;; ---------------------------------------------------------------------------
;; Boot, initialization, and facade
;; ---------------------------------------------------------------------------
(defn initialize!
  "Set up the initial system including graphs, caches, and dispoal queues"
  [config]
  (reset! *the-system* (is/make-system config)))

(defn dispose-pending!
  "Empties the dispoal queues of all values"
  []
  (dispose/dispose-pending *the-system*))

(defn- make-graph
  [& {:as options}]
  (let [volatility (:volatility options 0)]
    (assoc (ig/empty-graph) :_volatility volatility)))

(defn make-graph!
  "Create a new graph in the system with optional values of `:history` and `:volatility`. If no
  options are provided, the history ability is false and the volatility is 0

  Example:

  `(make-graph! :history true :volatility 1)`"
  [& {:keys [history volatility] :or {history false volatility 0}}]
  (let [g (assoc (ig/empty-graph) :_volatility volatility)
        s (swap! *the-system* (if history is/attach-graph-with-history is/attach-graph) g)]
    (:last-graph s)))

(defn last-graph-added
  "Retuns the last graph added to the system"
  []
  (is/last-graph @*the-system*))

(defn delete-graph!
  "Given a `graph-id`, deletes it from the system

  Example:

  ` (delete-graph! agraph-id)`"
  [graph-id]
  (when-let [graph (is/graph @*the-system* graph-id)]
    (transact (mapv it/delete-node (ig/node-ids graph)))
    (swap! *the-system* is/detach-graph graph-id)))

(defn undo!
  "Given a `graph-id` resets the graph back to the last _step_ in time.

  Example:

  (undo gid)"
  [graph-id]
  (let [snapshot @*the-system*]
    (when-let [ks (is/undo-history (is/graph-history snapshot graph-id) snapshot)]
      (invalidate! ks))))

(defn has-undo?
  "Returns true/false if a `graph-id` has an undo available"
  [graph-id]
  (let [undo-stack (is/undo-stack (is/graph-history @*the-system* graph-id))]
    (not (empty? undo-stack))))

(defn redo!
  "Given a `graph-id` reverts an undo of the graph

  Example: `(redo gid)`"
  [graph-id]
  (let [snapshot @*the-system*]
    (when-let [ks (is/redo-history (is/graph-history snapshot graph-id) snapshot)]
      (invalidate! ks))))

(defn has-redo?
  "Returns true/false if a `graph-id` has an redo available"
  [graph-id]
  (let [redo-stack (is/redo-stack (is/graph-history @*the-system* graph-id))]
    (not (empty? redo-stack))))

(defn reset-undo!
  "Given a `graph-id`, clears all undo history for the graph

  Example:
  `(reset-undo! gid)`"
  [graph-id]
  (is/clear-history (is/graph-history @*the-system* graph-id)))

(defn cancel!
  "Given a `graph-id` and a `sequence-id` _cancels_ any sequence of undos on the graph as
  if they had never happened in the history.

  Example:
  `(cancel! gid :a)`"
  [graph-id sequence-id]
  (let [snapshot @*the-system*]
    (when-let [ks (is/cancel (is/graph-history snapshot graph-id) snapshot sequence-id)]
      (invalidate! ks))))
