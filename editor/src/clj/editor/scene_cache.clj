(ns editor.scene-cache
  (:require [clojure.core.cache :as cache]
            [editor.volatile-cache :as vcache]))

(defonce ^:private object-caches (atom {}))

(defn register-object-cache! [cache-id make-fn update-fn destroy-batch-fn]
  (swap! object-caches conj [cache-id {:caches {} :make-fn make-fn :update-fn update-fn :destroy-batch-fn destroy-batch-fn}]))

(defn- dump-cache [cache]
  (prn "Cache dump" (count cache))
  (doseq [entry cache]
    (prn entry)))

(defn request-object! [cache-id request-id context data]
  (let [cache-meta (get @object-caches cache-id)
        make-fn (:make-fn cache-meta)
        cache (or (get-in cache-meta [:caches context])
                  (vcache/volatile-cache-factory {}))
        new-cache (if (cache/has? cache request-id)
                    (let [[object old-data] (cache/lookup cache request-id)]
                      (if (not= data old-data)
                        (let [update-fn (:update-fn cache-meta)]
                          (cache/miss cache request-id [(update-fn context object data) data]))
                        (cache/hit cache request-id)))
                    (cache/miss cache request-id [(make-fn context data) data]))]
    (swap! object-caches update-in [cache-id :caches] assoc context new-cache)
    (first (cache/lookup new-cache request-id))))

(defn lookup-object [cache-id request-id context]
  (let [cache-meta (get @object-caches cache-id)]
    (when-let [cache (get-in cache-meta [:caches context])]
      (first (cache/lookup cache request-id)))))

(defn prune [caches context]
  (into {} (map (fn [[cache-id meta]]
                  (let [destroy-batch-fn (:destroy-batch-fn meta)]
                    [cache-id (update-in meta [:caches context]
                                         (fn [cache]
                                           (when cache
                                             (let [pruned-cache (vcache/prune cache)
                                                   dead-entries (filter (fn [[request-id _]] (not (contains? pruned-cache request-id))) cache)
                                                   dead-objects (mapv (fn [[_ [object _]]] object) dead-entries)]
                                               (when (not (empty? dead-objects))
                                                 (destroy-batch-fn context dead-objects))
                                               pruned-cache))))]))
                caches)))

(defn prune-object-caches! [context]
  (swap! object-caches prune context))

(defn drop-context [caches context destroy-objects?]
  (into {} (map (fn [[cache-id meta]]
                  (let [destroy-batch-fn (:destroy-batch-fn meta)]
                    [cache-id (if-let [cache (get-in meta [:caches context])]
                                (do
                                  (when destroy-objects?
                                    (destroy-batch-fn context (map #(first (second %)) cache)))
                                  (update meta :caches dissoc context))
                                meta)]))
                caches)))

(defn drop-context! [context destroy-objects?]
  (swap! object-caches drop-context context destroy-objects?))
