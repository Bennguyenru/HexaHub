(ns editor.pipeline.font-gen
  (:require [clojure.java.io :as io]
            [editor.protobuf :as protobuf])
  (:import [com.defold.editor.pipeline Fontc Fontc$FontResourceResolver]
           [com.dynamo.render.proto Font$FontDesc Font$FontMap]
           [java.awt.image BufferedImage]))

(defn generate [font-desc font-path resolver]
  (let [^Font$FontDesc font-desc (protobuf/map->pb Font$FontDesc font-desc)
        font-map-builder (Font$FontMap/newBuilder)
        font-res-resolver (reify Fontc$FontResourceResolver
                            (getResource [this resource-name]
                              (io/input-stream (resolver (str "/" resource-name)))))]
    (with-open [font-stream (io/input-stream font-path)]
      (let [^BufferedImage image (-> (Fontc.)
                                   (.compile font-stream font-desc font-map-builder font-res-resolver))]
        {:image image
         :font-map (-> (protobuf/pb->map (.build font-map-builder))
                     (assoc :width (.getWidth image)
                            :height (.getHeight image)))}))))
