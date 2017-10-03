(ns editor.git
  (:require [camel-snake-kebab :as camel]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [editor.fs :as fs]
            [editor.prefs :as prefs]
            [editor.ui :as ui]
            [util.text-util :as text-util])
  (:import javafx.scene.control.ProgressBar
           [java.io File]
           [java.nio.file Files FileVisitResult Path SimpleFileVisitor]
           [java.util Collection]
           [org.eclipse.jgit.api Git ResetCommand$ResetType]
           [org.eclipse.jgit.api.errors StashApplyFailureException]
           [org.eclipse.jgit.diff DiffEntry RenameDetector]
           [org.eclipse.jgit.errors RepositoryNotFoundException]
           [org.eclipse.jgit.lib BatchingProgressMonitor ObjectId Repository]
           [org.eclipse.jgit.revwalk RevCommit RevWalk]
           [org.eclipse.jgit.transport UsernamePasswordCredentialsProvider]
           [org.eclipse.jgit.treewalk FileTreeIterator TreeWalk]
           [org.eclipse.jgit.treewalk.filter PathFilter PathFilterGroup]))

(set! *warn-on-reflection* true)

;; When opening a project, we ensure the .gitignore file contains every entry on this list.
(defonce required-gitignore-entries ["/.internal"
                                     "/build"])

;; Based on the contents of the .gitignore file in a new project created from the dashboard.
(defonce default-gitignore-entries (vec (concat required-gitignore-entries
                                                [".externalToolBuilders"
                                                 ".DS_Store"
                                                 "Thumbs.db"
                                                 ".lock-wscript"
                                                 "*.pyc"
                                                 ".project"
                                                 ".cproject"
                                                 "builtins"])))

(defn open ^Git [^File repo-path]
  (try
    (Git/open repo-path)
    (catch RepositoryNotFoundException e
      nil)))

(defn get-commit [^Repository repository revision]
  (let [walk (RevWalk. repository)]
    (.setRetainBody walk true)
    (.parseCommit walk (.resolve repository revision))))

(defn- diff-entry->map [^DiffEntry de]
  ; NOTE: We convert /dev/null paths to nil, and convert copies into adds.
  (let [f (fn [p] (when-not (= p "/dev/null") p))
        change-type (-> (.getChangeType de) str .toLowerCase keyword)]
    (if (= :copy change-type)
      {:score 0
       :change-type :add
       :old-path nil
       :new-path (f (.getNewPath de))}
      {:score (.getScore de)
       :change-type change-type
       :old-path (f (.getOldPath de))
       :new-path (f (.getNewPath de))})))

(defn- find-original-for-renamed [ustatus file]
  (->> ustatus
    (filter (fn [e] (= file (:new-path e))))
    (map :old-path)
    (first)))

;; =================================================================================


(defn credentials [prefs]
  (let [email (prefs/get-prefs prefs "email" nil)
        token (prefs/get-prefs prefs "token" nil)]
    (UsernamePasswordCredentialsProvider. ^String email ^String token)))

(defn- git-name
  [prefs]
  (let [name (str (prefs/get-prefs prefs "first-name" nil)
                  " "
                  (prefs/get-prefs prefs "last-name" nil))]
    (when-not (str/blank? name)
      name)))

(defn ensure-user-configured!
  [^Git git prefs]
  (let [email            (prefs/get-prefs prefs "email" nil)
        name             (or (git-name prefs) email "Unknown")
        config           (.. git getRepository getConfig)
        configured-name  (.getString config "user" nil "name")
        configured-email (.getString config "user" nil "email")]
    (when (str/blank? configured-name)
      (.setString config "user" nil "name" name))
    (when (str/blank? configured-email)
      (.setString config "user" nil "email" email))))

(defn remote-origin-url [^Git git]
  (let [config (.. git getRepository getConfig)]
    (not-empty (.getString config "remote" "origin" "url"))))

(defn worktree [^Git git]
  (.getWorkTree (.getRepository git)))

(defn get-current-commit-ref [^Git git]
  (get-commit (.getRepository git) "HEAD"))

(defn status [^Git git]
  (let [s (-> git (.status) (.call))]
    {:added                   (set (.getAdded s))
     :changed                 (set (.getChanged s))
     :conflicting             (set (.getConflicting s))
     :ignored-not-in-index    (set (.getIgnoredNotInIndex s))
     :missing                 (set (.getMissing s))
     :modified                (set (.getModified s))
     :removed                 (set (.getRemoved s))
     :uncommitted-changes     (set (.getUncommittedChanges s))
     :untracked               (set (.getUntracked s))
     :untracked-folders       (set (.getUntrackedFolders s))
     :conflicting-stage-state (apply hash-map
                                     (mapcat (fn [[k v]] [k (-> v str camel/->kebab-case keyword)])
                                             (.getConflictingStageState s)))}))

(defn unified-status
  "Get the actual status by comparing contents on disk and HEAD. The index, i.e. staged files, are ignored"
  ([^Git git]
   (unified-status git (status git)))
  ([^Git git git-status]
   (let [changed-paths (into #{}
                             (mapcat git-status)
                             [:added :changed :missing :modified :removed :untracked])]
     (if (empty? changed-paths)
       []
       (let [repository (.getRepository git)
             tree-walk (doto (TreeWalk. repository)
                         (.addTree (.getTree ^RevCommit (get-commit repository "HEAD")))
                         (.addTree (FileTreeIterator. repository))
                         (.setFilter (PathFilterGroup/createFromStrings ^Collection changed-paths))
                         (.setRecursive true))
             rename-detector (doto (RenameDetector. repository)
                               (.addAll (DiffEntry/scan tree-walk)))
             diff-entries (.compute rename-detector nil)]
         (mapv diff-entry->map diff-entries))))))

(defn file ^java.io.File [^Git git file-path]
  (io/file (str (worktree git) "/" file-path)))

(defn show-file
  (^bytes [^Git git name]
   (show-file git name "HEAD"))
  (^bytes [^Git git name ref]
   (let [repo (.getRepository git)]
     (with-open [rw (RevWalk. repo)]
       (let [last-commit-id (.resolve repo ref)
             commit (.parseCommit rw last-commit-id)
             tree (.getTree commit)]
         (with-open [tw (TreeWalk. repo)]
           (.addTree tw tree)
           (.setRecursive tw true)
           (.setFilter tw (PathFilter/create name))
           (.next tw)
           (let [id (.getObjectId tw 0)]
             (when (not= (ObjectId/zeroId) id)
               (let [loader (.open repo id)]
                 (.getBytes loader))))))))))

(defn locked-files
  "Returns a set of all files in the repository that could cause major operations
  on the work tree to fail due to permissions issues or file locks from external
  processes. Does not include ignored files or files below the .git directory."
  [^Git git]
  (let [locked-files (volatile! (transient #{}))
        repository (.getRepository git)
        work-directory-path (.toPath (.getWorkTree repository))
        dot-git-directory-path (.toPath (.getDirectory repository))
        {dirs true files false} (->> (.. git status call getIgnoredNotInIndex)
                                     (map #(.resolve work-directory-path ^String %))
                                     (group-by #(.isDirectory (.toFile ^Path %))))
        ignored-directory-paths (set dirs)
        ignored-file-paths (set files)]
    (Files/walkFileTree work-directory-path
                        (proxy [SimpleFileVisitor] []
                          (preVisitDirectory [^Path directory-path _attrs]
                            (if (contains? ignored-directory-paths directory-path)
                              FileVisitResult/SKIP_SUBTREE
                              FileVisitResult/CONTINUE))
                          (visitFile [^Path file-path _attrs]
                            (when (and (not (.startsWith file-path dot-git-directory-path))
                                       (not (contains? ignored-file-paths file-path)))
                              (let [file (.toFile file-path)]
                                (when (fs/locked-file? file)
                                  (vswap! locked-files conj! file))))
                            FileVisitResult/CONTINUE)
                          (visitFileFailed [^Path file-path _attrs]
                            (vswap! locked-files conj! (.toFile file-path))
                            FileVisitResult/CONTINUE)))
    (persistent! (deref locked-files))))

(defn locked-files-error-message [locked-files]
  (str/join "\n" (concat ["The following project files are locked or in use by another process:"]
                         (map #(str "\u00A0\u00A0\u2022\u00A0" %) ; "  * " (NO-BREAK SPACE, NO-BREAK SPACE, BULLET, NO-BREAK SPACE)
                              (sort locked-files))
                         [""
                          "Please ensure they are writable and quit other applications that reference files in the project before trying again."])))

(defn ensure-gitignore-configured!
  "When supplied a non-nil Git instance, ensures the repository has a .gitignore
  file that ignores our .internal and build output directories. Returns true if
  a change was made to the .gitignore file or a new .gitignore was created."
  [^Git git]
  (if (nil? git)
    false
    (let [gitignore-file (file git ".gitignore")
          ^String old-gitignore-text (when (.exists gitignore-file) (slurp gitignore-file :encoding "UTF-8"))
          old-gitignore-entries (some-> old-gitignore-text str/split-lines)
          old-gitignore-entries-set (into #{} (remove str/blank?) old-gitignore-entries)
          line-separator (text-util/guess-line-separator old-gitignore-text)]
      (if (empty? old-gitignore-entries-set)
        (do (spit gitignore-file (str/join line-separator default-gitignore-entries) :encoding "UTF-8")
            true)
        (let [new-gitignore-entries (into old-gitignore-entries
                                          (remove (fn [required-entry]
                                                    (or (contains? old-gitignore-entries-set required-entry)
                                                        (contains? old-gitignore-entries-set (fs/without-leading-slash required-entry)))))
                                          required-gitignore-entries)]
          (if (= old-gitignore-entries new-gitignore-entries)
            false
            (do (spit gitignore-file (str/join line-separator new-gitignore-entries) :encoding "UTF-8")
                true)))))))

(defn internal-files-are-tracked?
  "Returns true if the supplied Git instance is non-nil and we detect any
  tracked files under the .internal or build directories. This means a commit
  was made with an improperly configured .gitignore, and will likely cause
  issues during sync with collaborators."
  [^Git git]
  (if (nil? git)
    false
    (let [repo (.getRepository git)]
      (with-open [rw (RevWalk. repo)]
        (let [commit-id (.resolve repo "HEAD")
              commit (.parseCommit rw commit-id)
              tree (.getTree commit)
              ^Collection path-prefixes [".internal/" "build/"]]
          (with-open [tw (TreeWalk. repo)]
            (.addTree tw tree)
            (.setRecursive tw true)
            (.setFilter tw (PathFilterGroup/createFromStrings path-prefixes))
            (.next tw)))))))

(defn pull [^Git git ^UsernamePasswordCredentialsProvider creds]
  (-> (.pull git)
      (.setCredentialsProvider creds)
      (.call)))

(defn push [^Git git ^UsernamePasswordCredentialsProvider creds]
  (-> (.push git)
      (.setCredentialsProvider creds)
      (.call)))

(defn make-add-change [file-path]
  {:change-type :add :old-path nil :new-path file-path})

(defn make-delete-change [file-path]
  {:change-type :delete :old-path file-path :new-path nil})

(defn make-modify-change [file-path]
  {:change-type :modify :old-path file-path :new-path file-path})

(defn make-rename-change [old-path new-path]
  {:change-type :rename :old-path old-path :new-path new-path})

(defn change-path [{:keys [old-path new-path]}]
  (or new-path old-path))

(defn stage-change! [^Git git {:keys [change-type old-path new-path]}]
  (case change-type
    (:add :modify) (-> git .add (.addFilepattern new-path) .call)
    :delete        (-> git .rm (.addFilepattern old-path) .call)
    :rename        (do (-> git .add (.addFilepattern new-path) .call)
                       (-> git .rm (.addFilepattern old-path) .call))))

(defn unstage-change! [^Git git {:keys [change-type old-path new-path]}]
  (case change-type
    (:add :modify) (-> git .reset (.addPath new-path) .call)
    :delete        (-> git .reset (.addPath old-path) .call)
    :rename        (do (-> git .reset (.addPath new-path) .call)
                       (-> git .reset (.addPath old-path) .call))))

(defn revert-to-revision! [^Git git ^RevCommit start-ref]
  "High-level revert. Resets the working directory to the state it would have
  after a clean checkout of the specified start-ref. Performs the equivalent of
  git reset --hard
  git clean --force -d"
  (-> (.reset git)
      (.setMode ResetCommand$ResetType/HARD)
      (.setRef (.name start-ref))
      (.call))
  (-> (.clean git)
      (.setCleanDirectories true)
      (.call)))

(defn stash!
  "High-level stash. Before stashing, stages all untracked files. We do this
  because stashes internally treat untracked files differently from tracked
  files. Normally untracked files are not stashed at all, but even when using
  the setIncludeUntracked flag, untracked files are dealt with separately from
  tracked files.

  When later applied, a conflict among the tracked files will abort the stash
  apply command before the untracked files are restored. For our purposes, we
  want a unified set of conflicts among all the tracked and untracked files.

  Returns nil if there was nothing to stash, or a map with the stash ref and
  the set of file paths that were untracked at the time the stash was made."
  [^Git git]
  (let [untracked (:untracked (status git))]
    ; Stage any untracked files.
    (when (seq untracked)
      (let [add-command (.add git)]
        (doseq [path untracked]
          (.addFilepattern add-command path))
        (.call add-command)))

    ; Stash local changes and return stash info.
    (when-let [stash-ref (-> git .stashCreate .call)]
      {:ref stash-ref
       :untracked untracked})))

(defn stash-apply!
  [^Git git stash-info]
  (when stash-info
    ; Apply the stash. The apply-result will be either an ObjectId returned from
    ; (.call StashApplyCommand) or a StashApplyFailureException if thrown.
    (let [apply-result (try
                         (-> (.stashApply git)
                             (.setStashRef (.name ^RevCommit (:ref stash-info)))
                             (.call))
                         (catch StashApplyFailureException error
                           error))]
      ; Restore untracked state of all non-conflicting files
      ; that were untracked at the time the stash was made.
      (let [paths-with-conflicts (set (keys (:conflicting-stage-state (status git))))
            paths-to-unstage (into #{}
                                   (remove paths-with-conflicts)
                                   (:untracked stash-info))]
        (when (seq paths-to-unstage)
          (let [reset-command (.reset git)]
            (doseq [path paths-to-unstage]
              (.addPath reset-command path))
            (.call reset-command))))
      (if (instance? Throwable apply-result)
        (throw apply-result)
        apply-result))))

(defn stash-drop! [^Git git stash-info]
  (let [stash-ref ^RevCommit (:ref stash-info)
        stashes (map-indexed vector (.call (.stashList git)))
        matching-stash (first (filter #(= stash-ref (second %)) stashes))]
    (when matching-stash
      (-> (.stashDrop git)
          (.setStashRef (first matching-stash))
          (.call)))))

(defn commit [^Git git ^String message]
  (-> (.commit git)
      (.setMessage message)
      (.call)))

;; "High level" revert
;; * Changed files are checked out
;; * New files are removed
;; * Deleted files are checked out
;; NOTE: Not to be confused with "git revert"
(defn revert [^Git git files]
  (let [us (unified-status git)
        extra (->> files
                (map (fn [f] (find-original-for-renamed us f)))
                (remove nil?))
        co (-> git (.checkout))
        reset (-> git (.reset) (.setRef "HEAD"))]

    (doseq [f (concat files extra)]
      (.addPath co f)
      (.addPath reset f))
    (.call reset)
    (.call co))

  ;; Delete all untracked files in "files" as a last step
  ;; JGit doesn't support this operation with RmCommand
  (let [s (status git)]
    (doseq [f files]
      (when (contains? (:untracked s) f)
        (fs/delete-file! (file git f) {:missing :fail})))))

(defn make-clone-monitor [^ProgressBar progress-bar]
  (let [tasks (atom {"remote: Finding sources" 0
                     "remote: Getting sizes" 0
                     "remote: Compressing objects" 0
                     "Receiving objects" 0
                     "Resolving deltas" 0})
        set-progress (fn [task percent] (swap! tasks assoc task percent))
        current-progress (fn [] (let [n (count @tasks)]
                                  (if (zero? n) 0 (/ (reduce + (vals @tasks)) (float n) 100.0))))]
    (proxy [BatchingProgressMonitor] []
     (onUpdate
       ([taskName workCurr])
       ([taskName workCurr workTotal percentDone]
         (set-progress taskName percentDone)
         (ui/run-later (.setProgress progress-bar (current-progress)))))

     (onEndTask
       ([taskName workCurr])
       ([taskName workCurr workTotal percentDone])))))

;; =================================================================================

(defn selection-diffable? [selection]
  (and (= 1 (count selection))
       (let [change-type (:change-type (first selection))]
         (and (keyword? change-type)
              (not= :add change-type)
              (not= :delete change-type)))))

(defn selection-diff-data [git selection]
  (let [change (first selection)
        old-path (or (:old-path change) (:new-path change) )
        new-path (or (:new-path change) (:old-path change) )
        old (String. ^bytes (show-file git old-path))
        new (slurp (file git new-path))
        binary? (not-every? text-util/text-char? new)]
    {:binary? binary?
     :new new
     :new-path new-path
     :old old
     :old-path old-path}))

(defn init [^String path]
  (-> (Git/init) (.setDirectory (File. path)) (.call)))
