(ns editor.code
  (:require [clojure.string :as string]))

(defn- remove-optional-params [s]
  (string/replace s #"\[.*\]" ""))

(defn create-hint
  ([name]
   (create-hint name name name ""))
  ([name display-string insert-string doc]
   {:name name
    :display-string display-string
    :insert-string insert-string
    :doc doc}))

(defn match-while [body p]
  (loop [body body
         length 0]
    (if-let [c (first body)]
      (if (p c)
        (recur (rest body) (inc length))
        {:body body :length length})
      {:body body :length length})))

(defn match-while-eq [body ch]
  (match-while body (fn [c] (= c ch))))

(defn match-until-string [body s]
  (if-let [s (seq s)]
    (let [slen (count s)]
      (when-let [index (ffirst (->> (partition slen 1 body)
                                    (map-indexed vector)
                                    (filter #(= (second %) s))))]
        (let [length (+ index slen)]
          {:body (nthrest body length) :length length})))
    {:body body :length 0}))

(defn match-until-eol [body]
  (match-while body (fn [ch] (and ch (not (#{\newline \return} ch))))))

(defn match-string [body s]
  (if-let [s (seq s)]
    (let [length (count s)]
      (when (= s (take length body))
        {:body (nthrest body length) :length length}))
    {:body body :length 0}))

(defn combine-matches [& matches]
  {:body (last matches) :length (apply + (map :length matches))})

(defn match-regex [pattern s]
(when-let [result (re-find pattern s)]
    (if (string? result)
      {:body result :length (count result)}
      (let [first-group-match (->> result (remove nil?) first)]
        {:body first-group-match :length (count first-group-match)}))))

(def number-pattern   #"^-?(?:0|[1-9]\d*)(?:\.\d*)?(?:[eE][+\-]?\d+)?" )
(def leading-decimal-number-pattern   #"^-?(?:\.\d*)(?:[eE][+\-]?\d+)?" )

(defn match-number [s]
  (or (match-regex number-pattern s)
      (match-regex leading-decimal-number-pattern s)))
