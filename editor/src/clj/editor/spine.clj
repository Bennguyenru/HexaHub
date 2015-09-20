(ns editor.spine
  (:require [clojure.java.io :as io]
            [editor.protobuf :as protobuf]
            [dynamo.graph :as g]
            [editor.geom :as geom]
            [editor.math :as math]
            [editor.gl :as gl]
            [editor.gl.shader :as shader]
            [editor.gl.vertex :as vtx]
            [editor.project :as project]
            [editor.scene :as scene]
            [editor.workspace :as workspace]
            [editor.pipeline.spine-scene-gen :as spine-scene-gen]
            [internal.render.pass :as pass])
  (:import [com.dynamo.graphics.proto Graphics$Cubemap Graphics$TextureImage Graphics$TextureImage$Image Graphics$TextureImage$Type]
           [com.dynamo.spine.proto Spine$SpineSceneDesc Spine$SpineScene]
           [editor.types Region Animation Camera Image TexturePacking Rect EngineFormatTexture AABB TextureSetAnimationFrame TextureSetAnimation TextureSet]
           [com.defold.editor.pipeline BezierUtil SpineScene$Transform TextureSetGenerator$UVTransform]
           [java.awt.image BufferedImage]
           [java.io PushbackReader]
           [javax.media.opengl GL GL2 GLContext GLDrawableFactory]
           [javax.media.opengl.glu GLU]
           [javax.vecmath Matrix4d Point2d Point3d Quat4d Vector2d Vector3d Vector4d Tuple3d Tuple4d]))

(set! *warn-on-reflection* true)

(def spine-scene-icon "icons/32/Icons_16-Spine-scene.png")
(def spine-model-icon "icons/32/Icons_15-Spine-model.png")

; Node defs

(g/defnk produce-save-data [resource spine-json atlas sample-rate]
  {:resource resource
   :content (protobuf/map->str Spine$SpineSceneDesc
              {:spine-json (workspace/proj-path spine-json)
               :atlas (workspace/proj-path atlas)
               :sample-rate sample-rate})})

(defprotocol Interpolator
  (interpolate [v0 v1 t]))

(extend-protocol Interpolator
  Point3d
  (interpolate [v0 v1 t]
    (doto (Point3d.) (.interpolate ^Tuple3d v0 ^Tuple3d v1 ^double t)))
  Vector3d
  (interpolate [v0 v1 t]
    (doto (Vector3d.) (.interpolate ^Tuple3d v0 ^Tuple3d v1 ^double t)))
  Vector4d
  (interpolate [v0 v1 t]
    (doto (Vector4d.) (.interpolate ^Tuple4d v0 ^Tuple4d v1 ^double t)))
  Quat4d
  (interpolate [v0 v1 t]
    (doto (Quat4d.) (.interpolate ^Quat4d v0 ^Quat4d v1 ^double t))))

(defn- ->vecmath [pb-field clj]
  (let [v (cond
            (= pb-field :positions) (Point3d.)
            (= pb-field :rotations) (Quat4d. 0 0 0 1)
            (= pb-field :scale) (Vector3d.)
            (= pb-field :colors) (Vector4d.))]
    (math/clj->vecmath v clj)
    v))

(def default-vals {:positions [0 0 0]
                   :rotations [0 0 0 1]
                   :scale [1 1 1]
                   :colors [1 1 1 1]
                   :attachment true
                   :order-offset 0})

(defn- curve [[x0 y0 x1 y1] t]
  (let [t (BezierUtil/findT t 0.0 x0 x1 1.0)]
    (BezierUtil/curve t 0.0 y0 y1 1.0)))

(defn- angle->clj-quat [angle]
  (let [half-rad (/ (* 0.5 angle Math/PI) 180.0)
        c (Math/cos half-rad)
        s (Math/sin half-rad)]
    [0 0 s c]))

(defn- angle->quat [angle]
  (let [[x y z w] (angle->clj-quat angle)]
    (Quat4d. x y z w)))

(defn- hex->color [^String hex]
  (loop [i 0
         color []]
    (if (< i 4)
      (let [offset (* i 2)
            value (/ (Integer/valueOf (.substring hex offset (+ 2 offset)) 16) 255.0)]
        (recur (inc i) (conj color value)))
      color)))

(defn- key->value [type key]
  (case type
    "translate" [(get key "x" 0) (get key "y" 0) 0]
    "rotate" (angle->clj-quat (get key "angle" 0))
    "scale" [(get key "x" 1) (get key "y" 1) 1]
    "color" (hex->color (get key "color"))
    "drawOrder" (get key "offset")))

(def timeline-type->pb-field {"translate" :positions
                              "rotate" :rotations
                              "scale" :scale
                              "color" :colors
                              "attachment" :visible
                              "drawOrder" :order-offset})

(defn- sample [type keys duration sample-rate spf val-fn default-val interpolate?]
  (let [pb-field (timeline-type->pb-field type)
        val-fn (or val-fn key->value)
        default-val (if (nil? default-val) (default-vals pb-field) default-val)
        sample-count (Math/ceil (+ 1 (* duration sample-rate)))
        ; Sort keys
        keys (vec (sort-by #(get % "time") keys))
        vals (mapv #(val-fn type %) keys)
        ; Add dummy key for 0
        [keys vals] (if (or (empty? keys) (> (get (first keys) "time") 0.0))
                      [(vec (cons {"time" 0.0
                                   "curve" "stepped"} keys))
                       (vec (cons default-val vals))]
                      [keys vals])
        ; Convert keys to vecmath vals
        vals (if interpolate?
               (mapv #(->vecmath pb-field %) vals)
               vals)
        ; Accumulate key counts into sample slots
        key-counts (reduce (fn [counts key]
                             (let [sample (int (* (get key "time") sample-rate))]
                               (update-in counts [sample] inc))) (vec (repeat sample-count 0)) keys)
        ; LUT from sample to key index
        sample->key-idx (loop [key-counts key-counts
                               v (transient [])
                               offset 0]
                          (if-let [key-count (first key-counts)]
                            (recur (rest key-counts) (conj! v offset) (+ (int key-count) offset))
                            (persistent! v)))
        samples (mapv (fn [sample]
                        (let [cursor (* spf sample)
                              idx1 (get sample->key-idx sample)
                              idx0 (dec idx1)
                              k0 (get keys idx0)
                              v0 (get vals idx0)
                              k1 (get keys idx1)
                              v1 (get vals idx1)
                              v (if (and k0 (not interpolate?))
                                  v0
                                  (if-let [k1 (get keys (get sample->key-idx sample))]
                                    (if (>= cursor (get k1 "time"))
                                      v1
                                      (let [c (let [curve (get k0 "curve" "linear")]
                                                (case curve
                                                  "linear" [0 0 1 1]
                                                  "stepped" [0 0 1 0]
                                                  curve))
                                            t (/ (- cursor (get k0 "time")) (- (get k1 "time") (get k0 "time")))
                                            rate (curve c t)]
                                        (interpolate v0 v1 rate)))
                                    v0))]
                          (if interpolate?
                            (math/vecmath->clj v)
                            v)))
                      (range sample-count))]
    (flatten samples)))

; This value is used to counter how the key values for rotations are interpreted in spine:
; * All values are modulated into the interval 0 <= x < 360
; * If keys k0 and k1 have a difference > 180, the second key is adjusted with +/- 360 to lessen the difference to < 180
; ** E.g. k0 = 0, k1 = 270 are interpolated as k0 = 0, k1 = -90
(defn- wrap-angles [type keys]
  (if (= type "rotate")
    (loop [prev-key nil
           keys keys
           wrapped []]
      (if-let [key (first keys)]
        (let [key (if prev-key
                    (let [angle (get key "angle" 0.0)
                          diff (double (- angle (get prev-key "angle" 0.0)))]
                      (if (> (Math/abs diff) 180.0)
                        (assoc key "angle" (+ angle (* (Math/signum diff) (- 360.0))))
                        key))
                    key)]
          (recur key (rest keys) (conj wrapped key)))
        wrapped))
    keys))

(defn- build-tracks [timelines duration sample-rate spf bone-id->index]
  (let [tracks-by-bone (reduce-kv (fn [m bone-name timeline]
                                    (let [bone-index (bone-id->index (protobuf/hash64 bone-name))]
                                      (reduce-kv (fn [m type keys]
                                                   (let [field (timeline-type->pb-field type)
                                                         pb-track {field (sample type (wrap-angles type keys) duration sample-rate spf nil nil true)
                                                                   :bone-index bone-index}]
                                                   (update-in m [bone-index] merge pb-track))) m timeline)))
                                  {} timelines)]
    (sort-by :bone-index (vals tracks-by-bone))))

(defn- build-mesh-tracks [slot-timelines do-timelines duration sample-rate spf slots-data slot->track-data]
  (let [; Reshape do-timelines into slot-timelines
        do-by-slot (into {} (map (fn [[slot timeline]]
                                   [slot {"drawOrder" timeline}])
                             (reduce (fn [m timeline]
                                      (let [t (get timeline "time")
                                            explicit (reduce (fn [m offset]
                                                               (assoc m (get offset "slot") [{"time" t "offset" (get offset "offset")}]))
                                                             {} (get timeline "offsets"))
                                            ; Supply implicit slots with 0 in offset
                                            all (reduce (fn [m slot]
                                                          (if (not (contains? m slot))
                                                            (assoc m slot [{"time" t "offset" 0}])
                                                            m))
                                                        explicit (keys m))]
                                        (merge-with into m all)))
                                    {} do-timelines)))
        slot-timelines (merge-with merge slot-timelines do-by-slot)
        tracks-by-slot (reduce-kv (fn [m slot timeline]
                                    (let [slot-data (get slots-data slot)
                                          tracks (mapv (fn [{:keys [skin-id mesh-index attachment]}]
                                                         (reduce-kv (fn [track type keys]
                                                                      (let [interpolate? (= type "color")
                                                                            val-fn (when (= type "attachment")
                                                                                     (fn [type key]
                                                                                       (= attachment (get key "name"))))
                                                                            default-val (when (= type "attachment")
                                                                                          (= (:attachment slot-data) attachment))
                                                                            field (timeline-type->pb-field type)
                                                                            pb-track {:mesh-index mesh-index
                                                                                      :skin-id skin-id
                                                                                      field (sample type keys duration sample-rate spf val-fn default-val interpolate?)}]
                                                                        (merge track pb-track)))
                                                                    {} timeline))
                                                       (slot->track-data slot))]
                                      (assoc m slot tracks)))
                               {} slot-timelines)]
    (flatten (vals tracks-by-slot))))

(defn- keys-duration [max-duration keys]
  (reduce (fn [duration key] (max duration (get key "time" 0))) max-duration keys))

(defn- map-map-keys-duration [max-duration m]
  (reduce-kv (fn [duration _ m-outer] (max duration (reduce-kv (fn [duration _ keys] (keys-duration duration keys)) duration m-outer))) max-duration m))

(defn- anim-duration [anim]
  (-> 0
    (map-map-keys-duration (get anim "bones"))
    (map-map-keys-duration (get anim "slots"))
    (keys-duration (get anim "events"))
    (keys-duration (get anim "drawOrder"))))

(defn- bone->transform [bone]
  (let [t (SpineScene$Transform.)]
    (math/clj->vecmath (.position t) (:position bone))
    (math/clj->vecmath (.rotation t) (:rotation bone))
    (math/clj->vecmath (.scale t) (:scale bone))
    t))

(defn- normalize-weights [weights]
  (let [total-weight (reduce (fn [total w]
                               (+ total (:weight w)))
                             0 weights)]
    (mapv (fn [w] (update w :weight #(/ % total-weight))) weights)))

(defn- attachment->mesh [attachment att-name slot-data anim-data bones-remap bone-index->world]
  (let [type (get attachment "type" "region")
        world ^SpineScene$Transform (:bone-world slot-data)
        anim-id (get attachment "name" att-name)
        uv-trans ^TextureSetGenerator$UVTransform (first (get-in anim-data [anim-id :uv-transforms]))
        mesh (case type
               "region"
               (let [local (doto (SpineScene$Transform.)
                             (-> (.position) (.set (get attachment "x" 0) (get attachment "y" 0) 0))
                             (-> (.rotation) (.set ^Quat4d (angle->quat (get attachment "rotation" 0))))
                             (-> (.scale) (.set (get attachment "scaleX" 1) (get attachment "scaleY" 1) 1)))
                     world (doto (SpineScene$Transform. world)
                             (.mul local))
                     width (get attachment "width" 0)
                     height (get attachment "height" 0)
                     vertices (flatten (for [x [-0.5 0.5]
                                             y [-0.5 0.5]
                                             :let [p (Point3d. (* x width) (* y height) 0)
                                                   uv (Point2d. (+ x 0.5) (- 0.5 y))]]
                                         (do
                                           (.apply world p)
                                           (.apply uv-trans uv)
                                           [(.x p) (.y p) (.z p) (.x uv) (.y uv)])))]
                 {:positions (flatten (partition 3 5 vertices))
                  :texcoord0 (flatten (partition 2 5 (drop 3 vertices)))
                  :indices [0 1 2 2 1 3]
                  :weights (take 16 (cycle [1 0 0 0]))
                  :bone-indices (take 16 (cycle [(:bone-index slot-data) 0 0 0]))})
               ("mesh" "skinnedmesh")
               (let [vertices (get attachment "vertices" [])
                     uvs (get attachment "uvs" [])
                     skinned? (= type "skinnedmesh")
                     ; Use uvs because vertices have a dynamic format
                     vertex-count (/ (count uvs) 2)]
                 (if skinned?
                   (let [[positions
                          bone-indices
                          bone-weights] (loop [vertices vertices
                                               positions []
                                               bone-indices []
                                               bone-weights []]
                                          (if-let [bone-count (first vertices)]
                                            (let [weights (take bone-count (map (fn [[bone-index x y weight]]
                                                                                  {:bone-index (bones-remap bone-index)
                                                                                   :x x
                                                                                   :y y
                                                                                   :weight weight})
                                                                                (partition 4 (rest vertices))))
                                                  p ^Point3d (reduce (fn [^Point3d p w]
                                                                       (let [wp (Point3d. (:x w) (:y w) 0)
                                                                             world ^SpineScene$Transform (bone-index->world (:bone-index w))]
                                                                         (.apply world wp)
                                                                         (.scaleAdd wp ^double (:weight w) p)
                                                                         (.set p wp)
                                                                         p))
                                                                     (Point3d.) weights)
                                                  weights (normalize-weights (take 4 (sort-by #(- 1.0 (:weight %)) weights)))]
                                              (recur (drop (inc (* bone-count 4)) vertices)
                                                     (conj positions (.x p) (.y p) (.z p))
                                                     (into bone-indices (flatten (partition 4 4 (repeat 0) (mapv :bone-index weights))))
                                                     (into bone-weights (flatten (partition 4 4 (repeat 0) (mapv :weight weights))))))
                                            [positions bone-indices bone-weights]))]
                     {:positions positions
                      :texcoord0 (mapcat (fn [[u v]]
                                           (let [uv (Point2d. u v)]
                                             (.apply uv-trans uv)
                                             [(.x uv) (.y uv)]))
                                         (partition 2 uvs))
                      :indices (get attachment "triangles")
                      :weights bone-weights
                      :bone-indices bone-indices})
                   (let [weight-count (* vertex-count 4)]
                     {:positions (mapcat (fn [[x y]]
                                           (let [p (Point3d. x y 0)]
                                             (.apply world p)
                                             [(.x p) (.y p) (.z p)]))
                                         (partition 2 vertices))
                      :texcoord0 (mapcat (fn [[u v]]
                                           (let [uv (Point2d. u v)]
                                             (.apply uv-trans uv)
                                             [(.x uv) (.y uv)]))
                                         (partition 2 uvs))
                      :indices (get attachment "triangles")
                      :weights (take weight-count (cycle [1 0 0 0]))
                      :bone-indices (take weight-count (cycle [(:bone-index slot-data) 0 0 0]))})))
               ; Ignore other types
               nil)]
    (when mesh
      (assoc mesh
            :color (:color slot-data)
            :visible (= att-name (:attachment slot-data))
            :draw-order (:draw-order slot-data)))))

(defn skin->meshes [skin slots-data anim-data bones-remap bone-index->world]
  (reduce-kv (fn [m slot attachments]
               (let [mesh-pairs (mapcat (fn [[att-name att]]
                                          (if-let [mesh (attachment->mesh att att-name (get slots-data slot) anim-data bones-remap bone-index->world)]
                                            [[slot att-name] mesh]
                                            []))
                                        attachments)]
                 (if (empty? mesh-pairs)
                   m
                   (apply assoc m mesh-pairs))))
             {} skin))

(defn- build-spine-scene [self basis resource dep-resources user-data]
  (let [anim-data (:anim-data user-data)
        scene (:spine-scene user-data)
        sample-rate (:sample-rate user-data)
        spf (/ 1.0 sample-rate)
        ; Bone data
        bones (map (fn [b]
                     {:id (protobuf/hash64 (get b "name"))
                      :parent (when (contains? b "parent") (protobuf/hash64 (get b "parent")))
                      :position [(get b "x" 0) (get b "y" 0) 0]
                      :rotation (angle->clj-quat (get b "rotation" 0))
                      :scale [(get b "scaleX" 1) (get b "scaleY" 1) 1]
                      :inherit-scale (get b "inheritScale" true)}) (get scene "bones"))
        indexed-bone-children (reduce (fn [m [i b]] (update-in m [(:parent b)] conj [i b])) {} (map-indexed (fn [i b] [i b]) bones))
        ordered-bones (tree-seq (constantly true) (fn [[i b]] (get indexed-bone-children (:id b))) (first (get indexed-bone-children nil)))
        bones-remap (into {} (map-indexed (fn [i [first-i b]] [first-i i]) ordered-bones))
        bones (mapv second ordered-bones)
        bone-id->index (into {} (map-indexed (fn [i b] [(:id b) i]) bones))
        bones (mapv #(assoc % :parent (get bone-id->index (:parent %) 0xffff)) bones)
        bone-world-transforms (loop [bones bones
                                     wt []]
                                (if-let [bone (first bones)]
                                  (let [local-t ^SpineScene$Transform (bone->transform bone)
                                        world-t (if (not= 0xffff (:parent bone))
                                                  (let [world-t (doto (SpineScene$Transform. (get wt (:parent bone)))
                                                                  (.mul local-t))]
                                                    ; Reset scale when not inheriting
                                                    (when (not (:inherit-scale bone))
                                                      (doto (.scale world-t)
                                                        (.set (.scale local-t))))
                                                    world-t)
                                                  local-t)]
                                    (recur (rest bones) (conj wt world-t)))
                                  wt))
        ; Slot data
        slots (get scene "slots" [])
        slots-data (into {} (map-indexed (fn [i slot]
                                           (let [bone-index (bone-id->index (protobuf/hash64 (get slot "bone")))]
                                             [(get slot "name")
                                              {:bone-index bone-index
                                               :bone-world (get bone-world-transforms bone-index)
                                               :draw-order i
                                               :color (hex->color (get slot "color" "FFFFFFFF"))
                                               :attachment (get slot "attachment")}])) slots))
        ; Skin data
        mesh-sort-fn (fn [[k v]] (:draw-order v))
        flatten-meshes (fn [meshes] (map-indexed (fn [i [k v]] [k (assoc v :draw-order i)]) (sort-by mesh-sort-fn meshes)))
        skins (get scene "skins" {})
        generic-meshes (skin->meshes (get skins "default" {}) slots-data anim-data bones-remap bone-world-transforms)
        new-skins {"" (flatten-meshes generic-meshes)}
        new-skins (reduce (fn [m [skin slots]]
                            (let [specific-meshes (skin->meshes slots slots-data anim-data bones-remap bone-world-transforms)]
                              (assoc m skin (flatten-meshes (merge generic-meshes specific-meshes)))))
                          new-skins (filter (fn [[skin _]] (not= "default" skin)) skins))
        slot->track-data (reduce-kv (fn [m skin meshes]
                                      (let [skin-id (protobuf/hash64 skin)]
                                        (reduce (fn [m [[slot att] mesh]]
                                                  (update m slot conj {:skin-id skin-id :mesh-index (:draw-order mesh) :attachment att}))
                                                m meshes)))
                                    {} new-skins)
        ; Protobuf
        pb {:skeleton {:bones bones}
            :animation-set (let [events (into {} (get scene "events" []))
                                 animations (mapv (fn [[name a]]
                                                    (let [duration (anim-duration a)]
                                                      {:id (protobuf/hash64 name)
                                                      :sample-rate sample-rate
                                                      :duration duration
                                                      :tracks (build-tracks (get a "bones") duration sample-rate spf bone-id->index)
                                                      :mesh-tracks (build-mesh-tracks (get a "slots") (get a "drawOrder") duration sample-rate spf slots-data slot->track-data)
                                                      :event-tracks (mapv (fn [[event-name keys]]
                                                                            (let [default (merge {"int" 0 "float" 0 "string" ""} (get events event-name))]
                                                                              {:event-id (protobuf/hash64 event-name)
                                                                              :keys (mapv (fn [k]
                                                                                            {:t (get k "time")
                                                                                             :integer (get k "int" (get default "int"))
                                                                                             :float (get k "float" (get default "float"))
                                                                                             :string (protobuf/hash64 (get k "string" (get default "string")))})
                                                                                          keys)}))
                                                                          (reduce (fn [m e]
                                                                                    (update-in m [(get e "name")] conj e))
                                                                                  {} (get a "events")))}))
                                                  (get scene "animations"))]
                             {:animations animations})
             :mesh-set {:mesh-entries (mapv (fn [[skin meshes]]
                                              {:id (protobuf/hash64 skin)
                                               :meshes (mapv second meshes)}) new-skins)}}
        pb (reduce #(assoc %1 (first %2) (second %2)) pb (map (fn [[label res]] [label (workspace/proj-path (get dep-resources res))]) (:dep-resources user-data)))]
    {:resource resource :content (protobuf/map->bytes Spine$SpineScene pb)}))

(g/defnk produce-scene-build-targets [_node-id resource spine-scene atlas sample-rate anim-data dep-build-targets]
  (let [dep-build-targets (flatten dep-build-targets)
        deps-by-source (into {} (map #(let [res (:resource %)] [(:resource res) res]) dep-build-targets))
        dep-resources (map (fn [[label resource]] [label (get deps-by-source resource)]) [[:texture-set atlas]])]
    [{:node-id _node-id
      :resource (workspace/make-build-resource resource)
      :build-fn build-spine-scene
      :user-data {:spine-scene spine-scene
                  :sample-rate sample-rate
                  :dep-resources dep-resources
                  :anim-data anim-data}
      :deps dep-build-targets}]))

(defn- connect-atlas [project node-id atlas]
  (if-let [atlas-node (project/get-resource-node project atlas)]
    (let [outputs (-> atlas-node g/node-type* g/output-labels)]
      (if (every? #(contains? outputs %) [:anim-data :gpu-texture :build-targets])
        [(g/connect atlas-node :anim-data     node-id :anim-data)
         (g/connect atlas-node :gpu-texture   node-id :gpu-texture)
         (g/connect atlas-node :build-targets node-id :dep-build-targets)]
        []))
    []))

(defn- disconnect-all [node-id label]
  (for [[src-node-id src-label] (g/sources-of node-id label)]
    (g/disconnect src-node-id src-label node-id label)))

(defn reconnect [transaction graph self label kind labels]
  (when (some #{:atlas} labels)
    (let [atlas (g/node-value self :atlas)
          project (project/get-project self)]
      (concat
        (disconnect-all self :anim-data)
        (disconnect-all self :gpu-texture)
        (disconnect-all self :dep-build-targets)
        (connect-atlas project self atlas)))))

(g/defnode SpineSceneNode
  (inherits project/ResourceNode)

  (property spine-json (g/protocol workspace/Resource))
  (property atlas (g/protocol workspace/Resource))
  (property sample-rate g/Num)

  ;; TODO - replace with use of property setter/getter
  #_(trigger reconnect :property-touched #'reconnect)

  (input anim-data g/Any)
  (input gpu-texture g/Any)
  (input dep-build-targets g/Any :array)
  (input spine-scene g/Any)

  (output outline g/Any :cached (g/fnk [_node-id] {:node-id _node-id :label "Spine" :icon spine-scene-icon}))
  (output save-data g/Any :cached produce-save-data)
  (output build-targets g/Any :cached produce-scene-build-targets))

(defn load-spine-scene [project self resource]
  (let [spine          (protobuf/read-text Spine$SpineSceneDesc resource)
        spine-resource (workspace/resolve-resource resource (:spine-json spine))
        atlas          (workspace/resolve-resource resource (:atlas spine))]
    (concat
      (g/set-property self :spine-json spine-resource)
      (g/set-property self :atlas atlas)
      (g/set-property self :sample-rate (:sample-rate spine))
      (project/connect-resource-node project spine-resource self [[:content :spine-scene]])
      (connect-atlas project self atlas))))

(defn register-resource-types [workspace]
  (workspace/register-resource-type workspace
                                    :ext "spinescene"
                                    :label "Spine Scene"
                                    :node-type SpineSceneNode
                                    :load-fn load-spine-scene
                                    :icon spine-scene-icon
                                    :tags #{:component}))
