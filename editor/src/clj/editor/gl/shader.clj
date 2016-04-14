(ns editor.gl.shader
"# Building Shaders

To construct a shader object from .vp and .fp files on disk, use `load-shaders`.

Example:

(load-shaders gl (make-project-path project \"/builtins/tools/atlas/pos_uv\"))

This will look for pos_uv.vp (a vertex shader) and pos_uv.fp (a fragment shader). It will
load both shaders and link them into a program.

To make a shader object from GLSL strings (either just as literal strings, or created
via defshader), use `make-shader`

Example
(defshader frag ,,,)
(defshader vert ,,,)
(make-shader [vert] [frag])

This will use all the strings in the first collection as sources for the vertex shader
and all the strings in the second collection as sources for the fragment shader. If you
only have one string, you can pass that instead of a collection.

# GLSL Translator

The GLSL translator is derived from Roger Allen's Shadertone project (https://github.com/overtone/shadertone).
See licenses/shadertone.txt

This is only a single-pass \"lisp\" to GLSL translator.  Very basic.
If this is useful, then we can work to improve it.

# Basic Forms

Here are the essential forms.
  * define functions
    (defn <return-type> <function-name> <function-args-vector> <body-stmt1> ... )
  * function calls
    (<name> <arg1> <arg2> ... )
  * return value
    (return <statement>)
  * variable creation/assignment
    (uniform <type> <name>)
    (setq <type> <name> <statement>)
    (setq <name> <statement>)
  * looping
    (forloop [ <init-stmt> <test-stmt> <step-stmt> ] <body-stmt1> ... )
    (while <test-stmt> <body-stmt1> ... )
    (break)
    (continue)
  * conditionals
    (if <test> <stmt>)
    (if <test> (do <body-stmt1> ...))
    (if <test> <stmt> <else-stmt>)
    (if <test> (do <body-stmt1> ...) (do <else-stmt1> ...))
  * switch
    (switch <test> <case-int-1> <case-stmt-1> ...)
    cases can only be integers or the keyword :default

# Types

Variable types are exactly the GLSL types.

# Examples

The very simplest case, a constant fragment color.

(defshader test-shader
   (defn void main []
      (setq gl_FragColor (vec4 1.0 0.5 0.5 1.0))))

Note that the \"defn\" inside of defshader resembles clojure.core/defn, but
here it specifically means to create a shader function. Note also the return
type in the declaration.

Here is an example that uses a uniform variable to be set by the application.

(defshader test-shader
  (uniform vec3 iResolution)
  (defn void main []
    (setq vec2 uv (/ gl_FragCoord.xy iResolution.xy))
    (setq gl_FragColor (vec4 uv.x uv.y 0.0 1.0))))

There are some examples in the testcases in dynamo.shader.translate-test."
(:require [clojure.string :as string]
          [clojure.walk :as walk]
          [dynamo.graph :as g]
          [editor.buffers :refer [bbuf->string]]
          [editor.code :as code]
          [editor.geom :as geom]
          [editor.gl :as gl]
          [editor.gl.protocols :refer [GlBind]]
          [editor.types :as types]
          [editor.workspace :as workspace]
          [editor.defold-project :as project]
          [editor.scene-cache :as scene-cache])
(:import [java.nio IntBuffer ByteBuffer]
         [javax.media.opengl GL GL2 GLContext]
         [javax.vecmath Matrix4d Vector4f Point3d]))

(set! *warn-on-reflection* true)

;; ======================================================================
;; shader translation comes from https://github.com/overtone/shadertone.
;; See licenses/shadertone.txt

;; ======================================================================
;; translation functions for a dialect of clojure-like s-expressions
(declare shader-walk)

(defn- shader-typed-assign-str [z]
  (let [[type name value] z
        _ (assert (= 3 (count z)))
        asn-str (format "%s %s = %s;\n"
                          type name
                          (shader-walk (list value)))]
    asn-str))

(defn- shader-assign-str [z]
  (let [[name value] z
        _ (assert (= 2 (count z)))
        asn-str (format "%s = %s;\n"
                        name
                        (shader-walk (list value)))]
    asn-str))

(defn- shader-walk-assign [x]
  (case (count (rest x))
    2 (shader-assign-str (rest x))
    3 (shader-typed-assign-str (rest x))
    :else (assert false "incorrect number of args for setq statement")))

(defn- shader-walk-defn-args [x]
  (assert (vector? x))
  (if (empty? x)
    "void"
    (string/join \, (map #(apply (partial format "%s %s") %) (partition 2 x)))))

(defn- shader-walk-defn [x]
  (let [fn-str (format "%s %s(%s) {\n%s}\n"
                       (nth x 1)
                       (nth x 2)
                       (shader-walk-defn-args (nth x 3))
                       (string/join (shader-walk (drop 4 x))))]  ;; FIXME add indentation level?
   fn-str))

(defn- shader-walk-fn [x]
  (let [pre-fn (if (= (first (str (first x))) \.) "" (str (first x)))
        post-fn (if (= (first (str (first x))) \.) (str (first x)) "")
        fn-str (format "%s(%s)%s"
                       pre-fn
                       (string/join
                        \,
                        (map #(shader-walk (list %)) (rest x)))
                       post-fn)]
    fn-str))

(defn- shader-walk-infix [x]
  (let [fn-str (format "(%s)"
                       (string/join
                        (format " %s " (str (first x)))
                        (map #(shader-walk (list %)) (rest x))))]
    fn-str))

(defn- infix-operator? [x]
  (not (nil? (get #{ "+" "-" "*" "/" "=" "<" ">" "<=" ">=" "==" "!=" ">>" "<<"} x))))

(defn- shader-stmt [x]
  (format "%s;\n" (string/join \space x)))

;; (forloop [ init-stmt test-stmt step-stmt ] body )
(defn- shader-walk-forloop [x]
  (let [[init-stmt test-stmt step-stmt] (nth x 1)
        fl-str (format "for( %s %s; %s ) {\n%s}\n"
                       (shader-walk (list init-stmt))
                       (shader-walk (list test-stmt))
                       (shader-walk (list step-stmt))
                       (string/join (shader-walk (drop 2 x))))]
    fl-str))

;; (whileloop test-stmt body )
(defn- shader-walk-while [x]
  (let [w-str (format "while%s {\n%s}\n"
                      (shader-walk (list (nth x 1)))
                      (string/join (shader-walk (drop 2 x))))]
    w-str))

(defn- shader-walk-do [x]
  (let [w-str (format "{\n%s}\n" (string/join (shader-walk (drop 1 x))))]
    w-str))

(defn- shader-walk-if [x]
  (case (count (rest x))
    2  (let [w-str (format "if%s\n%s" ;; if() {}
                           (shader-walk (list (nth x 1)))
                           (shader-walk (list (nth x 2))))]
         w-str)
    3  (let [w-str (format "if%s\n%selse\n%s" ;; if() {} else {}
                           (shader-walk (list (nth x 1)))
                           (shader-walk (list (nth x 2)))
                           (shader-walk (list (nth x 3))))]
         w-str)
    :else (assert false "incorrect number of args for if statement")))

(defn- shader-walk-case [x]
  (let [[v s] x
        _ (assert (= 2 (count x)))
        c-str (if (number? v)
                (format "case %d:" v)
                (if (= v :default)
                  "default:"
                  (assert false (format "expected integer or default:, got: %s" v))))
        w-str (format "%s\n%s"
                      c-str
                      (shader-walk (list s)))]
    w-str))

(defn- shader-walk-switch [x]
  (let [v     (nth x 1)
        v-str (if (list? v)
                (shader-walk (list v))
                (format "(%s)" (shader-walk (list v))))
        w-str (format "switch%s {\n%s}\n"
                      v-str
                      (string/join (map shader-walk-case (partition 2 (drop 2 x)))))]
    w-str))

(defn- shader-walk-return [x]
  (format "%s;\n" (shader-walk-fn x)))

(defn- shader-walk-index [x]
  (format "%s[%d]" (second x) (nth x 2)))

(defn- inner-walk
  [x]
  (cond
   (list? x)    (let [sfx (str (first x))]
                  (cond
                   (= "defn" sfx)        (shader-walk-defn x)
                   (= "setq" sfx)        (shader-walk-assign x)
                   (= "forloop" sfx)     (shader-walk-forloop x)
                   (= "while" sfx)       (shader-walk-while x)
                   (= "if" sfx)          (shader-walk-if x)
                   (= "do" sfx)          (shader-walk-do x)
                   (= "switch" sfx)      (shader-walk-switch x)
                   (= "break" sfx)       (shader-stmt x)
                   (= "continue" sfx)    (shader-stmt x)
                   (= "uniform" sfx)     (shader-stmt x)
                   (= "varying" sfx)     (shader-stmt x)
                   (= "attribute" sfx)   (shader-stmt x)
                   (= "return" sfx)      (shader-walk-return x)
                   (= "nth" sfx)         (shader-walk-index x)
                   (infix-operator? sfx) (shader-walk-infix x)
                   :else                 (shader-walk-fn x)))
   (symbol? x)  (identity x)
   (float? x)   (identity x)
   (integer? x) (identity x)
   :else        (shader-walk x)))

(defn- outer-walk [x]
  (cond
   (list? x)     (string/join x)
   :else         (identity x)))

(defn- shader-walk [form]
  (walk/walk inner-walk outer-walk form))

;; ======================================================================
;; Public API
(defn create-shader
  "Returns a string in GLSL suitable for compilation. Takes a list of forms.
These forms should be quoted, as if they came from a macro."
  [params]
  (apply str (shader-walk params)))

(defmacro defshader
  "Macro to define the fragment shader program. Defines a new var whose contents will
be the return value of `create-shader`.

This must be submitted to the driver for compilation before you can use it. See
`make-shader`"
  [name & body]
  `(def ~name ~(create-shader body)))

(defprotocol ShaderVariables
  (get-attrib-location [this gl name])
  (set-uniform [this gl name val]))

(defmulti set-uniform-at-index (fn [_ _ _ val] (class val)))

(defmethod set-uniform-at-index Matrix4d
  [^GL2 gl progn loc val]
  (.glUniformMatrix4fv gl loc 1 false (float-array (geom/as-array val)) 0))

(defmethod set-uniform-at-index Vector4f
  [^GL2 gl progn loc ^Vector4f val]
  (.glUniform4f gl loc (.x val) (.y val) (.z val) (.w val)))

(defmethod set-uniform-at-index Point3d
  [^GL2 gl progn loc ^Point3d val]
  (.glUniform3f gl loc (float (.x val)) (float (.y val)) (float (.z val))))

(defmethod set-uniform-at-index (class (float-array []))
  [^GL2 gl progn loc ^floats val]
  (case (count val)
    3 (.glUniform4f gl loc (aget val 0) (aget val 1) (aget val 2) 1)
    4 (.glUniform4f gl loc (aget val 0) (aget val 1) (aget val 2) (aget val 3))))

(defmethod set-uniform-at-index Integer
  [^GL2 gl progn loc val]
  (.glUniform1i gl loc val))

(defmethod set-uniform-at-index Long
  [^GL2 gl progn loc val]
  (.glUniform1i gl loc (int val)))

(defn program-link-errors
  [^GL2 gl progn]
  (let [msg-len (IntBuffer/allocate 1)]
    (.glGetProgramiv gl progn GL2/GL_INFO_LOG_LENGTH msg-len)
    (let [msg (ByteBuffer/allocate (.get msg-len 0))]
      (.glGetProgramInfoLog gl progn (.capacity msg) nil msg)
      (bbuf->string msg))))

(defn make-program
  [^GL2 gl & shaders]
  (let [progn (.glCreateProgram gl)]
    (doseq [s shaders]
      (.glAttachShader gl progn s))
    (.glLinkProgram gl progn)
    (let [status (IntBuffer/allocate 1)]
      (.glGetProgramiv gl progn GL2/GL_LINK_STATUS status)
      (if (= GL/GL_TRUE (.get status 0))
        progn
        (try
          (throw (Exception. (str "Program link failure.\n" (program-link-errors gl progn))))
          (finally
            (.glDeleteProgram gl progn)))))
    progn))

(defn shader-compile-errors
  [^GL2 gl shader-name]
  (let [msg-len (IntBuffer/allocate 1)]
    (.glGetShaderiv gl shader-name GL2/GL_INFO_LOG_LENGTH msg-len)
    (let [msg (ByteBuffer/allocate (.get msg-len 0))]
      (.glGetShaderInfoLog gl shader-name (.capacity msg) nil msg)
      (bbuf->string msg))))

(defn make-shader*
  [type ^GL2 gl source]
  (let [shader-name (.glCreateShader gl type)]
    (.glShaderSource gl shader-name 1
      (into-array String
                  (if (coll? source)
                    source
                    [source]))
      nil)
    (.glCompileShader gl shader-name)
    (let [status (IntBuffer/allocate 1)]
      (.glGetShaderiv gl shader-name GL2/GL_COMPILE_STATUS status)
      (if (= GL/GL_TRUE (.get status 0))
        shader-name
        (try
          (throw (Exception. (str "Shader compilation failure.\n" (shader-compile-errors gl shader-name))))
          (finally
            (.glDeleteShader gl shader-name)))))))

(def make-fragment-shader (partial make-shader* GL2/GL_FRAGMENT_SHADER))
(def make-vertex-shader (partial make-shader* GL2/GL_VERTEX_SHADER))

(defn delete-shader
  [^GL2 gl shader]
  (when (not= 0 shader)
    (.glDeleteShader gl shader)))

(defrecord ShaderLifecycle [request-id verts frags uniforms]
  GlBind
  (bind [this gl render-args]
    (let [[program uniform-locs] (scene-cache/request-object! ::shader request-id gl [verts frags uniforms])]
      (.glUseProgram ^GL2 gl program)
      (doseq [[name val] uniforms
              :let [val (if (keyword? val)
                          (get render-args val)
                          val)
                    loc (uniform-locs name (.glGetUniformLocation ^GL2 gl program name))]]
        (set-uniform-at-index gl program loc val))))

  (unbind [this gl]
    (.glUseProgram ^GL2 gl 0))

  ShaderVariables
  (get-attrib-location [this gl name]
    (when-let [[program _] (scene-cache/request-object! ::shader request-id gl [verts frags uniforms])]
      (gl/gl-get-attrib-location ^GL2 gl program name)))

  (set-uniform [this gl name val]
    (when-let [[program uniform-locs] (scene-cache/request-object! ::shader request-id gl [verts frags uniforms])]
      (let [loc (uniform-locs name (.glGetUniformLocation ^GL2 gl program name))]
        (set-uniform-at-index gl program loc val)))))

(defn make-shader
  "Ready a shader program for use by compiling and linking it. Takes a collection
of GLSL strings and returns an object that satisfies GlBind and GlEnable."
  ([request-id verts frags]
    (make-shader request-id verts frags {}))
  ([request-id verts frags uniforms]
    (->ShaderLifecycle request-id verts frags uniforms)))

(defn load-shaders
  "Load a shader from files. Takes a PathManipulation that can be used to
locate the .vp and .fp files. Returns an object that satisifies GlBind and GlEnable."
  [request-id sdef]
  (make-shader
    request-id
    (slurp (types/replace-extension sdef "vp"))
    (slurp (types/replace-extension sdef "fp"))))

(defn- is-word-start [^Character c] (or (Character/isLetter c) (#{\_} c)))
(defn- is-word-part [^Character c] (or (is-word-start c) (Character/isDigit c)))

(defn- match-multi-comment [charseq]
  (when-let [match-open (code/match-string charseq "/*")]
    (when-let [match-body (code/match-until-string (:body match-open) "*/")]
      (code/combine-matches match-open match-body))))

(defn- match-single-comment [charseq]
  (when-let [match-open (code/match-string charseq "//")]
    (when-let [match-body (code/match-until-eol (:body match-open))]
      (code/combine-matches match-open match-body))))


(def ^:private basic-types ["void" "bool" "int" "float"])

(def ^:private vec-types (for [tp ["" "i" "b"]
                               n (range 2 4)]
                           (str tp "vec" n)))

(def ^:private mat-types (for [n (range 2 4)]
                            (str "mat" n)))

(def ^:private literals ["true" "false"])

(def ^:private extension-behaviors ["require" "enable" "warn" "disable"])

(def ^:private pp-directives (map (partial str "#") (string/split "define undef if ifdef ifndef else elif endif error pragma extension version line" #" ")))

(def ^:private storage-qualifiers ["const" "attribute" "uniform" "varying"])

(def ^:private parameter-qualifiers ["in" "out" "inout"])

(def ^:private precision-qualifiers ["lowp" "mediump" "highp"])

(def ^:private other-keywords (string/split "break continue do for while if else precision invariant discard return sampler2D samplerCube struct" #" "))

(def ^:private reserved (string/split "asm class union enum typedef template this packed goto switch default inline noinline volatile public static extern external interface flat long short double half fixed unsigned superp input output hvec2 hvec3 hvec4 dvec2 dvec3 dvec4 fvec2 fvec3 fvec4 sampler1D sampler3D sampler1DShadow sampler2dShadow sampler2DRect sampler3DRect sampler2DRectShadow sizeof cast namespace using" #" "))

(def ^:private keywords (concat basic-types
                                vec-types
                                mat-types
                                literals
                                extension-behaviors
                                storage-qualifiers
                                parameter-qualifiers
                                precision-qualifiers
                                other-keywords
                                reserved))

(def ^:private operators (string/split "( ) [ ] . ++ -- + - ~ ! * / % << >> < > <= >= == != & ^ | && ^^ || ? : = += -= *= /= %= <<= >>= &= ^= |= ," #" "))


(def glsl-opts {:code {:language "glsl"
                       :syntax
                       ;; see note in lua.clj on why we put multiline comments in the default partition
                       [#_{:partition "__multicomment"
                         :type :multiline
                         :start "/*" :end "*/"
                         :eof true
                         :rules
                         [{:type :default :class "comment"}]
                         }
                        {:partition :default
                         :type :default
                         :rules
                         [{:type :multiline :start "\"" :end "\"" :eof false :class "string"}
                          {:type :custom :scanner match-multi-comment :class "comment2"}
                          {:type :custom :scanner match-single-comment :class "comment"}
                          {:type :whitespace}
                          {:type :keyword :start? is-word-start :part? is-word-part :keywords keywords :class "keyword"}
                          {:type :word :start? is-word-start :part? is-word-part :class "default"}
                          {:type :number :class "number"}
                          {:type :default :class "default"}]
                         }
                        ]
                       }})

(def shader-defs [{:ext "vp"
                   :label "Vertex Program"
                   :icon "icons/32/Icons_32-Vertex-shader.png"
                   :view-types [:code :default]
                   :view-opts glsl-opts
                   :prefix (string/join "\n" ["#ifndef GL_ES"
                                              "#define lowp"
                                              "#define mediump"
                                              "#define highp"
                                              "#endif"
                                              ""])}
                  {:ext "fp"
                   :label "Fragment Program"
                   :icon "icons/32/Icons_33-Fragment-shader.png"
                   :view-types [:code :default]
                   :view-opts glsl-opts
                   :prefix (string/join "\n" ["#ifdef GL_ES"
                                              "precision mediump float;"
                                              "#endif"
                                              "#ifndef GL_ES"
                                              "#define lowp"
                                              "#define mediump"
                                              "#define highp"
                                              "#endif"
                                              ""])}])

(defn- build-shader [self basis resource dep-resources user-data]
  {:resource resource :content (.getBytes ^String (:source user-data))})

(g/defnk produce-build-targets [_node-id resource full-source def]
  [{:node-id _node-id
    :resource (workspace/make-build-resource resource)
    :build-fn build-shader
    :user-data {:source full-source
                :def def}}])

(g/defnode ShaderNode
  (inherits project/ResourceNode)

  (property code g/Str (dynamic visible (g/always false)))
  (property def g/Any (dynamic visible (g/always false)))
  (property caret-position g/Int (dynamic visible (g/always false)) (default 0))

  (output build-targets g/Any produce-build-targets)
  (output full-source g/Str (g/fnk [code def] (str (get def :prefix) code))))

(defn- load-shader [project self input def]
  (let [source (slurp input)]
    (concat
      (g/set-property self :code source)
      (g/set-property self :def def))))

(defn- register [workspace def]
  (workspace/register-resource-type workspace
                                   :ext (:ext def)
                                   :label (:label def)
                                   :node-type ShaderNode
                                   :load-fn (fn [project self resource] (load-shader project self resource def))
                                   :icon (:icon def)
                                   :view-types (:view-types def)
                                   :view-opts (:view-opts def)))

(defn register-resource-types [workspace]
  (for [def shader-defs]
    (register workspace def)))

(defn- make-shader-program [^GL2 gl [verts frags uniforms]]
  (let [vs     (make-vertex-shader gl verts)
        fs      (make-fragment-shader gl frags)
        program (make-program gl vs fs)
        uniform-locs (into {} (map (fn [[name val]] [name (.glGetUniformLocation ^GL2 gl program name)]) uniforms))]
    (delete-shader gl vs)
    (delete-shader gl fs)
    [program uniform-locs]))

(defn- update-shader-program [^GL2 gl [program uniform-locs] data]
  (delete-shader gl program)
  (make-shader-program gl data))

(defn- destroy-shader-programs [^GL2 gl programs _]
  (doseq [[program _] programs]
    (delete-shader gl program)))

(scene-cache/register-object-cache! ::shader make-shader-program update-shader-program destroy-shader-programs)
