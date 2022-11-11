;; Copyright 2020-2022 The Defold Foundation
;; Copyright 2014-2020 King
;; Copyright 2009-2014 Ragnar Svensson, Christian Murray
;; Licensed under the Defold License version 1.0 (the "License"); you may not use
;; this file except in compliance with the License.
;; 
;; You may obtain a copy of the License, together with FAQs at
;; https://www.defold.com/license
;; 
;; Unless required by applicable law or agreed to in writing, software distributed
;; under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
;; CONDITIONS OF ANY KIND, either express or implied. See the License for the
;; specific language governing permissions and limitations under the License.

(ns editor.workspace
  "Define the concept of a project, and its Project node type. This namespace bridges between Eclipse's workbench and
ordinary paths."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as string]
            [dynamo.graph :as g]
            [editor.dialogs :as dialogs]
            [editor.fs :as fs]
            [editor.library :as library]
            [editor.prefs :as prefs]
            [editor.progress :as progress]
            [editor.resource :as resource]
            [editor.resource-watch :as resource-watch]
            [editor.ui :as ui]
            [editor.url :as url]
            [editor.util :as util]
            [internal.cache :as c]
            [service.log :as log]
            [util.coll :refer [pair]])
  (:import [editor.resource FileResource]
           [java.io File PushbackReader]
           [java.net URI]
           [org.apache.commons.io FilenameUtils]))

(set! *warn-on-reflection* true)

(def build-dir "/build/default/")
(def plugins-dir "/build/plugins/")

(defn project-path
  (^File [workspace]
   (g/with-auto-evaluation-context evaluation-context
     (project-path workspace evaluation-context)))
  (^File [workspace evaluation-context]
   (io/as-file (g/node-value workspace :root evaluation-context))))

(defn- skip-first-char [path]
  (subs path 1))

(defn build-path
  (^File [workspace]
   (io/file (project-path workspace) (skip-first-char build-dir)))
  (^File [workspace build-resource-path]
   (io/file (build-path workspace) (skip-first-char build-resource-path))))

(defn plugin-path
  (^File [workspace]
   (io/file (project-path workspace) (skip-first-char plugins-dir)))
  (^File [workspace path]
   (io/file (project-path workspace) (str (skip-first-char plugins-dir) (skip-first-char path)))))

(defn as-proj-path
  (^String [workspace file-or-path]
   (g/with-auto-evaluation-context evaluation-context
     (as-proj-path workspace file-or-path evaluation-context)))
  (^String [workspace file-or-path evaluation-context]
   (let [file (io/as-file file-or-path)
         project-directory (project-path workspace evaluation-context)]
     (when (fs/below-directory? file project-directory)
       (resource/file->proj-path project-directory file)))))

(defrecord BuildResource [resource prefix]
  resource/Resource
  (children [this] nil)
  (ext [this] (:build-ext (resource/resource-type this) "unknown"))
  (resource-type [this] (resource/resource-type resource))
  (source-type [this] (resource/source-type resource))
  (read-only? [this] false)
  (path [this] (let [ext (resource/ext this)
                     ext (if (not-empty ext) (str "." ext) "")
                     suffix (format "%x" (resource/resource-hash this))]
                 (if-let [path (resource/path resource)]
                   (str (FilenameUtils/removeExtension path) ext)
                   (str prefix "_generated_" suffix ext))))
  (abs-path [this] (.getAbsolutePath (io/file (build-path (resource/workspace this)) (resource/path this))))
  (proj-path [this] (str "/" (resource/path this)))
  (resource-name [this] (resource/resource-name resource))
  (workspace [this] (resource/workspace resource))
  (resource-hash [this] (resource/resource-hash resource))
  (openable? [this] false)
  (editable? [this] false)

  io/IOFactory
  (make-input-stream  [this opts] (io/make-input-stream (File. (resource/abs-path this)) opts))
  (make-reader        [this opts] (io/make-reader (io/make-input-stream this opts) opts))
  (make-output-stream [this opts] (let [file (File. (resource/abs-path this))] (io/make-output-stream file opts)))
  (make-writer        [this opts] (io/make-writer (io/make-output-stream this opts) opts))

  io/Coercions
  (as-file [this] (File. (resource/abs-path this))))

(def build-resource? (partial instance? BuildResource))

(defn make-build-resource
  ([resource]
   (make-build-resource resource nil))
  ([resource prefix]
   (assert (resource/resource? resource))
   (BuildResource. resource prefix)))

(defn sort-resource-tree [{:keys [children] :as tree}]
  (let [sorted-children (->> children
                             (map sort-resource-tree)
                             (sort
                               (util/comparator-chain
                                 (util/comparator-on editor.resource/file-resource?)
                                 (util/comparator-on #({:folder 0 :file 1} (editor.resource/source-type %)))
                                 (util/comparator-on util/natural-order editor.resource/resource-name)))
                             vec)]
    (assoc tree :children sorted-children)))

(g/defnk produce-resource-tree [_node-id root resource-snapshot editable-proj-path?]
  (sort-resource-tree
    (resource/make-file-resource _node-id root (io/as-file root) (:resources resource-snapshot) editable-proj-path?)))

(g/defnk produce-resource-list [resource-tree]
  (vec (sort-by resource/proj-path util/natural-order (resource/resource-seq resource-tree))))

(g/defnk produce-resource-map [resource-list]
  (into {}
        (map #(pair (resource/proj-path %) %))
        resource-list))

(defn get-view-type
  ([workspace id]
   (g/with-auto-evaluation-context evaluation-context
     (get-view-type workspace id evaluation-context)))
  ([workspace id evaluation-context]
   (get (g/node-value workspace :view-types evaluation-context) id)))

(defn- editor-openable-view-type? [view-type]
  (case view-type
    (:default :text) false
    true))

(defn- make-editable-resource-type-merge-fn [prioritized-editable]
  {:pre [(boolean? prioritized-editable)]}
  (fn editable-resource-type-merge-fn [old-resource-type new-resource-type]
    (let [old-editable (:editable old-resource-type)
          new-editable (:editable new-resource-type)]
      (assert (boolean? old-editable))
      (assert (boolean? new-editable))
      (if (or (= old-editable new-editable)
              (= prioritized-editable new-editable))
        new-resource-type
        old-resource-type))))

(defn- make-editable-resource-type-map-update-fn [prioritized-editable]
  (let [editable-resource-type-merge-fn (make-editable-resource-type-merge-fn prioritized-editable)]
    (fn editable-resource-type-map-update-fn [resource-type-map updated-resource-types-by-ext]
      (merge-with editable-resource-type-merge-fn resource-type-map updated-resource-types-by-ext))))

(def ^:private editable-resource-type-map-update-fn (make-editable-resource-type-map-update-fn true))
(def ^:private non-editable-resource-type-map-update-fn (make-editable-resource-type-map-update-fn false))

(defn register-resource-type
  "Register new resource type to be handled by the editor

  Required kv-args:
    :ext    file extension associated with the resource type, either a string
            or a coll of strings

  Optional kv-args:
    :node-type          a loaded resource node type; defaults to
                        editor.placeholder-resource/PlaceholderResourceNode
    :textual?           whether the resource is saved as text and needs proper
                        lf/crlf handling, default false
    :language           language identifier string used for textual resources
                        that can be opened as code, used for LSP interactions,
                        see https://code.visualstudio.com/docs/languages/identifiers#_known-language-identifiers
                        for common values; defaults to \"plaintext\"
    :build-ext          file extension of a built resource, defaults to :ext's
                        value with appended \"c\"
    :dependencies-fn    fn of node's :source-value output to a collection of
                        resource project paths that this node depends on,
                        affects loading order
    :load-fn            a function from project, new node id and resource to
                        transaction step, invoked on loading the resource of
                        the type; default editor.placeholder-resource/load-node
    :read-fn            a fn from clojure.java.io/reader-able object (e.g.
                        a resource or a Reader) to a data structure
                        representation of the resource (a source value)
    :read-raw-fn        similar to :read-fn, but used during sanitization
    :sanitize-fn        if present, will be applied to read data (from
                        :read-raw-fn or, if absent, from :read-fn) on loading to
                        transform the loaded data
    :write-fn           a fn from a data representation of the resource
                        (a save value) to string
    :icon               classpath path to an icon image or project resource path
                        string; default \"icons/32/Icons_29-AT-Unknown.png\"
    :view-types         vector of alternative views that can be used for
                        resources of the resource type, e.g. :code, :scene,
                        :cljfx-form-view, :text, :html or :default.
    :view-opts          a map from a view-type keyword to options map that will
                        be merged with other opts used when opening a view
    :tags               a set of keywords that can be used for customizing the
                        behavior of the resource throughout the project
    :tag-opts           a map from tag keyword from :tags to additional options
                        map the configures the behavior of the resource with the
                        tag
    :template           classpath or project resource path to a template file
                        for a new resource file creation; defaults to
                        \"templates/template.{ext}\"
    :label              label for a resource type when shown in the editor
    :stateless?         whether the resource can be modified in the editor, by
                        default true if there is no :load-fn and false otherwise
    :auto-connect-save-data?    whether changes to the resource are saved
                                to disc (this can also be enabled in load-fn)
                                when there is a :write-fn, default true"
  [workspace & {:keys [textual? language editable ext build-ext node-type load-fn dependencies-fn read-raw-fn sanitize-fn read-fn write-fn icon view-types view-opts tags tag-opts template label stateless? auto-connect-save-data?]}]
  (let [editable (if (nil? editable) true (boolean editable))
        textual (true? textual?)
        resource-type {:textual? textual
                       :language (when textual (or language "plaintext"))
                       :editable editable
                       :editor-openable (some? (some editor-openable-view-type? view-types))
                       :build-ext (if (nil? build-ext) (str ext "c") build-ext)
                       :node-type node-type
                       :load-fn load-fn
                       :dependencies-fn dependencies-fn
                       :write-fn write-fn
                       :read-fn read-fn
                       :read-raw-fn (or read-raw-fn read-fn)
                       :sanitize-fn sanitize-fn
                       :icon icon
                       :view-types (map (partial get-view-type workspace) view-types)
                       :view-opts view-opts
                       :tags tags
                       :tag-opts tag-opts
                       :template template
                       :label label
                       :stateless? (if (nil? stateless?) (nil? load-fn) stateless?)
                       :auto-connect-save-data? (and editable
                                                     (some? write-fn)
                                                     (not (false? auto-connect-save-data?)))}
        resource-types-by-ext (if (string? ext)
                                (let [ext (string/lower-case ext)]
                                  {ext (assoc resource-type :ext ext)})
                                (into {}
                                      (map (fn [ext]
                                             (let [ext (string/lower-case ext)]
                                               (pair ext (assoc resource-type :ext ext)))))
                                      ext))]
    (concat
      (g/update-property workspace :resource-types editable-resource-type-map-update-fn resource-types-by-ext)
      (g/update-property workspace :resource-types-non-editable non-editable-resource-type-map-update-fn resource-types-by-ext))))

(defn- editability->output-label [editability]
  (case editability
    :editable :resource-types
    :non-editable :resource-types-non-editable))

(defn get-resource-type-map
  ([workspace]
   (g/node-value workspace :resource-types))
  ([workspace editability]
   (g/node-value workspace (editability->output-label editability))))

(defn get-resource-type
  ([workspace ext]
   (get (get-resource-type-map workspace) ext))
  ([workspace editability ext]
   (get (get-resource-type-map workspace editability) ext)))

(defn make-embedded-resource [workspace editability ext data]
  (let [resource-type-map (get-resource-type-map workspace editability)]
    (if-some [resource-type (resource-type-map ext)]
      (resource/make-memory-resource workspace resource-type data)
      (throw (ex-info (format "Unable to locate resource type info. Extension not loaded? (type=%s)"
                              ext)
                      {:type ext
                       :registered-types (into (sorted-set)
                                               (keys resource-type-map))})))))

(defn resource-icon [resource]
  (when resource
    (if (and (resource/read-only? resource)
             (= (resource/path resource) (resource/resource-name resource)))
      "icons/32/Icons_03-Builtins.png"
      (condp = (resource/source-type resource)
        :file
        (or (:icon (resource/resource-type resource)) "icons/32/Icons_29-AT-Unknown.png")
        :folder
        "icons/32/Icons_01-Folder-closed.png"))))

(defn file-resource
  ([workspace path-or-file]
   (let [evaluation-context (g/make-evaluation-context {:basis (g/now) :cache c/null-cache})]
     (file-resource workspace path-or-file evaluation-context)))
  ([workspace path-or-file evaluation-context]
   (let [root (g/node-value workspace :root evaluation-context)
         editable-proj-path? (g/node-value workspace :editable-proj-path? evaluation-context)
         f (if (instance? File path-or-file)
             path-or-file
             (File. (str root path-or-file)))]
     (resource/make-file-resource workspace root f [] editable-proj-path?))))

(defn find-resource
  ([workspace proj-path]
   (g/with-auto-evaluation-context evaluation-context
     (find-resource workspace proj-path evaluation-context)))
  ([workspace proj-path evaluation-context]
   (get (g/node-value workspace :resource-map evaluation-context) proj-path)))

(defn resolve-workspace-resource
  ([workspace path]
   (when (not-empty path)
     (g/with-auto-evaluation-context evaluation-context
       (or
         (find-resource workspace path evaluation-context)
         (file-resource workspace path evaluation-context)))))
  ([workspace path evaluation-context]
   (when (not-empty path)
     (or
       (find-resource workspace path evaluation-context)
       (file-resource workspace path evaluation-context)))))

(defn- absolute-path [^String path]
  (.startsWith path "/"))

(defn to-absolute-path
  ([rel-path] (to-absolute-path "" rel-path))
  ([base rel-path]
   (if (absolute-path rel-path)
     rel-path
     (str base "/" rel-path))))

(defn resolve-resource [base-resource path]
  (when-not (empty? path)
    (let [path (if (absolute-path path)
                 path
                 (to-absolute-path (str (.getParent (File. (resource/proj-path base-resource)))) path))]
      (when-let [workspace (:workspace base-resource)]
        (resolve-workspace-resource workspace path)))))

(defn- template-path [resource-type]
  (or (:template resource-type)
      (some->> resource-type :ext (str "templates/template."))))

(defn- get-template-resource [workspace resource-type]
  (let [path (template-path resource-type)
        java-resource (when path (io/resource path))
        editor-resource (when path (find-resource workspace path))]
    (or java-resource editor-resource)))
  
(defn has-template? [workspace resource-type]
  (let [resource (get-template-resource workspace resource-type)]
    (not= resource nil)))

(defn template [workspace resource-type]
  (when-let [resource (get-template-resource workspace resource-type)]
    (with-open [f (io/reader resource)]
      (slurp f))))

(defn set-project-dependencies! [workspace library-uris]
  (g/set-property! workspace :dependencies library-uris)
  library-uris)

(defn dependencies [workspace]
  (g/node-value workspace :dependencies))

(defn dependencies-reachable? [dependencies]
  (let [hosts (into #{} (map url/strip-path) dependencies)]
    (every? url/reachable? hosts)))

(defn missing-dependencies [workspace]
  (let [project-directory (project-path workspace)
        dependencies (g/node-value workspace :dependencies)]
    (into #{}
          (comp (remove :file)
                (map :uri))
          (library/current-library-state project-directory dependencies))))

(defn make-snapshot-info [workspace project-path dependencies snapshot-cache]
  (let [snapshot-info (resource-watch/make-snapshot-info workspace project-path dependencies snapshot-cache)]
    (assoc snapshot-info :map (resource-watch/make-resource-map (:snapshot snapshot-info)))))

(defn update-snapshot-cache! [workspace snapshot-cache]
  (g/set-property! workspace :snapshot-cache snapshot-cache))

(defn snapshot-cache [workspace]
  (g/node-value workspace :snapshot-cache))

(defn- is-plugin-clojure-file? [resource]
  (= "clj" (resource/ext resource)))

(defn- find-clojure-plugins [workspace]
  (let [resources (filter is-plugin-clojure-file? (g/node-value workspace :resource-list))]
    resources))

(defn- load-plugin! [workspace resource]
  (log/info :msg (str "Loading plugin " (resource/path resource)))
  (try
    (if-let [plugin-fn (load-string (slurp resource))]
      (do
        (plugin-fn workspace)
        (log/info :msg (str "Loaded plugin " (resource/path resource))))
      (log/error :msg (str "Unable to load plugin " (resource/path resource))))
    (catch Exception e
      (log/error :msg (str "Exception while loading plugin: " (.getMessage e))
                 :exception e)
      (ui/run-later
        (dialogs/make-info-dialog
          {:title "Unable to Load Plugin"
           :icon :icon/triangle-error
           :always-on-top true
           :header (format "The editor plugin '%s' is not compatible with this version of the editor. Please edit your project dependencies to refer to a suitable version." (resource/proj-path resource))}))
      false)))

(defn- load-editor-plugins! [workspace added]
  (let [added-resources (set (map resource/proj-path added))
        plugin-resources (find-clojure-plugins workspace)
        plugin-resources (filter (fn [x] (contains? added-resources (resource/proj-path x))) plugin-resources)]
    (dorun (map (fn [x] (load-plugin! workspace x)) plugin-resources))))

; Determine if the extension has plugins, if so, it needs to be extracted

(defn- is-jar-file? [resource]
  (= "jar" (resource/ext resource)))

; from native_extensions.clj
(defn- extension-root?
  [resource]
  (some #(= "ext.manifest" (resource/resource-name %)) (resource/children resource)))

(defn- is-extension-file? [workspace resource]
  (let [parent-path (resource/parent-proj-path (resource/proj-path resource))
        parent (find-resource workspace (str parent-path))]
    (if (extension-root? resource)
      true
      (if parent
        (is-extension-file? workspace parent)
        false))))

(defn- is-plugin-file? [workspace resource]
  (and
    (string/includes? (resource/proj-path resource) "/plugins/")
    (is-extension-file? workspace resource)))

(defn- is-shared-library? [resource]
  (contains? #{"dylib" "dll" "so"} (resource/ext resource)))

(defn- find-plugins-shared-libraries [workspace]
  (let [resources (filter (fn [x] (is-plugin-file? workspace x)) (g/node-value workspace :resource-list))]
    resources))

(defn unpack-resource! [workspace resource]
  (let [target-path (plugin-path workspace (resource/proj-path resource))
        parent-dir (.getParentFile ^File target-path)
        input-stream (io/input-stream resource)]
    (when-not (.exists parent-dir)
      (.mkdirs parent-dir))
    (io/copy input-stream target-path)))

; It's important to use the same class loader, so that the type signatures match
(def class-loader (clojure.lang.DynamicClassLoader. (.getContextClassLoader (Thread/currentThread))))

(defn load-class! [class-name]
  (Class/forName class-name true class-loader))

(defn- add-to-path-property [propertyname path]
  (let [current (System/getProperty propertyname)
        newvalue (if current
                   (str current java.io.File/pathSeparator path)
                   path)]
    (System/setProperty propertyname newvalue)))

(defn- register-jar-file! [workspace resource]
  (let [jar-file (plugin-path workspace (resource/proj-path resource))]
    (.addURL ^clojure.lang.DynamicClassLoader class-loader (io/as-url jar-file))))

(defn- register-shared-library-file! [workspace resource]
  (let [resource-file (plugin-path workspace (resource/proj-path resource))
        parent-dir (.getParent resource-file)]
; TODO: Only add files for the current platform (e.g. dylib on macOS)
    (add-to-path-property "jna.library.path" parent-dir)
    (add-to-path-property "java.library.path" parent-dir)))

(defn- delete-directory-recursive [^java.io.File file]
  ;; Recursively delete a directory. // https://gist.github.com/olieidel/c551a911a4798312e4ef42a584677397
  ;; when `file` is a directory, list its entries and call this
  ;; function with each entry. can't `recur` here as it's not a tail
  ;; position, sadly. could cause a stack overflow for many entries?
  ;; thanks to @nikolavojicic for the idea to use `run!` instead of
  ;; `doseq` :)
  (when (.isDirectory file)
    (run! delete-directory-recursive (.listFiles file)))
  ;; delete the file or directory. if it it's a file, it's easily
  ;; deletable. if it's a directory, we already have deleted all its
  ;; contents with the code above (remember?)
  (io/delete-file file))

(defn clean-editor-plugins! [workspace]
  ; At startup, we want to remove the plugins in order to avoid having issues copying the .dll on Windows
  (let [dir (plugin-path workspace)]
    (if (.exists dir)
      (delete-directory-recursive dir))))

(defn- unpack-editor-plugins! [workspace changed]
  ; Used for unpacking the .jar files and shared libraries (.so, .dylib, .dll) to disc
  ; TODO: Handle removed plugins (e.g. a dependency was removed)
  (let [changed-resources (set (map resource/proj-path changed))
        all-plugin-resources (find-plugins-shared-libraries workspace)
        changed-plugin-resources (filter (fn [x] (contains? changed-resources (resource/proj-path x))) all-plugin-resources)
        changed-shared-library-resources (filter is-shared-library? changed-plugin-resources)
        changed-jar-resources (filter is-jar-file? changed-plugin-resources)]
    (doseq [x changed-plugin-resources]
      (try
        (unpack-resource! workspace x)
        (catch java.io.FileNotFoundException error
          (throw (java.io.IOException. "\nExtension plugins needs updating.\nPlease restart editor for these changes to take effect!")))))
    (doseq [x changed-jar-resources]
      (register-jar-file! workspace x))
    (doseq [x changed-shared-library-resources]
      (register-shared-library-file! workspace x))))

(defn resource-sync!
  ([workspace]
   (resource-sync! workspace []))
  ([workspace moved-files]
   (resource-sync! workspace moved-files progress/null-render-progress!))
  ([workspace moved-files render-progress!]
   (let [snapshot-info (make-snapshot-info workspace (project-path workspace) (dependencies workspace) (snapshot-cache workspace))
         {new-snapshot :snapshot new-map :map new-snapshot-cache :snapshot-cache} snapshot-info]
     (update-snapshot-cache! workspace new-snapshot-cache)
     (resource-sync! workspace moved-files render-progress! new-snapshot new-map)))
  ([workspace moved-files render-progress! new-snapshot new-map]
   (let [project-path (project-path workspace)
         moved-proj-paths (keep (fn [[src tgt]]
                                  (let [src-path (resource/file->proj-path project-path src)
                                        tgt-path (resource/file->proj-path project-path tgt)]
                                    (assert (some? src-path) (str "project does not contain source " (pr-str src)))
                                    (assert (some? tgt-path) (str "project does not contain target " (pr-str tgt)))
                                    (when (not= src-path tgt-path)
                                      [src-path tgt-path])))
                                moved-files)
         old-snapshot (g/node-value workspace :resource-snapshot)
         old-map      (resource-watch/make-resource-map old-snapshot)
         changes      (resource-watch/diff old-snapshot new-snapshot)]
     (when (or (not (resource-watch/empty-diff? changes)) (seq moved-proj-paths))
       (g/set-property! workspace :resource-snapshot new-snapshot)
       (let [changes (into {} (map (fn [[type resources]] [type (filter #(= :file (resource/source-type %)) resources)]) changes))
             move-source-paths (map first moved-proj-paths)
             move-target-paths (map second moved-proj-paths)
             chain-moved-paths (set/intersection (set move-source-paths) (set move-target-paths))
             merged-target-paths (set (map first (filter (fn [[k v]] (> v 1)) (frequencies move-target-paths))))
             moved (keep (fn [[source-path target-path]]
                           (when-not (or
                                       ;; resource sync currently can't handle chained moves, so refactoring is
                                       ;; temporarily disabled for those cases (no move pair)
                                       (chain-moved-paths source-path) (chain-moved-paths target-path)
                                       ;; also can't handle merged targets, multiple files with same name moved to same dir
                                       (merged-target-paths target-path))
                             (let [src-resource (old-map source-path)
                                   tgt-resource (new-map target-path)]
                               ;; We used to (assert (some? src-resource)), but this could fail for instance if
                               ;; * source-path refers to a .dotfile (like .DS_Store) that we ignore in resource-watch
                               ;; * Some external process has created a file in a to-be-moved directory and we haven't run a resource-sync! before the move
                               ;; We handle these cases by ignoring the move. Any .dotfiles will stay ignored, and any new files will pop up as :added
                               ;;
                               ;; We also used to (assert (some? tgt-resource)) but an arguably very unlikely case is that the target of the move is
                               ;; deleted from disk after the move but before the snapshot.
                               ;; We handle that by ignoring the move and effectively treating target as just :removed.
                               ;; The source will be :removed or :changed (if a library snuck in).
                               (cond
                                 (nil? src-resource)
                                 (do (log/warn :msg (str "can't find source of move " source-path)) nil)

                                 (nil? tgt-resource)
                                 (do (log/warn :msg (str "can't find target of move " target-path)) nil)

                                 (and (= :file (resource/source-type src-resource))
                                      (= :file (resource/source-type tgt-resource))) ; paranoia
                                 [src-resource tgt-resource]))))
                         moved-proj-paths)
             changes-with-moved (assoc changes :moved moved)]
         (assert (= (count (distinct (map (comp resource/proj-path first) moved)))
                    (count (distinct (map (comp resource/proj-path second) moved)))
                    (count moved))) ; no overlapping sources, dito targets
         (assert (= (count (distinct (concat (map (comp resource/proj-path first) moved)
                                             (map (comp resource/proj-path second) moved))))
                    (* 2 (count moved)))) ; no chained moves src->tgt->tgt2...
         (assert (empty? (set/intersection (set (map (comp resource/proj-path first) moved))
                                           (set (map resource/proj-path (:added changes)))))) ; no move-source is in :added
         (try
           (let [listeners @(g/node-value workspace :resource-listeners)
                 total-progress-size (transduce (map first) + 0 listeners)
                 added (:added changes)
                 changed (:changed changes)
                 all-changed (set/union added changed)]
             (unpack-editor-plugins! workspace all-changed)
             (load-editor-plugins! workspace all-changed)
             (loop [listeners listeners
                    parent-progress (progress/make "" total-progress-size)]
               (when-some [[progress-span listener] (first listeners)]
                 (resource/handle-changes listener changes-with-moved
                                          (progress/nest-render-progress render-progress! parent-progress progress-span))
                 (recur (next listeners)
                        (progress/advance parent-progress progress-span)))))
           (finally
             (render-progress! progress/done)))))
     changes)))

(defn fetch-and-validate-libraries [workspace library-uris render-fn]
  (->> (library/current-library-state (project-path workspace) library-uris)
       (library/fetch-library-updates library/default-http-resolver render-fn)
       (library/validate-updated-libraries)))

(defn install-validated-libraries! [workspace library-uris lib-states]
  (set-project-dependencies! workspace library-uris)
  (library/install-validated-libraries! (project-path workspace) lib-states))

(defn add-resource-listener! [workspace progress-span listener]
  (swap! (g/node-value workspace :resource-listeners) conj [progress-span listener]))

(g/deftype UriVec [URI])

(g/defnode Workspace
  (property root g/Str)
  (property dependencies UriVec)
  (property opened-files g/Any (default (atom #{})))
  (property resource-snapshot g/Any)
  (property resource-listeners g/Any (default (atom [])))
  (property view-types g/Any)
  (property resource-types g/Any)
  (property resource-types-non-editable g/Any)
  (property snapshot-cache g/Any (default {}))
  (property build-settings g/Any)
  (property editable-proj-path? g/Any)

  (output resource-tree FileResource :cached produce-resource-tree)
  (output resource-list g/Any :cached produce-resource-list)
  (output resource-map g/Any :cached produce-resource-map))

(defn make-build-settings
  [prefs]
  {:compress-textures? (prefs/get-prefs prefs "general-enable-texture-compression" false)})

(defn update-build-settings!
  [workspace prefs]
  (g/set-property! workspace :build-settings (make-build-settings prefs)))

(defn artifact-map [workspace]
  (g/user-data workspace ::artifact-map))

(defn artifact-map! [workspace artifact-map]
  (g/user-data! workspace ::artifact-map artifact-map))

(defn etags [workspace]
  (g/user-data workspace ::etags))

(defn etag [workspace proj-path]
  (get (etags workspace) proj-path))

(defn etags! [workspace etags]
  (g/user-data! workspace ::etags etags))

(defn- artifact-map-file
  ^File [workspace]
  (io/file (build-path workspace) ".artifact-map"))

(defn- try-read-artifact-map [^File file]
  (when (.exists file)
    (try
      (with-open [reader (PushbackReader. (io/reader file))]
        (edn/read reader))
      (catch Exception error
        (log/warn :msg "Failed to read artifact map. Build cache invalidated." :exception error)
        nil))))

(defn artifact-map->etags [artifact-map]
  (when (seq artifact-map)
    (into {}
          (map (juxt key (comp :etag val)))
          artifact-map)))

(defn load-build-cache! [workspace]
  (let [file (artifact-map-file workspace)
        artifact-map (try-read-artifact-map file)
        etags (artifact-map->etags artifact-map)]
    (artifact-map! workspace artifact-map)
    (etags! workspace etags)
    nil))

(defn save-build-cache! [workspace]
  (let [file (artifact-map-file workspace)
        artifact-map (artifact-map workspace)]
    (if (empty? artifact-map)
      (fs/delete-file! file)
      (let [saved-artifact-map (into (sorted-map) artifact-map)]
        (fs/create-file! file (pr-str saved-artifact-map))))
    nil))

(defn clear-build-cache! [workspace]
  (let [file (artifact-map-file workspace)]
    (artifact-map! workspace nil)
    (etags! workspace nil)
    (fs/delete-file! file)
    nil))

(defn- make-editable-proj-path-predicate [non-editable-directory-proj-paths]
  {:pre [(vector? non-editable-directory-proj-paths)
         (every? string? non-editable-directory-proj-paths)]}
  (fn editable-proj-path? [proj-path]
    (not-any? (fn [non-editable-directory-proj-path]
                ;; A proj-path is considered non-editable if it matches or is
                ;; located below a non-editable directory. Thus, the character
                ;; immediately following the non-editable directory should be a
                ;; slash, or should be the end of the proj-path string. We can
                ;; test this fact before matching the non-editable directory
                ;; path against the beginning of the proj-path to make the test
                ;; more efficient.
                (case (get proj-path (count non-editable-directory-proj-path))
                  (\/ nil) (string/starts-with? proj-path non-editable-directory-proj-path)
                  false))
              non-editable-directory-proj-paths)))

(defn make-workspace [graph project-path build-settings workspace-config]
  (let [editable-proj-path? (if-some [non-editable-directory-proj-paths (not-empty (:non-editable-directories workspace-config))]
                              (make-editable-proj-path-predicate non-editable-directory-proj-paths)
                              (constantly true))]
    (g/make-node! graph Workspace
                  :root project-path
                  :resource-snapshot (resource-watch/empty-snapshot)
                  :view-types {:default {:id :default}}
                  :resource-listeners (atom [])
                  :build-settings build-settings
                  :editable-proj-path? editable-proj-path?)))

(defn register-view-type
  "Register a new view type that can be used by resources

  Required kv-args:
    :id       keyword identifying the view type
    :label    a label for the view type shown in the editor

  Optional kv-args:
    :make-view-fn          fn of graph, parent (AnchorPane), resource node and
                           opts that should create new view node, set it up and
                           return the node id; opts is a map that will contain:
                           - :app-view
                           - :select-fn
                           - :prefs
                           - :project
                           - :workspace
                           - :tab (Tab instance)
                           - all opts from resource-type's :view-opts
                           - any extra opts passed from the code
                           if not present, the resource will be opened in
                           an external editor
    :make-preview-fn       fn of graph, resource node, opts, width and height
                           that should return a node id with :image output (with
                           value of type Image); opts is a map with:
                           - :app-view
                           - :select-fn
                           - :project
                           - :workspace
                           - all opts from resource-type's :view-opts
                           This preview will be used in select resource dialog
                           on hover over resources
    :dispose-preview-fn    fn of node id returned by :make-preview-fn, will be
                           invoked on preview dispose
    :focus-fn              fn of node id returned by :make-view-fn and opts,
                           will be called on resource open request, opts will
                           only contain data passed from the code (e.g.
                           :cursor-range)
    :text-selection-fn     fn of node id returned by :make-view-fn, should
                           return selected text as a string or nil; will be used
                           to pre-populate Open Assets and Search in Files
                           dialogs"
  [workspace & {:keys [id label make-view-fn make-preview-fn dispose-preview-fn focus-fn text-selection-fn]}]
  (let [view-type (merge {:id    id
                          :label label}
                         (when make-view-fn
                           {:make-view-fn make-view-fn})
                         (when make-preview-fn
                           {:make-preview-fn make-preview-fn})
                         (when dispose-preview-fn
                           {:dispose-preview-fn dispose-preview-fn})
                         (when focus-fn
                           {:focus-fn focus-fn})
                         (when text-selection-fn
                           {:text-selection-fn text-selection-fn}))]
     (g/update-property workspace :view-types assoc (:id view-type) view-type)))
