(defproject defold-editor "2.0.0-SNAPSHOT"
  :description      "Defold game editor"
  :url              "http://doc.defold.com"

  :repositories     {"local" ~(str (.toURI (java.io.File. "localjars")))}

  :plugins          [[lein-protobuf-minimal "0.4.4" :hooks false]
                     [codox "0.8.10"]]

  :dependencies     [[org.clojure/clojure                         "1.7.0-beta3"]
                     [org.clojure/core.cache                      "0.6.3"]
                     [org.clojure/core.async                      "0.1.346.0-17112a-alpha"]
                     [org.clojure/core.match                      "0.2.1"]
                     [org.clojure/tools.macro                     "0.1.2"]
                     [org.clojure/tools.namespace                 "0.2.10"]
                     [prismatic/schema                            "0.3.1"]
                     [prismatic/plumbing                          "0.3.5"]
                     [inflections/inflections                     "0.9.10"]
                     [com.google.protobuf/protobuf-java           "2.3.0"]
                     ; Keep jogl version in sync with bundle.py. See JOGL_VERSION
                     [org.jogamp.gluegen/gluegen-rt-main          "2.0.2"]
                     [org.jogamp.jogl/jogl-all-main               "2.0.2"]
                     [org.slf4j/slf4j-api                         "1.6.4"]
                     [ch.qos.logback/logback-classic              "1.1.2"]
                     [joda-time/joda-time                         "2.1"]
                     [javafx-wrapper                              "0.1.0"]
                     [commons-io/commons-io                       "2.3"]
                     [org.clojure/tools.nrepl                     "0.2.7" :exclusions [org.clojure/clojure]]
                     [cider/cider-nrepl                           "0.9.0-SNAPSHOT" :exclusions [org.clojure/tools.nrepl]]

                     [com.nanohttpd/nanohttpd                     "2.1.1"]
                     [com.sun.jersey/jersey-core                  "1.19"]
                     [com.sun.jersey/jersey-client                "1.19"]
                     ; NOTE: Ancient libraries
                     [org.codehaus.jackson/jackson-core-asl       "1.9.13"]
                     [org.codehaus.jackson/jackson-mapper-asl     "1.9.13"]
                     [org.eclipse.jgit/org.eclipse.jgit           "4.0.0.201505050340-m2"]

                     [org.projectodd.shimdandy/shimdandy-api "1.1.0"]
                     [org.projectodd.shimdandy/shimdandy-impl "1.1.0"]

                     [javax/vecmath                               "1.5.2"]
                     [net.java.dev.jna/jna                        "4.1.0"]
                     [net.java.dev.jna/jna-platform               "4.1.0"]
                     ;; Temporarily removed upnp (not used and not installed by default).
                     ;; Move source to editor?
                     #_[dlib/upnp                                   "0.1"]]

  :source-paths      ["src/clj"
                      "../com.dynamo.cr/com.dynamo.cr.sceneed2/src/clj"]

  :java-source-paths ["src/java"
                      "../com.dynamo.cr/com.dynamo.cr.sceneed2/src/java"]

  :resource-paths    ["resources"]

  :proto-paths       ["../com.dynamo.cr/com.dynamo.cr.common/proto"
                      "../com.dynamo.cr/com.dynamo.cr.rlog/proto"
                      "../engine/ddf/src"
                      "../engine/engine/proto"
                      "../engine/gameobject/proto"
                      "../engine/gamesys/proto"
                      "../engine/graphics/proto"
                      "../engine/input/proto"
                      "../engine/particle/proto/particle"
                      "../engine/render/proto/render"
                      "../engine/resource/proto"
                      "../engine/script/src"
                      "../engine/vscript/proto"]

  :protobuf-includes ["../engine/gamesys/proto"
                      "../engine/ddf/src"
                      "../engine/script/src"
                      ~(str (System/getenv "DYNAMO_HOME") "/ext/include")]

  :protobuf-exclude  ["../engine/ddf/src/test"]

  :aliases           {"ci" ["do" "test," "uberjar"]
                      "benchmark" ["trampoline" "run" "-m" "benchmark.graph-benchmark"]}

  :codox             {:sources                   ["src/clj"]
                      :output-dir                "target/doc/api"
                      :exclude                   [internal]
                      :src-dir-uri               "http://github.com/relevance/defold/blob/clojure-sceneed"
                      :src-linenum-anchor-prefix "L"
                      :doc/format                :markdown}

  :profiles          {:test        {:injections [(defonce force-toolkit-init (javafx.embed.swing.JFXPanel.))]}
                      :uberjar     {:main com.defold.editor.Start
                                    :prep-tasks ["protobuf" "javac" "compile"]
                                    :aot  [editor]}
                      :repl        {:source-paths   ["dev"]
                                    :prep-tasks     ^:replace []
                                    :aot            ^:replace []
                                    :repl-options   {:init-ns user}}
                      :dev         {:dependencies   [[org.clojure/test.check   "0.5.8"]
                                                     [org.mockito/mockito-core "1.8.5"]
                                                     [org.clojure/tools.nrepl  "0.2.7" :exclusions [org.clojure/clojure]]
                                                     [criterium "0.4.3"]]
                                    :repl-options   {:port 4001}
                                    :proto-paths    ["test/proto"]
                                    :main ^:skip-aot com.defold.editor.Start
                                    :resource-paths ["test/resources"]}})

