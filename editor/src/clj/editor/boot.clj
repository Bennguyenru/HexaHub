(ns editor.boot
  (:require [clojure.java.io :as io]
            [clojure.stacktrace :as stack]
            [dynamo.graph :as g]
            [editor.app-view :as app-view]
            [editor.asset-browser :as asset-browser]
            [editor.atlas :as atlas]
            [editor.changes-view :as changes-view]
            [editor.collection :as collection]
            [editor.core :as core]
            [editor.cubemap :as cubemap]
            [editor.game-object :as game-object]
            [editor.game-project :as game-project]
            [editor.graph-view :as graph-view]
            [editor.image :as image]
            [editor.import :as import]
            [editor.outline-view :as outline-view]
            [editor.platformer :as platformer]
            [editor.prefs :as prefs]
            [editor.project :as project]
            [editor.properties-view :as properties-view]
            [editor.scene :as scene]
            [editor.sprite :as sprite]
            [editor.switcher :as switcher]
            [editor.font :as font]
            [editor.protobuf-types :as protobuf-types]
            [editor.script :as script]
            [editor.gl.shader :as shader]
            [editor.tile-source :as tile-source]
            [editor.sound :as sound]
            [editor.spine :as spine]
            [editor.json :as json]
            [editor.mesh :as mesh]
            [editor.text :as text]
            [editor.ui :as ui]
            [editor.workspace :as workspace])
  (:import [com.defold.editor EditorApplication]
           [com.defold.editor Start]
           [com.jogamp.opengl.util.awt Screenshot]
           [java.awt Desktop]
           [javafx.application Platform]
           [javafx.collections FXCollections ObservableList]
           [javafx.embed.swing SwingFXUtils]
           [javafx.event ActionEvent EventHandler]
           [javafx.fxml FXMLLoader]
           [javafx.geometry Insets]
           [javafx.scene Scene Node Parent]
           [javafx.scene.control Button Control ColorPicker Label ListCell ListView TextField
            TitledPane TextArea TreeItem TreeCell Menu MenuItem MenuBar Tab ProgressBar]
           [javafx.scene.image Image ImageView WritableImage PixelWriter]
           [javafx.scene.input MouseEvent]
           [javafx.scene.layout AnchorPane GridPane StackPane HBox Priority VBox]
           [javafx.scene.paint Color]
           [javafx.stage Stage FileChooser]
           [javafx.util Callback]
           [java.io File]
           [java.nio.file Paths]
           [javax.media.opengl GL GL2 GLContext GLProfile GLDrawableFactory GLCapabilities]))

(defn- setup-console [^VBox root]
  (let [^TextArea node (.lookup root "#console")]
   (.appendText node "Hello Console")))

; Editors
(g/defnode CurveEditor
  (inherits core/Scope)

  core/ICreate
  (post-create
   [this basis event]
   (let [btn (Button.)]
     (ui/text! btn "Curve Editor WIP!")
     (.add (.getChildren ^VBox (:parent event)) btn)))

  g/IDisposable
  (dispose [this]))

(def ^:dynamic *workspace-graph*)
(def ^:dynamic *project-graph*)
(def ^:dynamic *view-graph*)

(def the-root (atom nil))

(defn load-stage [workspace project-id prefs]
  (let [^VBox root (FXMLLoader/load (io/resource "editor.fxml"))
        stage (Stage.)
        scene (Scene. root)]
    (ui/observe (.focusedProperty stage) (fn [property old-val new-val]
                                           (when (true? new-val)
                                             (workspace/fs-sync workspace))))

    (ui/set-main-stage stage)
    (.setScene stage scene)
    (ui/show! stage)

    (let [close-handler (ui/event-handler event
                          (g/transact
                            (g/delete-node project-id))
                          (g/dispose-pending!))
          dispose-handler (ui/event-handler event (g/dispose-pending!))]
      (.addEventFilter stage MouseEvent/MOUSE_MOVED dispose-handler)
      (.setOnCloseRequest stage close-handler))
    (setup-console root)
    (let [^MenuBar menu-bar    (.lookup root "#menu-bar")
          ^TabPane editor-tabs (.lookup root "#editor-tabs")
          ^TreeView outline    (.lookup root "#outline")
          ^Tab assets          (.lookup root "#assets")
          app-view-id          (app-view/make-app-view *view-graph* *project-graph* project-id stage menu-bar editor-tabs prefs)
          outline-view-id      (outline-view/make-outline-view *view-graph* outline (fn [nodes] (project/select! project-id nodes)) project-id)]
      (g/transact
        (concat
          (g/connect project-id :selected-node-ids outline-view-id :selection)
          (for [label [:active-resource :active-outline :open-resources]]
            (g/connect app-view-id label outline-view-id label))
          (g/update-property app-view-id :auto-pulls conj [outline-view-id :tree-view])))
      (asset-browser/make-asset-browser workspace assets (fn [resource] (app-view/open-resource app-view-id workspace project-id resource))))
    (graph-view/setup-graph-view root *project-graph*)
    (reset! the-root root)
    root))

(defn- create-view [game-project ^VBox root place node-type]
  (let [node-id (g/make-node! (g/node-id->graph-id game-project) node-type)]
    (core/post-create (g/node-by-id node-id) (g/now) {:parent (.lookup root place)})))

(defn setup-workspace [project-path]
  (let [workspace (workspace/make-workspace *workspace-graph* project-path)]
    (g/transact
      (concat
        (text/register-view-types workspace)
        (scene/register-view-types workspace)))
    (g/transact
     (concat
      (collection/register-resource-types workspace)
      (font/register-resource-types workspace)
      (game-object/register-resource-types workspace)
      (game-project/register-resource-types workspace)
      (cubemap/register-resource-types workspace)
      (image/register-resource-types workspace)
      (atlas/register-resource-types workspace)
      (platformer/register-resource-types workspace)
      (protobuf-types/register-resource-types workspace)
      (script/register-resource-types workspace)
      (switcher/register-resource-types workspace)
      (sprite/register-resource-types workspace)
      (shader/register-resource-types workspace)
      (tile-source/register-resource-types workspace)
      (sound/register-resource-types workspace)
      (spine/register-resource-types workspace)
      (json/register-resource-types workspace)
      (mesh/register-resource-types workspace)))
    workspace))

(defn open-project
  [^File game-project-file prefs]
  (let [progress-bar nil
        project-path (.getPath (.getParentFile game-project-file))
        workspace    (setup-workspace project-path)
        project      (project/make-project *project-graph* workspace)
        project      (project/load-project project)
        ^VBox root   (ui/run-now (load-stage workspace project prefs))
        curve        (ui/run-now (create-view project root "#curve-editor-container" CurveEditor))
        changes      (ui/run-now (changes-view/make-changes-view *view-graph* workspace (.lookup root "#changes-container")))
        properties   (ui/run-now (properties-view/make-properties-view workspace project *view-graph* (.lookup root "#properties")))]
    (g/reset-undo! *project-graph*)))

(defn- add-to-recent-projects [prefs project-file]
  (let [recent (->> (prefs/get-prefs prefs "recent-projects" [])
                 (remove #(= % (str project-file)))
                 (cons (str project-file))
                 (take 3))]
    (prefs/set-prefs prefs "recent-projects" recent)))

(defn- make-list-cell [^File file]
  (let [path (.toPath file)
        parent (.getParent path)
        vbox (VBox.)
        project-label (Label. (str (.getFileName parent)))
        path-label (Label. (str (.getParent parent)))
        ^"[Ljavafx.scene.control.Control;" controls (into-array Control [project-label path-label])]
    ; TODO: Should be css stylable
    (.setStyle path-label "-fx-text-fill: grey; -fx-font-size: 10px;")
    (.addAll (.getChildren vbox) controls)
    vbox))

(def main)


(defn open-welcome [prefs]
  (let [^VBox root (FXMLLoader/load (io/resource "welcome.fxml"))
        stage (Stage.)
        scene (Scene. root)
        ^ListView recent-projects (.lookup root "#recent-projects")
        ^Button open-project (.lookup root "#open-project")
        import-project (.lookup root "#import-project")]
    (ui/set-main-stage stage)
    (ui/on-action! open-project (fn [_] (when-let [file-name (ui/choose-file "Open Project" "Project Files" ["*.project"])]
                                          (ui/close! stage)
                                          ; NOTE (TODO): We load the project in the same class-loader as welcome is loaded from.
                                          ; In other words, we can't reuse the welcome page and it has to be closed.
                                          ; We should potentially changed this when we have uberjar support and hence
                                          ; faster loading.
                                          (main [file-name]))))

    (ui/on-action! import-project (fn [_] (when-let [file-name (import/open-import-dialog prefs)]
                                            (ui/close! stage)
                                            ; See comment above about main and class-loaders
                                            (main [file-name]))))

    (.setOnMouseClicked recent-projects (ui/event-handler e (when (= 2 (.getClickCount ^MouseEvent e))
                                                              (when-let [file (-> recent-projects (.getSelectionModel) (.getSelectedItem))]
                                                                (ui/close! stage)
                                                                ; See comment above about main and class-loaders
                                                                (main [(.getAbsolutePath ^File file)])))))
    (.setCellFactory recent-projects (reify Callback (call ^ListCell [this view]
                                                       (proxy [ListCell] []
                                                         (updateItem [file empty]
                                                           (let [this ^ListCell this]
                                                             (proxy-super updateItem file empty)
                                                             (if (or empty (nil? file))
                                                               (proxy-super setText nil)
                                                               (proxy-super setGraphic (make-list-cell file)))))))))
    (let [recent (->>
                   (prefs/get-prefs prefs "recent-projects" [])
                   (map io/file)
                   (filter (fn [^File f] (.isFile f)))
                   (into-array File))]
      (.addAll (.getItems recent-projects) ^"[Ljava.io.File;" recent))
    (.setScene stage scene)
    (.setResizable stage false)
    (ui/show! stage)))

(defn main [args]
  (let [prefs (prefs/make-prefs "defold")]
    (if (= (count args) 0)
      (ui/run-later (open-welcome prefs))
      (try
        (ui/modal-progress "Loading project" 100
                           (fn [report-fn]
                             (report-fn -1 "loading assets")
                             (when (nil? @the-root)
                               (g/initialize! {})
                               (alter-var-root #'*workspace-graph* (fn [_] (g/last-graph-added)))
                               (alter-var-root #'*project-graph*   (fn [_] (g/make-graph! :history true  :volatility 1)))
                               (alter-var-root #'*view-graph*      (fn [_] (g/make-graph! :history false :volatility 2))))
                             (let [project-file (first args)]
                               (add-to-recent-projects prefs project-file)
                               (open-project (io/file project-file) prefs))))
        (catch Throwable t
          (stack/print-stack-trace t)
          (.flush *out*)
          (System/exit -1))))))
