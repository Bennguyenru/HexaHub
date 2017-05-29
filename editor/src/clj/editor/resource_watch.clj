(ns editor.resource-watch
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [editor.settings-core :as settings-core]
            [editor.library :as library]
            [editor.resource :as resource]
            [dynamo.graph :as g])
  (:import [java.io File]
           [editor.resource Resource FileResource ZipResource]))

(set! *warn-on-reflection* true)

(defn- resource-root-dir [resource]
  (when-let [path-splits (seq (rest (str/split (resource/proj-path resource) #"/")))] ; skip initial ""
    (if (= (count path-splits) 1)
      (when (= (resource/source-type resource) :folder)
        (first path-splits))
      (first path-splits))))

(defn parse-include-dirs [include-string]
  (filter (comp not str/blank?) (str/split include-string  #"[,\s]")))

(defn- extract-game-project-include-dirs [game-project-resource]
  (with-open [reader (io/reader game-project-resource)]
    (let [settings (settings-core/parse-settings reader)]
      (parse-include-dirs (str (settings-core/get-setting settings ["library" "include_dirs"]))))))

(defn- load-library-zip [workspace file]
  (let [base-path (library/library-base-path file)
        zip-resources (resource/load-zip-resources workspace file base-path)
        game-project-resource (first (filter (fn [resource] (= "game.project" (resource/resource-name resource))) (:tree zip-resources)))]
    (when game-project-resource
      (let [include-dirs (set (extract-game-project-include-dirs game-project-resource))]
        (update zip-resources :tree (fn [tree] (filter #(include-dirs (resource-root-dir %)) tree)))))))

(defn- make-library-snapshot [workspace lib-state]
  (let [file ^File (:file lib-state)
        tag (:tag lib-state)
        zip-file-version (if-not (str/blank? tag) tag (str (.lastModified file)))
        {resources :tree crc :crc} (load-library-zip workspace file)
        flat-resources (resource/resource-list-seq resources)]
    {:resources resources
     :status-map (into {} (map (fn [resource]
                                 (let [path (resource/proj-path resource)
                                       version (str zip-file-version ":" (crc path))]
                                   [path {:version version :source :library :library (:url lib-state)}]))
                               flat-resources))}))

(defn- make-library-snapshots [workspace project-directory library-urls]
  (let [lib-states (filter :file (library/current-library-state project-directory library-urls))]
    (map (partial make-library-snapshot workspace) lib-states)))

(defn- make-builtins-snapshot [workspace]
  (let [resources (:tree (resource/load-zip-resources workspace (io/resource "builtins.zip")))
        flat-resources (resource/resource-list-seq resources)]
    {:resources resources
     :status-map (into {} (map (juxt resource/proj-path (constantly {:version :constant :source :builtins})) flat-resources))}))

(defn- file-resource-filter [^File f]
  (let [name (.getName f)]
    (not (or (= name "build") ; dont look in build/
             (= name "builtins") ; ?
             (= (subs name 0 1) "."))))) ; dont look at dot-files (covers .internal/lib)

(defn- make-file-tree [workspace ^File root]
  (let [children (if (.isFile root) [] (mapv #(make-file-tree workspace %) (filter file-resource-filter (.listFiles root))))]
    (resource/make-file-resource workspace (g/node-value workspace :root) root children)))

(defn- file-resource-status-map-entry [r]
  [(resource/proj-path r)
   {:version (str (.lastModified ^File (:file r)))
    :source :directory}])

(defn- make-directory-snapshot [workspace ^File root]
  (assert (.isDirectory root))
  (let [resources (resource/children (make-file-tree workspace root))
        flat-resources (resource/resource-list-seq resources)]
    {:resources resources
     :status-map (into {} (map file-resource-status-map-entry flat-resources))}))

(defn- resource-paths [snapshot]
  (set (keys (:status-map snapshot))))

(defn empty-snapshot []
  {:resources nil
   :status-map nil
   :errors nil})

(defn- combine-snapshots [snapshots]
  (reduce
   (fn [result snapshot]
     (if-let [collisions (seq (clojure.set/intersection (resource-paths result) (resource-paths snapshot)))]
       (update result :errors conj {:collisions (select-keys (:status-map snapshot) collisions)})
       (-> result
           (update :resources concat (:resources snapshot))
           (update :status-map merge (:status-map snapshot)))))
   (empty-snapshot)
   snapshots))

(defn make-snapshot [workspace project-directory library-urls]
  (let [builtins-snapshot (make-builtins-snapshot workspace)
        fs-snapshot (make-directory-snapshot workspace project-directory)
        library-snapshots (make-library-snapshots workspace project-directory library-urls)]
    (combine-snapshots (list* builtins-snapshot fs-snapshot library-snapshots))))

(defn make-resource-map [snapshot]
  (into {} (map (juxt resource/proj-path identity) (resource/resource-list-seq (:resources snapshot)))))

(defn- resource-status [snapshot path]
  (get-in snapshot [:status-map path]))

(defn diff [old-snapshot new-snapshot]
  (let [old-map (make-resource-map old-snapshot)
        new-map (make-resource-map new-snapshot)
        old-paths (set (keys old-map))
        new-paths (set (keys new-map))
        common-paths (clojure.set/intersection new-paths old-paths)
        added-paths (clojure.set/difference new-paths old-paths)
        removed-paths (clojure.set/difference old-paths new-paths)
        changed-paths (filter #(not= (resource-status old-snapshot %) (resource-status new-snapshot %)) common-paths)
        added (map new-map added-paths)
        removed (map old-map removed-paths)
        changed (map new-map changed-paths)]
    (assert (empty? (clojure.set/intersection (set added) (set removed))))
    (assert (empty? (clojure.set/intersection (set added) (set changed))))
    (assert (empty? (clojure.set/intersection (set removed) (set changed))))
    {:added added
     :removed removed
     :changed changed}))

(defn empty-diff? [diff]
  (not (or (seq (:added diff))
           (seq (:removed diff))
           (seq (:changed diff)))))
