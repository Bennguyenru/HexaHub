(ns editor.game-project
  (:require [clojure.java.io :as io]
            [clojure.string :as s]
            [clojure.edn :as edn]
            [dynamo.graph :as g]
            [editor.project :as project]
            [editor.workspace :as workspace])
  (:import [com.dynamo.gameobject.proto GameObject GameObject$PrototypeDesc  GameObject$ComponentDesc GameObject$EmbeddedComponentDesc GameObject$PrototypeDesc$Builder]
           [com.dynamo.graphics.proto Graphics$Cubemap Graphics$TextureImage Graphics$TextureImage$Image Graphics$TextureImage$Type]
           [com.dynamo.proto DdfMath$Point3 DdfMath$Quat]
           [com.jogamp.opengl.util.awt TextRenderer]
           [editor.types Region Animation Camera Image TexturePacking Rect EngineFormatTexture AABB TextureSetAnimationFrame TextureSetAnimation TextureSet]
           [java.awt.image BufferedImage]
           [java.io PushbackReader StringReader BufferedReader]
           [javax.media.opengl GL GL2 GLContext GLDrawableFactory]
           [javax.media.opengl.glu GLU]
           [javax.vecmath Matrix4d Point3d Quat4d Vector3d]))

(def game-project-icon "icons/32/Icons_04-Project-file.png")

(defn- non-blank [vals]
  (remove s/blank? vals))

(defn- trimmed [lines]
  (map s/trim lines))

(defn- read-setting-lines [setting-reader]
  (non-blank (trimmed (line-seq setting-reader))))

(defn- resource-reader [resource-name]
  (io/reader (io/resource resource-name)))

(defn- pushback-reader [reader]
  (PushbackReader. reader))

(defn- string-reader [content]
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

(defn- parse-state->settings [{:keys [settings]}]
  (vec (reverse settings)))

(defn- parse-settings [reader]
  (parse-state->settings (reduce
                          (fn [parse-state line]
                            (or (parse-category-line parse-state line)
                                (parse-setting-line parse-state line)
                                parse-state))
                          (empty-parse-state)
                          (read-setting-lines reader))))

(defmulti parse-setting-value (fn [type _] type))

(defmethod parse-setting-value :string [_ raw]
  raw)

(defmethod parse-setting-value :boolean [_ raw]
  (try
    (Boolean/parseBoolean raw)
    (catch Throwable _
      (boolean (Integer/parseInt raw)))))

(defmethod parse-setting-value :integer [_ raw]
  (Integer/parseInt raw))

(defmethod parse-setting-value :number [_ raw]
  (Double/parseDouble raw))

(defmethod parse-setting-value :resource [_ raw]
  raw)

(def ^:private meta-info (edn/read (pushback-reader (resource-reader "meta.edn"))))

(defn- make-meta-settings-for-unknown [meta-settings settings]
  (let [known-settings (set (map :path meta-settings))
        unknown-settings (remove known-settings (map :path settings))]
    (map (fn [setting-path] {:path setting-path :type :string :help "unknown setting"}) unknown-settings)))

(defn- complement-meta-info [meta-info settings]
  (update meta-info :settings
          #(concat % (make-meta-settings-for-unknown % settings))))

(defn- sanitize-game-setting [meta-settings-map setting]
  (when-let [{:keys [type]} (meta-settings-map (:path setting))]
    (update setting :value #(parse-setting-value type %))))

(defn- sanitize-game-settings [meta-settings settings]
  (let [meta-settings-map (zipmap (map :path meta-settings) meta-settings)]
    (vec (map (partial sanitize-game-setting meta-settings-map) settings))))

(defn- make-form-field [setting]
  (assoc setting :label (second (:path setting))))

(defn- make-form-section [category-name category-info settings]
  {:section category-name
   :title category-name
   :help (:help category-info)
   :fields (map make-form-field settings)})

(defn- make-default-settings [meta-settings]
  (keep (fn [meta-setting]
          (let [default-value (:default meta-setting)]
            (when (not (nil? default-value))
              {:path (:path meta-setting) :value default-value})))
        meta-settings))

(defn- add-value-source [settings source]
  (keep (fn [setting]
          (when (contains? setting :value)
            (update setting :value (fn [value] {:value value :source source}))))
        settings))

(defn- make-form-values-map [meta-settings settings]
  (let [explicit-values (add-value-source settings :explicit)
        default-values (add-value-source (make-default-settings meta-settings) :default)
        all-values (concat default-values explicit-values)]
    (into {} (map (fn [item] [(:path item) (:value item)]) all-values))))

(def ^:private setting-category (comp first :path))

(defn- make-form-data [form-ops meta-info settings]
  (let [meta-settings (:settings meta-info)
        meta-category-map (:categories meta-info)
        categories (distinct (map setting-category meta-settings))
        category-settings (group-by setting-category meta-settings)
        sections (map #(make-form-section % (get-in meta-info [:categories %]) (category-settings %)) categories)
        values (make-form-values-map meta-settings settings)]
    {:form-ops form-ops :sections sections :values values}))

(defn- category-order [settings]
  (distinct (map setting-category settings)))

(defn- category-grouped-settings [settings]
  (group-by setting-category settings))

(defn- setting->str [setting]
  (let [key (s/join "." (rest (:path setting)))
        val (str (:value setting))]
    (str key " = " val)))

(defn- category->lines [category settings]
  (cons (str "[" category "]")
        (map setting->str (filter #(contains? % :value) settings))))

(defn- settings->lines [settings]
  (let [cat-order (category-order settings)
        cat-grouped-settings (category-grouped-settings settings)]
    (mapcat #(category->lines % (cat-grouped-settings %)) cat-order)))

(defn- settings->str [settings]
  (s/join "\n" (settings->lines settings)))

(defn- setting-index [settings path]
  (first (keep-indexed (fn [index item] (when (= (:path item) path) index)) settings)))

(defn- set-setting [settings path value]
  (if-let [index (setting-index settings path)]
    (assoc-in settings [index :value] value)
    (conj settings {:path path :value value})))

(defn- get-game-setting [settings path]
  (when-let [index (setting-index settings path)]
    (:value (nth settings index))))

(defn- get-default-setting [meta-settings path]
  (when-let [index (setting-index meta-settings path)]
    (:default (nth meta-settings index))))

(defn- get-game-setting-or-default [meta-settings settings path]
  (or (get-game-setting settings path)
      (get-default-setting meta-settings path)))

(defn- clear-setting [settings path]
  (if-let [index (setting-index settings path)]
    (update settings index dissoc :value)))

;;; accessing settings

(defn- game-settings [resource-node]
  (g/node-value resource-node :settings))

(defn- game-meta-info [resource-node]
  (g/node-value resource-node :meta-info))

(defn get-setting [resource-node category key]
  (get-game-setting (game-settings resource-node) [category key]))

(defn get-default [resource-node category key]
  (get-default-setting (:settings (game-meta-info resource-node)) [category key]))

;;; loading node

(defn- root-resource [base-resource settings meta-settings category key]
  (let [path (get-game-setting-or-default meta-settings settings [category key])
        ; TODO - hack for compiled files in game.project
        path (subs path 0 (dec (count path)))
        ; TODO - hack for inconsistencies in game.project paths
        path (if (.startsWith path "/")
               path
               (str "/" path))]
    (workspace/resolve-resource base-resource path)))

(defn- load-game-project [project self input]
  (let [raw-settings (parse-settings (string-reader (slurp input)))
        effective-meta-info (complement-meta-info meta-info raw-settings)
        sanitized-settings (sanitize-game-settings (:settings effective-meta-info) raw-settings)
        resource   (g/node-value self :resource)
        roots      (map (fn [[category field]] (root-resource resource sanitized-settings (:settings effective-meta-info) category field))
                        [["bootstrap" "main_collection"] ["input" "game_binding"] ["input" "gamepads"]
                         ["bootstrap" "render"] ["display" "display_profiles"]])]
    (concat
     (g/set-property self :settings sanitized-settings :meta-info effective-meta-info)
     (for [root roots]
       (project/connect-resource-node project root self [[:build-targets :dep-build-targets]])))))

(g/defnk produce-save-data [resource settings]
  {:resource resource :content (settings->str settings)})

(defn- build-game-project [self basis resource dep-resources user-data]
  {:resource resource :content (.getBytes (:content user-data))})

(defn- set-form-op [{:keys [node-id]} path value]
  (g/update-property! node-id :settings set-setting path value))

(defn- clear-form-op [{:keys [node-id]} path]
  (g/update-property! node-id :settings clear-setting path))

(defn- make-form-ops [node-id]
  {:user-data {:node-id node-id}
   :set set-form-op
   :clear clear-form-op})

(g/defnk produce-form-data [_node-id meta-info settings]
  (make-form-data (make-form-ops _node-id) meta-info settings))

(g/defnode GameProjectNode
  (inherits project/ResourceNode)

  (property settings g/Any (dynamic visible (g/always false)))
  (property meta-info g/Any (dynamic visible (g/always false)))
  
  (output form-data g/Any :cached produce-form-data)

  (input dep-build-targets g/Any :array)

  (output outline g/Any :cached (g/fnk [_node-id] {:node-id _node-id :label "Game Project" :icon game-project-icon}))
  (output save-data g/Any :cached produce-save-data)
  (output build-targets g/Any :cached (g/fnk [_node-id resource settings dep-build-targets]
                                             [{:node-id _node-id
                                               :resource (workspace/make-build-resource resource)
                                               :build-fn build-game-project
                                               :user-data {:content (settings->str settings)}
                                               :deps (vec (flatten dep-build-targets))}])))

(defn register-resource-types [workspace]
  (workspace/register-resource-type workspace
                                    :ext "project"
                                    :label "Project"
                                    :node-type GameProjectNode
                                    :load-fn load-game-project
                                    :icon game-project-icon
                                    :view-types [:form-view :text]))
