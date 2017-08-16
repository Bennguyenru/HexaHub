(ns editor.code.script
  (:require [clojure.string :as string]
            [dynamo.graph :as g]
            [editor.code.resource :as r]
            [editor.code-completion :as code-completion]
            [editor.defold-project :as project]
            [editor.graph-util :as gu]
            [editor.lua :as lua]
            [editor.luajit :as luajit]
            [editor.lua-parser :as lua-parser]
            [editor.pipeline.lua-scan :as lua-scan]
            [editor.properties :as properties]
            [editor.protobuf :as protobuf]
            [editor.resource :as resource]
            [editor.types :as t]
            [editor.workspace :as workspace]
            [internal.util :as iutil])
  (:import (com.dynamo.lua.proto Lua$LuaModule)
           (com.google.protobuf ByteString)))

(set! *warn-on-reflection* true)

(def ^:private lua-grammar
  {:name "Lua"
   :scope-name "source.lua"
   :patterns [{:captures {1 {:name "keyword.control.lua"}
                          2 {:name "entity.name.function.scope.lua"}
                          3 {:name "entity.name.function.lua"}
                          4 {:name "punctuation.definition.parameters.begin.lua"}
                          5 {:name "variable.parameter.function.lua"}
                          6 {:name "punctuation.definition.parameters.end.lua"}}
               :match #"\b(function)(?:\s+([a-zA-Z_.:]+[.:])?([a-zA-Z_]\w*)\s*)?(\()([^)]*)(\))"
               :name "meta.function.lua"}
              {:match #"(?<![\d.])\s0x[a-fA-F\d]+|\b\d+(\.\d+)?([eE]-?\d+)?|\.\d+([eE]-?\d+)?"
               :name "constant.numeric.lua"}
              {:begin #"'"
               :begin-captures {0 {:name "punctuation.definition.string.begin.lua"}}
               :end #"'"
               :end-captures {0 {:name "punctuation.definition.string.end.lua"}}
               :name "string.quoted.single.lua"
               :patterns [{:match #"\\."
                           :name "constant.character.escape.lua"}]}
              {:begin #"\""
               :begin-captures {0 {:name "punctuation.definition.string.begin.lua"}}
               :end #"\""
               :end-captures {0 {:name "punctuation.definition.string.end.lua"}}
               :name "string.quoted.double.lua"
               :patterns [{:match #"\\."
                           :name "constant.character.escape.lua"}]}
              {:begin #"(?<!--)\[(=*)\["
               :begin-captures {0 {:name "punctuation.definition.string.begin.lua"}}
               :end #"\]\1\]"
               :end-captures {0 {:name "punctuation.definition.string.end.lua"}}
               :name "string.quoted.other.multiline.lua"}
              {:begin #"--\[(=*)\["
               :captures {0 {:name "punctuation.definition.comment.lua"}}
               :end #"\]\1\]"
               :name "comment.block.lua"}
              {:captures {1 {:name "punctuation.definition.comment.lua"}}
               :match #"(--).*"
               :name "comment.line.double-dash.lua"}
              {:match #"\b(and|or|not|break|do|else|for|if|elseif|return|then|repeat|while|until|end|function|local|in|goto)\b"
               :name "keyword.control.lua"}
              {:match #"(?<![^.]\.|:)\b([A-Z_]+|false|nil|true|math\.(pi|huge))\b|(?<![.])\.{3}(?!\.)"
               :name "constant.language.lua"}
              {:match #"(?<![^.]\.|:)\b(self)\b"
               :name "variable.language.self.lua"}
              {:match #"(?<![^.]\.|:)\b(assert|collectgarbage|dofile|error|getfenv|getmetatable|ipairs|loadfile|loadstring|module|next|pairs|pcall|print|rawequal|rawget|rawset|require|select|setfenv|setmetatable|tonumber|tostring|type|unpack|xpcall)\b(?=\s*(?:[({\"']|\[\[))"
               :name "support.function.lua"}
              {:match #"(?<![^.]\.|:)\b(coroutine\.(create|resume|running|status|wrap|yield)|string\.(byte|char|dump|find|format|gmatch|gsub|len|lower|match|rep|reverse|sub|upper)|table\.(concat|insert|maxn|remove|sort)|math\.(abs|acos|asin|atan2?|ceil|cosh?|deg|exp|floor|fmod|frexp|ldexp|log|log10|max|min|modf|pow|rad|random|randomseed|sinh?|sqrt|tanh?)|io\.(close|flush|input|lines|open|output|popen|read|tmpfile|type|write)|os\.(clock|date|difftime|execute|exit|getenv|remove|rename|setlocale|time|tmpname)|package\.(cpath|loaded|loadlib|path|preload|seeall)|debug\.(debug|[gs]etfenv|[gs]ethook|getinfo|[gs]etlocal|[gs]etmetatable|getregistry|[gs]etupvalue|traceback))\b(?=\s*(?:[({\"']|\[\[))"
               :name "support.function.library.lua"}
              {:match #"\b([A-Za-z_]\w*)\b(?=\s*(?:[({\"']|\[\[))"
               :name "support.function.any-method.lua"}
              {:match #"(?<=[^.]\.|:)\b([A-Za-z_]\w*)"
               :name "variable.other.lua"}
              {:match #"\+|-|%|#|\*|\/|\^|==?|~=|<=?|>=?|(?<!\.)\.{2}(?!\.)"
               :name "keyword.operator.lua"}]})

(def ^:private lua-code-opts {:grammar lua-grammar})
(def go-prop-type->property-types
  {:property-type-number  g/Num
   :property-type-hash    g/Str
   :property-type-url     g/Str
   :property-type-vector3 t/Vec3
   :property-type-vector4 t/Vec4
   :property-type-quat    t/Vec3
   :property-type-boolean g/Bool})

(def script-defs [{:ext "script"
                   :label "Script"
                   :icon "icons/32/Icons_12-Script-type.png"
                   :view-types [:new-code :default]
                   :view-opts lua-code-opts
                   :tags #{:component :non-embeddable :overridable-properties}
                   :tag-opts {:component {:transform-properties #{}}}}
                  {:ext "render_script"
                   :label "Render Script"
                   :icon "icons/32/Icons_12-Script-type.png"
                   :view-types [:new-code :default]
                   :view-opts lua-code-opts}
                  {:ext "gui_script"
                   :label "Gui Script"
                   :icon "icons/32/Icons_12-Script-type.png"
                   :view-types [:new-code :default]
                   :view-opts lua-code-opts}
                  {:ext "lua"
                   :label "Lua Module"
                   :icon "icons/32/Icons_11-Script-general.png"
                   :view-types [:new-code :default]
                   :view-opts lua-code-opts}])

(def ^:private status-errors
  {:ok nil
   :invalid-args (g/error-fatal "Invalid arguments to go.property call") ; TODO: not used for now
   :invalid-value (g/error-fatal "Invalid value in go.property call")})

(defn- prop->key [p]
  (-> p :name properties/user-name->key))

(g/defnk produce-user-properties [_node-id script-properties]
  (let [script-props (filter (comp #{:ok} :status) script-properties)
        props (into {} (map (fn [p]
                              (let [type (:type p)
                                    prop (-> (select-keys p [:value])
                                             (assoc :node-id _node-id
                                                    :type (go-prop-type->property-types type)
                                                    :error (status-errors (:status p))
                                                    :edit-type {:type (go-prop-type->property-types type)}
                                                    :go-prop-type type
                                                    :read-only? (nil? (g/override-original _node-id))))]
                                [(prop->key p) prop]))
                            script-props))
        display-order (mapv prop->key script-props)]
    {:properties props
     :display-order display-order}))

(g/defnk produce-bytecode
  [_node-id code resource]
  (try
    (luajit/bytecode code (resource/proj-path resource))
    (catch Exception e
      (let [{:keys [filename line message]} (ex-data e)]
        (g/->error _node-id :code :fatal e (.getMessage e)
                   {:filename filename
                    :line     line
                    :message  message})))))

(defn- build-script [self basis resource dep-resources user-data]
  (let [user-properties (:user-properties user-data)
        properties (mapv (fn [[k v]] (let [type (:go-prop-type v)]
                                       {:id (properties/key->user-name k)
                                        :value (properties/go-prop->str (:value v) type)
                                        :type type}))
                         (:properties user-properties))
        modules (:modules user-data)
        bytecode (:bytecode user-data)]
    {:resource resource :content (protobuf/map->bytes Lua$LuaModule
                                                      {:source {:script (ByteString/copyFromUtf8 (:content user-data))
                                                                :filename (resource/proj-path (:resource resource))
                                                                :bytecode (when-not (g/error? bytecode)
                                                                            (ByteString/copyFrom ^bytes bytecode))}
                                                       :modules modules
                                                       :resources (mapv lua/lua-module->build-path modules)
                                                       :properties (properties/properties->decls properties)})}))

(g/defnk produce-build-targets [_node-id resource code bytecode user-properties modules dep-build-targets]
  [{:node-id _node-id
    :resource (workspace/make-build-resource resource)
    :build-fn build-script
    :user-data {:content code :user-properties user-properties :modules modules :bytecode bytecode}
    :deps dep-build-targets}])

(g/defnode ScriptNode
  (inherits r/CodeEditorResourceNode)

  #_(property prev-modules g/Any
            (dynamic visible (g/constantly false)))

  #_(property code g/Str
            (set (fn [basis self old-value new-value]
                   (let [modules (set (lua-scan/src->modules new-value))
                         prev-modules (g/node-value self :prev-modules {:basis basis})]
                     (when-not (= modules prev-modules)
                       (let [project (project/get-project self)]
                         (concat
                           (g/set-property self :prev-modules modules)
                           (gu/disconnect-all basis self :dep-build-targets)
                           (gu/disconnect-all basis self :module-completion-infos)
                           (for [module modules]
                             (let [path (lua/lua-module->path module)]
                               (project/connect-resource-node project path self
                                                              [[:build-targets :dep-build-targets]
                                                               [:completion-info :module-completion-infos]])))))))))
            (dynamic visible (g/constantly false)))

  #_(property caret-position g/Int (dynamic visible (g/constantly false)) (default 0))
  #_(property prefer-offset g/Int (dynamic visible (g/constantly false)) (default 0))
  #_(property tab-triggers g/Any (dynamic visible (g/constantly false)) (default nil))
  #_(property selection-offset g/Int (dynamic visible (g/constantly false)) (default 0))
  #_(property selection-length g/Int (dynamic visible (g/constantly false)) (default 0))

  (input dep-build-targets g/Any :array)
  (input module-completion-infos g/Any :array)

  ;; todo replace this with the lua-parser modules
  (output modules g/Any :cached (g/fnk [code] (lua-scan/src->modules code)))
  (output script-properties g/Any :cached (g/fnk [code]
                                            (->> (lua-scan/src->properties code)
                                                 (filter #(not (string/blank? (:name %))))
                                                 (iutil/distinct-by :name)
                                                 vec)))
  (output user-properties g/Properties :cached produce-user-properties)
  (output _properties g/Properties :cached (g/fnk [_declared-properties user-properties]
                                             ;; TODO - fix this when corresponding graph issue has been fixed
                                             (cond
                                               (g/error? _declared-properties) _declared-properties
                                               (g/error? user-properties) user-properties
                                               true (-> _declared-properties
                                                        (update :properties into (:properties user-properties))
                                                        (update :display-order into (:display-order user-properties))))))
  (output bytecode g/Any :cached produce-bytecode)
  (output build-targets g/Any :cached produce-build-targets)

  (output completion-info g/Any :cached (g/fnk [_node-id code resource]
                                          (assoc (lua-parser/lua-info code)
                                            :module (lua/path->lua-module (resource/proj-path resource)))))
  (output completions g/Any :cached (g/fnk [completion-info module-completion-infos]
                                      (code-completion/combine-completions completion-info module-completion-infos))))

(defn register-resource-types [workspace]
  (for [def script-defs
        :let [args (assoc def :node-type ScriptNode)]]
    (apply r/register-code-resource-type workspace (mapcat identity args))))
