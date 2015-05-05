(ns editor.handler-test
  (:require [clojure.test :refer :all]
            [editor.handler :as handler]))

(defn fixture [f]
  (reset! handler/*handlers* {})
  (f))

(use-fixtures :each fixture)

(deftest run
  (handler/defhandler :open
    (visible? [instances] (every? #(= % :foo) instances))
    (enabled? [instances] (every? #(= % :foo) instances))
    (run [instances] 123))
  (are [inst exp] (= exp (handler/enabled? :open {:instances [inst]}))
       :foo true
       :bar false)
  (are [inst exp] (= exp (handler/visible? :open {:instances [inst]}))
       :foo true
       :bar false)
  (is (= 123 (handler/run :open {:instances [:foo]}))))
