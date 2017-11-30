(ns editor.code.resource
  (:require [clojure.string :as string]
            [dynamo.graph :as g]
            [editor.code.data :as data]
            [editor.code.util :as util]
            [editor.graph-util :as gu]
            [editor.defold-project :as project]
            [editor.resource-node :as resource-node]
            [editor.workspace :as workspace]
            [schema.core :as s])
  (:import (editor.code.data Cursor CursorRange)))

(set! *warn-on-reflection* true)
(set! *unchecked-math* :warn-on-boxed)

(def TCursor (s/record Cursor {:row Long :col Long}))
(def TCursorRange (s/record CursorRange {:from TCursor :to TCursor}))
(def TRegion (s/record CursorRange {:from TCursor
                                    :to TCursor
                                    :type s/Keyword
                                    s/Keyword s/Any}))

(g/deftype BreakpointRows (sorted-set s/Num))
(g/deftype Cursors [TCursor])
(g/deftype CursorRanges [TCursorRange])
(g/deftype InvalidatedRows [Long])
(g/deftype Lines [String])
(g/deftype Regions [TRegion])
(g/deftype RegionGrouping {s/Any [TRegion]})

(defn- read-fn [resource]
  (util/split-lines (slurp resource)))

(defn- write-fn [lines]
  (string/join "\n" lines))

(defn- load-fn [project self resource]
  [(g/set-property self :lines (read-fn resource))
   (g/connect self :breakpoints project :breakpoints)] )

(g/defnk produce-breakpoint-rows [regions]
  (into (sorted-set)
        (comp (filter data/breakpoint-region?)
              (map data/breakpoint-row))
        regions))

(g/defnk produce-breakpoints [resource regions]
  (into []
        (comp (filter data/breakpoint-region?)
              (map (fn [region]
                     {:resource resource
                      :line (data/breakpoint-row region)})))
        regions))

(g/defnode CodeEditorResourceNode
  (inherits resource-node/ResourceNode)

  (property cursor-ranges CursorRanges (default [data/document-start-cursor-range]) (dynamic visible (g/constantly false)))
  (property invalidated-rows InvalidatedRows (default []) (dynamic visible (g/constantly false)))
  (property lines Lines (default [""]) (dynamic visible (g/constantly false)))
  (property regions Regions (default []) (dynamic visible (g/constantly false)))

  (output completions g/Any :cached (g/constantly {}))
  (output breakpoint-rows BreakpointRows :cached produce-breakpoint-rows)
  (output breakpoints g/Any #_project/Breakpoints :cached produce-breakpoints)
  (output save-value Lines (gu/passthrough lines)))

(defn register-code-resource-type [workspace & {:keys [ext node-type icon view-types view-opts tags tag-opts label] :as args}]
  (let [args (assoc args :load-fn load-fn
                         :read-fn read-fn
                         :write-fn write-fn
                         :textual? true)]
    (apply workspace/register-resource-type workspace (mapcat identity args))))
