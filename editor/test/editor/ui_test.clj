(ns editor.ui-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer :all]
            [editor.handler :as handler]
            [editor.menu :as menu]
            [editor.ui :as ui])
  (:import [javafx.fxml FXMLLoader]
           [javafx.scene Scene]
           [javafx.scene.control ListView Menu MenuBar MenuItem SelectionMode]
           [javafx.scene.layout Pane VBox]
           [javafx.stage Stage]))

(defn- make-fake-stage []
  (let [root (VBox.)
        stage (ui/make-stage)
        scene (Scene. root)]
    (.setScene stage scene)
    stage))

(defn fixture [f]
  (with-redefs [menu/*menus* (atom {})
                handler/*handlers* (atom {})
                ui/*main-stage* (atom (ui/run-now (make-fake-stage)))]
    (f)))

(use-fixtures :each fixture)

(deftest extend-menu-test
  (ui/extend-menu ::menubar nil
                  [{:label "File"
                    :children [ {:label "New"
                                 :id ::new} ]}])
  (ui/extend-menu ::save-menu ::new
                  [{:label "Save"}])
  (ui/extend-menu ::quit-menu ::new
                  [{:label "Quit"}])
  (is (= (#'menu/realize-menu ::menubar) [{:label "File"
                                           :children [{:label "New"
                                                      :id ::new}
                                                      {:label "Save"}
                                                      {:label "Quit"}]}])))

(defrecord TestSelectionProvider [selection]
  handler/SelectionProvider
  (selection [this] selection))

(deftest menu-test
  (ui/extend-menu ::my-menu nil
                  [{:label "File"
                    :children [{:label "Open"
                                :id ::open
                                :command :open}
                               {:label "Save"
                                :command :save}]}])

  (handler/defhandler :open :global
      (enabled? [selection] true)
      (run [selection] 123))

  (handler/defhandler :save :global
    (enabled? [selection] true)
    (run [selection] 124))

  (let [root (Pane.)
        scene (ui/run-now (Scene. root))
        selection-provider (TestSelectionProvider. [])
        command-context {:name :global :env {:selection []}}]
   (let [menu-items (#'ui/make-menu-items (#'menu/realize-menu ::my-menu) [command-context])]
     (is (= 1 (count menu-items)))
     (is (instance? Menu (first menu-items)))
     (is (= 2 (count (.getItems (first menu-items)))))
     (is (instance? MenuItem (first (.getItems (first menu-items))))))))


(deftest options-menu-test
  (ui/extend-menu ::my-menu nil
                  [{:label "Add"
                    :command :add}])

  (handler/defhandler :add :global
    (run [user-data] user-data)
    (active? [user-data] (or (not user-data) (= user-data 1)))
    (options [user-data] (when-not user-data [{:label "first"
                                               :command :add
                                               :user-data 1}
                                              {:label "second"
                                               :command :add
                                               :user-data 2}])))

  (let [command-context {:name :global :env {}}]
    (let [menu-items (#'ui/make-menu-items (#'menu/realize-menu ::my-menu) [command-context])]
      (is (= 1 (count menu-items)))
      (is (= 1 (count (.getItems (first menu-items))))))))

(deftest toolbar-test
  (ui/extend-menu ::my-menu nil
                  [{:label "Open"
                    :command :open
                    :id ::open}])

  (handler/defhandler :open :global
    (enabled? [selection] true)
    (run [selection] 123)
    (state [] false))

  (handler/defhandler :save :global
    (enabled? [selection] true)
    (run [selection] 124)
    (state [] false))

  (let [root (Pane.)
        scene (ui/run-now (Scene. root))
        selection-provider (TestSelectionProvider. [])
        command-context {:name :global :env {:selection []}}]
    (.setId root "toolbar")
    (ui/register-toolbar scene "#toolbar" ::my-menu)
    (ui/run-now (ui/refresh scene [command-context]))
    (let [c1 (ui/run-now (ui/refresh scene [command-context]) (.getChildren root))
          c2 (ui/run-now (ui/refresh scene [command-context]) (.getChildren root))]
      (is (= 1 (count c1) (count c2)))
      (is (= (.get c1 0) (.get c2 0))))

    (ui/extend-menu ::extra ::open
                    [{:label "Save"
                      :command :save}])
    (ui/run-now (ui/refresh scene [command-context]))
    (is (= 2 (count (.getChildren root))))))

(deftest menubar-test
  (ui/extend-menu ::my-menu nil
                  [{:label "File"
                    :children
                    [{:label "Open"
                      :id ::open
                      :command :open}]}])

  (handler/defhandler :open :global
    (enabled? [selection] true)
    (run [selection] 123))

  (handler/defhandler :save :global
    (enabled? [selection] true)
    (run [selection] 124))

  (let [root (Pane.)
        scene (ui/run-now (Scene. root))
        selection-provider (TestSelectionProvider. [])
        command-context {:name :global :env {:selection []}}
        menubar (MenuBar.)]
    (ui/run-now (.add (.getChildren root) menubar))
    (.setId menubar "menubar")
    (ui/register-menubar scene "#menubar" ::my-menu)
    (ui/run-now (ui/refresh scene [command-context]))
    (let [c1 (ui/run-now (ui/refresh scene [command-context]) (.getItems (first (.getMenus menubar))))
          c2 (ui/run-now (ui/refresh scene [command-context]) (.getItems (first (.getMenus menubar))))]
      (is (= 1 (count c1) (count c2)))
      (is (= (.get c1 0) (.get c2 0))))
    (ui/extend-menu ::extra ::open
                    [{:label "Save"
                      :command :save}])
    (ui/run-now (ui/refresh scene [command-context]))
    (let [c1 (ui/run-now (ui/refresh scene [command-context]) (.getItems (first (.getMenus menubar))))
          c2 (ui/run-now (ui/refresh scene [command-context]) (.getItems (first (.getMenus menubar))))]
      (is (= 2 (count c1) (count c2)))
      (is (= (.get c1 0) (.get c2 0))))))

(deftest list-view-test
  (let [selected-items (atom nil)]
    (ui/run-now
      (let [root (ui/main-root)
            list (doto (ListView.)
                   (ui/items! [:a :b :c :d]))]
        (doto (.getSelectionModel list)
          (.setSelectionMode SelectionMode/MULTIPLE))
        (ui/observe-list (ui/selection list)
                         (fn [_ values]
                           (reset! selected-items values)))
        (ui/add-child! root list)
        (doto (.getSelectionModel list)
          (.selectRange 1 3))))
    (is (= [:b :c] @selected-items))))
