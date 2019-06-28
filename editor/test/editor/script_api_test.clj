(ns editor.script-api-test
  (:require [clojure.string :as string]
            [clojure.test :refer :all]
            [editor.script-api :as sapi]))

(defn std
  ([name type]
   (std name type nil))
  ([name type doc]
   {:type type
    :name name
    :doc doc
    :display-string name
    :insert-string name}))

(def just-a-variable
  "
- name: other
  type: number")

(def just-a-variable-expected-result
  {"" [(std "other" :variable)]})

(def empty-table
  "
- name: table
  type: table")

(def empty-table-expected-result
  {"" [(std "table" :namespace)]
   "table" []})

(def table-with-members
  "
- name: other
  type: table
  desc: 'Another table'
  members:
    - name: Hello
      type: number")

(def table-with-members-expected-result
  {"" [(std "other" :namespace "Another table")]
   "other" [(std "other.Hello" :variable)]})

(def function-with-one-parameter
  "
- name: fun
  type: function
  desc: This is super great function!
  parameters:
    - name: plopp
      type: plupp")

(def function-with-one-parameter-expected-result
  {""
   [{:type :function
     :name "fun"
     :doc "This is super great function!"
     :display-string "fun(plopp)"
     :insert-string "fun(plopp)"
     :tab-triggers {:select ["plopp"]
                    :exit ")"}}]})

(def empty-top-level-definition
  "- ")

(def empty-top-level-definition-expected-result
  {"" []})

(def broken-table-member-list
  "
- name: other
  type: table
  desc: 'Another table'
  members:
    - nam")

(def broken-table-member-list-expected-result
  {"" [(std "other" :namespace "Another table")]
   "other" []})

(def no-type-means-variable
  "
- name: hej")

(def no-type-means-variable-expected-result
  {"" [(std "hej" :variable)]})

(def function-with-optional-parameter
  "
- name: fun
  type: function
  parameters:
    - name: optopt
      type: integer
      optional: true")

(def function-with-optional-parameter-expected-result
  {""
   [{:type :function
     :name "fun"
     :doc nil
     :display-string "fun([optopt])"
     :insert-string "fun()"
     :tab-triggers {:select []
                    :exit ")"}}]})

(defn convert
  [source]
  (sapi/combine-conversions (sapi/convert-lines (string/split-lines source))))

(deftest conversions
  (are [x y] (= x (convert y))
    just-a-variable-expected-result just-a-variable
    empty-table-expected-result empty-table
    table-with-members-expected-result table-with-members
    function-with-one-parameter-expected-result function-with-one-parameter
    empty-top-level-definition-expected-result empty-top-level-definition
    broken-table-member-list-expected-result broken-table-member-list
    function-with-optional-parameter-expected-result function-with-optional-parameter
    no-type-means-variable-expected-result no-type-means-variable))

