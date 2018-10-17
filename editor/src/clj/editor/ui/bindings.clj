(ns editor.ui.bindings
  (:refer-clojure :exclude [= and constantly empty? if-not map nil? not not= or some? when when-not])
  (:require [clojure.core :as core]
            [clojure.string :as string]
            [editor.ui :as ui])
  (:import (javafx.beans InvalidationListener Observable)
           (javafx.beans.value ChangeListener ObservableBooleanValue ObservableObjectValue ObservableStringValue ObservableValue)
           (javafx.beans.binding Bindings BooleanBinding ObjectBinding)
           (javafx.beans.property Property SimpleObjectProperty)
           (javafx.collections ObservableList ObservableMap ObservableSet)
           (javafx.css Styleable)))

(set! *warn-on-reflection* true)

(defn- dependencies
  ^"[Ljavafx.beans.Observable;" [& observables]
  (into-array Observable observables))

(def ^:private ^"[Ljavafx.beans.Observable;" no-dependencies (make-array Observable 0))
(def ^:private observable-value? (partial instance? ObservableValue))

(defn- unpack-observable-value [value]
  (if (observable-value? value)
    (.getValue ^ObservableValue value)
    value))

(defn =
  (^BooleanBinding [_op]
   (Bindings/createBooleanBinding (core/constantly true) no-dependencies))
  (^BooleanBinding [op1 op2 & rest]
   (let [ops (into [op1 op2] rest)]
     (Bindings/createBooleanBinding #(apply core/= (core/map unpack-observable-value ops))
                                    (into-array Observable (filter observable-value? ops))))))

(declare not)

(def not= (comp not =))

(defn and
  ^BooleanBinding [^ObservableBooleanValue first-op & rest-ops]
  (reduce #(Bindings/and %1 %2) first-op rest-ops))

(defn bind! [^Property property ^ObservableValue observable]
  (.bind property observable))

(defn unbind! [^Property property]
  (.unbind property))

(defn bind-bidirectional! [^Property property ^Property other]
  (.bindBidirectional property other))

(defn unbind-bidirectional! [^Property property ^Property other]
  (.unbindBidirectional property other))

(defn bind-style!
  "Create a binding that applies a style class to a node. Since style classes
  must be added and removed from the styleable node, each style binding occupies
  a slot in the set of style classes that are applied to the node. When the
  observable style-class changes, the previous style class occupying the slot
  (if any) is removed from the styleable node before the new style class
  (if any) is added. The style-class observable should return a string name of a
  CSS style class, or nil to clear the style from the slot."
  [^Styleable node slot-kw ^ObservableValue style-class]
  (assert (instance? Styleable node))
  (assert (keyword? slot-kw))
  (let [style-class-slots (ui/user-data node ::style-class-slots)]
    (assert (core/not (contains? style-class-slots slot-kw)) (str "Style class slot " slot-kw " is already bound"))
    (let [slot-property (SimpleObjectProperty.)
          slot-listener (reify ChangeListener
                          (changed [_ _ old-style-class new-style-class]
                            (let [node-style-classes (.getStyleClass node)]
                              (core/when-not (string/blank? old-style-class)
                                (.remove node-style-classes old-style-class))
                              (core/when-not (string/blank? new-style-class)
                                (.add node-style-classes new-style-class)))))]
      (.addListener slot-property slot-listener)
      (bind! slot-property style-class)
      ;; NOTE: Because the binding system uses weak references internally, we
      ;; *must* keep a live reference to the slot-property, or the binding will
      ;; cease when it is garbage-collected.
      (ui/user-data! node ::style-class-slots
                     (assoc style-class-slots slot-kw [slot-property slot-listener])))))

(defn unbind-style! [^Styleable node slot-kw]
  (assert (instance? Styleable node))
  (assert (keyword? slot-kw))
  (let [style-class-slots (ui/user-data node ::style-class-slots)]
    (assert (contains? style-class-slots slot-kw) (str "Style class slot " slot-kw " is not bound"))
    (let [[^Property slot-property ^ChangeListener slot-listener] (get style-class-slots slot-kw)]
      (.removeListener slot-property slot-listener)
      (unbind! slot-property)
      (ui/user-data! node ::style-class-slots
                     (not-empty (dissoc style-class-slots slot-kw))))))

(defn blank?
  ^BooleanBinding [^ObservableValue observable]
  (assert (instance? ObservableValue observable))
  (Bindings/createBooleanBinding #(string/blank? (.getValue observable))
                                 (dependencies observable)))

(defn constantly
  ^ObservableObjectValue [value]
  (reify ObservableObjectValue
    (get [_this] value)
    (getValue [_this] value)
    (^void addListener [_this ^ChangeListener _listener])
    (^void addListener [_this ^InvalidationListener _listener])
    (^void removeListener [_this ^ChangeListener _listener])
    (^void removeListener [_this ^InvalidationListener _listener])))

(defn empty?
  ^BooleanBinding [op]
  (cond
    (instance? ObservableList op) (Bindings/isEmpty ^ObservableList op)
    (instance? ObservableMap op) (Bindings/isEmpty ^ObservableMap op)
    (instance? ObservableSet op) (Bindings/isEmpty ^ObservableSet op)
    (instance? ObservableStringValue op) (Bindings/isEmpty ^ObservableStringValue op)
    :else (throw (ex-info (str "Cannot create isEmpty binding from " op)
                          {:op op}))))

;; NOTE: `if` is a special form and cannot be addressed using `core/if`.
;; Since we want to use `if`-expressions in this file, we must use a different
;; name for this function internally. It is exported as `if` at the bottom.
(defn- -if
  ^ObjectBinding [^ObservableBooleanValue condition ^ObservableObjectValue then-value ^ObservableObjectValue otherwise-value]
  (-> (Bindings/when condition)
      (.then then-value)
      (.otherwise otherwise-value)))

(defn if-not
  ^ObjectBinding [^ObservableBooleanValue condition ^ObservableObjectValue then-value ^ObservableObjectValue otherwise-value]
  (-> (Bindings/when condition)
      (.then otherwise-value)
      (.otherwise then-value)))

(defn map
  ^ObjectBinding [value-fn ^ObservableValue observable]
  (assert (instance? ObservableValue observable))
  (assert (ifn? value-fn))
  (Bindings/createObjectBinding #(value-fn (.getValue observable))
                                (dependencies observable)))

(defn nil?
  ^BooleanBinding [^ObservableObjectValue op]
  (Bindings/isNull op))

(defn not
  ^BooleanBinding [^ObservableBooleanValue op]
  (Bindings/not op))

(defn or
  ^BooleanBinding [^ObservableBooleanValue first-op & rest-ops]
  (reduce #(Bindings/or %1 %2) first-op rest-ops))

(defn some?
  ^BooleanBinding [^ObservableObjectValue op]
  (Bindings/isNotNull op))

(defn when
  ^ObjectBinding [^ObservableBooleanValue condition ^ObservableObjectValue then-value]
  (let [^Object typed-nil nil]
    (-> (Bindings/when condition)
        (.then then-value)
        (.otherwise typed-nil))))

(defn when-not
  ^ObjectBinding [^ObservableBooleanValue condition ^ObservableObjectValue then-value]
  (when (not condition)
    then-value))

;; Export our `-if` function as `if` to the outside world.
(def if -if)
