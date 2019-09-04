(ns preflight.kibit
  (:require [clojure.pprint :as pp]
            [clojure.string :as string]
            [kibit.check :refer [check-file]]
            [kibit.rules :refer [all-rules]])
  (:import [java.io File PrintWriter StringWriter]
           [java.util.concurrent.locks ReentrantLock]))

(defn- pprint-code
  [form]
  (let [string-writer (StringWriter.)]
    (pp/write form
              :dispatch pp/code-dispatch
              :stream string-writer
              :pretty true)
    (->> (str string-writer)
         string/split-lines
         (map #(str "  " %))
         (string/join \newline))))

(defn- make-string-reporter
  []
  (let [sw (StringWriter.)
        pw (PrintWriter. sw)
        lock (ReentrantLock.)
        counts (atom {:incorrect 0 :error 0})]
    {:reporter (fn [{:keys [file line expr alt]}]
                 (try
                   (.lock lock)
                   (swap! counts update :incorrect inc)
                   (.print pw (format "\nAt %s:%s:\nConsider using:\n" file line))
                   (.print pw (pprint-code alt))
                   (.print pw "\ninstead of:\n")
                   (.print pw (pprint-code expr))
                   (.print pw "\n")
                   (finally
                     (.unlock lock))))
     :str-writer sw
     :writer pw
     :lock lock
     :counts counts}))

(defn- run-check
  [source-files rules]
  (let [reporter (make-string-reporter)]
    (dorun
      (pmap (fn
              [^File file]
              (try
                (check-file
                  file
                  :reporter (:reporter reporter)
                  :rules (or rules all-rules))
                (catch Exception e
                  (let [e-info (ex-data e)
                        pw ^PrintWriter (:writer reporter)
                        lock ^ReentrantLock (:lock reporter)]
                    (try
                      (.lock lock)
                      (swap! (:counts reporter) update :error inc)
                      (.print pw (format "Check failed -- skipping rest of file (%s:%s:%s)\n"
                                         (.getPath file)
                                         (:line e-info)
                                         (:column e-info)))
                      (.print pw (.getMessage e))
                      (finally
                        (.unlock lock)))))))
            source-files))
    {:counts @(:counts reporter)
     :report (str (:str-writer reporter))}))

(defn check
  [files]
  (run-check files nil))

