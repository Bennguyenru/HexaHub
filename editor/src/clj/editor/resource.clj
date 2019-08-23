(ns editor.resource
  (:require [clojure.java.io :as io]
            [clojure.string :as string]
            [cognitect.transit :as transit]
            [dynamo.graph :as g]
            [schema.core :as s]
            [editor.core :as core]
            [editor.fs :as fs]
            [util.digest :as digest])
  (:import [java.io ByteArrayOutputStream File IOException]
           [java.nio.file FileSystem FileSystems]
           [java.net URI]
           [java.util.zip ZipEntry ZipInputStream]
           [org.apache.commons.io FilenameUtils IOUtils]))

(set! *warn-on-reflection* true)

(defprotocol ResourceListener
  (handle-changes [this changes render-progress!]))

(defprotocol Resource
  (children [this])
  (ext [this])
  (resource-type [this])
  (source-type [this])
  (exists? [this])
  (read-only? [this])
  (path [this])
  (abs-path ^String [this])
  (proj-path ^String [this])
  (resource-name ^String [this])
  (workspace [this])
  (resource-hash [this])
  (openable? [this]))

(defn type-ext [resource]
  (string/lower-case (ext resource)))

(defn openable-resource? [value]
  ;; A resource is considered openable if its kind can be opened. Typically this
  ;; is a resource that is part of the project and is not a directory. Note
  ;; that the resource does not have to be openable in the Defold Editor - an
  ;; external application could be assigned to handle it. Before opening, you
  ;; must also make sure the resource exists.
  (and (satisfies? Resource value)
       (openable? value)))

(defn editable-resource? [value]
  ;; A resource is considered editable if the Defold Editor can edit it. Before
  ;; opening, you must also make sure the resource exists.
  (and (openable-resource? value)
       (true? (:editable? (resource-type value)))))

(defn- ->unix-seps ^String [^String path]
  (FilenameUtils/separatorsToUnix path))

(defn relative-path [^File f1 ^File f2]
  ;; The strange comparison below is done due to the fact that we support case
  ;; insensitive file systems. For example NTFS and HFS. We want to compare the
  ;; paths without case but preserve the casing as supplied by the caller in the
  ;; result we produce.
  (let [p1-lower (string/lower-case (->unix-seps (str (.getAbsolutePath f1))))
        p2-abs (->unix-seps (str (.getAbsolutePath f2)))
        p2-lower (string/lower-case p2-abs)
        path (if (string/starts-with? p2-lower p1-lower)
               (subs p2-abs (count p1-lower))
               p2-abs)]
    (if (string/starts-with? path "/")
      (subs path 1)
      path)))

(defn file->proj-path [^File project-path ^File f]
  (try
    (str "/" (relative-path project-path f))
    (catch IllegalArgumentException e
      nil)))

(defn parent-proj-path [^String proj-path]
  (when-let [last-slash (string/last-index-of proj-path "/")]
    (subs proj-path 0 last-slash)))

;; Note! Used to keep a file here instead of path parts, but on
;; Windows (File. "test") equals (File. "Test") which broke
;; FileResource equality tests.
(defrecord FileResource [workspace ^String abs-path ^String project-path ^String name ^String ext source-type children]
  Resource
  (children [this] children)
  (ext [this] ext)
  (resource-type [this] (get (g/node-value workspace :resource-types) (type-ext this)))
  (source-type [this] source-type)
  (exists? [this]
    (try
      ;; The path must match the casing of the file on disk exactly. We treat
      ;; this as an error to make the user manually fix mismatches. We could fix
      ;; such references automatically by using the canonical path to create the
      ;; FileResource. However, some ids used by the engine are derived from the
      ;; file names, most notably AtlasImage ids. Since these can be referenced
      ;; from scripts, we make the user aware of the bad reference so she can
      ;; fix it manually and hopefully remember to update the scripts too.
      (let [file (io/file this)]
        (and (.exists file)
             (string/ends-with? (->unix-seps (.getCanonicalPath file)) project-path)))
      (catch IOException _
        false)
      (catch SecurityException _
        false)))
  (read-only? [this]
    (try
      (not (.canWrite (io/file this)))
      (catch SecurityException _
        true)))
  (path [this] (if (= "" project-path) "" (subs project-path 1)))
  (abs-path [this] abs-path)
  (proj-path [this] project-path)
  (resource-name [this] name)
  (workspace [this] workspace)
  (resource-hash [this] (hash (proj-path this)))
  (openable? [this] (= :file source-type))

  io/IOFactory
  (io/make-input-stream  [this opts] (io/make-input-stream (io/file this) opts))
  (io/make-reader        [this opts] (io/make-reader (io/make-input-stream this opts) opts))
  (io/make-output-stream [this opts] (io/make-output-stream (io/file this) opts))
  (io/make-writer        [this opts] (io/make-writer (io/make-output-stream this opts) opts))

  io/Coercions
  (io/as-file [this] (File. abs-path)))

(defn make-file-resource [workspace ^String root ^File file children]
  (let [source-type (if (.isDirectory file) :folder :file)
        abs-path (.getAbsolutePath file)
        path (.getPath file)
        name (.getName file)
        project-path (if (= "" name) "" (str "/" (relative-path (File. root) (io/file path))))
        ext (FilenameUtils/getExtension path)]
    (FileResource. workspace abs-path project-path name ext source-type children)))

(defn file-resource? [resource]
  (instance? FileResource resource))

(core/register-read-handler!
  "file-resource"
  (transit/read-handler
    (fn [{:keys [workspace ^String abs-path ^String project-path ^String name ^String ext source-type children]}]
      (FileResource. workspace abs-path project-path name ext source-type children))))

(core/register-write-handler!
 FileResource
 (transit/write-handler
  (constantly "file-resource")
  (fn [^FileResource r]
    {:workspace (:workspace r)
     :abs-path (:abs-path r)
     :project-path (:project-path r)
     :name (:name r)
     :ext (:ext r)
     :source-type (:source-type r)
     :children (:children r)})))

(defmethod print-method FileResource [file-resource ^java.io.Writer w]
  (.write w (format "{:FileResource %s}" (pr-str (proj-path file-resource)))))

;; Note that `data` is used for resource-hash, used to name
;; the output of build-resources. So better be unique for the
;; data the MemoryResource represents!
(defrecord MemoryResource [workspace ext data]
  Resource
  (children [this] nil)
  (ext [this] ext)
  (resource-type [this] (get (g/node-value workspace :resource-types) (type-ext this)))
  (source-type [this] :file)
  (exists? [this] true)
  (read-only? [this] false)
  (path [this] nil)
  (abs-path [this] nil)
  (proj-path [this] nil)
  (resource-name [this] nil)
  (workspace [this] workspace)
  (resource-hash [this] (hash data))
  (openable? [this] false)

  io/IOFactory
  (io/make-input-stream  [this opts] (io/make-input-stream (IOUtils/toInputStream ^String (:data this)) opts))
  (io/make-reader        [this opts] (io/make-reader (io/make-input-stream this opts) opts))
  (io/make-output-stream [this opts] (io/make-output-stream (.toCharArray ^String (:data this)) opts))
  (io/make-writer        [this opts] (io/make-writer (io/make-output-stream this opts) opts)))

(core/register-record-type! MemoryResource)

(defmethod print-method MemoryResource [memory-resource ^java.io.Writer w]
  (.write w (format "{:MemoryResource %s}" (pr-str (ext memory-resource)))))

(defn make-memory-resource [workspace resource-type data]
  (MemoryResource. workspace (:ext resource-type) data))

(defrecord ZipResource [workspace ^URI zip-uri name path data children]
  Resource
  (children [this] children)
  (ext [this] (FilenameUtils/getExtension name))
  (resource-type [this] (get (g/node-value workspace :resource-types) (type-ext this)))
  (source-type [this] (if (zero? (count children)) :file :folder))
  (exists? [this] (not (nil? data)))
  (read-only? [this] true)
  (path [this] path)
  (abs-path [this] nil)
  (proj-path [this] (str "/" path))
  (resource-name [this] name)
  (workspace [this] workspace)
  (resource-hash [this] (hash (proj-path this)))
  (openable? [this] (= :file (source-type this)))

  io/IOFactory
  (io/make-input-stream  [this opts] (io/make-input-stream (:data this) opts))
  (io/make-reader        [this opts] (io/make-reader (io/make-input-stream this opts) opts))
  (io/make-output-stream [this opts] (throw (Exception. "Zip resources are read-only")))
  (io/make-writer        [this opts] (throw (Exception. "Zip resources are read-only")))

  io/Coercions
  (io/as-file [this]
    (let [zip-url (.toURL zip-uri)]
      (when (= (.getPath zip-url) (.getFile zip-url))
        (io/file (.getFile zip-url))))))

(core/register-record-type! ZipResource)

(core/register-read-handler!
 "zip-resource"
 (transit/read-handler
  (fn [{:keys [workspace ^String zip-uri name path data children]}]
    (ZipResource. workspace (URI. zip-uri) name path data children))))

(core/register-write-handler!
 ZipResource
 (transit/write-handler
  (constantly "zip-resource")
  (fn [^ZipResource r]
    {:workspace (:workspace r)
     :zip-uri   (.toString ^URI (:zip-uri r))
     :name      (:name r)
     :path      (:path r)
     :data      (:data r)     
     :children  (:children r)})))

(defmethod print-method ZipResource [zip-resource ^java.io.Writer w]
  (.write w (format "{:ZipResource %s}" (pr-str (proj-path zip-resource)))))

(defn- read-zip-entry [^ZipInputStream zip ^ZipEntry e]
  (let [os (ByteArrayOutputStream.)
        buf (byte-array (* 1024 16))]
    (loop [n (.read zip buf 0 (count buf))]
     (when (> n 0)
       (.write os buf 0 n)
       (recur (.read zip buf 0 (count buf)))))
    (.toByteArray os)))

(defn- outside-base-path? [base-path ^ZipEntry entry]
  (and (seq base-path) (not (.startsWith (->unix-seps (.getName entry)) (str base-path "/")))))

(defn- path-relative-base [base-path ^ZipEntry entry]
  (let [entry-name (->unix-seps (.getName entry))]
    (if (seq base-path)
      (.replaceFirst entry-name (str base-path "/") "")
      entry-name)))

(defn- load-zip [file-or-url base-path]
  (when-let [stream (and file-or-url (io/input-stream file-or-url))]
    (with-open [zip (ZipInputStream. stream)]
      (loop [entries []]
        (let [e (.getNextEntry zip)]
          (if-not e
            entries
            (recur (if (or (.isDirectory e) (outside-base-path? base-path e))
                     entries
                     (conj entries {:name (FilenameUtils/getName (.getName e))
                                    :path (path-relative-base base-path e)
                                    :buffer (read-zip-entry zip e)
                                    :crc (.getCrc e)})))))))))

(defn- ->zip-resources [workspace zip-uri path [key val]]
  (let [path' (if (string/blank? path) key (str path "/" key))]
    (if (:path val) ; i.e. we've reached an actual entry with name, path, buffer
      (ZipResource. workspace zip-uri (:name val) (:path val) (:buffer val) nil)
      (ZipResource. workspace zip-uri key path' nil (mapv (fn [x] (->zip-resources workspace zip-uri path' x)) val)))))

(defn load-zip-resources
  ([workspace file-or-url]
   (load-zip-resources workspace file-or-url nil))
  ([workspace file-or-url base-path]
   (let [entries (load-zip file-or-url base-path)]
     {:tree (->> (reduce (fn [acc node] (assoc-in acc (string/split (:path node) #"/") node)) {} entries)
                 (mapv (fn [x] (->zip-resources workspace (.toURI (io/as-url file-or-url)) "" x))))
      :crc (into {} (map (juxt (fn [e] (str "/" (:path e))) :crc) entries))})))

(g/defnode ResourceNode
  (property resource Resource :unjammable
            (dynamic visible (g/constantly false))))

(defn base-name ^String [resource]
  (FilenameUtils/getBaseName (resource-name resource)))

(defn- seq-children [resource]
  (seq (children resource)))

(defn resource-seq [root]
  (tree-seq seq-children seq-children root))

(defn resource-list-seq [resource-list]
  (apply concat (map resource-seq resource-list)))

(defn resource-map [roots]
  (into {} (map (juxt proj-path identity) roots)))

(defn resource->proj-path [resource]
  (if resource
    (proj-path resource)
    ""))

(defn resource->sha1-hex [resource]
  (with-open [rs (io/input-stream resource)]
    (digest/stream->sha1-hex rs)))

(defn resource->path-inclusive-sha1-hex
  "For certain files, we want to include the proj-path in the sha1 identifier
  along with the file contents. For example, it used to be that two atlases
  that referred to separate copies of an image would be packed into a shared
  `.texturec` resource since the image contents matched. While this appears good
  on paper, there is an issue with it: If a texture is modified at runtime, it
  will affect both atlases. The user might expect this if both atlases refer to
  the same image file, but most would assume that having one of them refer to a
  copy of the image would make the packed atlas textures unique.

  See DEFEDIT-4218 for additional info about the rationale."
  ^String [resource]
  (with-open [input-stream (io/input-stream resource)
              digest-output-stream (digest/make-digest-output-stream "SHA-1")]
    (io/copy input-stream digest-output-stream)
    (when-some [^String proj-path (proj-path resource)]
      (.write digest-output-stream (.getBytes proj-path "UTF-8")))
    (.flush digest-output-stream)
    (digest/digest-output-stream->hex digest-output-stream)))

(g/deftype ResourceVec [(s/maybe (s/protocol Resource))])

(defn temp-path [resource]
  (when (and resource (= :file (source-type resource)))
    (let [^File f (fs/create-temp-file! "tmp" (format ".%s" (ext resource)))]
      (with-open [in (io/input-stream resource)]
        (io/copy in f))
      (.getAbsolutePath f))))

(defn style-classes [resource]
  (into #{"resource"}
        (keep not-empty)
        [(when (read-only? resource) "resource-read-only")
         (case (source-type resource)
           :file (some->> resource ext not-empty (str "resource-ext-"))
           :folder "resource-folder"
           nil)]))

(defn ext-style-classes [resource-ext]
  (assert (or (nil? resource-ext) (string? resource-ext)))
  (if-some [ext (not-empty resource-ext)]
    #{"resource" (str "resource-ext-" ext)}
    #{"resource"}))

(defn filter-resources [resources query]
  (let [file-system ^FileSystem (FileSystems/getDefault)
        matcher (.getPathMatcher file-system (str "glob:" query))]
    (filter (fn [r] (let [path (.getPath file-system (path r) (into-array String []))] (.matches matcher path))) resources)))

(defn internal?
  [resource]
  (string/starts-with? (resource->proj-path resource) "/_defold"))
