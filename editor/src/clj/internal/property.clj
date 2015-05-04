(ns internal.property
  (:require [clojure.core.match :refer [match]]
            [dynamo.types :as t]
            [dynamo.util :refer :all]))

(def ^:private default-validation-fn (constantly true))

(defn- validation-problems
  [value-type validations value]
  (if (t/check value-type value)
    (list "invalid value type")
    (keep identity
      (reduce
        (fn [errs {:keys [fn formatter]}]
          (conj errs
            (let [valid? (try (apply-if-fn (var-get-recursive fn) value) (catch Exception e false))]
              (when-not valid?
                (apply-if-fn (var-get-recursive formatter) value)))))
        []
        validations))))

(defrecord PropertyTypeImpl
  [value-type default validation visible tags enabled]
  t/PropertyType
  (property-value-type    [this]   value-type)
  (property-default-value [this]   (some-> default var-get-recursive apply-if-fn))
  (property-validate      [this v] (validation-problems value-type (map second validation) v))
  (property-valid-value?  [this v] (empty? (validation-problems value-type (map second validation) v)))
  (property-enabled?      [this v] (or (nil? enabled) (apply-if-fn (var-get-recursive enabled) v)))
  (property-visible?      [this v] (or (nil? visible) (apply-if-fn (var-get-recursive visible) v)))
  (property-tags          [this]   tags))

(defn- resolve-if-symbol [sym]
  (if (symbol? sym)
    `(do
       ~sym ; eval to generate CompilerException when symbol cannot be resolved
       (resolve '~sym))
    sym))

(defn compile-defproperty-form [form]
  (match [form]
    [(['default default] :seq)]
    {:default (resolve-if-symbol default)}

    [(['validate label :message formatter-fn validation-fn] :seq)]
    {:validation {(keyword label) {:fn (resolve-if-symbol validation-fn)
                                   :formatter (resolve-if-symbol formatter-fn)}}}

    [(['validate label validation-fn] :seq)]
    {:validation [[(keyword label) {:fn (resolve-if-symbol validation-fn)
                                   :formatter "invalid value"}]]}

    [(['enabled enablement-fn] :seq)]
    {:enabled (resolve-if-symbol enablement-fn)}

    [(['visible visibility-fn] :seq)]
    {:visible (resolve-if-symbol visibility-fn)}

    [(['tag tag] :seq)]
    {:tags [tag]}

    :else
    (assert false (str "invalid form within property type definition: " (pr-str form)))))

(defn merge-props [props new-props]
  (-> (merge (dissoc props :validation) (dissoc new-props :validation))
    (assoc :validation (concat (:validation props) (:validation new-props)))
    (assoc :tags (into (vec (:tags new-props)) (:tags props)))))

(defn property-type-descriptor [name-sym value-type body-forms]
  `(let [value-type#     ~value-type
         base-props#     (if (t/property-type? value-type#)
                           value-type#
                           {:value-type value-type# :tags []})
         override-props# ~(mapv compile-defproperty-form body-forms)
         props#          (reduce merge-props base-props# override-props#)
         ; protocol detection heuristic based on private function `clojure.core/protocol?`
         protocol?#      (fn [~'p] (and (map? ~'p) (contains? ~'p :on-interface)))]
     (assert (not (protocol?# value-type#)) (str "Property " '~name-sym " type " '~value-type " looks like a protocol; try (schema.core/protocol " '~value-type ") instead."))
     (map->PropertyTypeImpl props#)))

(defn def-property-type-descriptor [name-sym value-type & body-forms]
  `(def ~name-sym ~(property-type-descriptor name-sym value-type body-forms)))
