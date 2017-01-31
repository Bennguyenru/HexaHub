(ns editor.game-project-core
  (:require [clojure.java.io :as io]
            [clojure.string :as s]
            [clojure.edn :as edn])
  (:import [java.io PushbackReader StringReader BufferedReader]))

(set! *warn-on-reflection* true)

(defn- non-blank [vals]
  (remove s/blank? vals))

(defn- trimmed [lines]
  (map s/trim lines))

(defn- read-setting-lines [setting-reader]
  (non-blank (trimmed (line-seq setting-reader))))

(defn- resource-reader [resource-name]
  (io/reader (io/resource resource-name)))

(defn- pushback-reader ^PushbackReader [reader]
  (PushbackReader. reader))

(defn string-reader [content]
  (BufferedReader. (StringReader. content)))

(defn- empty-parse-state []
  {:current-category nil :settings nil})

(defn- parse-category-line [{:keys [current-category settings] :as parse-state} line]
  (when-let [[_ new-category] (re-find #"\[([^\]]*)\]" line)]
    (assoc parse-state :current-category new-category)))

(defn- parse-setting-line [{:keys [current-category settings] :as parse-state} line]
  (when-let [[_ key val] (seq (map s/trim (re-find #"([^=]+)=(.*)" line)))]
    (when-let [setting-path (seq (non-blank (s/split key #"\.")))]
      (update parse-state :settings conj {:path (cons current-category setting-path) :value val}))))

(defn- parse-error [line]
  (throw (Exception. (format "Invalid game.project line: %s" line))))

(defn- parse-state->settings [{:keys [settings]}]
  (vec (reverse settings)))

(defn parse-settings [reader]
  (parse-state->settings (reduce
                          (fn [parse-state line]
                            (or (parse-category-line parse-state line)
                                (parse-setting-line parse-state line)
                                (parse-error line)))
                          (empty-parse-state)
                          (read-setting-lines reader))))

(defmulti parse-setting-value (fn [type ^String raw] type))

(defmethod parse-setting-value :string [_ raw]
  raw)

(defmethod parse-setting-value :boolean [_ raw]
  ;; this is roughly how the old editor does it, rather than != 0.
  (= raw "1"))

(defmethod parse-setting-value :integer [_ raw]
  (Integer/parseInt raw))

(defmethod parse-setting-value :number [_ raw]
  (Double/parseDouble raw))

(defmethod parse-setting-value :resource [_ raw]
  raw)

(def ^:private type-defaults
  {:string ""
   :boolean false
   :integer 0
   :number 0.0
   :resource ""})

(defn- add-type-defaults [meta-info]
  (update-in meta-info [:settings]
             (partial map (fn [setting] (update setting :default #(if (nil? %) (type-defaults (:type setting)) %))))))

(def basic-meta-info (with-open [r (pushback-reader (resource-reader "meta.edn"))]
                       (add-type-defaults (edn/read r))))

(defn- make-meta-settings-for-unknown [meta-settings settings]
  (let [known-settings (set (map :path meta-settings))
        unknown-settings (remove known-settings (map :path settings))]
    (map (fn [setting-path] {:path setting-path :type :string :help "unknown setting"}) unknown-settings)))

(defn add-meta-info-for-unknown-settings [meta-info settings]
  (update meta-info :settings #(concat % (make-meta-settings-for-unknown % settings))))

(defn make-meta-settings-map [meta-settings]
  (zipmap (map :path meta-settings) meta-settings))

(defn- trim-trailing-c [value]
  (if (= (last value) \c)
    (subs value 0 (dec (count value)))
    value))

(defn- sanitize-value [{:keys [type preserve-extension]} value]
  (if (and (= type :resource) (not preserve-extension))
    (trim-trailing-c value)
    value))

(defn- sanitize-setting [meta-settings-map {:keys [path] :as setting}]
  (when-let [{:keys [type] :as meta-setting} (meta-settings-map path)]
    (update setting :value
            #(do (->> %
                     (parse-setting-value type)
                     (sanitize-value meta-setting))))))

(defn sanitize-settings [meta-settings settings]
  (vec (map (partial sanitize-setting (make-meta-settings-map meta-settings)) settings)))

(defn make-default-settings [meta-settings]
  (mapv (fn [meta-setting]
         {:path (:path meta-setting) :value (:default meta-setting)})
       meta-settings))

(def setting-category (comp first :path))

(defn- category-order [settings]
  (distinct (map setting-category settings)))

(defn- category-grouped-settings [settings]
  (group-by setting-category settings))

(defn- setting->str [{:keys [path value]}]
  (let [key (s/join "." (rest path))]
    (str key " = " value)))

(defn- category->str [category settings]
  (s/join "\n" (cons (str "[" category "]") (map setting->str settings))))

(defn settings->str [settings]
  (let [cat-order (category-order settings)
        cat-grouped-settings (category-grouped-settings settings)]
    ;; Here we interleave categories with \n\n rather than join to make sure the file also ends with
    ;; two consecutive newlines. This is purely to avoid whitespace diffs when loading a project
    ;; created in the old editor and saving.
    (s/join (interleave (map #(category->str % (cat-grouped-settings %)) cat-order) (repeat "\n\n")))))

(defn make-settings-map [settings]
  (into {} (map (juxt :path :value) settings)))

(defn setting-index [settings path]
  (first (keep-indexed (fn [index item] (when (= (:path item) path) index)) settings)))

(defn get-setting [settings path]
  (when-let [index (setting-index settings path)]
    (:value (nth settings index))))

(defmulti render-raw-setting-value (fn [meta-setting value] (:type meta-setting)))

(defmethod render-raw-setting-value :boolean [_ value]
  (if value "1" "0"))

(defmethod render-raw-setting-value :resource [{:keys [preserve-extension]} value]
  (if (and (not (s/blank? value))
           (not preserve-extension))
    (str value "c")
    value))

(defmethod render-raw-setting-value :default [_ value]
  (str value))

(defn set-setting [settings path value]
  (if-let [index (setting-index settings path)]
    (assoc-in settings [index :value] value)
    (conj settings {:path path :value value})))

(defn clear-setting [settings path]
  (if-let [index (setting-index settings path)]
    (update settings index dissoc :value)
    settings))

(defn get-default-setting [meta-settings path]
  (when-let [index (setting-index meta-settings path)]
    (:default (nth meta-settings index))))

(defn get-setting-or-default [meta-settings settings path]
  (or (get-setting settings path)
      (get-default-setting meta-settings path)))

(defn get-meta-setting
  [meta-settings path]
  (nth meta-settings (setting-index meta-settings path)))

(defn settings-with-value [settings]
  (filter #(contains? % :value) settings))

(defn default-settings []
  (make-settings-map (make-default-settings (:settings basic-meta-info))))
