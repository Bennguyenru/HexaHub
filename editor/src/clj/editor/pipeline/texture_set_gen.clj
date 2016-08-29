(ns editor.pipeline.texture-set-gen
  (:require [editor.protobuf :as protobuf])
  (:import [com.defold.editor.pipeline TextureSetGenerator TextureSetGenerator$AnimIterator TextureSetGenerator$AnimDesc
            TextureSetGenerator$UVTransform TextureSetGenerator$TextureSetResult TileSetUtil TileSetUtil$Metrics TextureUtil
            TextureSetLayout$Grid ConvexHull]
           [com.dynamo.tile.proto Tile$Playback Tile$TileSet Tile$ConvexHull]
           [com.dynamo.textureset.proto TextureSetProto$TextureSet$Builder]
           [java.util ArrayList]
           [java.awt.image BufferedImage]))

(set! *warn-on-reflection* true)

(defn- anim->AnimDesc [anim]
  (when anim
    (TextureSetGenerator$AnimDesc. (:id anim) (protobuf/val->pb-enum Tile$Playback (:playback anim)) (:fps anim)
                                   (:flip-horizontal anim) (:flip-vertical anim))))

(defn- Metrics->map
  [^TileSetUtil$Metrics metrics]
  (when metrics
    {:tiles-per-row (.tilesPerRow metrics)
     :tiles-per-column (.tilesPerColumn metrics)
     :tile-set-width (.tileSetWidth metrics)
     :tile-set-height (.tileSetHeight metrics)
     :visual-width (.visualWidth metrics)
     :visual-height (.visualHeight metrics)}))

(defn- TextureSetResult->result
  [^TextureSetGenerator$TextureSetResult tex-set-result]
  {:texture-set (protobuf/pb->map (.build (.builder tex-set-result)))
   :image (.image tex-set-result)
   :uv-transforms (vec (.uvTransforms tex-set-result))})

(defn ->texture-set-data [animations images margin inner-padding extrude-borders]
  (let [img-to-index (into {} (map-indexed #(do [%2 (Integer. ^int %1)]) images))
        anims-atom (atom animations)
        anim-imgs-atom (atom [])
        anim-iterator (reify TextureSetGenerator$AnimIterator
                        (nextAnim [this]
                          (let [anim (first @anims-atom)]
                            (reset! anim-imgs-atom (or (:images anim) []))
                            (swap! anims-atom rest)
                            (anim->AnimDesc anim)))
                        (nextFrameIndex [this]
                          (let [img (first @anim-imgs-atom)]
                            (swap! anim-imgs-atom rest)
                            (img-to-index img)))
                        (rewind [this]
                          (reset! anims-atom animations)
                          (reset! anim-imgs-atom [])))
        result (TextureSetGenerator/generate
                 (ArrayList. ^java.util.Collection (map :contents images)) anim-iterator margin inner-padding extrude-borders
                 false false true false nil)]
    ; This will be supplied later when producing the byte data for the pipeline
    (.setTexture (.builder result) "unknown")
    (TextureSetResult->result result)))

(defn- calc-tile-start [{:keys [spacing margin]} size tile-index]
  (let [actual-tile-size (+ size spacing (* 2 margin))]
    (+ margin (* tile-index actual-tile-size))))

(defn- sub-image [tile-source-attributes tile-x tile-y image type]
  (let [w (:width tile-source-attributes)
        h (:height tile-source-attributes)
        tgt (BufferedImage. w h type)
        g (.getGraphics tgt)
        sx (calc-tile-start tile-source-attributes w tile-x)
        sy (calc-tile-start tile-source-attributes h tile-y)]
    (.drawImage g image 0 0 w h sx sy (+ sx w) (+ sy h) nil)
    (.dispose g)
    tgt))

(defn- split
  [image {:keys [tiles-per-column tiles-per-row] :as tile-source-attributes}]
  (let [type (TextureUtil/getImageType image)]
    (for [tile-y (range tiles-per-column)
          tile-x (range tiles-per-row)]
      (sub-image tile-source-attributes tile-x tile-y image type))))

(defn- tile-anim->AnimDesc [anim]
  (when anim
    (TextureSetGenerator$AnimDesc. (:id anim) (protobuf/val->pb-enum Tile$Playback (:playback anim)) (:fps anim)
               (not= 0 (:flip-horizontal anim)) (not= 0 (:flip-vertical anim)))))


(defn calculate-convex-hulls
  [^BufferedImage collision {:keys [width height margin spacing] :as tile-properties}]
  (let [convex-hulls (TileSetUtil/calculateConvexHulls (.getAlphaRaster collision) 16 (.getWidth collision) (.getHeight collision)
                                                       width height margin spacing)
        points (vec (.points convex-hulls))]
    (mapv (fn [^ConvexHull hull]
            (let [index (.getIndex hull)
                  count (.getCount hull)]
              {:index index
               :count count
               :points (subvec points (* 2 index) (+ (* 2 index) (* 2 count)))}))
          (.hulls convex-hulls))))

(defn calculate-tile-metrics
  [^BufferedImage image {:keys [width height margin spacing] :as tile-properties} ^BufferedImage collision]
  (Metrics->map (TileSetUtil/calculateMetrics image width height margin spacing collision 1 0)))

(defn- add-convex-hulls!
  [^TextureSetProto$TextureSet$Builder builder convex-hulls collision-groups]
  (.addAllCollisionGroups builder collision-groups)
  (when convex-hulls
    (run! (fn [{:keys [index count points collision-group]}]
            (.addConvexHulls builder (doto (Tile$ConvexHull/newBuilder)
                                       (.setIndex index)
                                       (.setCount count)
                                       (.setCollisionGroup (or collision-group ""))))
            
            (run! #(.addConvexHullPoints builder %) points))
          convex-hulls)))

(defn tile-source->texture-set-data [image tile-source-attributes convex-hulls collision-groups animations]
  (let [images (split image tile-source-attributes)
        anims-atom (atom animations)
        anim-indices-atom (atom [])
        anim-iterator (reify TextureSetGenerator$AnimIterator
                        (nextAnim [this]
                          (let [anim (first @anims-atom)]
                            (reset! anim-indices-atom (if anim
                                                        (vec (map int (range (dec (:start-tile anim)) (:end-tile anim))))
                                                        []))
                            (swap! anims-atom rest)
                            (tile-anim->AnimDesc anim)))
                        (nextFrameIndex [this]
                          (let [index (first @anim-indices-atom)]
                            (swap! anim-indices-atom rest)
                            index))
                        (rewind [this]
                          (reset! anims-atom animations)
                          (reset! anim-indices-atom [])))
        grid (TextureSetLayout$Grid. (:tiles-per-row tile-source-attributes) (:tiles-per-column tile-source-attributes))
        result (TextureSetGenerator/generate
                (ArrayList. ^java.util.Collection images)
                anim-iterator
                (:margin tile-source-attributes)
                (:inner-padding tile-source-attributes)
                (:extrude-borders tile-source-attributes)
                false false false true grid)]
    (doto (.builder result)
      (.setTileWidth (:width tile-source-attributes))
      (.setTileHeight (:height tile-source-attributes))
      (add-convex-hulls! convex-hulls collision-groups)
      ;; "This will be supplied later when producing the byte data for the pipeline"
      ;; TODO: check what that means and if it's true
      (.setTexture "unknown"))
    (TextureSetResult->result result)))
