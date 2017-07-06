(ns integration.build-errors-test
  (:require [clojure.test :refer :all]
            [dynamo.graph :as g]
            [editor.build-errors-view :as build-errors-view]
            [editor.collection :as collection]
            [editor.defold-project :as project]
            [editor.game-object :as game-object]
            [editor.resource :as resource]
            [integration.test-util :as test-util]
            [internal.util :as util]
            [support.test-support :refer [with-clean-system]]))

(def ^:private project-path "test/resources/errors_project")

(defn- created-node [select-fn-call-logger]
  (let [calls (test-util/call-logger-calls select-fn-call-logger)
        args (last calls)
        selection (first args)
        node-id (first selection)]
    node-id))

(defn- build-error [render-error-fn-call-logger]
  (let [calls (test-util/call-logger-calls render-error-fn-call-logger)
        args (last calls)
        error-value (first args)]
    error-value))

(defn- add-empty-game-object! [workspace project collection]
  (let [select-fn (test-util/make-call-logger)]
    (collection/add-game-object workspace project collection collection select-fn)
    (let [embedded-go-instance (created-node select-fn)]
      (g/node-value embedded-go-instance :source-id))))

(defn- add-component-from-file! [workspace game-object resource-path]
  (let [select-fn (test-util/make-call-logger)]
    (game-object/add-component-file game-object (test-util/resource workspace resource-path) select-fn)
    (created-node select-fn)))

(defn- error-resource [error-tree]
  (get-in error-tree [:children 0 :value :resource]))

(defn- error-resource-node [error-tree]
  (get-in error-tree [:children 0 :value :node-id]))

(defn- error-outline-node [error-tree]
  (get-in error-tree [:children 0 :children 0 :node-id]))

(defn- find-outline-node [outline-node labels]
  (loop [labels (seq labels)
         node-outline (g/node-value outline-node :node-outline)]
    (if (empty? labels)
      (:node-id node-outline)
      (when-let [child-outline (util/first-where #(= (first labels) (:label %)) (:children node-outline))]
        (recur (next labels) child-outline)))))

(deftest build-errors-test
  (with-clean-system
    (let [workspace (test-util/setup-workspace! world project-path)
          project (test-util/setup-project! workspace)
          main-collection (test-util/resource-node project "/main/main.collection")
          game-object (add-empty-game-object! workspace project main-collection)
          resource (partial test-util/resource workspace)
          resource-node (partial test-util/resource-node project)
          outline-node (fn [resource-path labels] (find-outline-node (resource-node resource-path) labels))
          make-restore-point! #(test-util/make-graph-reverter (project/graph project))]

      (testing "Build error links to source node"
        (are [component-resource-path error-resource-path error-outline-path]
          (with-open [_ (make-restore-point!)]
            (let [render-error! (test-util/make-call-logger)]
              (add-component-from-file! workspace game-object component-resource-path)
              (project/build project main-collection {:render-error! render-error!})
              (let [error-value (build-error render-error!)]
                (when (is (some? error-value) component-resource-path)
                  (let [error-tree (build-errors-view/build-resource-tree error-value)]
                    (is (= (resource error-resource-path)
                           (error-resource error-tree)))
                    (is (= (resource-node error-resource-path)
                           (error-resource-node error-tree)))
                    (is (= (outline-node error-resource-path error-outline-path)
                           (error-outline-node error-tree)))))))
            true)

          "/errors/syntax_error.script"
          "/errors/syntax_error.script" []

          "/errors/button_break_self.gui"
          "/errors/button_break_self.gui" ["Nodes" "box"]

          "/errors/panel_using_button_break_self.gui"
          "/errors/button_break_self.gui" ["Nodes" "box"]

          "/errors/panel_break_button.gui"
          "/errors/panel_break_button.gui" ["Nodes" "button" "button/box"]

          "/errors/window_using_panel_break_button.gui"
          "/errors/panel_break_button.gui" ["Nodes" "button" "button/box"]

          "/errors/window_break_panel.gui"
          "/errors/window_break_panel.gui" ["Nodes" "panel" "panel/button"]

          "/errors/window_break_button.gui"
          "/errors/window_break_button.gui" ["Nodes" "panel" "panel/button" "panel/button/box"]))

      (testing "Errors from the same source are not duplicated"
        (with-open [_ (make-restore-point!)]
          (let [render-error! (test-util/make-call-logger)]
            (add-component-from-file! workspace game-object "/errors/button_break_self.gui")
            (add-component-from-file! workspace game-object "/errors/panel_break_button.gui")
            (add-component-from-file! workspace game-object "/errors/panel_using_button_break_self.gui")
            (add-component-from-file! workspace game-object "/errors/window_using_panel_break_button.gui")
            (project/build project main-collection {:render-error! render-error!})
            (let [error-value (build-error render-error!)]
              (when (is (some? error-value))
                (let [error-tree (build-errors-view/build-resource-tree error-value)]
                  (is (= ["/errors/button_break_self.gui" "/errors/panel_break_button.gui"]
                         (sort (map #(resource/proj-path (get-in % [:value :resource])) (:children error-tree)))))
                  (is (= 1 (count (get-in error-tree [:children 0 :children]))))
                  (is (= 1 (count (get-in error-tree [:children 1 :children])))))))))))))
