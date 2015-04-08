(ns dynamo.graph
  (:require [clojure.set :as set]
            [clojure.tools.macro :as ctm]
            [dynamo.node :as dn]
            [dynamo.system :as ds]
            [dynamo.types :as t]
            [internal.graph :as ig]
            [internal.node :as in]
            [internal.system :as is]
            [internal.transaction :as it]
            [potemkin.namespaces :refer [import-vars]]))

(import-vars [plumbing.core <- ?> ?>> aconcat as->> assoc-when conj-when cons-when count-when defnk dissoc-in distinct-by distinct-fast distinct-id fn-> fn->> fnk for-map frequencies-fast get-and-set! grouped-map if-letk indexed interleave-all keywordize-map lazy-get letk map-from-keys map-from-vals map-keys map-vals mapply memoized-fn millis positions rsort-by safe-get safe-get-in singleton sum swap-pair! unchunk update-in-when when-letk])

(import-vars [internal.graph.types tempid nref->gid nref->nid node-by-id node-by-property sources targets connected? dependencies query Node node-id node-type transforms transform-types properties inputs injectable-inputs input-types outputs cached-outputs input-dependencies NodeType supertypes interfaces protocols method-impls triggers transforms' transform-types' properties' inputs' injectable-inputs' outputs' cached-outputs' event-handlers' input-dependencies' MessageTarget process-one-event])

(let [gid ^java.util.concurrent.atomic.AtomicInteger (java.util.concurrent.atomic.AtomicInteger. 0)]
  (defn next-graph-id [] (.getAndIncrement gid)))

;; ---------------------------------------------------------------------------
;; Definition
;; ---------------------------------------------------------------------------
(defmacro defnode
  "Given a name and a specification of behaviors, creates a node,
   and attendant functions.

  Allowed clauses are:

  (inherits _symbol_)

  Compose the behavior from the named node type

  (input _symbol_ _schema_)

  Define an input with the name, whose values must match the schema.

  (property _symbol_ _property-type_ & _options_)

  Define a property with schema and, possibly, default value and
  constraints.  Property type and options have the same syntax as for
  `dynamo.property/defproperty`.

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
      (property texture-filename t/Str (default \"\"))
      (output   texturec t/Any compile-texturec)))

    (defnode TextureSetCompiler
      (input    textureset TextureSet)
      (property textureset-filename t/Str (default \"\"))
      (output   texturesetc t/Any compile-texturesetc)))

    (defnode AtlasCompiler
      (inherit TextureCompiler)
      (inherit TextureSetCompiler))

  This will produce a record `AtlasCompiler`. `defnode` merges the
  behaviors appropriately.

  Every node can receive messages. The node declares message handlers
  with a special syntax:

  (trigger _symbol_ _type_ _action_)

  A trigger is invoked during transaction execution, when a node of
  the type is touched by the transaction. _symbol_ is a label for the
  trigger. Triggers are inherited, colliding labels are overwritten by
  the descendant.

  _type_ is a keyword, one of:

    :added             - The node was added in this transaction.
    :input-connections - One or more inputs to the node were connected to or disconnected from
    :property-touched  - One or more properties on the node were changed.
    :deleted           - The node was deleted in this transaction.

  For :added and :deleted triggers, _action_ is a function of five
  arguments:

    1. The current transaction context.
    2. The new graph as it has been modified during the transaction
    3. The node itself
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

  (on _message_type_ _form_)

  The form will be evaluated inside a transactional body. This means
  that it can use the special clauses to create nodes, change
  connections, update properties, and so on.

  A node definition allows any number of 'on' clauses.

  A node may also implement protocols or interfaces, using a syntax
  identical to `deftype` or `defrecord`. A node may implement any
  number of such protocols.

  Every node always implements dynamo.types/Node.

  If there are any event handlers defined for the node type, then it
  will also implement MessageTarget."
  [symb & body]
  (let [[symb forms] (ctm/name-with-attributes symb body)
        record-name  (in/classname-for symb)
        ctor-name    (symbol (str 'map-> record-name))]
    `(do
       (declare ~ctor-name ~symb)
       (let [description#    ~(in/node-type-sexps symb (concat dn/node-intrinsics forms))
             replacing#      (if-let [x# (and (resolve '~symb) (var-get (resolve '~symb)))]
                               (when (satisfies? NodeType x#) x#))
             all-graphs#     (map-vals deref (is/graphs @ds/*the-system*))
             to-be-replaced# (when (and all-graphs# replacing#)
                               (filterv #(= replacing# (node-type %)) (mapcat ig/node-values (vals all-graphs#))))
             ctor#           (fn [args#] (~ctor-name (merge (in/defaults ~symb) args#)))]
         (def ~symb (in/make-node-type (assoc description# :dynamo.node/ctor ctor#)))
         (in/define-node-record  '~record-name '~symb ~symb)
         (in/define-print-method '~record-name '~symb ~symb)
         (when (< 0 (count to-be-replaced#))
           (ds/transact
            (mapcat (fn [r#]
                      (let [new# (dn/construct ~symb)]
                        (g/become r# new#)))
                    to-be-replaced#)))
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
  [gid binding-expr & body-exprs]
  (assert (vector? binding-expr) "make-nodes requires a vector for its binding")
  (assert (even? (count binding-expr)) "make-nodes requires an even number of forms in binding vector")
  (let [locals (take-nth 2 binding-expr)
        ctors  (take-nth 2 (next binding-expr))
        ids    (repeat (count locals) `(tempid ~gid))]
    `(let [~@(interleave locals ids)]
       (concat
        ~@(map
           (fn [ctor id]
             (if (sequential? ctor)
               `(g/make-node ~gid ~@ctor :_id ~id)
               `(g/make-node ~gid ~ctor :_id ~id)))
           ctors locals)
        ~@body-exprs))))

(defmacro graph-with-nodes
  [volatility binding-expr & body-exprs]
  `(let [g# (ds/attach-graph (g/make-graph :volatility ~volatility))]
     (ds/transact
      (make-nodes g# ~binding-expr ~@body-exprs))
     g#))

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
  [gid node-type & args]
  (it/new-node (apply dn/construct node-type :_id (tempid gid) args)))

(defn delete-node
  "Remove a node from the world."
  [n]
  (it/delete-node n))

(defn connect
  "Make a connection from an output of the source node to an input on the target node.
   Takes effect when a transaction is applied."
  [source-node source-label target-node target-label]
  (it/connect source-node source-label target-node target-label))

(defn disconnect
  "Remove a connection from an output of the source node to the input on the target node.
  Note that there might still be connections between the two nodes,
  from other outputs to other inputs.  Takes effect when a transaction
  is applied."
  [source-node source-label target-node target-label]
  (it/disconnect source-node source-label target-node target-label))

(defn become
  "Turn one kind of node into another, in a transaction. All properties and their values
   will be carried over from source-node to new-node. The resulting node will still have
   the same node-id.

   Any input or output connections to labels that exist on both
  source-node and new-node will continue to exist. Any connections to
  labels that don't exist on new-node will be disconnected in the same
  transaction."
  [source-node new-node]
  (it/become source-node new-node))

(defn set-property
  "Assign a value to a node's property (or properties) value(s) in a
  transaction."
  [n & kvs]
  (mapcat
   (fn [[p v]]
     (it/update-property n p (constantly v) []))
   (partition-all 2 kvs)))

(defn update-property
  "Apply a function to a node's property in a transaction. The
  function f will be invoked as if by (apply f current-value args)"
  [n p f & args]
  (it/update-property n p f args))

;; ---------------------------------------------------------------------------
;; Values
;; ---------------------------------------------------------------------------
(defn node
  "Get a node, given a node ID."
  [node-id]
  (node-by-id (ds/now) node-id))

(defn refresh
  [n]
  (node-by-id (ds/now) (node-id n)))

(defn node-value
  "Pull a value from a node's output, identified by `label`.
  The value may be cached or it may be computed on demand. This is
  transparent to the caller.

  This uses the \"current\" value of the node and its output.
  That means the caller will receive a value consistent with the most
  recently committed transaction.

  The label must exist as a defined transform on the node, or an
  AssertionError will result."
  ([node label]
   (in/node-value (ds/now) (ds/cache) node label))
  ([basis node label]
   (in/node-value basis (ds/cache) node label))
  ([basis cache node label]
   (in/node-value basis cache node label)))

(defn graph-id
  [g]
  (:_gid g))

(defn make-graph
  [& {:as options}]
  (let [volatility (:volatility options 0)]
    (assoc (ig/empty-graph) :_volatility volatility)))

;; ---------------------------------------------------------------------------
;; Interrogating the Graph
;; ---------------------------------------------------------------------------
(defn node-feeding-into
  "Find the one-and-only node that sources this input on this node.
   Should you use this on an input label with multiple connections,
   the result is undefined."
  [basis node label]
  (node basis
        (ffirst (sources basis (node-id node) label))))

(defn sources-of
  "Find the [node label] pairs for all connections into the given
  node's input label. The result is a sequence of pairs."
  [basis node label]
  (map
   (fn [[node-id label]]
     [(node-by-id basis node-id) label])
   (sources basis (node-id node) label)))

(defn nodes-consuming
  "Find the [node label] pairs for all connections reached from the
  given node's input label.  The result is a sequence of pairs."
  [basis node label]
  (map
   (fn [[node-id label]]
     [(node-by-id basis node-id) label])
   (targets basis (node-id node) label)))

(defn node-consuming
  "Like nodes-consuming, but only returns the first result."
  [basis node label]
  (ffirst (nodes-consuming basis node label)))

(defn output-dependencies
  "Find all the outputs that could be affected by a change in the
   given outputs.  Outputs are specified as pairs of [node-id label]
   for both the argument and return value."
  [basis outputs]
  (dependencies basis outputs))
