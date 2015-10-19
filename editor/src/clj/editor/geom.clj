(ns editor.geom
  (:require [dynamo.graph :as g]
            [editor.types :as types]
            [editor.math :as math])
  (:import [com.defold.util Geometry]
           [editor.types Rect AABB]
           [javax.vecmath Point2d Point3d Point4d Vector4d Vector3d Quat4d Matrix4d]
           [com.defold.editor.pipeline TextureSetGenerator$UVTransform]))

(defn clamper [low high] (fn [x] (min (max x low) high)))

(defn lift-f1 [op] (fn [c xs] (into (empty xs) (for [x xs] (op c x)))))

(def *& (lift-f1 *))
(def +& (lift-f1 +))
(def -& (lift-f1 -))

; -------------------------------------
; 2D geometry
; -------------------------------------
(g/s-defn area :- double
  [r :- Rect]
  (if r
    (* (double (.width r)) (double (.height r)))
    0))

(g/s-defn intersect :- (g/maybe Rect)
  ([r :- Rect] r)
  ([r1 :- Rect r2 :- Rect]
    (when (and r1 r2)
      (let [l (max (.x r1) (.x r2))
            t (max (.y r1) (.y r2))
            r (min (+ (.x r1) (.width r1))  (+ (.x r2) (.width r2)))
            b (min (+ (.y r1) (.height r1)) (+ (.y r2) (.height r2)))
            w (- r l)
            h (- b t)]
        (if (and (< 0 w) (< 0 h))
          (types/rect l t w h)
          nil))))
  ([r1 :- Rect r2 :- Rect & rs :- [Rect]]
    (reduce intersect (intersect r1 r2) rs)))

(g/s-defn split-rect-| :- [Rect]
  "Splits the rectangle such that the side slices extend to the top and bottom"
  [^Rect container :- Rect ^Rect content :- Rect]
  (let [new-rects     (transient [])
        overlap ^Rect (intersect container content)]
    (if overlap
      (do
        ;; left slice
        (if (< (.x container) (.x overlap))
          (conj! new-rects (types/rect (.x container)
                                 (.y container)
                                 (- (.x overlap) (.x container))
                                 (.height container))))

        ;; right slice
        (if (< (+ (.x overlap) (.width overlap)) (+ (.x container) (.width container)))
          (conj! new-rects (types/rect ""
                                (+ (.x overlap) (.width overlap))
                                (.y container)
                                (- (+ (.x container) (.width container))
                                    (+ (.x overlap)   (.width overlap)))
                                (.height container))))
        ;; bottom slice
        (if (< (.y container) (.y overlap))
          (conj! new-rects (types/rect ""
                                 (.x overlap)
                                 (.y container)
                                 (.width overlap)
                                 (- (.y overlap) (.y container)))))

        ;; top slice
        (if (< (+ (.y overlap) (.height overlap)) (+ (.y container) (.height container)))
          (conj! new-rects (types/rect ""
                                 (.x overlap)
                                 (+ (.y overlap) (.height overlap))
                                 (.width overlap)
                                 (- (+ (.y container) (.height container))
                                    (+ (.y overlap)   (.height overlap)))))))
      (conj! new-rects container))
    (persistent! new-rects)))

(g/s-defn split-rect-= :- [Rect]
  "Splits the rectangle such that the top and bottom slices extend to the sides"
  [^Rect container :- Rect ^Rect content :- Rect]
  (let [new-rects     (transient [])
        overlap ^Rect (intersect container content)]
    (if overlap
      (do
         ;; bottom slice
         (if (< (.y container) (.y overlap))
           (conj! new-rects (types/rect ""
                                  (.x container)
                                  (.y container)
                                  (.width container)
                                  (- (.y overlap) (.y container)))))

         ;; top slice
         (if (< (+ (.y overlap) (.height overlap)) (+ (.y container) (.height container)))
           (conj! new-rects (types/rect ""
                                  (.x container)
                                  (+ (.y overlap) (.height overlap))
                                  (.width container)
                                  (- (+ (.y container) (.height container))
                                    (+ (.y overlap) (.height overlap))))))

         ;; left slice
         (if (< (.x container) (.x overlap))
           (conj! new-rects (types/rect ""
                                  (.x container)
                                  (.y overlap)
                                  (- (.x overlap) (.x container))
                                  (.height overlap))))

         ;; right slice
         (if (< (+ (.x overlap) (.width overlap)) (+ (.x container) (.width container)))
           (conj! new-rects (types/rect ""
                                 (+ (.x overlap) (.width overlap))
                                 (.y overlap)
                                 (- (+ (.x container) (.width container))
                                     (+ (.x overlap)   (.width overlap)))
                                 (.height overlap)))))
      (conj! new-rects container))
    (persistent! new-rects)))

(defn- largest-area
  [rs]
  (reduce max 0 (map area rs)))

(g/s-defn split-rect :- [Rect]
  "Splits the rectangle with an attempt to minimize fragmentation"
  [^Rect container :- Rect ^Rect content :- Rect]
  (let [horizontal (split-rect-= container content)
        vertical   (split-rect-| container content)]
    (if (> (largest-area horizontal) (largest-area vertical))
      horizontal
      vertical)))

; This is off-by-one in many cases, due to Clojure's preference to promote things into Double and Long.
;
;(defn to-short-uv
;  "Return a fixed-point integer representation of the fractional part of the given fuv."
;  [^Float fuv]
;  (.shortValue
;    (bit-and
;      (int
;        (* (float fuv) (.floatValue 65535.0)))
;      0xffff)))

(defn to-short-uv
  [^Float fuv]
  (Geometry/toShortUV fuv))

; -------------------------------------
; Transformations
; -------------------------------------

(g/s-defn world-space [node :- {:world-transform Matrix4d g/Any g/Any} point :- Point3d]
  (let [p             (Point3d. point)
        tfm ^Matrix4d (:world-transform node)]
    (.transform tfm p)
    p))

(def ^Matrix4d Identity4d (doto (Matrix4d.) (.setIdentity)))

; -------------------------------------
; Matrix sloshing
; -------------------------------------
(defprotocol AsArray
  (^doubles as-array [this]))

(defprotocol Invertible
  (invert [this]))

(extend-type Matrix4d
  AsArray
  (as-array [this]
    (double-array [(.m00 this) (.m10 this) (.m20 this) (.m30 this)
                   (.m01 this) (.m11 this) (.m21 this) (.m31 this)
                   (.m02 this) (.m12 this) (.m22 this) (.m32 this)
                   (.m03 this) (.m13 this) (.m23 this) (.m33 this)]))

  Invertible
  (invert [this]
    (doto (Matrix4d. this)
      .invert)))

(extend-type Vector3d
  AsArray
  (as-array [v]
    (let [vals (double-array 3)]
      (.get v vals)
      vals)))

(extend-type Point3d
  AsArray
  (as-array [v]
    (let [vals (double-array 3)]
      (.get v vals)
      vals)))

(extend-type Vector4d
  AsArray
  (as-array [v]
    (let [vals (double-array 4)]
      (.get v vals)
      vals)))

(g/s-defn ident :- Matrix4d
  []
  (doto (Matrix4d.)
    (.setIdentity)))

; -------------------------------------
; 3D geometry
; -------------------------------------
(g/s-defn null-aabb :- AABB
  []
  (types/->AABB (Point3d. Integer/MAX_VALUE Integer/MAX_VALUE Integer/MAX_VALUE)
             (Point3d. Integer/MIN_VALUE Integer/MIN_VALUE Integer/MIN_VALUE)))

(g/s-defn aabb-incorporate :- AABB
  ([^AABB aabb :- AABB
    ^Point3d p :- Point3d]
    (aabb-incorporate aabb (.x p) (.y p) (.z p)))
  ([^AABB aabb :- AABB
    ^double x :- g/Num
    ^double y :- g/Num
    ^double z :- g/Num]
    (let [minx (Math/min (-> aabb types/min-p .x) x)
          miny (Math/min (-> aabb types/min-p .y) y)
          minz (Math/min (-> aabb types/min-p .z) z)
          maxx (Math/max (-> aabb types/max-p .x) x)
          maxy (Math/max (-> aabb types/max-p .y) y)
          maxz (Math/max (-> aabb types/max-p .z) z)]
      (types/->AABB (Point3d. minx miny minz)
                    (Point3d. maxx maxy maxz)))))

(g/s-defn aabb-union :- AABB
  ([aabb1 :- AABB] aabb1)
  ([aabb1 :- AABB aabb2 :- AABB]
    (let [null (null-aabb)]
      (cond
        (= aabb1 null) aabb2
        (= aabb2 null) aabb1
        true (-> aabb1
                (aabb-incorporate (types/min-p aabb2))
                (aabb-incorporate (types/max-p aabb2))))))
  ([aabb1 :- AABB aabb2 :- AABB & aabbs :- [AABB]]
    (aabb-union (aabb-union aabb1 aabb2) aabbs)))

(g/s-defn aabb-contains?
  [^AABB aabb :- AABB ^Point3d p :- Point3d]
  (and
    (>= (-> aabb types/max-p .x) (.x p) (-> aabb types/min-p .x))
    (>= (-> aabb types/max-p .y) (.y p) (-> aabb types/min-p .y))
    (>= (-> aabb types/max-p .z) (.z p) (-> aabb types/min-p .z))))

(g/s-defn aabb-extent :- Point3d
  [aabb :- AABB]
  (let [v (Point3d. (types/max-p aabb))]
    (.sub v (types/min-p aabb))
    v))

(g/s-defn aabb-center :- Point3d
  [^AABB aabb :- AABB]
  (Point3d. (/ (+ (-> aabb types/min-p .x) (-> aabb types/max-p .x)) 2.0)
            (/ (+ (-> aabb types/min-p .y) (-> aabb types/max-p .y)) 2.0)
            (/ (+ (-> aabb types/min-p .z) (-> aabb types/max-p .z)) 2.0)))

(g/s-defn rect->aabb :- AABB
  [^Rect bounds :- Rect]
  (assert bounds "rect->aabb require boundaries")
  (let [x1 (.x bounds)
        y1 (.y bounds)
        x2 (+ x1 (.width bounds))
        y2 (+ y1 (.height bounds))]
    (-> (null-aabb)
        (aabb-incorporate (Point3d. x1 y1 0))
        (aabb-incorporate (Point3d. x2 y2 0)))))

(def unit-bounding-box
  (-> (null-aabb)
    (aabb-incorporate  1  1  1)
    (aabb-incorporate -1 -1 -1)))

(defn aabb-transform [^AABB aabb ^Matrix4d transform]
  (if (= aabb (null-aabb))
    aabb
    (let [extents [(types/min-p aabb) (types/max-p aabb)]
          points (for [x (map #(let [^Point3d p %] (.x p)) extents)
                       y (map #(let [^Point3d p %] (.y p)) extents)
                       z (map #(let [^Point3d p %] (.z p)) extents)
                       ] (Point3d. x y z))]
      (doseq [^Point3d p points]
        (.transform transform p))
      (reduce #(aabb-incorporate %1 %2) (null-aabb) points))))

; -------------------------------------
; Primitive shapes as vertex arrays
; -------------------------------------


(g/s-defn unit-sphere-pos-nrm [lats longs]
  (for [lat-i (range lats)
       long-i (range longs)]
   (let [lat-angle   (fn [rate] (* Math/PI rate))
         long-angle  (fn [rate] (* (* 2 Math/PI) rate))
         make-vertex (fn [lat-a long-a]
                       (let [y      (Math/cos lat-a)
                             radius (Math/sin lat-a)
                             x      (* radius (Math/cos long-a))
                             z      (* radius (Math/sin long-a))]
                         [x y z x y z]))
         vertices (for [lat-idx  [lat-i (inc lat-i)]
                        long-idx [long-i (inc long-i)]]
                    (make-vertex (lat-angle (/ lat-idx lats)) (long-angle (/ long-idx longs))))]
                (map #(nth vertices %) [0 1 2 1 3 2]))))

; Procedural geometry

(defn transl
  ([delta]
    (map (partial apply transl delta)))
  ([delta ps]
    (let [res (mapv (fn [p] (mapv + delta p)) ps)]
      res)))

(defn scale
  ([factor]
    (map (partial apply scale factor)))
  ([factor ps]
    (mapv (fn [p] (mapv * factor p)) ps)))

(defn rotate
  ([euler]
    (map (partial apply rotate euler)))
  ([euler ps]
    (let [q (math/euler->quat euler)
          tmp-v (Vector3d.)]
      (let [res (mapv (fn [[^double x ^double y ^double z]]
                    (.set tmp-v x y z)
                    (let [v (math/rotate q tmp-v)]
                      [(.x v) (.y v) (.z v)])) ps)]
        res))))

(defn transf-p
  [^Matrix4d m4d ps]
  (let [p (Point3d.)]
    (let [res (mapv (fn [[^double x ^double y ^double z]]
                      (.set p x y z)
                      (.transform m4d p)
                      [(.x p) (.y p) (.z p)]) ps)]
      res)))

(defn chain [n f ps]
  (loop [i n
         v ps
         result ps]
    (if (> i 0)
      (let [v' (f v)]
        (recur (dec i) v' (into result v')))
      result)))

(def empty-geom [])
(def origin-geom [[0 0 0]])

(defn circling [segments ps]
  (let [angle (/ 360 segments)]
    (chain (dec segments) (partial rotate [0 0 angle]) ps)))

(defn uv-trans [^TextureSetGenerator$UVTransform uv-trans ps]
  (if uv-trans
    (let [p (Point2d.)]
     (mapv (fn [[^double x ^double y]]
             (.set p x y)
             (.apply uv-trans p)
             [(.x p) (.y p)]) ps))
    ps))
