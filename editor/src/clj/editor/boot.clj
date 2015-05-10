(ns editor.boot
  (:require [clojure.java.io :as io]
            [dynamo.graph :as g]
            [dynamo.types :as t]
            [editor.app-view :as app-view]
            [editor.asset-browser :as asset-browser]
            [editor.atlas :as atlas]
            [editor.collection :as collection]
            [editor.core :as core]
            [editor.cubemap :as cubemap]
            [editor.game-object :as game-object]
            [editor.graph-view :as graph-view]
            [editor.image :as image]
            [editor.prefs :as prefs]
            [editor.jfx :as jfx]
            [editor.outline-view :as outline-view]
            [editor.platformer :as platformer]
            [editor.project :as project]
            [editor.properties-view :as properties-view]
            [editor.scene :as scene]
            [editor.sprite :as sprite]
            [editor.switcher :as switcher]
            [editor.text :as text]
            [editor.ui :as ui]
            [editor.workspace :as workspace]
            [clojure.stacktrace :as stack])
  (:import [com.defold.editor Start]
           [com.jogamp.opengl.util.awt Screenshot]
           [com.defold.editor EditorApplication]
           [java.awt Desktop]
           [javafx.application Platform]
           [javafx.collections FXCollections ObservableList]
           [javafx.embed.swing SwingFXUtils]
           [javafx.event ActionEvent EventHandler]
           [javafx.fxml FXMLLoader]
           [javafx.geometry Insets]
           [javafx.scene Scene Node Parent]
           [javafx.scene.control Button ColorPicker Label ListCell TextField TitledPane TextArea TreeItem TreeCell Menu MenuItem MenuBar Tab ProgressBar]
           [javafx.scene.image Image ImageView WritableImage PixelWriter]
           [javafx.scene.input MouseEvent]
           [javafx.scene.layout AnchorPane GridPane StackPane HBox Priority VBox]
           [javafx.scene.paint Color]
           [javafx.stage Stage FileChooser]
           [javafx.util Callback]
           [java.io File]
           [java.nio.file Paths]
           [java.util.prefs Preferences]
           [javax.media.opengl GL GL2 GLContext GLProfile GLDrawableFactory GLCapabilities]))

(defn- setup-console [root]
  (.appendText (.lookup root "#console") "Hello Console"))

; Editors
(g/defnode CurveEditor
  (inherits core/Scope)
  (on :create
      (let [btn (Button.)]
        (.setText btn "Curve Editor WIP!")
        (.add (.getChildren (:parent event)) btn)))

  t/IDisposable
  (dispose [this]))

(defn on-outline-selection-fn [project items]
  (project/select project (map :self items)))

(def ^:dynamic *workspace-graph*)
(def ^:dynamic *project-graph*)
(def ^:dynamic *view-graph*)

(def the-root (atom nil))

(defn load-stage [workspace project]
  (let [root (FXMLLoader/load (io/resource "editor.fxml"))
        stage (Stage.)
        scene (Scene. root)]

    (ui/set-main-stage stage)
    (.setScene stage scene)
    (.show stage)

    (let [close-handler (ui/event-handler event
                          (g/transact
                            (g/delete-node project))
                          (g/dispose-pending))
          dispose-handler (ui/event-handler event (g/dispose-pending))]
      (.addEventFilter stage MouseEvent/MOUSE_MOVED dispose-handler)
      (.setOnCloseRequest stage close-handler))
    (setup-console root)
    (let [app-view (app-view/make-app-view *view-graph* *project-graph* project stage (.lookup root "#menu-bar") (.lookup root "#editor-tabs"))
          outline-view (outline-view/make-outline-view *view-graph* (.lookup root "#outline") (fn [items] (on-outline-selection-fn project items)))]
      (g/transact
        (concat
          (g/connect project :selection outline-view :selection)
          (for [label [:active-resource :active-outline :open-resources]]
            (g/connect app-view label outline-view label))
          (g/update-property app-view :auto-pulls conj [outline-view :tree-view])))
      (asset-browser/make-asset-browser workspace (.lookup root "#assets") (fn [resource] (app-view/open-resource app-view workspace project resource))))
    (graph-view/setup-graph-view root *project-graph*)
    (reset! the-root root)
    root))

(defn- create-view [game-project root place node-type]
  (let [node (g/make-node! (g/node->graph-id game-project) node-type)]
    (g/dispatch-message (g/now) node :create :parent (.lookup root place))))

(defn setup-workspace [project-path]
  (let [workspace (workspace/make-workspace *workspace-graph* project-path)]
    (g/transact
      (concat
        (text/register-view-types workspace)
        (scene/register-view-types workspace)))
    (let [workspace (g/refresh workspace)]
      (g/transact
        (concat
          (collection/register-resource-types workspace)
          (game-object/register-resource-types workspace)
          (cubemap/register-resource-types workspace)
          (image/register-resource-types workspace)
          (atlas/register-resource-types workspace)
          (platformer/register-resource-types workspace)
          (switcher/register-resource-types workspace)
          (sprite/register-resource-types workspace))))
    (g/refresh workspace)))


(defn open-project
  [^File game-project-file]
  (let [progress-bar nil
        project-path (.getPath (.getParentFile game-project-file))
        workspace    (setup-workspace project-path)
        project      (project/make-project *project-graph* workspace)
        project      (project/load-project project)
        root         (ui/run-now (load-stage workspace project))
        curve        (ui/run-now (create-view project root "#curve-editor-container" CurveEditor))
        properties   (ui/run-now (properties-view/make-properties-view *view-graph* (.lookup root "#properties")))]
    (g/transact (g/connect project :selection properties :selection))
    (g/reset-undo! *project-graph*)))


(defn- add-to-recent-projects [project-file]
  (let [recent (->> (prefs/get-prefs "recent-projects" [])
                 (remove #(= % (str project-file)))
                 (cons (str project-file))
                 (take 3))]
    (prefs/set-prefs "recent-projects" recent)))

(defn- make-list-cell [file]
  (let [path (.toPath file)
        parent (.getParent path)
        vbox (VBox.)
        project-label (Label. (str (.getFileName parent)))
        path-label (Label. (str (.getParent parent)))]
    ; TODO: Should be css stylable
    (.setStyle path-label "-fx-text-fill: grey; -fx-font-size: 10px;")
    (.addAll (.getChildren vbox) [project-label path-label])
    vbox))

(def main)

(defn open-welcome []
  (let [root (FXMLLoader/load (io/resource "welcome.fxml"))
        stage (Stage.)
        scene (Scene. root)
        recent-projects (.lookup root "#recent-projects")
        open-project (.lookup root "#open-project")]
    (.setOnAction open-project (ui/event-handler event (when-let [file-name (ui/choose-file "Open Project" "Project Files" ["*.project"])]
                                                         (.close stage)
                                                         ; NOTE (TODO): We load the project in the same class-loader as welcome is loaded from.
                                                         ; In other words, we can't reuse the welcome page and it has to be closed.
                                                         ; We should potentially changed this when we have uberjar support and hence
                                                         ; faster loading.
                                                         (main [file-name]))))

    (.setOnMouseClicked recent-projects (ui/event-handler e (when (= 2 (.getClickCount e))
                                                              (when-let [file (-> recent-projects (.getSelectionModel) (.getSelectedItem))]
                                                                (.close stage)
                                                                ; See comment above about main and class-loaders
                                                                (main [(.getAbsolutePath file)])))))
    (.setCellFactory recent-projects (reify Callback (call ^ListCell [this view]
                                                       (proxy [ListCell] []
                                                         (updateItem [file empty]
                                                           (proxy-super updateItem file empty)
                                                           (if (or empty (nil? file))
                                                             (proxy-super setText nil)
                                                             (proxy-super setGraphic (make-list-cell file))))))))
    (let [recent (->>
                   (prefs/get-prefs "recent-projects" [])
                   (map io/file)
                   (filter #(.isFile %)))]
      (.addAll (.getItems recent-projects) recent))
    (.setScene stage scene)
    (.setResizable stage false)
    (.show stage)))

(defn main [args]
  (if (= (count args) 0)
    (ui/run-later (open-welcome))
    (try
      (ui/modal-progress "Loading project" 100
                         (fn [report-fn]
                           (report-fn -1 "loading assets")
                           (when (nil? @the-root)
                             (g/initialize {})
                             (alter-var-root #'*workspace-graph* (fn [_] (g/last-graph-added)))
                             (alter-var-root #'*project-graph*   (fn [_] (g/make-graph! :history true  :volatility 1)))
                             (alter-var-root #'*view-graph*      (fn [_] (g/make-graph! :history false :volatility 2))))
                           (let [project-file (first args)]
                             (add-to-recent-projects project-file)
                             (open-project (io/file project-file)))))
      (catch Throwable t
        (stack/print-stack-trace t)
        (.flush *out*)
        (System/exit -1)))))
