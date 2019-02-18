(ns util.net
  (:require [clojure.java.io :as io]))

(set! *warn-on-reflection* true)

(def ^:const ^:private default-read-timeout 2000)

(def ^:const ^:private default-connect-timeout 2000)

(def ^:private default-cancelled-derefable (delay false))

(defn- default-progress-callback [current total])

(defn download! [url out & {:keys [read-timeout connect-timeout chunk-size progress-callback cancelled-derefable]
                            :or {read-timeout default-read-timeout
                                 connect-timeout default-connect-timeout
                                 chunk-size 0
                                 cancelled-derefable default-cancelled-derefable
                                 progress-callback default-progress-callback}}]
  (let [conn (doto (.openConnection (io/as-url url))
               (.setRequestProperty "Connection" "close")
               (.setConnectTimeout connect-timeout)
               (.setReadTimeout read-timeout))
        length (.getContentLengthLong conn)]
    (when-not @cancelled-derefable
      (with-open [input (.getInputStream conn)
                  output (io/output-stream out)]
        (if (pos? chunk-size)
          (let [buf (byte-array chunk-size)]
            (loop [count (.read input buf)
                   previous 0]
              (when (and (<= 0 count) (not @cancelled-derefable))
                (.write output buf 0 count)
                (progress-callback (+ previous count) length)
                (recur (.read input buf) (+ previous count)))))
          (do
            ;; io/copy will use an internal buffer of 1024 bytes for transfer
            (io/copy input output)
            (when (not @cancelled-derefable)
              (progress-callback length length))))))))
