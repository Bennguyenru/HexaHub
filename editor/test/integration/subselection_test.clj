(ns integration.subselection-test
  (:require [clojure.test :refer :all]
            [dynamo.graph :as g]
            [util.id-vec :as iv]
            [editor.types :as types]
            [editor.defold-project :as project]
            [editor.math :as math]
            [editor.properties :as properties]
            [editor.particlefx :as particlefx]
            [integration.test-util :as test-util]
            [support.test-support :refer [with-clean-system tx-nodes]])
  (:import [javax.vecmath Matrix4d Point3d Vector3d]
           [editor.properties Curve]))

(defn- select! [project selection]
  (let [opseq (gensym)]
    (project/select! project (mapv first selection) opseq)
    (project/sub-select! project selection opseq)))

(defn- selection [project]
  (g/node-value project :sub-selection))

(defrecord View [fb select-fn])

(defn- ->view [select-fn]
  (View. {} select-fn))

(defn view-render [view point user-data]
  (update view :fb assoc point user-data))

(defmulti render (fn [basis node-id view] (g/node-type* basis node-id)))

(defrecord Mesh [vertices]
  types/GeomCloud
  (types/geom-aabbs [this ids] (->> (iv/iv-filter-ids vertices ids)
                                (iv/iv-mapv (fn [[id v]] [id [v v]]))
                                (into {})))
  (types/geom-insert [this positions] (update this :vertices iv/iv-into positions))
  (types/geom-delete [this ids] (update this :vertices iv/iv-remove-ids ids))
  (types/geom-update [this ids f] (let [ids (set ids)]
                             (assoc this :vertices (iv/iv-mapv (fn [entry]
                                                                 (let [[id v] entry]
                                                                   (if (ids id) [id (f v)] entry))) vertices))))
  (types/geom-transform [this ids transform]
    (let [p (Point3d.)]
      (types/geom-update this ids (fn [v]
                             (let [[x y] v]
                               (.set p x y 0.0)
                               (.transform transform p)
                               [(.getX p) (.getY p) 0.0]))))))

(defn ->mesh [vertices]
  (Mesh. (iv/iv-vec vertices)))

(g/defnode Model
  (property mesh Mesh))

(defn- ->p3 [v]
  (let [[x y z] v]
    (Point3d. x y (or z 0.0))))

(defn- p3-> [^Point3d p]
  [(.getX p) (.getY p) (.getZ p)])

(defn- centroid [aabb]
  (let [[^Point3d min ^Point3d max] (map ->p3 aabb)]
    (.sub max min)
    (.scaleAdd max 0.5 min)
    (p3-> max)))

(defn- render-geom-cloud [basis view node-id property]
  (let [render-data (-> (g/node-value node-id property {:basis basis})
                      (types/geom-aabbs nil))]
    (reduce (fn [view [id aabb]] (view-render view (centroid aabb) {:node-id node-id
                                                                    :property property
                                                                    :element-id id}))
            view render-data)))

(defmethod render Model [basis node-id view]
  (render-geom-cloud basis view node-id :mesh))

(defmethod render particlefx/EmitterNode [basis node-id view]
  (render-geom-cloud basis view node-id :particle-key-alpha))

(defn- render-clear [view]
  (assoc view :fb {}))

(defn- render-all [view renderables]
  (reduce (fn [view r] (render (g/now) r view))
          view renderables))

(defn- box-select! [view box]
  (let [[minp maxp] box
        selection (->> (:fb view)
                    (filter (fn [[p v]]
                              (and (= minp (mapv min p minp))
                                   (= maxp (mapv max p maxp)))))
                    (map second)
                    (reduce (fn [s v]
                              (update-in s [(:node-id v) (:property v)]
                                         (fn [ids] (conj (or ids []) (:element-id v)))))
                            {})
                    (mapv identity))]
    ((:select-fn view) selection)))

;; Commands

(defn delete! [project]
  (let [s (selection project)]
    (g/transact (reduce (fn [tx-data v]
                          (if (sequential? v)
                            (let [[nid props] v]
                              (into tx-data
                                    (for [[k ids] props]
                                      (g/update-property nid k types/geom-delete ids))))
                            (into tx-data (g/delete-node s))))
                        [] s))))

(g/defnode MoveManip
  (input selection g/Any)
  (output position g/Any (g/fnk [selection basis]
                                (let [positions (->> (for [[nid props] selection
                                                           [k ids] props]
                                                       (map (fn [[id aabb]] [id (centroid aabb)]) (-> (g/node-value nid k {:basis basis})
                                                                                                    (types/geom-aabbs ids))))
                                                  (reduce into [])
                                                  (map second))
                                      avg (mapv / (reduce (fn [r p] (mapv + r p)) [0.0 0.0 0.0] positions)
                                                (repeat (double (count positions))))]
                                  avg))))

(defn- start-move [selection p]
  {:selection selection
   :origin p
   :basis (g/now)})

(defn- move! [ctx p]
  (let [origin (->p3 (:origin ctx))
        delta (doto (->p3 p)
                (.sub origin))
        basis (:basis ctx)
        selection (:selection ctx)
        transform (doto (Matrix4d.)
                    (.set (Vector3d. delta)))]
    (g/transact
      (for [[nid props] selection
            [k ids] props
            :let [v (g/node-value nid k {:basis basis})]]
        (g/set-property nid k (types/geom-transform v ids transform))))))

;; Tests

(deftest delete-mixed
  (with-clean-system
    (let [workspace (test-util/setup-workspace! world)
          project   (test-util/setup-project! workspace)
          pfx-id   (test-util/resource-node project "/particlefx/fireworks_big.particlefx")
          emitter (doto (:node-id (test-util/outline pfx-id [0]))
                    (g/set-property! :particle-key-alpha (properties/->curve [[0.0 0.0 1.0 0.0]
                                                                              [0.6 0.6 1.0 0.0]
                                                                              [1.0 1.0 1.0 0.0]])))
          proj-graph (g/node-id->graph-id project)
          [model] (tx-nodes (g/make-nodes proj-graph [model [Model :mesh (->mesh [[0.5 0.5] [0.9 0.9]])]]))
          view (-> (->view (fn [s] (select! project s)))
                 (render-all [model emitter]))
          box [[0.5 0.5] [0.9 0.9]]]
      (box-select! view box)
      (is (not (empty? (selection project))))
      (delete! project)
      (let [view (-> view
                   render-clear
                   (render-all [model emitter]))]
        (box-select! view box)
        (is (empty? (selection project)))))))

(deftest move-mixed
  (with-clean-system
    (let [workspace (test-util/setup-workspace! world)
          project   (test-util/setup-project! workspace)
          pfx-id   (test-util/resource-node project "/particlefx/fireworks_big.particlefx")
          emitter (doto (:node-id (test-util/outline pfx-id [0]))
                    (g/set-property! :particle-key-alpha (properties/->curve [[0.0 0.0 1.0 0.0]
                                                                              [0.6 0.6 1.0 0.0]
                                                                              [1.0 1.0 1.0 0.0]])))
          proj-graph (g/node-id->graph-id project)
          [model
           manip] (tx-nodes (g/make-nodes proj-graph [model [Model :mesh (->mesh [[0.5 0.5] [0.9 0.9]])]
                                                      manip MoveManip]
                                          (g/connect project :sub-selection manip :selection)))
          view (-> (->view (fn [s] (select! project s)))
                 (render-all [model emitter]))
          box [[0.5 0.5] [0.9 0.9]]]
      (box-select! view box)
      (is (not (empty? (selection project))))
      (is (= [(/ 2.0 3.0) (/ 2.0 3.0) 0.0] (g/node-value manip :position)))
      (-> (start-move (selection project) (g/node-value manip :position))
        (move! [2.0 2.0 0.0]))
      (let [view (-> view
                   render-clear
                   (render-all [model emitter]))]
        (box-select! view box)
        (is (empty? (selection project)))))))

(defn- near [v1 v2]
  (< (Math/abs (- v1 v2)) 0.000001))

(deftest insert-control-point
  (with-clean-system
    (let [workspace (test-util/setup-workspace! world)
         project   (test-util/setup-project! workspace)
         pfx-id   (test-util/resource-node project "/particlefx/fireworks_big.particlefx")
         emitter (doto (:node-id (test-util/outline pfx-id [0]))
                   (g/set-property! :particle-key-alpha (properties/->curve [[0.0 0.0 0.5 0.5]
                                                                             [0.5 0.5 0.5 0.5]
                                                                             [1.0 1.0 0.5 0.5]])))
         proj-graph (g/node-id->graph-id project)
         view (-> (->view (fn [s] (select! project s)))
                (render-all [emitter]))
         box [[0.5 0.5] [1.0 1.0]]
         half-sq-2 (* 0.5 (Math/sqrt 2.0))]
     (g/transact
       (g/update-property emitter :particle-key-alpha types/geom-insert [[0.25 0.25 0.0]]))
     (let [[x y tx ty] (-> (g/node-value emitter :particle-key-alpha)
                         :points
                         (iv/iv-filter-ids [4])
                         iv/iv-vals
                         first)]
       (is (near half-sq-2 tx))
       (is (near half-sq-2 ty))))))
