(ns util.net
  (:require
    [clojure.java.io :as io])
  (:import
    (java.io ByteArrayOutputStream InputStream IOException)
    (java.net URL URLConnection)))

(def ^:const ^:private default-read-timeout 200)
(def ^:const ^:private default-connect-timeout 200)


(defn download! [url output & {:keys [read-timeout connect-timeout chunk-size progress-callback]
                               :or {read-timeout default-read-timeout
                                    connect-timeout default-connect-timeout
                                    chunk-size 0}}]
  (let [conn ^URLConnection (doto (.openConnection (io/as-url url))
                              (.setRequestProperty "Connection" "close")
                              (.setConnectTimeout connect-timeout)
                              (.setReadTimeout read-timeout))
        length (.getContentLengthLong conn)]
    (with-open [input (.getInputStream conn)]
      (if (< 0 chunk-size)
        (let [buf (byte-array chunk-size)]
          (loop [count (.read input buf)
                 previous 0]
            (when (<= 0 count)
              (.write output buf 0 count)
              (when progress-callback
                (progress-callback (+ previous count) length))
              (recur (.read input buf) (+ previous count)))))
        (do
          ;; io/copy will use an internal buffer of 1024 bytes for transfer
          (io/copy input output)
          (when progress-callback
            (progress-callback length length)))))))
