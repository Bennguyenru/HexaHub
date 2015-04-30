(ns dynamo.defnode-test
  (:require [clojure.test :refer :all]
            [dynamo.graph :as g]
            [dynamo.graph.test-support :refer [tx-nodes with-clean-system]]
            [dynamo.property :as dp]
            [dynamo.types :as t]
            [internal.node :as in])
  (:import clojure.lang.Compiler$CompilerException))

(defn substitute-value-fn [& _] "substitute value")

(deftest nodetype
  (testing "is created from a data structure"
    (is (satisfies? g/NodeType (in/make-node-type {:inputs {:an-input t/Str}}))))
  (testing "supports direct inheritance"
    (let [super-type (in/make-node-type {:inputs {:an-input t/Str}})
          node-type  (in/make-node-type {:supertypes [super-type]})]
      (is (= [super-type] (g/supertypes node-type)))
      (is (= {:an-input t/Str} (g/inputs' node-type)))))
  (testing "supports multiple inheritance"
    (let [super-type (in/make-node-type {:inputs {:an-input t/Str}})
          mixin-type (in/make-node-type {:inputs {:mixin-input t/Str}})
          node-type  (in/make-node-type {:supertypes [super-type mixin-type]})]
      (is (= [super-type mixin-type] (g/supertypes node-type)))
      (is (= {:an-input t/Str :mixin-input t/Str} (g/inputs' node-type)))))
  (testing "supports inheritance hierarchy"
    (let [grandparent-type (in/make-node-type {:inputs {:grandparent-input t/Str}})
          parent-type      (in/make-node-type {:supertypes [grandparent-type] :inputs {:parent-input t/Str}})
          node-type        (in/make-node-type {:supertypes [parent-type]})]
      (is (= {:parent-input t/Str :grandparent-input t/Str} (g/inputs' node-type))))))

(g/defnode BasicNode)

(deftest basic-node-definition
  (is (satisfies? g/NodeType BasicNode))
  (is (= BasicNode (g/node-type (g/construct BasicNode)))))

(g/defnode IRootNode)
(g/defnode ChildNode
  (inherits IRootNode))
(g/defnode GChild
  (inherits ChildNode))
(g/defnode MixinNode)
(g/defnode GGChild
  (inherits ChildNode)
  (inherits MixinNode))

(deftest inheritance
 (is (= [IRootNode]           (g/supertypes ChildNode)))
 (is (= ChildNode             (g/node-type (g/construct ChildNode))))
 (is (= [ChildNode]           (g/supertypes GChild)))
 (is (= GChild                (g/node-type (g/construct GChild))))
 (is (= [ChildNode MixinNode] (g/supertypes GGChild)))
 (is (= GGChild               (g/node-type (g/construct GGChild)))))

(g/defnode OneInputNode
  (input an-input t/Str))

(g/defnode InheritedInputNode
  (inherits OneInputNode))

(g/defnode InjectableInputNode
  (input for-injection t/Int :inject))

(g/defnode SubstitutingInputNode
  (input substitute-fn  String :substitute substitute-value-fn)
  (input var-to-fn      String :substitute #'substitute-value-fn)
  (input inline-literal String :substitute "inline literal"))

(deftest nodes-can-have-inputs
  (testing "labeled input"
    (let [node (g/construct OneInputNode)]
      (is (:an-input (g/inputs' OneInputNode)))
      (is (= t/Str (:an-input (g/input-types node))))
      (is (:an-input (g/inputs node)))))
  (testing "inherited input"
    (let [node (g/construct InheritedInputNode)]
      (is (:an-input (g/inputs' InheritedInputNode)))
      (is (= t/Str (:an-input (g/input-types node))))
      (is (:an-input (g/inputs node)))))
  (testing "inputs can be flagged for injection"
    (let [node (g/construct InjectableInputNode)]
      (is (:for-injection (g/injectable-inputs' InjectableInputNode)))))
  (testing "inputs can have substitute values to use when there is no source"
    (is (= substitute-value-fn   (g/substitute-for' SubstitutingInputNode :substitute-fn)))
    (is (= #'substitute-value-fn (g/substitute-for' SubstitutingInputNode :var-to-fn)))
    (is (= "inline literal"      (g/substitute-for' SubstitutingInputNode :inline-literal)))
    (let [node (g/construct SubstitutingInputNode)]
      (is (= substitute-value-fn   (g/substitute-for node :substitute-fn)))
      (is (= #'substitute-value-fn (g/substitute-for node :var-to-fn)))
      (is (= "inline literal"      (g/substitute-for node :inline-literal))))))

(definterface MarkerInterface)
(definterface SecondaryInterface)

(g/defnode MarkerInterfaceNode
  MarkerInterface)

(g/defnode MarkerAndSecondaryInterfaceNode
  MarkerInterface
  SecondaryInterface)

(g/defnode InheritedInterfaceNode
 (inherits MarkerInterfaceNode))

(definterface OneMethodInterface
  (oneMethod [^Long x]))

(defn- private-function [x] [x :ok])

(g/defnode OneMethodNode
  (input an-input t/Str)

  OneMethodInterface
  (oneMethod [this ^Long x] (private-function x)))

(g/defnode InheritedMethodNode
  (inherits OneMethodNode))

(g/defnode OverriddenMethodNode
  (inherits OneMethodNode)

  OneMethodInterface
  (oneMethod [this x] [x :overridden]))

(deftest nodes-can-implement-interfaces
  (testing "implement a single interface"
    (let [node (g/construct MarkerInterfaceNode)]
      (is (= #{`MarkerInterface} (g/interfaces MarkerInterfaceNode)))
      (is (instance? MarkerInterface node))
      (is (not (instance? SecondaryInterface node)))))
  (testing "implement two interfaces"
    (let [node (g/construct MarkerAndSecondaryInterfaceNode)]
      (is (= #{`MarkerInterface `SecondaryInterface} (g/interfaces MarkerAndSecondaryInterfaceNode)))
      (is (instance? MarkerInterface node))
      (is (instance? SecondaryInterface node))))
  (testing "implement interface with methods"
    (let [node (g/construct OneMethodNode)]
      (is (instance? OneMethodInterface node))
      (is (= [5 :ok] (.oneMethod node 5)))))
  (testing "interface inheritance"
    (let [node (g/construct InheritedInterfaceNode)]
      (is (instance? MarkerInterface node)))
    (let [node (g/construct InheritedMethodNode)]
      (is (instance? OneMethodInterface node))
      (is (= [5 :ok] (.oneMethod node 5))))
    (let [node (g/construct OverriddenMethodNode)]
      (is (instance? OneMethodInterface node))
      (is (= [42 :overridden] (.oneMethod node 42)))))
  (testing "preserves type hints"
    (let [[arglist _] (get (g/method-impls OneMethodNode) 'oneMethod)]
      (is (= ['this 'x] arglist))
      (is (= {:tag 'Long} (meta (second arglist)))))))

(defprotocol LocalProtocol
  (protocol-method [this x y]))

(g/defnode LocalProtocolNode
  LocalProtocol
  (protocol-method [this x y] [:ok x y]))

(g/defnode InheritedLocalProtocol
  (inherits LocalProtocolNode))

(g/defnode InheritedProtocolOverride
  (inherits LocalProtocolNode)
  (protocol-method [this x y] [:override-ok x y]))

(deftest nodes-can-support-protocols
  (testing "support a single protocol"
    (let [node (g/construct LocalProtocolNode)]
      (is (= #{`LocalProtocol} (g/protocols LocalProtocolNode)))
      (is (satisfies? LocalProtocol node))
      (is (= [:ok 5 10] (protocol-method node 5 10))))
    (let [node (g/construct InheritedLocalProtocol)]
      (is (satisfies? LocalProtocol node))
      (is (= [:ok 5 10] (protocol-method node 5 10))))
    (let [node (g/construct InheritedProtocolOverride)]
      (is (satisfies? LocalProtocol node))
      (is (= [:override-ok 5 10] (protocol-method node 5 10))))))

(g/defnode SinglePropertyNode
  (property a-property t/Str))

(g/defnode TwoPropertyNode
 (property a-property t/Str (default "default value"))
 (property another-property t/Int))

(g/defnode InheritedPropertyNode
  (inherits TwoPropertyNode)
  (property another-property t/Int (default -1)))

(deftest nodes-can-include-properties
  (testing "a single property"
    (let [node (g/construct SinglePropertyNode)]
      (is (:a-property (g/properties' SinglePropertyNode)))
      (is (:a-property (g/properties node)))
      (is (some #{:a-property} (keys node)))))

  (testing "two properties"
    (let [node (g/construct TwoPropertyNode)]
      (is (:a-property       (g/properties' TwoPropertyNode)))
      (is (:another-property (g/properties node)))
      (is (:a-property       (g/properties node)))
      (is (some #{:a-property}       (keys node)))
      (is (some #{:another-property} (keys node)))))

  (testing "properties can have defaults"
    (let [node (g/construct TwoPropertyNode)]
      (is (= "default value" (:a-property node)))))

  (testing "properties are inherited"
    (let [node (g/construct InheritedPropertyNode)]
      (is (:a-property       (g/properties' InheritedPropertyNode)))
      (is (:another-property (g/properties node)))
      (is (:a-property       (g/properties node)))
      (is (some #{:a-property}       (keys node)))
      (is (some #{:another-property} (keys node)))))

  (testing "property defaults can be inherited or overridden"
    (let [node (g/construct InheritedPropertyNode)]
      (is (= "default value" (:a-property node)))
      (is (= -1              (:another-property node)))))

  (testing "output dependencies include properties"
    (let [node (g/construct InheritedPropertyNode)]
      (is (= {:another-property #{:properties :another-property}
              :a-property #{:properties :a-property}}
             (g/input-dependencies node)))))

  (testing "do not allow a property to shadow an input of the same name"
    (is (thrown? AssertionError
                 (eval '(dynamo.graph/defnode ReflexiveFeedbackPropertySingularToSingular
                          (property port dynamo.types/Keyword (default :x))
                          (input port dynamo.types/Keyword :inject)))))))

(g/defnk string-production-fnk [this integer-input] "produced string")
(g/defnk integer-production-fnk [this project] 42)
(defn schemaless-production-fn [this & _] "schemaless fn produced string")


(dp/defproperty IntegerProperty t/Int (validate positive? (comp not neg?)))

(g/defnode MultipleOutputNode
  (input integer-input t/Int)
  (input string-input t/Str)

  (output string-output         t/Str                                                 string-production-fnk)
  (output integer-output        IntegerProperty                                       integer-production-fnk)
  (output cached-output         t/Str           :cached                               string-production-fnk)
  (output inline-string         t/Str                                                 (g/fnk [string-input] "inline-string"))
  (output schemaless-production t/Str                                                 schemaless-production-fn))

(g/defnode AbstractOutputNode
  (output abstract-output t/Str :abstract))

(g/defnode InheritedOutputNode
  (inherits MultipleOutputNode)
  (inherits AbstractOutputNode)

  (output abstract-output t/Str string-production-fnk))

(g/defnode TwoLayerDependencyNode
  (property a-property t/Str)

  (output direct-calculation t/Str (g/fnk [a-property] a-property))
  (output indirect-calculation t/Str (g/fnk [direct-calculation] direct-calculation)))

(deftest nodes-can-have-outputs
  (testing "basic output definition"
    (let [node (g/construct MultipleOutputNode)]
      (doseq [expected-output [:string-output :integer-output :cached-output :inline-string :schemaless-production]]
        (is (get (g/outputs' MultipleOutputNode) expected-output))
        (is (get (g/outputs  node)               expected-output)))
      (doseq [[label expected-schema] {:string-output t/Str :integer-output IntegerProperty :cached-output t/Str :inline-string t/Str :schemaless-production t/Str}]
        (is (= expected-schema (get-in MultipleOutputNode [:transform-types label]))))
      (is (:cached-output (g/cached-outputs' MultipleOutputNode)))
      (is (:cached-output (g/cached-outputs node)))))

  (testing "output inheritance"
    (let [node (g/construct InheritedOutputNode)]
      (doseq [expected-output [:string-output :integer-output :cached-output :inline-string :schemaless-production :abstract-output]]
        (is (get (g/outputs' InheritedOutputNode) expected-output))
        (is (get (g/outputs  node)               expected-output)))
      (doseq [[label expected-schema] {:string-output t/Str :integer-output IntegerProperty :cached-output t/Str :inline-string t/Str :schemaless-production t/Str :abstract-output t/Str}]
        (is (= expected-schema (get-in InheritedOutputNode [:transform-types label]))))
      (is (:cached-output (g/cached-outputs' InheritedOutputNode)))
      (is (:cached-output (g/cached-outputs node)))))

  (testing "output dependencies include transforms and their inputs"
    (let [node (g/construct MultipleOutputNode)]
      (is (= {:project #{:integer-output}
              :string-input #{:inline-string}
              :integer-input #{:string-output :cached-output}}
             (g/input-dependencies node))))
    (let [node (g/construct InheritedOutputNode)]
      (is (= {:project #{:integer-output}
              :string-input #{:inline-string}
              :integer-input #{:string-output :abstract-output :cached-output}}
             (g/input-dependencies node)))))

  (testing "output dependencies are the transitive closure of their inputs"
    (let [node (g/construct TwoLayerDependencyNode)]
      (is (= {:a-property #{:direct-calculation :indirect-calculation :properties :a-property}
              :direct-calculation #{:indirect-calculation}}
             (g/input-dependencies node)))))

  (testing "outputs defined without the type cause a compile error"
    (is (not (nil? (eval '(dynamo.graph/defnode FooNode
                            (output my-output dynamo.types/Any :abstract))))))
    (is (thrown? Compiler$CompilerException
                 (eval '(dynamo.graph/defnode FooNode
                          (output my-output :abstract)))))
    (is (thrown? Compiler$CompilerException
                 (eval '(dynamo.graph/defnode FooNode
                          (output my-output (dynamo.graph/fnk [] "constant string"))))))))

(g/defnode OneEventNode
  (on :an-event
    :ok))

(g/defnode EventlessNode)

(g/defnode MixinEventNode
  (on :mixin-event
    :mixin-ok))

(g/defnode InheritedEventNode
  (inherits OneEventNode)
  (inherits MixinEventNode)

  (on :another-event
    :another-ok))

(deftest nodes-can-handle-events
  (with-clean-system
    (testing "nodes with event handlers implement MessageTarget"
      (let [[node] (tx-nodes (g/make-node world OneEventNode))]
        (is (:an-event (g/event-handlers' OneEventNode)))
        (is (satisfies? g/MessageTarget node))
        (is (= :ok (g/dispatch-message (g/now) node :an-event)))))
    (testing "nodes without event handlers do not implement MessageTarget"
      (let [[node] (tx-nodes (g/make-node world EventlessNode))]
        (is (not (satisfies? g/MessageTarget node)))))
    (testing "nodes can inherit handlers from their supertypes"
      (let [[node] (tx-nodes (g/make-node world InheritedEventNode))]
        (is ((every-pred :an-event :mixin-event :another-event) (g/event-handlers' InheritedEventNode)))
        (is (= :ok         (g/dispatch-message (g/now) node :an-event)))
        (is (= :mixin-ok   (g/dispatch-message (g/now) node :mixin-event)))
        (is (= :another-ok (g/dispatch-message (g/now) node :another-event)))))))

(defn- not-neg? [x] (not (neg? x)))

(dp/defproperty TypedProperty t/Int)
(dp/defproperty DerivedProperty TypedProperty)
(dp/defproperty DefaultProperty DerivedProperty
  (default 0))
(dp/defproperty ValidatedProperty DefaultProperty
  (validate must-be-positive not-neg?))

(g/defnode NodeWithPropertyVariations
  (property typed-external TypedProperty)
  (property derived-external DerivedProperty)
  (property default-external DefaultProperty)
  (property validated-external ValidatedProperty)

  (property typed-internal t/Int)
  (property derived-internal TypedProperty)
  (property default-internal TypedProperty
    (default 0))
  (property validated-internal DefaultProperty
            (validate always-valid (fn [value] true)))
  (property literally-disabled TypedProperty
            (enabled false))
  (property functionally-disabled TypedProperty
            (enabled #(pos? %))))

(g/defnode InheritsPropertyVariations
  (inherits NodeWithPropertyVariations))

(def original-node-definition
  '(dynamo.graph/defnode MutagenicNode
     (property a-property schema.core/Str  (default "a-string"))
     (property b-property schema.core/Bool (default true))))

(def replacement-node-definition
  '(dynamo.graph/defnode MutagenicNode
     (property a-property schema.core/Str  (default "Genosha"))
     (property b-property schema.core/Bool (default false))
     (property c-property schema.core/Int  (default 42))
     dynamo.defnode_test.MarkerInterface))

(deftest redefining-nodes-updates-existing-world-instances
  (with-clean-system
    (binding [*ns* (find-ns 'dynamo.defnode-test)]
      (eval original-node-definition))

    (let [node-type-var          (resolve 'dynamo.defnode-test/MutagenicNode)
          node-type              (var-get node-type-var)
          [node-before-mutation] (tx-nodes (g/make-node world node-type))
          original-node-id       (:_id node-before-mutation)]
      (binding [*ns* (find-ns 'dynamo.defnode-test)]
        (eval replacement-node-definition))

      (let [node-after-mutation (g/refresh node-before-mutation)]
        (is (not (instance? MarkerInterface node-before-mutation)))
        (is (= "a-string" (:a-property node-after-mutation)))
        (is (= true       (:b-property node-after-mutation)))
        (is (= 42         (:c-property node-after-mutation)))
        (is (instance? MarkerInterface node-after-mutation))))))

(g/defnode BaseTriggerNode
  (trigger added-trigger        :added             (fn [& _] nil))
  (trigger multiway-trigger     :added :deleted    (fn [& _] nil))
  (trigger on-delete            :deleted           (fn [& _] nil))
  (trigger on-property-touched  :property-touched  (fn [& _] nil))
  (trigger on-input-connections :input-connections (fn [& _] nil)))

(g/defnode InheritedTriggerNode
  (inherits BaseTriggerNode)

  (trigger extra-added      :added           (fn [& _] nil))
  (trigger on-delete        :deleted         (fn [& _] :override)))

(deftest nodes-can-have-triggers
  (testing "basic trigger definition"
    (is (fn? (get-in (g/triggers BaseTriggerNode) [:added :added-trigger])))
    (is (fn? (get-in (g/triggers BaseTriggerNode) [:added :multiway-trigger])))
    (is (fn? (get-in (g/triggers BaseTriggerNode) [:deleted :multiway-trigger])))
    (is (fn? (get-in (g/triggers BaseTriggerNode) [:deleted :on-delete])))
    (is (fn? (get-in (g/triggers BaseTriggerNode) [:property-touched :on-property-touched])))
    (is (fn? (get-in (g/triggers BaseTriggerNode) [:input-connections :on-input-connections]))))

  (testing "triggers are inherited"
    (is (fn? (get-in (g/triggers InheritedTriggerNode) [:added :added-trigger])))
    (is (fn? (get-in (g/triggers InheritedTriggerNode) [:added :multiway-trigger])))
    (is (fn? (get-in (g/triggers InheritedTriggerNode) [:added :extra-added])))
    (is (fn? (get-in (g/triggers InheritedTriggerNode) [:deleted :multiway-trigger]))))

  (testing "inherited triggers can be overridden"
    (is (fn? (get-in (g/triggers InheritedTriggerNode) [:deleted :on-delete])))
    (is (= :override ((get-in (g/triggers InheritedTriggerNode) [:deleted :on-delete])))))

  (testing "disallows unknown trigger kinds"
    (is (thrown-with-msg? clojure.lang.Compiler$CompilerException #"Valid trigger kinds are"
          (eval '(dynamo.graph/defnode NoSuchTriggerNode
                   (trigger nope :not-a-real-trigger-kind (fn [& _] :nope))))))
    (is (thrown-with-msg? clojure.lang.Compiler$CompilerException #"Valid trigger kinds are"
          (eval '(dynamo.graph/defnode NoSuchTriggerNode
                   (trigger nope :modified (fn [& _] :nope))))))))
