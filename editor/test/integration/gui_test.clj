(ns integration.gui-test
  (:require [clojure.test :refer :all]
            [clojure.string :as str]
            [dynamo.graph :as g]
            [editor.app-view :as app-view]
            [editor.defold-project :as project]
            [editor.gl.pass :as pass]
            [editor.gui :as gui]
            [editor.handler :as handler]
            [editor.workspace :as workspace]
            [integration.test-util :as test-util]
            [support.test-support :refer [with-clean-system]])
  (:import [javax.vecmath Point3d Matrix4d Vector3d]))

(defn- prop [node-id label]
  (test-util/prop node-id label))

(defn- prop! [node-id label val]
  (test-util/prop! node-id label val))

(defn- gui-node [scene id]
  (let [id->node (->> (get-in (g/node-value scene :node-outline) [:children 0])
                   (tree-seq (constantly true) :children)
                   (map :node-id)
                   (map (fn [node-id] [(g/node-value node-id :id) node-id]))
                   (into {}))]
    (id->node id)))

(defn- gui-resource [resources-node-label scene id]
  (->> (-> scene
           (g/node-value resources-node-label)
           (g/node-value :node-outline))
       :children
       (filter #(= id (:label %)))
       first
       :node-id))

(def ^:private gui-texture (partial gui-resource :textures-node))
(def ^:private gui-font (partial gui-resource :fonts-node))
(def ^:private gui-layer (partial gui-resource :layers-node))
(def ^:private gui-spine-scene (partial gui-resource :spine-scenes-node))

(defn- property-value-choices [node-id label]
  (->> (g/node-value node-id :_properties)
       :properties
       label
       :edit-type
       :options
       (mapv first)))

(deftest load-gui
  (test-util/with-loaded-project
    (let [node-id   (test-util/resource-node project "/logic/main.gui")
          _gui-node (ffirst (g/sources-of node-id :child-outlines))]
      (is (some? _gui-node)))))

(deftest gui-scene-generation
  (test-util/with-loaded-project
    (let [node-id   (test-util/resource-node project "/logic/main.gui")
          scene (g/node-value node-id :scene)]
      (is (= 0.25 (get-in scene [:children 2 :children 0 :renderable :user-data :color 3]))))))

(deftest gui-scene-material
  (test-util/with-loaded-project
    (let [node-id   (test-util/resource-node project "/gui/simple.gui")
          scene (g/node-value node-id :scene)]
      (test-util/test-uses-assigned-material workspace project node-id
                                             :material
                                             [:children 0 :renderable :user-data :material-shader]
                                             [:children 0 :renderable :user-data :gpu-texture]))))

(deftest gui-scene-validation
  (test-util/with-loaded-project
    (let [node-id   (test-util/resource-node project "/logic/main.gui")]
      (is (nil? (test-util/prop-error node-id :script)))
      ;; Script is not required, so nil would be ok
      (test-util/with-prop [node-id :script nil]
        (is (nil? (test-util/prop-error node-id :script))))
      (test-util/with-prop [node-id :script (workspace/resolve-workspace-resource workspace "/not_found.script")]
        (is (g/error-fatal? (test-util/prop-error node-id :script))))
      (is (nil? (test-util/prop-error node-id :material)))
      (doseq [v [nil (workspace/resolve-workspace-resource workspace "/not_found.material")]]
        (test-util/with-prop [node-id :material v]
          (is (g/error-fatal? (test-util/prop-error node-id :material)))))
      (is (nil? (test-util/prop-error node-id :max-nodes)))
      (doseq [v [0 1025]]
        (test-util/with-prop [node-id :max-nodes v]
          (is (g/error-fatal? (test-util/prop-error node-id :max-nodes)))))
      ;; Valid number, but now the amount of nodes exceeds the max
      (test-util/with-prop [node-id :max-nodes 1]
        (is (g/error-fatal? (test-util/prop-error node-id :max-nodes)))))))

(deftest gui-box-auto-size
  (test-util/with-loaded-project
    (let [node-id   (test-util/resource-node project "/logic/main.gui")
          box (gui-node node-id "left")
          size [150.0 50.0 0.0]
          sizes {:ball [64.0 32.0 0.0]
                 :left-hud [200.0 640.0 0.0]}]
      (is (= size (g/node-value box :size)))
      (g/set-property! box :texture "atlas_texture/left_hud")
      (is (= size (g/node-value box :size)))
      (g/set-property! box :texture "atlas_texture/ball")
      (is (= size (g/node-value box :size)))
      (g/set-property! box :size-mode :size-mode-auto)
      (is (= (:ball sizes) (g/node-value box :size)))
      (g/set-property! box :texture "atlas_texture/left_hud")
      (is (= (:left-hud sizes) (g/node-value box :size))))))

(deftest gui-scene-pie
  (test-util/with-loaded-project
    (let [node-id   (test-util/resource-node project "/logic/main.gui")
          pie (gui-node node-id "hexagon")
          scene (g/node-value node-id :scene)]
      (is (> (count (get-in scene [:children 3 :renderable :user-data :line-data])) 0))
      (test-util/with-prop [pie :perimeter-vertices 3]
        (is (g/error-fatal? (test-util/prop-error pie :perimeter-vertices))))
      (test-util/with-prop [pie :perimeter-vertices 4]
        (is (not (g/error-fatal? (test-util/prop-error pie :perimeter-vertices)))))
      (test-util/with-prop [pie :perimeter-vertices 1000]
        (is (not (g/error-fatal? (test-util/prop-error pie :perimeter-vertices)))))
      (test-util/with-prop [pie :perimeter-vertices 1001]
        (is (g/error-fatal? (test-util/prop-error pie :perimeter-vertices)))))))

(deftest gui-textures
  (test-util/with-loaded-project
   (let [node-id   (test-util/resource-node project "/logic/main.gui")
         outline (g/node-value node-id :node-outline)
         png-node (get-in outline [:children 0 :children 1 :node-id])
         png-tex (get-in outline [:children 1 :children 0 :node-id])]
     (is (some? png-tex))
     (is (= "png_texture" (prop png-node :texture)))
     (prop! png-tex :name "new-name")
     (is (= "new-name" (prop png-node :texture))))))

(deftest gui-texture-validation
  (test-util/with-loaded-project
   (let [node-id   (test-util/resource-node project "/logic/main.gui")
        atlas-tex (:node-id (test-util/outline node-id [1 1]))]
     (test-util/with-prop [atlas-tex :name ""]
       (is (g/error-fatal? (test-util/prop-error atlas-tex :name))))
     (doseq [v [nil (workspace/resolve-workspace-resource workspace "/not_found.atlas")]]
       (test-util/with-prop [atlas-tex :texture v]
         (is (g/error-fatal? (test-util/prop-error atlas-tex :texture))))))))

(deftest gui-atlas
  (test-util/with-loaded-project
   (let [node-id   (test-util/resource-node project "/logic/main.gui")
         outline (g/node-value node-id :node-outline)
         box (get-in outline [:children 0 :children 2 :node-id])
         atlas-tex (get-in outline [:children 1 :children 1 :node-id])]
     (is (some? atlas-tex))
     (is (= "atlas_texture/anim" (prop box :texture)))
     (prop! atlas-tex :name "new-name")
     (is (= "new-name/anim" (prop box :texture))))))

(deftest gui-shaders
  (test-util/with-loaded-project
   (let [node-id   (test-util/resource-node project "/logic/main.gui")]
     (is (some? (g/node-value node-id :material-shader))))))

(defn- font-resource-node [project gui-font-node]
  (project/get-resource-node project (g/node-value gui-font-node :font)))

(defn- build-targets-deps [gui-scene-node]
  (map :node-id (:deps (first (g/node-value gui-scene-node :build-targets)))))

(deftest gui-fonts
  (test-util/with-loaded-project
   (let [gui-scene-node   (test-util/resource-node project "/logic/main.gui")
         outline (g/node-value gui-scene-node :node-outline)
         gui-font-node (get-in outline [:children 2 :children 0 :node-id])
         old-font (font-resource-node project gui-font-node)
         new-font (project/get-resource-node project "/fonts/big_score.font")]
     (is (some? (g/node-value gui-font-node :font-data)))
     (is (some #{old-font} (build-targets-deps gui-scene-node)))
     (g/transact (g/set-property gui-font-node :font (g/node-value new-font :resource)))
     (is (not (some #{old-font} (build-targets-deps gui-scene-node))))
     (is (some #{new-font} (build-targets-deps gui-scene-node))))))

(deftest gui-font-validation
  (test-util/with-loaded-project
   (let [gui-scene-node (test-util/resource-node project "/logic/main.gui")
         gui-font-node (:node-id (test-util/outline gui-scene-node [2 0]))]
     (is (nil? (test-util/prop-error gui-font-node :font)))
     (doseq [v [nil (workspace/resolve-workspace-resource workspace "/not_found.font")]]
       (test-util/with-prop [gui-font-node :font v]
         (is (g/error-fatal? (test-util/prop-error gui-font-node :font))))))))

(deftest gui-text-node
  (test-util/with-loaded-project
   (let [node-id   (test-util/resource-node project "/logic/main.gui")
         outline (g/node-value node-id :node-outline)
         nodes (into {} (map (fn [item] [(:label item) (:node-id item)]) (get-in outline [:children 0 :children])))
         text-node (get nodes "hexagon_text")]
     (is (= false (g/node-value text-node :line-break))))))

(deftest gui-text-node-validation
  (test-util/with-loaded-project
   (let [node-id   (test-util/resource-node project "/logic/main.gui")
         outline (g/node-value node-id :node-outline)
         nodes (into {} (map (fn [item] [(:label item) (:node-id item)]) (get-in outline [:children 0 :children])))
         text-node (get nodes "hexagon_text")]
     (are [prop v test] (test-util/with-prop [text-node prop v]
                          (is (test (test-util/prop-error text-node prop))))
       :font nil                  g/error-fatal?
       :font "not_a_defined_font" g/error-fatal?
       :font "highscore"          nil?))))

(deftest gui-text-node-text-layout
  (test-util/with-loaded-project
   (let [node-id   (test-util/resource-node project "/logic/main.gui")
         outline (g/node-value node-id :node-outline)
         nodes (into {} (map (fn [item] [(:label item) (:node-id item)]) (get-in outline [:children 0 :children])))
         text-node (get nodes "multi_line_text")]
     (is (some? (g/node-value text-node :text-layout)))
     (is (some? (g/node-value text-node :aabb)))
     (is (some? (g/node-value text-node :text-data))))))

(defn- render-order [view]
  (let [renderables (g/node-value view :renderables)]
    (->> (get renderables pass/transparent)
      (map :node-id)
      (filter #(and (some? %) (g/node-instance? gui/GuiNode %)))
      (map #(g/node-value % :id))
      vec)))

(deftest gui-layers
  (test-util/with-loaded-project
    (let [[node-id view] (test-util/open-scene-view! project app-view "/gui/layers.gui" 16 16)]
      (is (= ["box" "pie" "box1" "text"] (render-order view)))
      (g/set-property! (gui-node node-id "box") :layer "layer1")
      (is (= ["box1" "box" "pie" "text"] (render-order view)))
      (g/set-property! (gui-node node-id "box") :layer "")
      (is (= ["box" "pie" "box1" "text"] (render-order view))))))

;; Templates

(deftest gui-templates
  (test-util/with-loaded-project
    (let [node-id (test-util/resource-node project "/gui/scene.gui")
          original-template (test-util/resource-node project "/gui/sub_scene.gui")
          tmpl-node (gui-node node-id "sub_scene")
          path [:children 0 :node-id]]
      (is (not= (get-in (g/node-value tmpl-node :scene) path)
                (get-in (g/node-value original-template :scene) path))))))

(defn- drag-pull-outline! [scene-id node-id i]
  (g/set-property! node-id :position [i 0 0])
  (g/node-value scene-id :scene)
  (g/node-value scene-id :node-outline))

(defn- clock []
  (/ (System/nanoTime) 1000000.0))

(defmacro measure [binding & body]
  `(let [start# (clock)]
     (dotimes ~binding
       ~@body)
     (let [end# (clock)]
       (/ (- end# start#) ~(second binding)))))

(defn- test-load []
  ;; Don't use with-loaded-project here as we are in fact testing load speed
  (with-clean-system
    (let [workspace (test-util/setup-workspace! world)
          project (test-util/setup-project! workspace)])))

(deftest gui-template-outline-perf
  (testing "loading"
           ;; WARM-UP
           (dotimes [i 10]
             (test-load))
           (let [elapsed (measure [i 20]
                                  (test-load))]
         (is (< elapsed 1300))))
  (testing "drag-pull-outline"
           (test-util/with-loaded-project
             (let [node-id (test-util/resource-node project "/gui/scene.gui")
                   box (gui-node node-id "sub_scene/sub_box")]
               ;; (bench/bench (drag-pull-outline! node-id box))
               ;; WARM-UP
               (dotimes [i 20]
                 (drag-pull-outline! node-id box i))
               ;; GO!
               (let [elapsed (measure [i 500]
                                      (drag-pull-outline! node-id box i))]
                 (is (< elapsed 12)))))))

(deftest gui-template-ids
  (test-util/with-loaded-project
    (let [node-id (test-util/resource-node project "/gui/scene.gui")
          original-template (test-util/resource-node project "/gui/sub_scene.gui")
          tmpl-node (gui-node node-id "sub_scene")
          old-name "sub_scene/sub_box"
          new-name "sub_scene2/sub_box"]
      (is (not (nil? (gui-node node-id old-name))))
      (is (nil? (gui-node node-id new-name)))
      (g/transact (g/set-property tmpl-node :id "sub_scene2"))
      (is (not (nil? (gui-node node-id new-name))))
      (is (nil? (gui-node node-id old-name)))
      (is (true? (get-in (g/node-value tmpl-node :_declared-properties) [:properties :id :visible])))
      (is (false? (get-in (g/node-value tmpl-node :_declared-properties) [:properties :generated-id :visible])))
      (let [sub-node (gui-node node-id new-name)
            props (get (g/node-value sub-node :_declared-properties) :properties)]
        (is (= new-name (prop sub-node :generated-id)))
        (is (= (g/node-value sub-node :generated-id)
               (get-in props [:generated-id :value])))
        (is (false? (get-in props [:id :visible])))))))

(deftest gui-templates-complex-property
 (test-util/with-loaded-project
   (let [node-id (test-util/resource-node project "/gui/scene.gui")
         sub-node (gui-node node-id "sub_scene/sub_box")]
     (let [alpha (prop sub-node :alpha)]
       (g/transact (g/set-property sub-node :alpha (* 0.5 alpha)))
       (is (not= alpha (prop sub-node :alpha)))
       (g/transact (g/clear-property sub-node :alpha))
       (is (= alpha (prop sub-node :alpha)))))))

(deftest gui-template-hierarchy
  (test-util/with-loaded-project
    (let [node-id (test-util/resource-node project "/gui/super_scene.gui")
          sub-node (gui-node node-id "scene/sub_scene/sub_box")]
      (is (not= nil sub-node))
      (let [template (gui-node node-id "scene/sub_scene")
            resource (workspace/find-resource workspace "/gui/sub_scene.gui")]
        (is (= resource (get-in (g/node-value template :_properties) [:properties :template :value :resource])))
        (is (true? (get-in (g/node-value template :_properties) [:properties :template :read-only?]))))
      (let [template (gui-node node-id "scene")
            overrides (get-in (g/node-value template :_properties) [:properties :template :value :overrides])]
        (is (contains? overrides "sub_scene/sub_box"))
        (is (false? (get-in (g/node-value template :_properties) [:properties :template :read-only?])))))))

(deftest gui-template-selection
  (test-util/with-loaded-project
    (let [node-id (test-util/open-tab! project app-view "/gui/super_scene.gui")
          tmpl-node (gui-node node-id "scene/sub_scene")]
      (app-view/select! app-view [tmpl-node])
      (let [props (g/node-value app-view :selected-node-properties)]
        (is (not (empty? (keys props))))))))

(deftest gui-template-set-leak
  (test-util/with-loaded-project
    (let [node-id (test-util/resource-node project "/gui/scene.gui")
          sub-node (test-util/resource-node project "/gui/sub_scene.gui")
          tmpl-node (gui-node node-id "sub_scene")]
      (is (= 1 (count (g/overrides sub-node))))
      (g/transact (g/set-property tmpl-node :template {:resource (workspace/find-resource workspace "/gui/layers.gui") :overrides {}}))
      (is (= 0 (count (g/overrides sub-node)))))))

(defn- options [node-id prop]
  (mapv second (get-in (g/node-value node-id :_properties) [:properties prop :edit-type :options])))

(deftest gui-template-dynamics
  (test-util/with-loaded-project
    (let [node-id (test-util/resource-node project "/gui/super_scene.gui")
          box (gui-node node-id "scene/box")
          text (gui-node node-id "scene/text")]
      (is (= ["" "main/particle_blob" "main_super/particle_blob"] (options box :texture)))
      (is (= ["" "layer"] (options text :layer)))
      (is (= ["system_font" "system_font_super"] (options text :font)))
      (g/transact (g/set-property text :layer "layer"))
      (let [l (gui-layer node-id "layer")]
        (g/transact (g/set-property l :name "new-name"))
        (is (= "new-name" (prop text :layer)))))))

(defn- strip-scene [scene]
  (-> scene
    (select-keys [:node-id :children :renderable])
    (update :children (fn [c] (mapv #(strip-scene %) c)))
    (update-in [:renderable :user-data] select-keys [:color])
    (update :renderable select-keys [:user-data])))

(defn- scene-by-nid [root-id node-id]
  (let [scene (g/node-value root-id :scene)
        scenes (into {} (map (fn [s] [(:node-id s) s]) (tree-seq (constantly true) :children (strip-scene scene))))]
    (scenes node-id)))

(deftest gui-template-alpha
  (test-util/with-loaded-project
    (let [node-id   (test-util/resource-node project "/gui/super_scene.gui")
          scene-fn (comp (partial scene-by-nid node-id) (partial gui-node node-id))]
      (is (= 1.0 (get-in (scene-fn "scene/box") [:renderable :user-data :color 3])))
      (g/transact
        (concat
          (g/set-property (gui-node node-id "scene") :alpha 0.5)))
      (is (= 0.5 (get-in (scene-fn "scene/box") [:renderable :user-data :color 3]))))))

(deftest gui-template-reload
  (test-util/with-loaded-project
    (let [node-id (test-util/resource-node project "/gui/super_scene.gui")
          template (gui-node node-id "scene")
          box (gui-node node-id "scene/box")]
      (g/transact (g/set-property box :position [-100.0 0.0 0.0]))
      (is (= -100.0 (get-in (g/node-value template :_properties) [:properties :template :value :overrides "box" :position 0])))
      (use 'editor.gui :reload)
      (is (= -100.0 (get-in (g/node-value template :_properties) [:properties :template :value :overrides "box" :position 0]))))))

(deftest gui-template-add
  (test-util/with-loaded-project
    (let [node-id (test-util/resource-node project "/gui/scene.gui")
          super (test-util/resource-node project "/gui/super_scene.gui")
          parent (:node-id (test-util/outline node-id [0]))
          new-tmpl (gui/add-gui-node! project node-id parent :type-template (fn [node-ids] (app-view/select app-view node-ids)))
          super-template (gui-node super "scene")]
      (is (= new-tmpl (gui-node node-id "template")))
      (is (not (contains? (:overrides (prop super-template :template)) "template/sub_box")))
      (prop! new-tmpl :template {:resource (workspace/resolve-workspace-resource workspace "/gui/sub_scene.gui") :overrides {}})
      (is (contains? (:overrides (prop super-template :template)) "template/sub_box")))))

(deftest gui-template-overrides-anchors
  (test-util/with-loaded-project
    (let [node-id (test-util/resource-node project "/gui/scene.gui")
          box (gui-node node-id "sub_scene/sub_box")]
      (is (= :xanchor-left (g/node-value box :x-anchor))))))

;; Layouts

(deftest gui-layout
  (test-util/with-loaded-project
    (let [node-id (test-util/resource-node project "/gui/scene.gui")]
      (is (= ["Landscape"] (map :name (:layouts (g/node-value node-id :pb-msg))))))))

(defn- max-x [scene]
  (.getX ^Point3d (:max (:aabb scene))))

(defn- add-layout! [project app-view scene name]
  (let [parent (g/node-value scene :layouts-node)
        user-data {:scene scene :parent parent :display-profile name :handler-fn gui/add-layout-handler}]
    (test-util/handler-run :add [{:name :workbench :env {:selection [parent] :project project :user-data user-data :app-view app-view}}] user-data)))

(defn- add-gui-node! [project scene app-view parent node-type]
  (let [user-data {:scene scene :parent parent :node-type node-type :handler-fn gui/add-gui-node-handler}]
    (test-util/handler-run :add [{:name :workbench :env {:selection [parent] :project project :user-data user-data :app-view app-view}}] user-data)))

(defn- set-visible-layout! [scene layout]
  (g/transact (g/set-property scene :visible-layout layout)))

(deftest gui-layout-active
  (test-util/with-loaded-project
    (let [node-id (test-util/resource-node project "/gui/layouts.gui")
          box (gui-node node-id "box")
          dims (g/node-value node-id :scene-dims)
          scene (g/node-value node-id :scene)]
      (set-visible-layout! node-id "Landscape")
      (let [new-box (gui-node node-id "box")]
        (is (and new-box (not= box new-box))))
      (let [new-dims (g/node-value node-id :scene-dims)]
        (is (and new-dims (not= dims new-dims))))
      (let [new-scene (g/node-value node-id :scene)]
        (is (not= (max-x scene) (max-x new-scene)))))))

(deftest gui-layout-add
  (test-util/with-loaded-project
    (let [node-id (test-util/resource-node project "/gui/layouts.gui")
          box (gui-node node-id "box")]
      (add-layout! project app-view node-id "Portrait")
      (set-visible-layout! node-id "Portrait")
      (is (not= box (gui-node node-id "box"))))))

(deftest gui-layout-add-node
  (test-util/with-loaded-project
    (let [scene (test-util/resource-node project "/gui/layouts.gui")]
      (add-layout! project app-view scene "Portrait")
      (set-visible-layout! scene "Portrait")
      (let [node-tree (g/node-value scene :node-tree)]
        (is (= #{"box"} (set (map :label (:children (test-util/outline scene [0]))))))
        (add-gui-node! project scene app-view node-tree :type-box)
        (is (= #{"box" "box1"} (set (map :label (:children (test-util/outline scene [0]))))))))))

(defn- gui-text [scene id]
  (-> (gui-node scene id)
    (g/node-value :text)))

(defn- trans-x [root-id target-id]
  (let [s (tree-seq (constantly true) :children (g/node-value root-id :scene))]
    (when-let [^Matrix4d m (some #(and (= (:node-id %) target-id) (:transform %)) s)]
      (let [t (Vector3d.)]
        (.get m t)
        (.getX t)))))

(deftest gui-layout-template
  (test-util/with-loaded-project
    (let [node-id (test-util/resource-node project "/gui/super_scene.gui")]
      (testing "regular layout override, through templates"
               (is (= "Test" (gui-text node-id "scene/text")))
               (set-visible-layout! node-id "Landscape")
               (is (= "Testing Text" (gui-text node-id "scene/text"))))
      (testing "scene generation"
        (is (= {:width 1280 :height 720}
               (g/node-value node-id :scene-dims)))
        (set-visible-layout! node-id "Portrait")
        (is (= {:width 720 :height 1280}
               (g/node-value node-id :scene-dims)))))))

(deftest gui-legacy-alpha
  (test-util/with-loaded-project
    (let [node-id (test-util/resource-node project "/gui/legacy_alpha.gui")
          box (gui-node node-id "box")
          text (gui-node node-id "text")]
      (is (= 0.5 (g/node-value box :alpha)))
      (is (every? #(= 0.5 (g/node-value text %)) [:alpha :outline-alpha :shadow-alpha])))))

(deftest set-gui-layout
  (test-util/with-loaded-project
    (let [node-id (test-util/resource-node project "/gui/layouts.gui")
          gui-resource (g/node-value node-id :resource)
          context (handler/->context :workbench {:active-resource gui-resource :project project})
          options (test-util/handler-options :set-gui-layout [context] nil)
          options-by-label (zipmap (map :label options) options)]
      (is (= ["Default" "Landscape"] (map :label options)))
      (is (= (get options-by-label "Default") (test-util/handler-state :set-gui-layout [context] nil)))
      (g/set-property! node-id :visible-layout "Landscape")
      (is (= (get options-by-label "Landscape") (test-util/handler-state :set-gui-layout [context] nil))))))

(deftest spine-node-bone-order
  (test-util/with-loaded-project
    (let [scene-id (test-util/resource-node project "/gui/spine.gui")
          spine-scene-id (test-util/resource-node project "/spine/player/spineboy.spinescene")
          spine-node (gui-node scene-id "spine")]
      (testing "Order of generated bone nodes should be same as bone order in rig scene"
        (is (= (->> (g/node-value spine-scene-id :spine-scene-pb)
                    :skeleton
                    :bones
                    (map :name))
               (->> (g/node-value scene-id :node-rt-msgs)
                    (filter :spine-node-child )
                    (map #(str/replace-first (:id %) "spine/" "")))))))))

(deftest rename-referenced-gui-resource
  (test-util/with-loaded-project
    (let [make-restore-point! #(test-util/make-graph-reverter (project/graph project))
          scene (test-util/resource-node project "/gui_resources/gui_resources.gui")
          resources {:font (gui-font scene "font")
                     :layer (gui-layer scene "layer")
                     :texture (gui-texture scene "texture")
                     :spine-scene (gui-spine-scene scene "spine_scene")}
          shapes {:box (gui-node scene "box")
                  :pie (gui-node scene "pie")
                  :spine (gui-node scene "spine")
                  :text (gui-node scene "text")}]
      (is (every? (comp some? val) resources))
      (is (every? (comp some? val) shapes))

      (testing "Renaming font resource updates references"
        (with-open [_ (make-restore-point!)]
          (g/set-property! (:font resources) :name "renamed_font")
          (is (= "renamed_font" (g/node-value (:text shapes) :font)))
          (is (= ["renamed_font"] (property-value-choices (:text shapes) :font)))

          (testing "Reference remains updated after resource deletion"
            (g/delete-node! (:font resources))
            (is (= "renamed_font" (g/node-value (:text shapes) :font))))))

      (testing "Renaming layer resource updates references"
        (with-open [_ (make-restore-point!)]
          (g/set-property! (:layer resources) :name "renamed_layer")
          (is (= "renamed_layer" (g/node-value (:pie shapes) :layer)))
          (is (= ["" "renamed_layer"] (property-value-choices (:pie shapes) :layer)))

          (testing "Reference remains updated after resource deletion"
            (g/delete-node! (:layer resources))
            (is (= "renamed_layer" (g/node-value (:pie shapes) :layer))))))

      (testing "Renaming texture resource updates references"
        (with-open [_ (make-restore-point!)]
          (g/set-property! (:texture resources) :name "renamed_texture")
          (is (= "renamed_texture/particle_blob" (g/node-value (:box shapes) :texture)))
          (is (= ["" "renamed_texture/particle_blob"] (property-value-choices (:box shapes) :texture)))

          (testing "Reference remains updated after resource deletion"
            (g/delete-node! (:texture resources))
            (is (= "renamed_texture/particle_blob" (g/node-value (:box shapes) :texture))))))

      (testing "Renaming spine scene resource updates references"
        (with-open [_ (make-restore-point!)]
          (g/set-property! (:spine-scene resources) :name "renamed_spine_scene")
          (is (= "renamed_spine_scene" (g/node-value (:spine shapes) :spine-scene)))
          (is (= ["renamed_spine_scene"] (property-value-choices (:spine shapes) :spine-scene)))

          (testing "Reference remains updated after resource deletion"
            (g/delete-node! (:spine-scene resources))
            (is (= "renamed_spine_scene" (g/node-value (:spine shapes) :spine-scene)))))))))

(deftest rename-referenced-gui-resource-in-template
  (test-util/with-loaded-project
    (let [make-restore-point! #(test-util/make-graph-reverter (project/graph project))
          template-scene (test-util/resource-node project "/gui_resources/gui_resources.gui")
          template-resources {:font (gui-font template-scene "font")
                              :layer (gui-layer template-scene "layer")
                              :texture (gui-texture template-scene "texture")
                              :spine-scene (gui-spine-scene template-scene "spine_scene")}
          template-shapes {:box (gui-node template-scene "box")
                           :pie (gui-node template-scene "pie")
                           :spine (gui-node template-scene "spine")
                           :text (gui-node template-scene "text")}
          scene (test-util/resource-node project "/gui_resources/uses_gui_resources.gui")
          shapes {:box (gui-node scene "gui_resources/box")
                  :pie (gui-node scene "gui_resources/pie")
                  :spine (gui-node scene "gui_resources/spine")
                  :text (gui-node scene "gui_resources/text")}]
      (is (every? (comp some? val) template-resources))
      (is (every? (comp some? val) template-shapes))
      (is (every? (comp some? val) shapes))

      (testing "Renaming font in template scene"
        (with-open [_ (make-restore-point!)]
          (g/set-property! (:font template-resources) :name "renamed_font")
          (is (= "renamed_font" (g/node-value (:text template-shapes) :font)))
          (is (= "renamed_font" (g/node-value (:text shapes) :font)))
          (is (= ["renamed_font"] (property-value-choices (:text template-shapes) :font)))
          (is (= ["renamed_font"] (property-value-choices (:text shapes) :font)))
          (is (false? (g/property-overridden? (:text shapes) :font)))))

      (testing "Renaming layer in template scene"
        (with-open [_ (make-restore-point!)]
          (g/set-property! (:layer template-resources) :name "renamed_layer")
          (is (= "renamed_layer" (g/node-value (:pie template-shapes) :layer)))
          (is (= "renamed_layer" (g/node-value (:pie shapes) :layer)))
          (is (= ["" "renamed_layer"] (property-value-choices (:pie template-shapes) :layer)))
          (is (= [""] (property-value-choices (:pie shapes) :layer)))
          (is (false? (g/property-overridden? (:pie shapes) :layer)))))

      (testing "Renaming texture in template scene"
        (with-open [_ (make-restore-point!)]
          (g/set-property! (:texture template-resources) :name "renamed_texture")
          (is (= "renamed_texture/particle_blob" (g/node-value (:box template-shapes) :texture)))
          (is (= "renamed_texture/particle_blob" (g/node-value (:box shapes) :texture)))
          (is (= ["" "renamed_texture/particle_blob"] (property-value-choices (:box template-shapes) :texture)))
          (is (= ["" "renamed_texture/particle_blob"] (property-value-choices (:box shapes) :texture)))
          (is (false? (g/property-overridden? (:box shapes) :texture)))))

      (testing "Renaming spine scene in template scene"
        (with-open [_ (make-restore-point!)]
          (g/set-property! (:spine-scene template-resources) :name "renamed_spine_scene")
          (is (= "renamed_spine_scene" (g/node-value (:spine template-shapes) :spine-scene)))
          (is (= "renamed_spine_scene" (g/node-value (:spine shapes) :spine-scene)))
          (is (= ["renamed_spine_scene"] (property-value-choices (:spine template-shapes) :spine-scene)))
          (is (= ["renamed_spine_scene"] (property-value-choices (:spine shapes) :spine-scene)))
          (is (false? (g/property-overridden? (:spine shapes) :spine-scene))))))))

(deftest rename-referenced-gui-resource-in-outer-scene
  (test-util/with-loaded-project
    (let [make-restore-point! #(test-util/make-graph-reverter (project/graph project))
          template-scene (test-util/resource-node project "/gui_resources/gui_resources.gui")
          template-resources {:font (gui-font template-scene "font")
                              :layer (gui-layer template-scene "layer")
                              :texture (gui-texture template-scene "texture")
                              :spine-scene (gui-spine-scene template-scene "spine_scene")}
          template-shapes {:box (gui-node template-scene "box")
                           :pie (gui-node template-scene "pie")
                           :spine (gui-node template-scene "spine")
                           :text (gui-node template-scene "text")}
          scene (test-util/resource-node project "/gui_resources/replaces_gui_resources.gui")
          resources {:font (gui-font scene "replaced_font")
                     :layer (gui-layer scene "replaced_layer")
                     :texture (gui-texture scene "replaced_texture")
                     :spine-scene (gui-spine-scene scene "replaced_spine_scene")}
          shapes {:box (gui-node scene "gui_resources/box")
                  :pie (gui-node scene "gui_resources/pie")
                  :spine (gui-node scene "gui_resources/spine")
                  :text (gui-node scene "gui_resources/text")}]
      (is (every? (comp some? val) template-resources))
      (is (every? (comp some? val) template-shapes))
      (is (every? (comp some? val) resources))
      (is (every? (comp some? val) shapes))

      (testing "Renaming font in outer scene"
        (with-open [_ (make-restore-point!)]
          (g/set-property! (:font resources) :name "renamed_replaced_font")
          (is (= "font" (g/node-value (:text template-shapes) :font)))
          (is (= "renamed_replaced_font" (g/node-value (:text shapes) :font)))
          (is (= ["font" "renamed_replaced_font"] (property-value-choices (:text shapes) :font)))

          (testing "Reference remains updated after resource deletion"
            (g/delete-node! (:font resources))
            (is (= "renamed_replaced_font" (g/node-value (:text shapes) :font))))))

      (testing "Renaming layer in template scene"
        (with-open [_ (make-restore-point!)]
          (g/set-property! (:layer resources) :name "renamed_replaced_layer")
          (is (= "layer" (g/node-value (:pie template-shapes) :layer)))
          (is (= "renamed_replaced_layer" (g/node-value (:pie shapes) :layer)))
          (is (= ["" "renamed_replaced_layer"] (property-value-choices (:pie shapes) :layer)))

          (testing "Reference remains updated after resource deletion"
            (g/delete-node! (:layer resources))
            (is (= "renamed_replaced_layer" (g/node-value (:pie shapes) :layer))))))

      (testing "Renaming texture in template scene"
        (with-open [_ (make-restore-point!)]
          (g/set-property! (:texture resources) :name "renamed_replaced_texture")
          (is (= "texture/particle_blob" (g/node-value (:box template-shapes) :texture)))
          (is (= "renamed_replaced_texture/particle_blob" (g/node-value (:box shapes) :texture)))
          (is (= ["" "renamed_replaced_texture/particle_blob" "texture/particle_blob"] (property-value-choices (:box shapes) :texture)))

          (testing "Reference remains updated after resource deletion"
            (g/delete-node! (:texture resources))
            (is (= "renamed_replaced_texture/particle_blob" (g/node-value (:box shapes) :texture))))))

      (testing "Renaming spine scene in template scene"
        (with-open [_ (make-restore-point!)]
          (g/set-property! (:spine-scene resources) :name "renamed_replaced_spine_scene")
          (is (= "spine_scene" (g/node-value (:spine template-shapes) :spine-scene)))
          (is (= "renamed_replaced_spine_scene" (g/node-value (:spine shapes) :spine-scene)))
          (is (= ["renamed_replaced_spine_scene" "spine_scene"] (property-value-choices (:spine shapes) :spine-scene)))

          (testing "Reference remains updated after resource deletion"
            (g/delete-node! (:spine-scene resources))
            (is (= "renamed_replaced_spine_scene" (g/node-value (:spine shapes) :spine-scene)))))))))

(defn- add-font! [scene name resource]
  (first
    (g/tx-nodes-added
      (g/transact
        (gui/add-font scene (g/node-value scene :fonts-node) resource name)))))

(defn- add-layer! [scene name]
  (first
    (g/tx-nodes-added
      (gui/add-layer! nil scene (g/node-value scene :layers-node) name nil))))

(defn- add-texture! [scene name resource]
  (first
    (g/tx-nodes-added
      (g/transact
        (gui/add-texture scene (g/node-value scene :textures-node) resource name)))))

(defn- add-spine-scene! [scene name resource]
  (first
    (g/tx-nodes-added
      (g/transact
        (gui/add-spine-scene scene (g/node-value scene :spine-scenes-node) resource name)))))

(deftest introduce-missing-referenced-gui-resource
  (test-util/with-loaded-project
    (let [[workspace project _app-view] (test-util/setup! world)
          make-restore-point! #(test-util/make-graph-reverter (project/graph project))
          scene (test-util/resource-node project "/gui_resources/broken_gui_resources.gui")
          shapes {:box (gui-node scene "box")
                  :pie (gui-node scene "pie")
                  :spine (gui-node scene "spine")
                  :text (gui-node scene "text")}]
      (is (every? (comp some? val) shapes))

      (testing "Introduce missing referenced font"
        (with-open [_ (make-restore-point!)]
          (let [font-path "/fonts/highscore.font"
                font-resource (test-util/resource workspace font-path)
                font-resource-node (test-util/resource-node project font-path)
                after-font-data (g/node-value font-resource-node :font-data)]
          (is (not= after-font-data (g/node-value (:text shapes) :font-data)))
          (add-font! scene (g/node-value (:text shapes) :font) font-resource)
          (is (= after-font-data (g/node-value (:text shapes) :font-data))))))

      (testing "Introduce missing referenced layer"
        (with-open [_ (make-restore-point!)]
          (is (nil? (g/node-value (:pie shapes) :layer-index)))
          (add-layer! scene (g/node-value (:pie shapes) :layer))
          (is (= 0 (g/node-value (:pie shapes) :layer-index)))))

      (testing "Introduce missing referenced texture"
        (with-open [_ (make-restore-point!)]
          (let [texture-path "/gui/gui.atlas"
                texture-resource (test-util/resource workspace texture-path)
                texture-resource-node (test-util/resource-node project texture-path)
                [missing-texture-name anim-name] (str/split (g/node-value (:box shapes) :texture) #"/")
                after-anim-data (get (g/node-value texture-resource-node :anim-data) anim-name)]
            (is (string? (not-empty missing-texture-name)))
            (is (string? (not-empty anim-name)))
            (is (some? after-anim-data))
            (is (not= after-anim-data (g/node-value (:box shapes) :anim-data)))
            (add-texture! scene missing-texture-name texture-resource)
            (is (= after-anim-data (g/node-value (:box shapes) :anim-data))))))

      (testing "Introduce missing referenced spine scene"
        (with-open [_ (make-restore-point!)]
          (let [spine-scene-path "/spine/player/spineboy.spinescene"
                spine-scene-resource (test-util/resource workspace spine-scene-path)
                spine-scene-resource-node (test-util/resource-node project spine-scene-path)
                after-spine-anim-ids (into (sorted-set) (g/node-value spine-scene-resource-node :spine-anim-ids))]
          (is (not= after-spine-anim-ids (g/node-value (:spine shapes) :spine-anim-ids)))
          (add-spine-scene! scene (g/node-value (:spine shapes) :spine-scene) spine-scene-resource)
          (is (= after-spine-anim-ids (g/node-value (:spine shapes) :spine-anim-ids)))))))))

(deftest introduce-missing-referenced-gui-resource-in-template
  (test-util/with-loaded-project
    (let [[workspace project _app-view] (test-util/setup! world)
          make-restore-point! #(test-util/make-graph-reverter (project/graph project))
          template-scene (test-util/resource-node project "/gui_resources/broken_gui_resources.gui")
          template-shapes {:box (gui-node template-scene "box")
                           :pie (gui-node template-scene "pie")
                           :spine (gui-node template-scene "spine")
                           :text (gui-node template-scene "text")}
          scene (test-util/resource-node project "/gui_resources/uses_broken_gui_resources.gui")
          shapes {:box (gui-node scene "gui_resources/box")
                  :pie (gui-node scene "gui_resources/pie")
                  :spine (gui-node scene "gui_resources/spine")
                  :text (gui-node scene "gui_resources/text")}]
      (is (every? (comp some? val) template-shapes))
      (is (every? (comp some? val) shapes))

      (testing "Introduce missing referenced font in template scene"
        (with-open [_ (make-restore-point!)]
          (let [font-path "/fonts/highscore.font"
                font-resource (test-util/resource workspace font-path)
                font-resource-node (test-util/resource-node project font-path)
                after-font-data (g/node-value font-resource-node :font-data)]
            (is (not= after-font-data (g/node-value (:text template-shapes) :font-data)))
            (is (not= after-font-data (g/node-value (:text shapes) :font-data)))
            (add-font! template-scene (g/node-value (:text template-shapes) :font) font-resource)
            (is (= after-font-data (g/node-value (:text template-shapes) :font-data)))
            (is (= after-font-data (g/node-value (:text shapes) :font-data))))))

      (testing "Introduce missing referenced layer in template scene"
        (with-open [_ (make-restore-point!)]
          (is (nil? (g/node-value (:pie template-shapes) :layer-index)))
          (is (nil? (g/node-value (:pie shapes) :layer-index)))
          (add-layer! template-scene (g/node-value (:pie template-shapes) :layer))
          (is (= 0 (g/node-value (:pie template-shapes) :layer-index)))
          (is (= 0 (g/node-value (:pie shapes) :layer-index)))))

      (testing "Introduce missing referenced texture in template scene"
        (with-open [_ (make-restore-point!)]
          (let [texture-path "/gui/gui.atlas"
                texture-resource (test-util/resource workspace texture-path)
                texture-resource-node (test-util/resource-node project texture-path)
                [missing-texture-name anim-name] (str/split (g/node-value (:box template-shapes) :texture) #"/")
                after-anim-data (get (g/node-value texture-resource-node :anim-data) anim-name)]
            (is (string? (not-empty missing-texture-name)))
            (is (string? (not-empty anim-name)))
            (is (some? after-anim-data))
            (is (not= after-anim-data (g/node-value (:box template-shapes) :anim-data)))
            (is (not= after-anim-data (g/node-value (:box shapes) :anim-data)))
            (add-texture! template-scene missing-texture-name texture-resource)
            (is (= after-anim-data (g/node-value (:box template-shapes) :anim-data)))
            (is (= after-anim-data (g/node-value (:box shapes) :anim-data))))))

      (testing "Introduce missing referenced spine scene in template scene"
        (with-open [_ (make-restore-point!)]
          (let [spine-scene-path "/spine/player/spineboy.spinescene"
                spine-scene-resource (test-util/resource workspace spine-scene-path)
                spine-scene-resource-node (test-util/resource-node project spine-scene-path)
                after-spine-anim-ids (into (sorted-set) (g/node-value spine-scene-resource-node :spine-anim-ids))]
            (is (not= after-spine-anim-ids (g/node-value (:spine template-shapes) :spine-anim-ids)))
            (is (not= after-spine-anim-ids (g/node-value (:spine shapes) :spine-anim-ids)))
            (add-spine-scene! template-scene (g/node-value (:spine template-shapes) :spine-scene) spine-scene-resource)
            (is (= after-spine-anim-ids (g/node-value (:spine template-shapes) :spine-anim-ids)))
            (is (= after-spine-anim-ids (g/node-value (:spine shapes) :spine-anim-ids)))))))))
