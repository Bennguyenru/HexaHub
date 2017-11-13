(ns editor.system
  (:require [clojure.java.io :as io]))

(set! *warn-on-reflection* true)

(defn os-name
  ^String []
  (System/getProperty "os.name"))

(defn os-arch
  ^String []
  (System/getProperty "os.arch"))

(defn os-version
  ^String []
  (System/getProperty "os.version"))

(defn defold-version
  ^String []
  (System/getProperty "defold.version"))

(defn defold-channel
  ^String []
  (System/getProperty "defold.channel"))

(defn defold-resourcespath
  ^String []
  (System/getProperty "defold.resourcespath"))

(defn defold-editor-sha1
  ^String []
  (System/getProperty "defold.editor.sha1"))

(defn defold-engine-sha1
  ^String []
  (System/getProperty "defold.engine.sha1"))

(defn defold-build-time
  ^String []
  (System/getProperty "defold.buildtime"))

(defn defold-dev? []
  (not (defold-version)))

(defn java-home
  ^String []
  (System/getProperty "java.home"))

(defn user-home
  ^String []
  (System/getProperty "user.home"))

(defn java-runtime-version
  ^String []
  (System/getProperty "java.runtime.version"))

(defonce mac? (-> (os-name)
                  (.toLowerCase)
                  (.indexOf "mac")
                  (>= 0)))
