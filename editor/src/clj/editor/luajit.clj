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

(ns editor.luajit
  (:require
   [clojure.java.io :as io]
   [clojure.java.shell :as shell]
   [clojure.string :as str]
   [editor.fs :as fs]
   [editor.system :as system])
  (:import
   (java.io File ByteArrayOutputStream)
   (com.defold.editor Platform)))

(set! *warn-on-reflection* true)

(defn- parse-compilation-error
  [s]
  (-> (zipmap [:exec :filename :line :message] (map str/trim (str/split s #":")))
      (update :line #(try (Integer/parseInt %) (catch Exception _)))))


(defn- luajit-exec-path
  [arch]
  (str (system/defold-unpack-path) "/" (.getPair (Platform/getJavaPlatform)) (str (case arch
                                                                                        :32-bit "/bin/luajit-32"
                                                                                        :64-bit "/bin/luajit-64"))))

(defn- luajit-lua-path
  []
  (str (system/defold-unpack-path) "/shared/luajit"))


;; We need to be consistent with the chunk names generated by the editor and bob
;; to ensure that the debugger works properly for both bundles apps and editor
;; builds. If the chunk names aren't consistent it will result in breakpoints
;; being skipped or files not being identified when hitting a breakpoint.

;; Bob sets the chunk name in LuaBuilder.constructBytecode():
;;
;; String chunkName = "@" + task.input(0).getPath()
;;
;; Resources in Bob are created in DefaultFileSystem.get() where the function
;; explicitly removes the initial / from the path.
;;
;; We replicate this chunk name format here by removing any initial /.
(defn luajit-path-to-chunk-name
  [^String s]
  (if (.startsWith s "/")
    (subs s 1)
    s))

;; The reverse of above
(defn chunk-name-to-luajit-path
  [^String s]
  (if (not (.startsWith s "/"))
    (str "/" s)
    s))

(defn- compile-file
  [proj-path ^File input ^File output arch]
  (let [{:keys [exit out err]} (shell/sh (luajit-exec-path arch)
                                         "-bgf"
                                         (str "@" (luajit-path-to-chunk-name proj-path))
                                         (.getAbsolutePath input)
                                         (.getAbsolutePath output)
                                         :env {"LUA_PATH" (str (luajit-lua-path) "/?.lua")})]
    (if-not (zero? exit)
      (let [{:keys [filename line message]} (parse-compilation-error err)]
        (throw (ex-info (format "Compilation failed: %s" message)
                        {:exit exit
                         :out out
                         :err err
                         :filename filename
                         :line line
                         :message message}))))))

(defn bytecode
  [source proj-path arch]
  (let [input (fs/create-temp-file! "script" ".lua")
        output (fs/create-temp-file! "script" ".luajitbc")]
    (try
      (io/copy source input)
      (compile-file proj-path input output arch)
      (with-open [buf (ByteArrayOutputStream.)]
        (io/copy output buf)
        (.toByteArray buf))
      (finally
        (fs/delete-file! input {:fail :silently})
        (fs/delete-file! output {:fail :silently})))))
