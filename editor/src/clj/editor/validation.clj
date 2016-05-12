(ns editor.validation
  (:require [dynamo.graph :as g]
            [editor.protobuf :as protobuf]
            [clojure.string :as str]))

(set! *warn-on-reflection* true)

(defn- error-seq [e]
  (tree-seq :causes :causes e))

(defn- error-messages [e]
  (letfn [(extract-message [user-data]
          (cond
            (string? user-data) user-data
            (map? user-data) (:message user-data)
            true nil))]
    (distinct (keep (comp extract-message :user-data) (error-seq e)))))

(defn error-message [e]
  (str/join "\n" (error-messages e)))

(defn error-aggregate [vals]
  (when-let [errors (seq (remove nil? (distinct (filter g/error? vals))))]
    (g/error-aggregate errors)))

(defmacro validate-positive [field message]
  `(g/fnk [~field]
     (when (neg? ~field)
       (g/error-severe ~message))))

(defn pos [fld msg] (when (neg? fld) (g/error-severe msg)))

(defn blend-mode-tip [field pb-blend-type]
  (when (= field :blend-mode-add-alpha)
     (let [options (protobuf/enum-values pb-blend-type)
           options (zipmap (map first options) (map (comp :display-name second) options))]
       (format "\"%s\" has been replaced by \"%s\"",
               (get options :blend-mode-add-alpha) (get options :blend-mode-add)))))

(defn- validate-resource-fn
  ([field] (validate-resource-fn field (str "Missing " (str/replace (name field) "-" " "))))
  ([field message] (validate-resource-fn field message []))
  ([field message inputs]
   `(g/fnk [~field ~@inputs]
           (when (nil? ~field)
             (g/error-warning ~message)))))

(defmacro validate-resource [& args]
  (apply validate-resource-fn args))

(defn resource
  ([fld]
   (resource fld (str "Missing " (str/replace (name fld) "-" " "))))
  ([fld msg]
   (when (nil? fld)
     (g/error-warning msg))))

(defmacro validate-animation [animation anim-data]
  `(g/fnk [~animation ~anim-data]
     (when (not (contains? ~anim-data ~animation))
       (g/error-severe (format "The animation \"%s\" could not be found in the specified image" ~animation)))))

(defn animation
  [animation anim-data]
  (when (not (contains? anim-data animation))
    (g/error-severe (format "The animation \"%s\" could not be found in the specified image" animation))))
