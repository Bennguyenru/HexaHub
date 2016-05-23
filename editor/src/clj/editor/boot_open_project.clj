(ns editor.boot-open-project
  (:require [editor.defold-project :as project]
            [editor.progress :as progress]
            [editor.workspace :as workspace]
            [editor.ui :as ui]
            [editor.changes-view :as changes-view]
            [editor.properties-view :as properties-view]
            [editor.text :as text]
            [editor.code-view :as code-view]
            [editor.scene :as scene]
            [editor.form-view :as form-view]
            [editor.collection :as colleciton]
            [editor.font :as font]
            [editor.game-object :as game-object]
            [editor.game-project :as game-project]
            [editor.console :as console]
            [editor.cubemap :as cubemap]
            [editor.image :as image]
            [editor.workspace :as workspace]
            [editor.collection :as collection]
            [editor.atlas :as atlas]
            [editor.platformer :as platformer]
            [editor.protobuf-types :as protobuf-types]
            [editor.script :as script]
            [editor.switcher :as switcher]
            [editor.sprite :as sprite]
            [editor.gl.shader :as shader]
            [editor.tile-source :as tile-source]
            [editor.sound :as sound]
            [editor.spine :as spine]
            [editor.json :as json]
            [editor.mesh :as mesh]
            [editor.material :as material]
            [editor.particlefx :as particlefx]
            [editor.gui :as gui]
            [editor.app-view :as app-view]
            [editor.outline-view :as outline-view]
            [editor.asset-browser :as asset-browser]
            [editor.graph-view :as graph-view]
            [editor.core :as core]
            [dynamo.graph :as g]
            [editor.display-profiles :as display-profiles]
            [editor.web-profiler :as web-profiler]
            [util.http-server :as http-server])
  (:import  [java.io File]
            [javafx.scene.layout VBox]
            [javafx.scene Scene]
            [javafx.stage Stage]
            [javafx.scene.control Button TextArea]))

(set! *warn-on-reflection* true)

(def ^:dynamic *workspace-graph*)
(def ^:dynamic *project-graph*)
(def ^:dynamic *view-graph*)

(def the-root (atom nil))

;; inovked to control the timing of when the namepsaces load
(defn load-namespaces []
  (println "loaded namespaces"))

(defn initialize-project []
  (when (nil? @the-root)
    (g/initialize! {})
    (alter-var-root #'*workspace-graph* (fn [_] (g/last-graph-added)))
    (alter-var-root #'*project-graph*   (fn [_] (g/make-graph! :history true  :volatility 1)))
    (alter-var-root #'*view-graph*      (fn [_] (g/make-graph! :history false :volatility 2)))))

(g/defnode CurveEditor
    (inherits editor.core/Scope)

    editor.core/ICreate
    (post-create
     [this basis event]
     (let [btn (Button.)]
       (ui/text! btn "Curve Editor WIP!")
       (.add (.getChildren ^VBox (:parent event)) btn))))

(defn setup-workspace [project-path]
  (let [workspace (workspace/make-workspace *workspace-graph* project-path)]
    (g/transact
      (concat
        (text/register-view-types workspace)
        (code-view/register-view-types workspace)
        (scene/register-view-types workspace)
        (form-view/register-view-types workspace)))
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
      (mesh/register-resource-types workspace)
      (material/register-resource-types workspace)
      (particlefx/register-resource-types workspace)
      (gui/register-resource-types workspace)
      (display-profiles/register-resource-types workspace)))
    (workspace/resource-sync! workspace)
    workspace))

(defn load-stage [workspace project prefs]

  (let [^VBox root (ui/load-fxml "editor.fxml")
        stage (Stage.)
        scene (Scene. root)]
    (ui/observe (.focusedProperty stage)
                (fn [property old-val new-val]
                  (when (true? new-val)
                    (ui/with-disabled-ui
                      (ui/with-progress [render-fn ui/default-render-progress!]
                        (editor.workspace/resource-sync! workspace true [] render-fn))))))

    (ui/set-main-stage stage)
    (.setScene stage scene)
    (ui/show! stage)

    (let [close-handler (ui/event-handler event
                                          (g/transact
                                           (g/delete-node project)))]
      (.setOnCloseRequest stage close-handler))
    (let [^MenuBar menu-bar    (.lookup root "#menu-bar")
          ^TabPane editor-tabs (.lookup root "#editor-tabs")
          ^TabPane tool-tabs   (.lookup root "#tool-tabs")
          ^TreeView outline    (.lookup root "#outline")
          ^Tab assets          (.lookup root "#assets")
          console              (.lookup root "#console")
          prev-console         (.lookup root "#prev-console")
          next-console         (.lookup root "#next-console")
          clear-console        (.lookup root "#clear-console")
          search-console       (.lookup root "#search-console")
          app-view             (app-view/make-app-view *view-graph* *project-graph* project stage menu-bar editor-tabs prefs)
          outline-view         (outline-view/make-outline-view *view-graph* outline (fn [nodes] (project/select! project nodes)) project)
          properties-view      (properties-view/make-properties-view workspace project *view-graph* (.lookup root "#properties"))
          asset-browser        (asset-browser/make-asset-browser *view-graph* workspace assets
                                                                 (fn [resource & [opts]]
                                                                   (app-view/open-resource app-view workspace project resource (or opts {})))
                                                                 (partial app-view/remove-resource-tab editor-tabs))
          web-server           (-> (http-server/->server 0 {"/profiler" web-profiler/handler})
                                   http-server/start!)]
      (console/setup-console! {:text   console
                               :search search-console
                               :clear  clear-console
                               :next   next-console
                               :prev   prev-console})
      (ui/restyle-tabs! tool-tabs)
      (let [context-env {:app-view      app-view
                         :project       project
                         :project-graph (project/graph project)
                         :prefs         prefs
                         :workspace     (g/node-value project :workspace)
                         :outline-view  outline-view
                         :web-server    web-server}]
        (ui/context! (.getRoot (.getScene stage)) :global context-env (project/selection-provider project) {:active-resource [:app-view :active-resource]}))
      (g/transact
       (concat
        (g/connect project :selected-node-ids outline-view :selection)
        (for [label [:active-resource :active-outline :open-resources]]
          (g/connect app-view label outline-view label))
        (for [view [outline-view asset-browser]]
          (g/update-property app-view :auto-pulls conj [view :tree-view]))
        (g/update-property app-view :auto-pulls conj [properties-view :pane]))))
    (graph-view/setup-graph-view root)
    (reset! the-root root)
    root))

(defn- create-view [game-project ^VBox root place node-type]
  (let [node-id (g/make-node! (g/node-id->graph-id game-project) node-type)]
    (core/post-create (g/node-by-id node-id) (g/now) {:parent (.lookup root place)})))

(defn open-project
  [^File game-project-file prefs render-progress!]
  (let [progress     (atom (progress/make "Loading project" 3))
        _            (render-progress! @progress)
        project-path (.getPath (.getParentFile game-project-file))
        workspace    (setup-workspace project-path)
        project      (project/make-project *project-graph* workspace)
        project      (project/load-project project
                                           (g/node-value project :resources)
                                           (progress/nest-render-progress render-progress! @progress))
        _            (render-progress! (swap! progress progress/advance 1 "Updating dependencies"))
        _            (workspace/set-project-dependencies! workspace (project/project-dependencies project))
        _            (workspace/update-dependencies! workspace
                                                     (progress/nest-render-progress render-progress! @progress))
        _            (render-progress! (swap! progress progress/advance 1 "Reloading dependencies"))
        _            (workspace/resource-sync! workspace true []
                                               (progress/nest-render-progress render-progress! @progress))
        ^VBox root   (ui/run-now (load-stage workspace project prefs))
        curve        (ui/run-now (create-view project root "#curve-editor-container" CurveEditor))
        changes      (ui/run-now (changes-view/make-changes-view *view-graph* workspace
                                                                 (.lookup root "#changes-container")))]
    (workspace/update-version-on-disk! *workspace-graph*)
    (g/reset-undo! *project-graph*)))
