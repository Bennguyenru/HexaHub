(ns integration.script-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer :all]
            [dynamo.graph :as g]
            [editor.script :as script]
            [editor.resource :as resource]
            [editor.workspace :as workspace]
            [editor.defold-project :as project]
            [editor.code-completion :refer :all]
            [support.test-support :refer [tx-nodes with-clean-system]]
            [integration.test-util :as test-util]))

(defn make-script-resource
  [world workspace path code]
  (test-util/make-fake-file-resource workspace (g/node-value workspace :root) (io/file path) (.getBytes code "UTF-8")))

(deftest script-node-dependencies
  (with-clean-system
    (let [workspace        (test-util/setup-workspace! world "test/resources/empty_project")
          script-resource  (make-script-resource world workspace "test/resources/empty_project/test.script"
                                                 "x = 42")
          module1-resource (make-script-resource world workspace "test/resources/empty_project/module1.lua"
                                                 "y = 4711")
          module2-resource (make-script-resource world workspace "test/resources/empty_project/module2.lua"
                                                 "z = 11")
          project          (test-util/setup-project! workspace [script-resource module1-resource module2-resource])
          script-node      (project/get-resource-node project script-resource)
          module1-node     (project/get-resource-node project module1-resource)
          module2-node     (project/get-resource-node project module2-resource)]
      (testing "are empty when no modules have been required"
        (is (not (g/error? (g/node-value script-node :build-targets))))
        (is (empty? (g/node-value script-node :dep-build-targets)))
        (is (empty? (g/node-value script-node :module-completion-infos))))
      (testing "are added when a module is required"
        (g/set-property! script-node :code "local x = require('module1')")
        (is (not (g/error? (g/node-value script-node :build-targets))))
        (is (= (g/node-value script-node :dep-build-targets)
               [(g/node-value module1-node :build-targets)]))
        (is (= (g/node-value script-node :module-completion-infos)
               [(g/node-value module1-node :completion-info)])))
      (testing "are updated when a requirements change"
        (g/set-property! script-node :code "local x = require('module1')\nlocal y = require('module2')")
        (is (not (g/error? (g/node-value script-node :build-targets))))
        (is (= (set (g/node-value script-node :dep-build-targets))
               #{(g/node-value module1-node :build-targets)
                 (g/node-value module2-node :build-targets)}))
        (is (= (set (g/node-value script-node :module-completion-infos))
               #{(g/node-value module1-node :completion-info)
                 (g/node-value module2-node :completion-info)})))
      (testing "are updated when a required module is no longer required"
        (g/set-property! script-node :code "local x = require('module2')")
        (is (not (g/error? (g/node-value script-node :build-targets))))
        (is (= (g/node-value script-node :dep-build-targets)
               [(g/node-value module2-node :build-targets)]))
        (is (= (g/node-value script-node :module-completion-infos)
               [(g/node-value module2-node :completion-info)])))
      (testing "are removed when a module is no longer required"
        (g/set-property! script-node :code "local x = 4711")
        (is (not (g/error? (g/node-value script-node :build-targets))))
        (is (empty? (g/node-value script-node :dep-build-targets)))
        (is (empty? (g/node-value script-node :module-completion-infos)))))))

(defn- lines [& args] (str (str/join "\n" args) "\n"))

(deftest script-node-completions
  (testing "includes completions from direct requires"
    (with-clean-system
      (let [workspace        (test-util/setup-workspace! world "test/resources/empty_project")
            script-resource  (make-script-resource world workspace "test/resources/empty_project/test.script"
                                                   (lines "local a = require(\"module1\")"
                                                          "local b = require(\"subdir.module2\")"))
            module1-resource (make-script-resource world workspace "test/resources/empty_project/module1.lua"
                                                   (lines "local M = {}"
                                                          "function M.f1() end"
                                                          "function M.f2() end"
                                                          "return M"))
            module2-resource (make-script-resource world workspace "test/resources/empty_project/subdir/module2.lua"
                                                   (lines "local M = {}"
                                                          "function M.f3() end"
                                                          "return M"))
            project          (test-util/setup-project! workspace [module1-resource module2-resource script-resource])
            script-node      (project/get-resource-node project script-resource)
            completions      (g/node-value script-node :completions)]
        (is (= #{"a.f1" "a.f2"}
               (set (map :name (get completions "a")))))
        (is (= #{"b.f3"}
               (set (map :name (get completions "b"))))))))
  (testing "does not include completions from transitive requires"
    (with-clean-system
      (let [workspace        (test-util/setup-workspace! world "test/resources/empty_project")
            script-resource  (make-script-resource world workspace "test/resources/empty_project/test.script"
                                                   (lines "local a = require(\"module1\")"))
            module1-resource (make-script-resource world workspace "test/resources/empty_project/module1.lua"
                                                   (lines "local m2 = require(\"module2\")"
                                                          "local M = {}"
                                                          "function M.f() end"
                                                          "return M"))
            module2-resource (make-script-resource world workspace "test/resources/empty_project/subdir/module2.lua"
                                                   (lines "local M = {}"
                                                          "function M.g() end"
                                                          "return M"))
            project          (test-util/setup-project! workspace [module1-resource module2-resource script-resource])
            script-node      (project/get-resource-node project script-resource)
            completions      (g/node-value script-node :completions)]
        (is (= #{"a.f"}
               (set (map :name (get completions "a")))))))))
