(ns editor.bootloader
  "Loads namespaces in parallel when possible to speed up start time."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]))

(def load-info (atom nil))

(defn load-boot
  []
  ;; The file sorted_clojure_ns_list.edn is generated at boot time if using lein
  ;; run. See debug.clj. When bundling it is generated by a leiningen task
  ;; called build_ns_batches which is called from bundle.py. The code that
  ;; generates the file is in ns_batch_builder.clj.
  (let [batches (->> (io/resource "sorted_clojure_ns_list.edn")
                     (slurp)
                     (edn/read-string))
        boot-loaded? (promise)
        loader (future
                 (try
                   (doseq [batch batches]
                     (dorun (pmap require batch))
                     (when (contains? batch 'editor.boot)
                       (deliver boot-loaded? true)))
                   (catch Exception e
                     (deliver boot-loaded? e)
                     (throw e))))]
    (reset! load-info [loader boot-loaded?])))

(defn wait-until-editor-boot-loaded
  []
  (let [result @(second @load-info)]
    (when (instance? Exception result)
      (throw result))))

(defn main
  [args]
  (let [[loader _] @load-info]
    (reset! load-info nil)
    (ns-unmap *ns* 'load-info)
    (let [boot-main (resolve 'editor.boot/main)]
      (boot-main args loader))))
