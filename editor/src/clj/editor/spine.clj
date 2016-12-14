(ns editor.spine
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [editor.protobuf :as protobuf]
            [dynamo.graph :as g]
            [util.murmur :as murmur]
            [editor.graph-util :as gu]
            [editor.geom :as geom]
            [editor.math :as math]
            [editor.gl :as gl]
            [editor.gl.shader :as shader]
            [editor.gl.vertex :as vtx]
            [editor.graph-util :as gu]
            [editor.defold-project :as project]
            [editor.resource :as resource]
            [editor.scene :as scene]
            [editor.render :as render]
            [editor.validation :as validation]
            [editor.workspace :as workspace]
            [editor.resource :as resource]
            [editor.pipeline.spine-scene-gen :as spine-scene-gen]
            [editor.validation :as validation]
            [editor.gl.pass :as pass]
            [editor.types :as types]
            [editor.json :as json]
            [editor.outline :as outline]
            [editor.properties :as properties]
            [editor.rig :as rig])
  (:import [com.dynamo.graphics.proto Graphics$Cubemap Graphics$TextureImage Graphics$TextureImage$Image Graphics$TextureImage$Type]
           [com.dynamo.spine.proto Spine$SpineSceneDesc Spine$SpineModelDesc Spine$SpineModelDesc$BlendMode]
           [editor.types Region Animation Camera Image TexturePacking Rect EngineFormatTexture AABB TextureSetAnimationFrame TextureSetAnimation TextureSet]
           [com.defold.editor.pipeline BezierUtil SpineScene$Transform TextureSetGenerator$UVTransform]
           [java.awt.image BufferedImage]
           [java.io PushbackReader]
           [com.jogamp.opengl GL GL2 GLContext GLDrawableFactory]
           [com.jogamp.opengl.glu GLU]
           [javax.vecmath Matrix4d Point2d Point3d Quat4d Vector2d Vector3d Vector4d Tuple3d Tuple4d]
           [editor.gl.shader ShaderLifecycle]))

(set! *warn-on-reflection* true)

(def spine-scene-icon "icons/32/Icons_16-Spine-scene.png")
(def spine-model-icon "icons/32/Icons_15-Spine-model.png")
(def spine-bone-icon "icons/32/Icons_S_13_radiocircle.png")

; Node defs

(g/defnk produce-save-data [resource spine-json-resource atlas-resource sample-rate]
  {:resource resource
   :content (protobuf/map->str Spine$SpineSceneDesc
              {:spine-json (resource/resource->proj-path spine-json-resource)
               :atlas (resource/resource->proj-path atlas-resource)
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
                                    (let [bone-index (bone-id->index (murmur/hash64 bone-name))]
                                      (reduce-kv (fn [m type keys]
                                                   (if-let [field (timeline-type->pb-field type)]
                                                     (let [pb-track {field (sample type (wrap-angles type keys) duration sample-rate spf nil nil true)
                                                                     :bone-index bone-index}]
                                                       (update-in m [bone-index] merge pb-track))
                                                     m))
                                                 m timeline)))
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
                                                                                      :mesh-id skin-id
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

(def ^:private ^TextureSetGenerator$UVTransform uv-identity (TextureSetGenerator$UVTransform.))

(defn- attachment->mesh [attachment att-name slot-data anim-data bones-remap bone-index->world]
  (when anim-data
    (let [type (get attachment "type" "region")
          world ^SpineScene$Transform (:bone-world slot-data)
          anim-id (get attachment "name" att-name)
          uv-trans (or ^TextureSetGenerator$UVTransform (first (get-in anim-data [anim-id :uv-transforms])) uv-identity)
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
             :draw-order (:draw-order slot-data))))))

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



(g/defnk produce-scene-build-targets
  [_node-id resource spine-scene-pb atlas dep-build-targets]
  (rig/make-rig-scene-build-targets _node-id
                                    resource
                                    (assoc spine-scene-pb
                                           :texture-set atlas)
                                    dep-build-targets
                                    [:texture-set]))

(defn- connect-atlas [project node-id atlas]
  (if-let [atlas-node (project/get-resource-node project atlas)]
    (let [outputs (-> atlas-node g/node-type* g/output-labels)]
      (if (every? #(contains? outputs %) [:anim-data :gpu-texture :build-targets])
        [(g/connect atlas-node :anim-data     node-id :anim-data)
         (g/connect atlas-node :gpu-texture   node-id :gpu-texture)
         (g/connect atlas-node :build-targets node-id :dep-build-targets)]
        []))
    []))

(defn reconnect [transaction graph self label kind labels]
  (when (some #{:atlas} labels)
    (let [atlas (g/node-value self :atlas)
          project (project/get-project self)]
      (concat
        (gu/disconnect-all self :anim-data)
        (gu/disconnect-all self :gpu-texture)
        (gu/disconnect-all self :dep-build-targets)
        (connect-atlas project self atlas)))))

(g/defnk produce-spine-scene-pb [spine-scene anim-data sample-rate]
  (let [spf (/ 1.0 sample-rate)
        ; Bone data
        bones (map (fn [b]
                     {:id (murmur/hash64 (get b "name"))
                      :parent (when (contains? b "parent") (murmur/hash64 (get b "parent")))
                      :position [(get b "x" 0) (get b "y" 0) 0]
                      :rotation (angle->clj-quat (get b "rotation" 0))
                      :scale [(get b "scaleX" 1) (get b "scaleY" 1) 1]
                      :inherit-scale (get b "inheritScale" true)}) (get spine-scene "bones"))
        indexed-bone-children (reduce (fn [m [i b]] (update-in m [(:parent b)] conj [i b])) {} (map-indexed (fn [i b] [i b]) bones))
        root (first (get indexed-bone-children nil))
        ordered-bones (if root
                        (tree-seq (constantly true) (fn [[i b]] (get indexed-bone-children (:id b))) root)
                        [])
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
        slots (get spine-scene "slots" [])
        slots-data (into {} (map-indexed (fn [i slot]
                                           (let [bone-index (bone-id->index (murmur/hash64 (get slot "bone")))]
                                             [(get slot "name")
                                              {:bone-index bone-index
                                               :bone-world (get bone-world-transforms bone-index)
                                               :draw-order i
                                               :color (hex->color (get slot "color" "FFFFFFFF"))
                                               :attachment (get slot "attachment")}])) slots))
        ; Skin data
        mesh-sort-fn (fn [[k v]] (:draw-order v))
        flatten-meshes (fn [meshes] (map-indexed (fn [i [k v]] [k (assoc v :draw-order i)]) (sort-by mesh-sort-fn meshes)))
        skins (get spine-scene "skins" {})
        generic-meshes (skin->meshes (get skins "default" {}) slots-data anim-data bones-remap bone-world-transforms)
        new-skins {"default" (flatten-meshes generic-meshes)}
        new-skins (reduce (fn [m [skin slots]]
                            (let [specific-meshes (skin->meshes slots slots-data anim-data bones-remap bone-world-transforms)]
                              (assoc m skin (flatten-meshes (merge generic-meshes specific-meshes)))))
                          new-skins (filter (fn [[skin _]] (not= "default" skin)) skins))
        slot->track-data (reduce-kv (fn [m skin meshes]
                                      (let [skin-id (murmur/hash64 skin)]
                                        (reduce (fn [m [[slot att] mesh]]
                                                  (update m slot conj {:skin-id skin-id :mesh-index (:draw-order mesh) :attachment att}))
                                                m meshes)))
                                    {} new-skins)
        ; Protobuf
        pb {:skeleton {:bones bones}
            :animation-set (let [events (into {} (get spine-scene "events" []))
                                 animations (mapv (fn [[name a]]
                                                    (let [duration (anim-duration a)]
                                                      {:id (murmur/hash64 name)
                                                      :sample-rate sample-rate
                                                      :duration duration
                                                      :tracks (build-tracks (get a "bones") duration sample-rate spf bone-id->index)
                                                      :mesh-tracks (build-mesh-tracks (get a "slots") (get a "drawOrder") duration sample-rate spf slots-data slot->track-data)
                                                      :event-tracks (mapv (fn [[event-name keys]]
                                                                            (let [default (merge {"int" 0 "float" 0 "string" ""} (get events event-name))]
                                                                              {:event-id (murmur/hash64 event-name)
                                                                              :keys (mapv (fn [k]
                                                                                            {:t (get k "time")
                                                                                             :integer (get k "int" (get default "int"))
                                                                                             :float (get k "float" (get default "float"))
                                                                                             :string (murmur/hash64 (get k "string" (get default "string")))})
                                                                                          keys)}))
                                                                          (reduce (fn [m e]
                                                                                    (update-in m [(get e "name")] conj e))
                                                                                  {} (get a "events")))}))
                                                  (get spine-scene "animations"))]
                             {:animations animations})
             :mesh-set {:mesh-entries (mapv (fn [[skin meshes]]
                                              {:id (murmur/hash64 skin)
                                               :meshes (mapv second meshes)}) new-skins)}}]
    pb))

(defn- transform-positions [^Matrix4d transform mesh]
  (let [p (Point3d.)]
    (update mesh :positions (fn [positions]
                              (->> positions
                                (partition 3)
                                (mapcat (fn [[x y z]]
                                          (.set p x y z)
                                          (.transform transform p)
                                          [(.x p) (.y p) (.z p)])))))))

(defn- renderable->meshes [renderable]
  (->> (get-in renderable [:user-data :spine-scene-pb :mesh-set :mesh-entries])
    (first)
    (:meshes)
    (filter :visible)
    (sort-by :draw-order)
    (map (partial transform-positions (:world-transform renderable)))))

(defn- mesh->verts [mesh]
  (let [verts (mapv concat (partition 3 (:positions mesh)) (partition 2 (:texcoord0 mesh)) (repeat (:color mesh)))]
    (map (partial get verts) (:indices mesh))))

(defn- gen-vb [renderables rcount]
  (let [meshes (mapcat renderable->meshes renderables)
        vcount (reduce + 0 (map (comp count :indices) meshes))]
    (when (> vcount 0)
      (let [vb (render/->vtx-pos-tex-col vcount)
            verts (mapcat mesh->verts meshes)]
        (persistent! (reduce conj! vb verts))))))

(def color [1.0 1.0 1.0 1.0])

(defn- skeleton-vs [parent-pos bone vs ^Matrix4d wt]
  (let [pos (Vector3d.)
        t (doto (Matrix4d.)
            (.mul wt ^Matrix4d (:transform bone)))
        _ (.get ^Matrix4d t pos)
        pos [(.x pos) (.y pos) (.z pos)]
        vs (if parent-pos
             (conj vs (into parent-pos color) (into pos color))
             vs)]
    (reduce (fn [vs bone] (skeleton-vs pos bone vs wt)) vs (:children bone))))

(defn- gen-skeleton-vb [renderables rcount]
  (let [vs (loop [renderables renderables
                  vs []]
             (if-let [r (first renderables)]
               (let [skeleton (get-in r [:user-data :scene-structure :skeleton])]
                 (recur (rest renderables) (skeleton-vs nil skeleton vs (:world-transform r))))
               vs))
        vcount (count vs)]
    (when (> vcount 0)
      (let [vb (render/->vtx-pos-col vcount)]
        (persistent! (reduce conj! vb vs))))))

(defn render-spine-scenes [^GL2 gl render-args renderables rcount]
  (let [pass (:pass render-args)]
    (cond
      (= pass pass/outline)
      (let [outline-vertex-binding (vtx/use-with ::spine-outline (render/gen-outline-vb renderables rcount) render/shader-outline)]
        (gl/with-gl-bindings gl render-args [render/shader-outline outline-vertex-binding]
          (gl/gl-draw-arrays gl GL/GL_LINES 0 (* rcount 8))))

      (= pass pass/transparent)
      (do (when-let [vb (gen-vb renderables rcount)]
            (let [vertex-binding (vtx/use-with ::spine-trans vb render/shader-tex-tint)
                  user-data (:user-data (first renderables))
                  gpu-texture (:gpu-texture user-data)
                  blend-mode (:blend-mode user-data)]
              (gl/with-gl-bindings gl render-args [gpu-texture render/shader-tex-tint vertex-binding]
                (case blend-mode
                  :blend-mode-alpha (.glBlendFunc gl GL/GL_ONE GL/GL_ONE_MINUS_SRC_ALPHA)
                  (:blend-mode-add :blend-mode-add-alpha) (.glBlendFunc gl GL/GL_ONE GL/GL_ONE)
                  :blend-mode-mult (.glBlendFunc gl GL/GL_ZERO GL/GL_SRC_COLOR))
                (shader/set-uniform render/shader-tex-tint gl "texture" 0)
                (gl/gl-draw-arrays gl GL/GL_TRIANGLES 0 (count vb))
                (.glBlendFunc gl GL/GL_SRC_ALPHA GL/GL_ONE_MINUS_SRC_ALPHA))))
        (when-let [vb (gen-skeleton-vb renderables rcount)]
            (let [vertex-binding (vtx/use-with ::spine-skeleton vb render/shader-outline)]
              (gl/with-gl-bindings gl render-args [render/shader-outline vertex-binding]
                (gl/gl-draw-arrays gl GL/GL_LINES 0 (count vb))))))

      (= pass pass/selection)
      (when-let [vb (gen-vb renderables rcount)]
        (let [vertex-binding (vtx/use-with ::spine-selection vb render/shader-tex-tint)]
          (gl/with-gl-bindings gl render-args [render/shader-tex-tint vertex-binding]
            (gl/gl-draw-arrays gl GL/GL_TRIANGLES 0 (count vb))))))))

(g/defnk produce-scene [_node-id aabb gpu-texture spine-scene-pb scene-structure]
  (let [scene {:node-id _node-id
               :aabb aabb}]
    (if gpu-texture
      (let [blend-mode :blend-mode-alpha]
        (assoc scene :renderable {:render-fn render-spine-scenes
                                  :batch-key gpu-texture
                                  :select-batch-key _node-id
                                  :user-data {:spine-scene-pb spine-scene-pb
                                              :scene-structure scene-structure
                                              :gpu-texture gpu-texture
                                              :blend-mode blend-mode}
                                  :passes [pass/transparent pass/selection pass/outline]}))
      scene)))

(defn- mesh->aabb [aabb mesh]
  (let [positions (partition 3 (:positions mesh))]
    (reduce (fn [aabb pos] (apply geom/aabb-incorporate aabb pos)) aabb positions)))

(defn- prop-resource-error [_node-id prop-kw prop-value prop-name]
  (or (validation/prop-error :info _node-id prop-kw validation/prop-nil? prop-value prop-name)
      (validation/prop-error :fatal _node-id prop-kw validation/prop-resource-not-exists? prop-value prop-name)))

(g/defnode SpineSceneNode
  (inherits project/ResourceNode)

  (property spine-json resource/Resource
            (value (gu/passthrough spine-json-resource))
            (set (fn [basis self old-value new-value]
                   (project/resource-setter basis self old-value new-value
                                                [:resource :spine-json-resource]
                                                [:content :spine-scene]
                                                [:structure :scene-structure]
                                                [:node-outline :source-outline])))
            (dynamic edit-type (g/constantly {:type resource/Resource :ext "json"}))
            (dynamic error (g/fnk [_node-id spine-json]
                                  (prop-resource-error _node-id :spine-json spine-json "Spine Json"))))

  (property atlas resource/Resource
            (value (gu/passthrough atlas-resource))
            (set (fn [basis self old-value new-value]
                   (project/resource-setter basis self old-value new-value
                                                [:resource :atlas-resource]
                                                [:anim-data :anim-data]
                                                [:gpu-texture :gpu-texture]
                                                [:build-targets :dep-build-targets])))
            (dynamic edit-type (g/constantly {:type resource/Resource :ext "atlas"}))
            (dynamic error (g/fnk [_node-id atlas]
                                  (prop-resource-error _node-id :atlas atlas "Atlas"))))

  (property sample-rate g/Num)

  (input spine-json-resource resource/Resource)
  (input atlas-resource resource/Resource)

  (input anim-data g/Any)
  (input gpu-texture g/Any)
  (input dep-build-targets g/Any :array)
  (input spine-scene g/Any)
  (input scene-structure g/Any)

  (output save-data g/Any :cached produce-save-data)
  (output build-targets g/Any :cached produce-scene-build-targets)
  (output spine-scene-pb g/Any :cached produce-spine-scene-pb)
  (output scene g/Any :cached produce-scene)
  (output aabb AABB :cached (g/fnk [spine-scene-pb] (let [meshes (mapcat :meshes (get-in spine-scene-pb [:mesh-set :mesh-entries]))]
                                                      (reduce mesh->aabb (geom/null-aabb) meshes))))
  (output anim-data g/Any (gu/passthrough anim-data))
  (output scene-structure g/Any (gu/passthrough scene-structure))
  (output spine-anim-ids g/Any (g/fnk [spine-scene] (keys (get spine-scene "animations")))))

(defn load-spine-scene [project self resource]
  (let [spine          (protobuf/read-text Spine$SpineSceneDesc resource)
        spine-resource (workspace/resolve-resource resource (:spine-json spine))
        atlas          (workspace/resolve-resource resource (:atlas spine))]
    (g/set-property self
                    :spine-json spine-resource
                    :atlas atlas
                    :sample-rate (:sample-rate spine))))

(g/defnk produce-model-pb [spine-scene-resource default-animation skin material-resource blend-mode]
  {:spine-scene (resource/resource->proj-path spine-scene-resource)
   :default-animation default-animation
   :skin skin
   :material (resource/resource->proj-path material-resource)
   :blend-mode blend-mode})

(g/defnk produce-model-save-data [resource model-pb]
  {:resource resource
   :content (protobuf/map->str Spine$SpineModelDesc model-pb)})

(defn- build-spine-model [self basis resource dep-resources user-data]
  (let [pb (:proto-msg user-data)
        pb (reduce #(assoc %1 (first %2) (second %2)) pb (map (fn [[label res]] [label (resource/proj-path (get dep-resources res))]) (:dep-resources user-data)))]
    {:resource resource :content (protobuf/map->bytes Spine$SpineModelDesc pb)}))

(g/defnk produce-model-build-targets [_node-id resource model-pb spine-scene-resource material-resource dep-build-targets scene-structure]
  (let [dep-build-targets (flatten dep-build-targets)
        deps-by-source (into {} (map #(let [res (:resource %)] [(:resource res) res]) dep-build-targets))
        dep-resources (map (fn [[label resource]] [label (get deps-by-source resource)]) [[:spine-scene spine-scene-resource] [:material material-resource]])
        model-pb (update model-pb :skin (fn [skin] (if (not-empty skin) skin (first (:skins scene-structure)))))]
    [{:node-id _node-id
      :resource (workspace/make-build-resource resource)
      :build-fn build-spine-model
      :user-data {:proto-msg model-pb
                  :dep-resources dep-resources}
      :deps dep-build-targets}]))

(defn- sort-spine-anim-ids
  [spine-anim-ids]
  (sort-by str/lower-case spine-anim-ids))

(g/defnode SpineModelNode
  (inherits project/ResourceNode)

  (property spine-scene resource/Resource
            (value (gu/passthrough spine-scene-resource))
            (set (fn [basis self old-value new-value]
                     (project/resource-setter basis self old-value new-value
                                                  [:resource :spine-scene-resource]
                                                  [:scene :spine-scene-scene]
                                                  [:spine-anim-ids :spine-anim-ids]
                                                  [:aabb :aabb]
                                                  [:build-targets :dep-build-targets]
                                                  [:node-outline :source-outline]
                                                  [:anim-data :anim-data]
                                                  [:scene-structure :scene-structure])))
            (dynamic edit-type (g/constantly {:type resource/Resource :ext "spinescene"}))
            (dynamic error (g/fnk [_node-id spine-scene]
                                  (prop-resource-error _node-id :spine-scene spine-scene "Spine Scene"))))
  (property blend-mode g/Any (default :blend-mode-alpha)
            (dynamic tip (validation/blend-mode-tip blend-mode Spine$SpineModelDesc$BlendMode))
            (dynamic edit-type (g/constantly (properties/->pb-choicebox Spine$SpineModelDesc$BlendMode))))
  (property material resource/Resource
            (value (gu/passthrough material-resource))
            (set (fn [basis self old-value new-value]
                   (project/resource-setter basis self old-value new-value
                                                [:resource :material-resource]
                                                [:shader :material-shader]
                                                [:build-targets :dep-build-targets])))
            (dynamic edit-type (g/constantly {:type resource/Resource :ext "material"}))
            (dynamic error (g/fnk [_node-id material]
                                  (prop-resource-error _node-id :material material "Material"))))
  (property default-animation g/Str
            (value (g/fnk [default-animation spine-anim-ids]
                     (if (and (str/blank? default-animation) spine-anim-ids)
                       (first (sort-spine-anim-ids spine-anim-ids))
                       default-animation)))
            (dynamic error (g/fnk [_node-id spine-anim-ids default-animation spine-scene]
                                  (when spine-scene
                                    (validation/prop-error :fatal _node-id
                                                           :default-animation (fn [anim ids]
                                                                                (when (not (contains? ids anim))
                                                                                  (format "animation '%s' could not be found in the specified scene" anim))) default-animation (set spine-anim-ids)))))
            (dynamic edit-type (g/fnk [spine-anim-ids] (properties/->choicebox spine-anim-ids))))
  (property skin g/Str
            (dynamic error (g/fnk [_node-id skin scene-structure spine-scene]
                                  (when spine-scene
                                    (validation/prop-error :fatal _node-id :skin
                                                         (fn [skin skins]
                                                           (when (and (not-empty skin) (not (contains? skins skin)))
                                                             (format "skin '%s' could not be found in the specified scene" skin))) skin (set (:skins scene-structure))))))
            (dynamic edit-type (g/fnk [scene-structure]
                                      (properties/->choicebox (cons "" (:skins scene-structure))))))

  (input dep-build-targets g/Any :array)
  (input spine-scene-resource resource/Resource)
  (input spine-scene-scene g/Any)
  (input scene-structure g/Any)
  (input spine-anim-ids g/Any)
  (input aabb AABB)
  (input material-resource resource/Resource)
  (input material-shader ShaderLifecycle)
  (input anim-data g/Any)
  (output anim-ids g/Any :cached (g/fnk [anim-data] (vec (sort (keys anim-data)))))
  (output material-shader ShaderLifecycle (gu/passthrough material-shader))
  (output scene g/Any :cached (gu/passthrough spine-scene-scene))
  (output model-pb g/Any :cached produce-model-pb)
  (output save-data g/Any :cached produce-model-save-data)
  (output build-targets g/Any :cached produce-model-build-targets)
  (output aabb AABB (gu/passthrough aabb)))

(defn load-spine-model [project self resource]
  (let [resolve-fn (partial workspace/resolve-resource resource)
        spine (-> (protobuf/read-text Spine$SpineModelDesc resource)
                (update :spine-scene resolve-fn)
                (update :material resolve-fn))]
    (for [[k v] spine]
      (g/set-property self k v))))

(defn register-resource-types [workspace]
  (concat
    (workspace/register-resource-type workspace
                                      :ext "spinescene"
                                      :build-ext "rigscenec"
                                      :label "Spine Scene"
                                      :node-type SpineSceneNode
                                      :load-fn load-spine-scene
                                      :icon spine-scene-icon
                                      :view-types [:scene :text]
                                      :view-opts {:scene {:grid true}})
    (workspace/register-resource-type workspace
                                     :ext "spinemodel"
                                     :label "Spine Model"
                                     :node-type SpineModelNode
                                     :load-fn load-spine-model
                                     :icon spine-model-icon
                                     :view-types [:scene :text]
                                     :view-opts {:scene {:grid true}}
                                     :tags #{:component})))

(g/defnk produce-transform [position rotation scale]
  (math/->mat4-non-uniform (Vector3d. (double-array position))
                           (math/euler-z->quat rotation)
                           (Vector3d. (double-array scale))))

(g/defnode SpineBone
  (inherits outline/OutlineNode)
  (property name g/Str (dynamic read-only? (g/constantly true)))
  (property position types/Vec3
            (dynamic edit-type (g/constantly (properties/vec3->vec2 0.0)))
            (dynamic read-only? (g/constantly true)))
  (property rotation g/Num (dynamic read-only? (g/constantly true)))
  (property scale types/Vec3
            (dynamic edit-type (g/constantly (properties/vec3->vec2 1.0)))
            (dynamic read-only? (g/constantly true)))

  (input child-bones g/Any :array)

  (output transform Matrix4d :cached produce-transform)
  (output bone g/Any (g/fnk [name transform child-bones]
                            {:name name
                             :local-transform transform
                             :children child-bones}))
  (output node-outline outline/OutlineData (g/fnk [_node-id name child-outlines]
                                                  {:node-id _node-id
                                                   :label name
                                                   :icon spine-bone-icon
                                                   :children child-outlines
                                                   :read-only true})))

(defn- update-transforms [^Matrix4d parent bone]
  (let [t ^Matrix4d (:local-transform bone)
        t (doto (Matrix4d.)
            (.mul parent t))]
    (-> bone
      (assoc :transform t)
      (assoc :children (mapv #(update-transforms t %) (:children bone))))))

(g/defnode SpineSceneJson
  (inherits outline/OutlineNode)
  (input source-outline outline/OutlineData)
  (output node-outline outline/OutlineData (g/fnk [source-outline] source-outline))
  (input skeleton g/Any)
  (input content g/Any)
  (output structure g/Any :cached (g/fnk [skeleton content]
                                         {:skeleton (update-transforms (math/->mat4) skeleton)
                                          :skins (vec (sort (keys (get content "skins"))))})))

(defn accept-spine-scene-json [content]
  (when (or (get-in content ["skeleton" "spine"])
            (and (get content "bones") (get content "animations")))
    content))

(defn- tx-first-created [tx-data]
  (get-in (first tx-data) [:node :_node-id]))

(defn load-spine-scene-json [node-id content]
  (let [bones (get content "bones")
        graph (g/node-id->graph-id node-id)
        scene-tx-data (g/make-nodes graph [scene SpineSceneJson]
                                    (g/connect scene :_node-id node-id :nodes)
                                    (g/connect scene :node-outline node-id :child-outlines)
                                    (g/connect scene :structure node-id :structure)
                                    (g/connect node-id :content scene :content))
        scene-id (tx-first-created scene-tx-data)]
    (concat
      scene-tx-data
      (loop [bones bones
             tx-data []
             bone-ids {}]
        (if-let [bone (first bones)]
          (let [name (get bone "name")
                parent (get bone "parent")
                x (get bone "x" 0)
                y (get bone "y" 0)
                rotation (get bone "rotation" 0)
                scale-x (get bone "scaleX" 1.0)
                scale-y (get bone "scaleY" 1.0)
                bone-tx-data (g/make-nodes graph [bone [SpineBone :name name :position [x y 0] :rotation rotation :scale [scale-x scale-y 1.0]]]
                                           (g/connect bone :_node-id node-id :nodes)
                                           (if-let [parent (get bone-ids parent)]
                                             (concat
                                               (g/connect bone :node-outline parent :child-outlines)
                                               (g/connect bone :bone parent :child-bones))
                                             (concat
                                               (g/connect bone :node-outline scene-id :source-outline)
                                               (g/connect bone :bone scene-id :skeleton))))
                bone-id (tx-first-created bone-tx-data)]
            (recur (rest bones) (conj tx-data bone-tx-data) (assoc bone-ids name bone-id)))
          tx-data)))))

(json/register-json-loader ::spine-scene accept-spine-scene-json load-spine-scene-json)
