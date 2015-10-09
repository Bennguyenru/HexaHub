(ns integration.undo-test
  (:require [clojure.test :refer :all]
            [clojure.walk :as walk]
            [dynamo.graph :as g]
            [support.test-support :refer [with-clean-system]]
            [editor.project :as project]
            [editor.outline :as outline]
            [editor.switcher :as switcher]
            [integration.test-util :as test-util]))

(deftest open-editor
  (testing "Opening editor does not alter undo history"
           (with-clean-system
             (let [workspace  (test-util/setup-workspace! world)
                   project    (test-util/setup-project! workspace)
                   proj-graph (g/node-id->graph-id project)
                   app-view   (test-util/setup-app-view!)
                   _          (is (not (g/has-undo? proj-graph)))
                   atlas-node (test-util/resource-node project "/switcher/fish.atlas")
                   view       (test-util/open-scene-view! project app-view atlas-node 128 128)]
               (is (not (g/has-undo? proj-graph)))))))

(deftest undo-node-deletion-reconnects-editor
  (testing "Undoing the deletion of a node reconnects it to its editor"
           (with-clean-system
             (let [workspace            (test-util/setup-workspace! world)
                   project              (test-util/setup-project! workspace)
                   project-graph        (g/node-id->graph-id project)
                   app-view             (test-util/setup-app-view!)
                   atlas-node           (test-util/resource-node project "/switcher/fish.atlas")
                   view                 (test-util/open-scene-view! project app-view atlas-node 128 128)
                   check-conn           #(not (empty? (g/node-value view :scene)))
                   connected-after-open (check-conn)]
               (g/transact (g/delete-node atlas-node))
               (let [connected-after-delete (check-conn)]
                 (g/undo! project-graph)
                 (let [connected-after-undo (check-conn)]
                   (is connected-after-open)
                   (is (not connected-after-delete))
                   (is connected-after-undo)))))))

(deftest undo-unseq-tx-does-not-coalesce
  (testing "Undoing in unsequenced transactions does not coalesce"
           (with-clean-system
             (let [workspace     (test-util/setup-workspace! world)
                   project       (test-util/setup-project! workspace)
                   project-graph (g/node-id->graph-id project)
                   atlas-node    (test-util/resource-node project "/switcher/fish.atlas")]
               (g/transact
                 (concat
                   (g/operation-sequence (gensym))
                   (g/set-property atlas-node :margin 1)))
               (g/transact (g/set-property atlas-node :margin 10))
               (g/transact (g/set-property atlas-node :margin 2))
               (g/undo! project-graph)
               (is (= 10 (g/node-value atlas-node :margin)))))))

(defn outline-children [node-id] (:children (g/node-value node-id :node-outline)))

(deftest redo-undone-deletion-still-deletes
 (with-clean-system
   (let [workspace  (test-util/setup-workspace! world)
         project    (test-util/setup-project! workspace)
         proj-graph (g/node-id->graph-id project)
         go-node    (test-util/resource-node project "/switcher/test.go")]
     (is (= 1 (count (outline-children go-node))))

     (g/transact (g/delete-node (:node-id (first (outline-children go-node)))))

     (is (= 0 (count (outline-children go-node))))

     ;; undo deletion
     (g/undo! proj-graph)
     (is (= 1 (count (outline-children go-node))))

     ;; redo deletion
     (g/redo! proj-graph)

     (is (= 0 (count (outline-children go-node)))))))

(g/defnode DummyComponent
  (inherits outline/OutlineNode)
  (output node-outline outline/OutlineData (g/fnk [_node-id] {:node-id _node-id :label "dummy" :icon "" :children []})))

(g/defnode OutlineViewSimulator
  (input outline g/Any)

  (property counter g/Any)

  (output outline g/Any :cached
          (g/fnk [_node-id outline]
                 (swap! (g/node-value _node-id :counter) inc)
                 outline)))

(defn remove-fns
  "Dynamic functions are never equal. Strip them out of the outline"
  [outline]
  (let [fns #{:handler-fn :child-reqs}]
    (walk/postwalk
      (fn [form]
        (if-not (and (vector? form) (fns (first form)))
          form))
      outline)))

(defn- add-component! [project go-node-id]
  (let [op-seq     (gensym)
        proj-graph (g/node-id->graph-id project)
        component  (first
                    (g/tx-nodes-added
                     (g/transact
                      (concat
                       (g/operation-label "Add Component")
                       (g/operation-sequence op-seq)
                       (g/make-nodes proj-graph
                                     [comp-node DummyComponent]
                                     (g/connect comp-node :node-outline go-node-id :child-outlines))))))]
    (g/transact
     (concat
      (g/operation-sequence op-seq)
      (g/operation-label "Add Component")
      (project/select project [component])))
    component))

(deftest undo-redo-undo-redo
 (with-clean-system
   (let [workspace  (test-util/setup-workspace! world)
         project    (test-util/setup-project! workspace)
         proj-graph (g/node-id->graph-id project)
         app-view   (test-util/setup-app-view!)
         view-graph (g/node-id->graph-id app-view)
         go-node    (test-util/resource-node project "/switcher/test.go")
         outline-id (g/make-node! view-graph OutlineViewSimulator :counter (atom 0))
         component  (add-component! project go-node)]

     (g/transact (g/connect go-node :node-outline outline-id :outline))

     (let [original-outline (remove-fns (g/node-value outline-id :outline))]
       (g/reset-undo! proj-graph)

       ;; delete the component
       (g/transact
         [(g/operation-label "delete node")
          (g/delete-node component)])

       ;; force :outline to be cached
       (let [outline-without-component (remove-fns (g/node-value outline-id :outline))]

         ;; undo the deletion (component is back)
         (g/undo! proj-graph)

         ;; same :outline should be re-produced
         (let [outline-after-undo (remove-fns (g/node-value outline-id :outline))]
           (is (= original-outline outline-after-undo)))

         ;; redo the deletion (component is gone)
         (g/redo! proj-graph)

         ;; :outline should be re-produced again
         (is (= outline-without-component (remove-fns (g/node-value outline-id :outline))))

         ;; undo the deletion again (component is back again)
         (g/undo! proj-graph)

         ;; :outline should be re-produced
         (let [outline-after-second-undo (remove-fns (g/node-value outline-id :outline))]
           (is (= original-outline outline-after-second-undo)))

         ;; redo the deletion yet again (component is gone again)
         (g/redo! proj-graph)

         ;; :outline should be re-produced yet again
         (is (= outline-without-component (remove-fns (g/node-value outline-id :outline)))))))))

(defn- child-count [outline-id]
  (count (:children (g/node-value outline-id :outline))))

(deftest add-undo-updated-outline
 (with-clean-system
   (let [workspace  (test-util/setup-workspace! world)
         project    (test-util/setup-project! workspace)
         proj-graph (g/node-id->graph-id project)
         app-view   (test-util/setup-app-view!)
         view-graph (g/node-id->graph-id app-view)
         go-node    (test-util/resource-node project "/switcher/test.go")
         outline-id (g/make-node! view-graph OutlineViewSimulator :counter (atom 0))]
     (g/transact (g/connect go-node :node-outline outline-id :outline))
     (is (= 1 (child-count outline-id)))
     (let [component (add-component! project go-node)]
       (is (= 2 (child-count outline-id)))
       (g/undo! proj-graph)
       (is (= 1 (child-count outline-id)))))))
