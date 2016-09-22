(ns editor.validation
  (:require [dynamo.graph :as g]
            [editor.protobuf :as protobuf]
            [clojure.string :as str]
            [editor.properties :as properties]
            [editor.resource :as resource]))

(set! *warn-on-reflection* true)

(defmacro blend-mode-tip [field pb-blend-type]
  `(g/fnk [~field]
     (when (= ~field :blend-mode-add-alpha)
       (let [options# (protobuf/enum-values ~pb-blend-type)
             options# (zipmap (map first options#) (map (comp :display-name second) options#))]
         (format "\"%s\" has been replaced by \"%s\"",
                 (options# :blend-mode-add-alpha) (options# :blend-mode-add))))))

(defn prop-negative? [v name]
  (when (< v 0)
    (format "'%s' must be positive" name)))

(defn prop-zero-or-below? [v name]
  (when (<= v 0)
    (format "'%s' must be greater than zero" name)))

(defn prop-nil? [v name]
  (when (nil? v)
    (format "'%s' must be specified" name)))

(defn prop-empty? [v name]
  (when (empty? v)
    (format "'%s' must be specified" name)))

(defn prop-resource-not-exists? [v name]
  (and v (not (resource/exists? v)) (format "%s '%s' could not be found" name (resource/resource-name v))))

(defn prop-0-1? [v name]
  (when (not (<= 0.0 v 1.0))
    (format "'%s' must be between 0.0 and 1.0" name)))

(defn prop-1-1? [v name]
  (when (not (<= -1.0 v 1.0))
    (format "'%s' must be between 0.0 and 1.0" name)))

(defn prop-error
  ([severity _node-id prop-kw f prop-value & args]
  (when-let [msg (apply f prop-value args)]
    (g/->error _node-id prop-kw severity prop-value msg {}))))

(defmacro prop-error-fnk
  [severity f property]
  (let [name-kw# (keyword property)
        name# (properties/keyword->name name-kw#)]
    `(g/fnk [~'_node-id ~property]
            (prop-error ~severity ~'_node-id ~name-kw# ~f ~property ~name#))))
