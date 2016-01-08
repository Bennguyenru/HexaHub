(ns editor.image
  (:require [clojure.java.io :as io]
            [dynamo.graph :as g]
            [editor.core :as core]
            [editor.geom :refer [clamper]]
            [editor.project :as project]
            [editor.types :as types]
            [editor.resource :as resource]
            [editor.workspace :as workspace]
            [editor.pipeline.tex-gen :as tex-gen]
            [service.log :as log])
  (:import [editor.types Rect Image]
           [java.awt Color]
           [java.awt.image BufferedImage]
           [javax.imageio ImageIO]))

; TODO - fix real profiles
(def test-profile {:name "test-profile"
                   :platforms [{:os :os-id-generic
                                :formats [{:format :texture-format-rgba
                                           :compression-level :fast}]
                                :mipmaps false}]})

(defn- build-texture [self basis resource dep-resources user-data]
  {:resource resource :content (tex-gen/->bytes (:image user-data) test-profile)})

(g/defnk produce-build-targets [_node-id resource content]
  [{:node-id _node-id
    :resource (workspace/make-build-resource resource)
    :build-fn build-texture
    :user-data {:image content}}])

(g/defnode ImageNode
  (inherits project/ResourceNode)

  (output content BufferedImage :cached (g/fnk [resource] (try
                                                            (if-let [img (ImageIO/read (io/input-stream resource))]
                                                              img
                                                              (g/error-severe {:type :invalid-content
                                                                        :message (format "The image '%s' could not be loaded." (resource/proj-path resource))}))
                                                            (catch java.io.FileNotFoundException e
                                                              (log/warn :exception e)
                                                              (g/error-severe {:type :file-not-found
                                                                               :message (format "The image '%s' could not be found." (resource/proj-path resource))})))))
  (output build-targets g/Any :cached produce-build-targets))

(defmacro with-graphics
  [binding & body]
  (let [rsym (gensym)]
    `(let ~(into [] (concat binding [rsym `(do ~@body)]))
       (.dispose ~(first binding))
       ~rsym)))

(g/s-defn make-color :- java.awt.Color
  "creates a color using rgb values (optional a). Color values between 0 and 1.0"
  ([r :- Float g :- Float b :- Float]
    (java.awt.Color. r g b))
  ([r :- Float g :- Float b :- Float a :- Float]
    (java.awt.Color. r g b a)))

(g/s-defn make-image :- Image
  [nm :- g/Any contents :- BufferedImage]
  (Image. nm contents (.getWidth contents) (.getHeight contents)))

(g/s-defn blank-image :- BufferedImage
  ([space :- Rect]
    (blank-image (.width space) (.height space)))
  ([width :- g/Int height :- g/Int]
    (blank-image width height BufferedImage/TYPE_4BYTE_ABGR))
  ([width :- g/Int height :- g/Int t :- g/Int]
    (BufferedImage. width height t)))

(g/s-defn flood :- BufferedImage
  "Floods the image with the specified color (r g b <a>). Color values between 0 and 1.0."
  [^BufferedImage img :- BufferedImage r :- Float g :- Float b :- Float]
  (let [gfx (.createGraphics img)
        color (make-color r g b)]
    (.setColor gfx color)
    (.fillRect gfx 0 0 (.getWidth img) (.getHeight img))
    (.dispose gfx)
    img))

(defn load-image [src reference]
  (make-image reference (ImageIO/read (io/input-stream src))))

;; Use "Hollywood Cerise" for the placeholder image color.
(def placeholder-image (make-image "placeholder" (flood (blank-image 64 64) 0.9568 0.0 0.6313)))

;; Behavior
(g/defnode ImageSource
  (inherits core/ResourceNode)

  (output content Image :cached (g/fnk [filename] (load-image filename (types/local-path filename)))))

(g/s-defn image-color-components :- long
  [src :- BufferedImage]
  (.. src (getColorModel) (getNumComponents)))

(g/s-defn image-infer-type :- long
  [src :- BufferedImage]
  (if (not= 0 (.getType src))
    (.getType src)
    (case (image-color-components src)
      4 BufferedImage/TYPE_4BYTE_ABGR
      3 BufferedImage/TYPE_3BYTE_BGR
      1 BufferedImage/TYPE_BYTE_GRAY)))

(g/s-defn image-type :- g/Int
  [src :- BufferedImage]
  (let [t (.getType src)]
    (if (not= 0 t) t (image-infer-type src))))

(g/s-defn image-convert-type :- BufferedImage
  [original :- BufferedImage new-type :- g/Int]
  (if (= new-type (image-type original))
    original
    (let [new (blank-image (.getWidth original) (.getHeight original) new-type)]
      (with-graphics [g2d (.createGraphics new)]
        (.drawImage g2d original 0 0 nil))
      new)))

(g/s-defn image-bounds :- Rect
  [source :- Image]
  (types/rect (.path source) 0 0 (.width source) (.height source)))

(g/s-defn image-pixels :- ints
  [src :- BufferedImage]
  (let [w      (.getWidth src)
        h      (.getHeight src)
        pixels (int-array (* w h (image-color-components src)))]
    (.. src (getRaster) (getPixels 0 0 w h pixels))
    pixels))

(g/s-defn image-from-pixels :- BufferedImage
  [^long width :- g/Int ^long height :- g/Int t :- g/Int pixels :- ints]
  (doto (blank-image width height t)
    (.. (getRaster) (setPixels 0 0 width height pixels))))

(defmacro pixel-index [x y step stride]
  `(* ~step (+ ~x (* ~y ~stride))))

(g/s-defn extrude-borders :- Image
  "Return a new pixel array, larger than the original by `extrusion`
with the orig-pixels centered in it. The source pixels on the edges
will bleed into the surrounding empty space. The pixels in the border
region will be identical to the nearest pixel of the source image."
  [extrusion :- g/Int src :- Image]
  (if-not (< 0 extrusion)
    src
    (let [src-img        (types/contents src)
          src-img        (image-convert-type src-img BufferedImage/TYPE_4BYTE_ABGR)
          orig-width     (.width src)
          orig-height    (.height src)
          new-width      (+ orig-width (* 2 extrusion))
          new-height     (+ orig-height (* 2 extrusion))
          src-pixels     (image-pixels src-img)
          num-components (image-color-components src-img)
          clampx         (clamper 0 (dec orig-width))
          clampy         (clamper 0 (dec orig-height))
          new-pixels     (int-array (* new-width new-height num-components))]
      (doseq [y (range new-height)
              x (range new-width)]
        (let [sx (clampx (- x extrusion))
              sy (clampy (- y extrusion))
              src-idx (pixel-index sx sy num-components orig-width)
              tgt-idx (pixel-index x   y num-components new-width)]
          (doseq [i (range num-components)]
            (aset-int new-pixels (+ i tgt-idx) (aget src-pixels (+ i src-idx))))))
      (make-image (.path src) (image-from-pixels new-width new-height (.getType src-img) new-pixels)))))

(defn- map-by [p coll]
  (zipmap (map p coll) coll))

(g/s-defn composite :- BufferedImage
  [onto :- BufferedImage placements :- [Rect] sources :- [Image]]
  (let [src-by-path (map-by :path sources)]
    (with-graphics [graphics (.getGraphics onto)]
      (doseq [^Rect rect placements]
        (.drawImage graphics (:contents (get src-by-path (.path rect))) (int (.x rect)) (int (.y rect)) nil)))
    onto))

(defn register-resource-types [workspace]
  (concat
    (workspace/register-resource-type workspace
                                      :ext ["jpg" "png"]
                                      :label "Image"
                                      :icon "icons/32/Icons_25-AT-Image.png"
                                      :build-ext "texturec"
                                      :node-type ImageNode
                                      :view-types [:default])
    (workspace/register-resource-type workspace :ext "texture")))

(defn- build-texture [self basis resource dep-resources user-data]
  {:resource resource :content (tex-gen/->bytes (:image user-data) test-profile)})

(defn make-texture-build-target [workspace node-id image]
  (let [texture-type     (workspace/get-resource-type workspace "texture")
        texture-resource (resource/make-memory-resource workspace texture-type (str (gensym)))]
    {:node-id   node-id
     :resource  (workspace/make-build-resource texture-resource)
     :build-fn  build-texture
     :user-data {:image image}}))
