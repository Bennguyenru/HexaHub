(ns leiningen.init
  (:require
   [clojure.java.shell :as sh]
   [clojure.string :as string]
   [leiningen.core.main :as main]))

(defn- sha-from-version-file
  []
  (let [stable-version (slurp "../VERSION")
        {:keys [exit out err]} (sh/sh "git" "rev-list" "-n" "1" stable-version)]
    (if (zero? exit)
      (string/trim out)
      (throw (ex-info (format "Unable to determine engine version sha from version file, was '%s'" stable-version)
                      {:exit exit
                       :out out
                       :err err})))))

(defn- sha-from-ref
  [ref]
  (let [{:keys [exit out err]} (sh/sh "git" "rev-parse" ref)]
    (if (zero? exit)
      (string/trim out)
      (throw (ex-info (format "Unable to determine engine version sha for HEAD")
                      {:exit exit
                       :out out
                       :err err})))))

(defn- head-is-descended-from-branch?
  [branch]
  (let [{:keys [exit out err]} (sh/sh "git" "merge-base" "--is-ancestor" branch "HEAD")]
    (zero? exit)))

(defn- autodetect-sha
  []
  (println "Autodetecting which engine artifacts to use")
  (cond
    (head-is-descended-from-branch? "origin/dev")
    (do
      (println "origin/dev branch: Using artifacts from dynamo-home")
      nil)

    (head-is-descended-from-branch? "origin/editor-dev")
    (do
      (println "origin/editor-dev branch: Using artifacts from VERSION")
      (sha-from-version-file))

    :else
    (throw (Exception. "Don't know which artifacts to use for HEAD" ))))

(defn resolve-version
  [version]
  (when version
    (case version
      "dynamo-home"     nil ; for symmetry
      "archived-stable" (sha-from-version-file)
      "archived"        (sha-from-ref "HEAD")
      "auto"            (autodetect-sha)
      version)))

(defn init
  [project & [version]]
  (let [git-sha (resolve-version version)
        project (assoc project :engine git-sha)]
    (println (format "Initializing editor with version '%s', resolved to '%s'" version git-sha))
    (main/resolve-and-apply project ["do-init"])))
