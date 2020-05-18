;; Copyright 2020 The Defold Foundation
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

(ns leiningen.builtins
  (:require [clojure.java.io :as io]
            [leiningen.util.http-cache :as http-cache]))

(defn- builtins-zip
  [sha]
  (if sha
    (http-cache/download (format "https://d.defold.com/archive/%s/engine/share/builtins.zip" sha))
    (io/file (format "%s/share/builtins.zip" (get (System/getenv) "DYNAMO_HOME")))))

(defn builtins [project & [git-sha]]
  (let [sha (or git-sha (:engine project))]
    (io/copy (builtins-zip sha)
             (io/file "generated-resources/builtins.zip"))))
