(ns internal.property
  (:require [clojure.tools.macro :as ctm]
            [internal.util :as util]
            [internal.graph :as ig]
            [internal.graph.types :as gt]))

(set! *warn-on-reflection* true)

(defn property-default-setter
  [basis node property _ new-value]
  (first (gt/replace-node basis node (gt/set-property (ig/node-by-id-at basis node) basis property new-value))))

(def property-value-type :value-type)
(def property-tags       :tags)
(defn property-default-value [this] (some-> this :default util/var-get-recursive util/apply-if-fn))
(defn dynamic-attributes     [this] (util/map-vals util/var-get-recursive (:dynamic this)))
(defn default-getter?        [this] (not (contains? this ::value)))

(def assert-symbol (partial util/assert-form-kind "property" "symbol" symbol?))

(defn- property-form [description form]
  (case (first form)
    default
    (assoc description :default (second form))

    validate
    (assoc description ::validate (second form))

    dynamic
    (let [[_ kind remainder] form]
      (assert-symbol "dynamic" kind)
      (assoc-in description [:dynamic (keyword kind)] remainder))

    set
    (assoc description ::setter (second form))

    value
    (assoc description ::value (second form))

    (assert false (str "invalid form within property type definition: " (pr-str form)))))

(def validation ::validate)
(def getter-for ::value)
(def setter-for ::setter)

(defn property-dependencies
  [property]
  (reduce into #{} [(util/fnk-arguments (getter-for property))
                    (util/fnk-arguments (validation property))
                    (mapcat util/fnk-arguments (vals (gt/dynamic-attributes property)))]))

(defn inheriting? [from] (util/property? (util/vgr from)))

(defn parse-forms
  [label value-type body-forms]
  (println "parse-forms " body-forms)
  (util/assert-schema "property" label value-type)
  (let [base (if (inheriting? value-type) (util/vgr value-type) {:value-type value-type})
        base (assoc base :name label)]
    (reduce property-form base body-forms)))

(defn- check-for-protocol-type
  [name-str value-type]
  (let [resolved-value-type (util/vgr value-type)]
    (assert (not (gt/protocol? resolved-value-type))
            (str "Property " name-str " type " value-type " looks like a protocol; try (dynamo.graph/protocol " value-type ") instead."))))

(defn- check-for-invalid-type
  [name-str value-type]
  (let [value-type (util/vgr value-type)]
    (assert (or (util/property? value-type) (util/schema? value-type))
            (str "Property " name-str " is declared with type " value-type " but that doesn't seem like a real value type"))))


(defn property-type-descriptor
  [label value-type body-forms]
  (let [label-str   (str label)
        description (parse-forms label-str value-type body-forms)]
    (check-for-protocol-type label-str value-type)
    (check-for-invalid-type  label-str value-type)
    description))

(defn def-property-type-descriptor
  [name-sym & remainder]
  (let [[name-sym [value-type & body-forms]] (ctm/name-with-attributes name-sym remainder)]
    `(def ~name-sym ~(property-type-descriptor name-sym value-type body-forms))))
