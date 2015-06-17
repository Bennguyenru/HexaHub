(ns editor.project
  "Define the concept of a project, and its Project node type. This namespace bridges between Eclipse's workbench and
ordinary paths."
  (:require [clojure.set :as set]
            [clojure.java.io :as io]
            [dynamo.file :as file]
            [dynamo.graph :as g]
            [dynamo.property :as dp]
            [dynamo.types :as t]
            [dynamo.util :refer :all]
            [editor.core :as core]
            [editor.workspace :as workspace]
            [editor.handler :as handler]
            [editor.ui :as ui]
            [internal.clojure :as clojure]
            [internal.ui.dialogs :as dialogs]
            [service.log :as log])
  (:import [java.io File]
           [java.nio.file FileSystem FileSystems PathMatcher]))

(g/defnode ResourceNode
  (inherits core/Scope)

  (property resource (t/protocol workspace/Resource) (visible (g/fnk [] false)))
  (property project t/Any (visible (g/fnk [] false)))

  (output save-data t/Any (g/fnk [resource] {:resource resource}))
  (output build-targets t/Any (g/fnk [] [])))

(g/defnode PlaceholderResourceNode
  (inherits ResourceNode))

(defn graph [project]
  (g/node->graph-id project))

(defn- make-nodes [project resources]
  (let [project-graph (graph project)]
    (g/tx-nodes-added
      (g/transact
        (for [[resource-type resources] (group-by workspace/resource-type resources)
              :let     [node-type (:node-type resource-type PlaceholderResourceNode)]
              resource resources]
          (if (not= (workspace/source-type resource) :folder)
            (g/make-nodes
              project-graph
              [new-resource [node-type :resource resource :project-id (g/node-id project) :resource-type resource-type]]
              (g/connect new-resource :self project :nodes)
              (if ((g/outputs' node-type) :save-data)
                (g/connect new-resource :save-data project :save-data)
                []))
            []))))))

(defn- load-nodes [project nodes]
  (let [new-nodes (g/tx-nodes-added (g/transact
                                     (for [node nodes
                                           :let [load-fn (get-in node [:resource-type :load-fn])]
                                           :when load-fn]
                                       (load-fn project node (io/reader (:resource node))))))]
    (when (not (empty? new-nodes))
      (recur project new-nodes))))

(defn load-project
  ([project] (load-project project (g/node-value project :resources)))
  ([project resources]
   (let [nodes (make-nodes project resources)]
     (load-nodes (g/refresh project) nodes)
     (g/refresh project))))

(defn make-embedded-resource [project type data]
  (when-let [resource-type (get (g/node-value project :resource-types) type)]
    (workspace/make-memory-resource (:workspace project) resource-type data)))

(handler/defhandler :save-all :global
    (enabled? [] true)
    (run [project] (let [save-data (g/node-value project :save-data)]
                     (doseq [{:keys [resource content]} save-data]
                       (spit resource content)))))

(defn- target-key [target]
  [(:resource (:resource target))
   (:build-fn target)
   (:user-data target)])

(defn- build-target [basis target all-targets build-cache]
  (let [resource (:resource target)
        key (:key target)
        cache (let [cache (get @build-cache resource)] (and (= key (:key cache)) cache))]
    (if cache
     cache
     (let [node (g/node-by-id basis (:node-id target))
           dep-resources (into {} (map #(let [resource (:resource %)
                                              key (target-key %)] [resource (:resource (get all-targets key))]) (:deps target)))
           result ((:build-fn target) node basis resource dep-resources (:user-data target))
           result (assoc result :key key)]
       (swap! build-cache assoc resource (assoc result :cached true))
       result))))

(defn targets-by-key [build-targets]
  (into {} (map #(let [key (target-key %)] [key (assoc % :key key)]) build-targets)))

(defn build [project node]
  (let [basis (g/now)
        build-cache (:build-cache project)
        build-targets (targets-by-key (mapcat #(tree-seq (comp boolean :deps) :deps %) (g/node-value node :build-targets)))]
    (mapv #(build-target basis (second %) build-targets build-cache) build-targets)))

(defn- prune-fs [files-on-disk built-files]
  (let [files-on-disk (reverse files-on-disk)
        built (set built-files)]
    (doseq [file files-on-disk
            :let [dir? (.isDirectory file)
                  empty? (= 0 (count (.listFiles file)))
                  keep? (or (and dir? (not empty?)) (contains? built file))]]
      (when (not keep?)
        (.delete file)))))

(defn build-and-write [project node]
  (let [files-on-disk (file-seq (io/file (workspace/build-path (:workspace project))))
        build-results (build project node)
        fs-build-cache (:fs-build-cache project)]
    (prune-fs files-on-disk (map #(File. (workspace/abs-path (:resource %))) build-results))
    (doseq [result build-results
            :let [{:keys [resource content key]} result
                  abs-path (workspace/abs-path resource)
                  mtime (let [f (File. abs-path)]
                          (if (.exists f)
                            (.lastModified f)
                            0))
                  build-key [key mtime]
                  cached? (= (get @fs-build-cache resource) build-key)]]
      (when (not cached?)
        (let [parent (-> (File. ^String (workspace/abs-path resource))
                       (.getParentFile))]
          ; Create underlying directories
          (when (not (.exists parent))
            (.mkdirs parent))
          ; Write bytes
          (with-open [out (io/output-stream resource)]
            (.write out ^bytes content))
          (let [f (File. abs-path)]
            (swap! fs-build-cache assoc resource [key (.lastModified f)])))))))

(handler/defhandler :undo :global
    (enabled? [project-graph] (g/has-undo? project-graph))
    (run [project-graph] (g/undo project-graph)))

(handler/defhandler :redo :global
    (enabled? [project-graph] (g/has-redo? project-graph))
    (run [project-graph] (g/redo project-graph)))

(ui/extend-menu ::menubar :editor.app-view/open
                [{:label "Save All"
                  :acc "Shortcut+S"
                  :command :save-all}])

(ui/extend-menu ::menubar :editor.app-view/edit
                [{:label "Project"
                  :id ::project
                  :children [{:label "Build"
                              :acc "Shortcut+B"
                              :command :build}]}])

(defn clear-build-cache [project]
  (reset! (:build-cache project) {}))

(defn clear-fs-build-cache [project]
  (reset! (:fs-build-cache project) {}))

(g/defnode Project
  (inherits core/Scope)

  (property workspace t/Any)
  (property build-cache t/Any)
  (property fs-build-cache t/Any)

  (input selected-node-ids t/Any :array)
  (input selected-nodes t/Any :array)
  (input resources t/Any)
  (input resource-types t/Any)
  (input save-data t/Any :array)

  (output selected-node-ids t/Any :cached (g/fnk [selected-node-ids] selected-node-ids))
  (output selected-nodes t/Any :cached (g/fnk [selected-nodes] selected-nodes))
  (output nodes-by-resource t/Any :cached (g/fnk [nodes] (into {} (map (fn [n] [(:resource n) n]) nodes))))
  (output save-data t/Any :cached (g/fnk [save-data] (filter #(and % (:content %)) save-data)))

  workspace/SelectionProvider
  (selection [this] (g/node-value this :selected-node-ids)))

(defn get-resource-type [resource-node]
  (when resource-node (workspace/resource-type (:resource resource-node))))

(defn get-project [resource-node]
  (g/node-by-id (:project-id resource-node)))

(defn filter-resources [resources query]
  (let [file-system ^FileSystem (FileSystems/getDefault)
        matcher (.getPathMatcher file-system (str "glob:" query))]
    (filter (fn [r] (let [path (.getPath file-system (workspace/path r) (into-array String []))] (.matches matcher path))) resources)))

(defn find-resources [project query]
  (let [resource-to-node (g/node-value project :nodes-by-resource)
        resources        (filter-resources (g/node-value project :resources) query)]
    (map (fn [r] [r (get resource-to-node r)]) resources)))

(defn get-resource-node [project resource]
  (let [nodes-by-resource (g/node-value project :nodes-by-resource)]
    (get nodes-by-resource resource)))

(handler/defhandler :build :global
    (enabled? [] true)
    (run [project] (let [workspace (:workspace project)
                         game-project (get-resource-node project (workspace/file-resource workspace "/game.project"))]
                     (build-and-write project game-project))))

(defn resolve-resource-node [base-resource-node path]
  (let [project (get-project base-resource-node)
        resource (workspace/resolve-resource (:resource base-resource-node) path)]
    (get-resource-node project resource)))

(defn select
  [project nodes]
    (concat
      (for [[node label] (g/sources-of project :selected-node-ids)]
        (g/disconnect node label project :selected-node-ids))
      (for [[node label] (g/sources-of project :selected-nodes)]
        (g/disconnect node label project :selected-nodes))
      (for [node nodes]
        (concat
          (g/connect node :node-id project :selected-node-ids)
          (g/connect node :self project :selected-nodes)))))

(defn select!
  ([project nodes]
    (select! project nodes (gensym)))
  ([project nodes op-seq]
    (let [old-nodes (g/node-value project :selected-nodes)]
      (when (not= nodes old-nodes)
        (g/transact
          (concat
            (g/operation-sequence op-seq)
            (g/operation-label "Select")
            (select project nodes)))))))

(defn make-project [graph workspace]
  (first
   (g/tx-nodes-added
     (g/transact
       (g/make-nodes graph
                     [project [Project :workspace workspace :build-cache (atom {}) :fs-build-cache (atom {})]]
                     (g/connect workspace :resource-list project :resources)
                     (g/connect workspace :resource-types project :resource-types))))))
