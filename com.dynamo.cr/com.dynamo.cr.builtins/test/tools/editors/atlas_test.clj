(ns editors.atlas-test
  (:require [clojure.string :as str]
            [clojure.java.io :as io]
            [schema.core :as s]
            [plumbing.core :refer [fnk defnk]]
            [clojure.test :refer :all]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [clojure.test.check.clojure-test :refer [defspec]]
            [dynamo.file :as file]
            [dynamo.image :as image]
            [dynamo.node :as n]
            [dynamo.project :as p]
            [dynamo.system :as ds]
            [dynamo.system.test-support :refer [with-clean-world tempfile mock-iproject fixture]]
            [dynamo.types :as t]
            [schema.test]
            [editors.atlas :as atlas])
  (:import [com.dynamo.atlas.proto AtlasProto AtlasProto$Atlas AtlasProto$AtlasAnimation AtlasProto$AtlasImage]
           [java.io StringReader]
           [dynamo.types Image]
           [javax.imageio ImageIO]))

(def ident (gen/not-empty gen/string-alpha-numeric))

(def image-name (gen/fmap (fn [ns] (str (str/join \/ ns) ".png")) (gen/not-empty (gen/vector ident))))

(def image (gen/fmap #(format "images{\nimage: \"%s\"\n}"  %) image-name))

(def animation-name ident)
(def fps (gen/such-that #(< 0 % 60) gen/pos-int))
(def flip (gen/frequency [[9 (gen/return 0)] [1 (gen/return 1)]]))
(def playback (gen/elements ["PLAYBACK_NONE"
                             "PLAYBACK_ONCE_FORWARD"
                             "PLAYBACK_ONCE_BACKWARD"
                             "PLAYBACK_ONCE_PINGPONG"
                             "PLAYBACK_LOOP_FORWARD"
                             "PLAYBACK_LOOP_BACKWARD"
                             "PLAYBACK_LOOP_PINGPONG"]))

(def animation (gen/fmap (fn [[id imgs fps flip-horiz flip-vert playback]]
                           (format "animations {\nid: \"%s\"\n%s\nfps: %d\nflip_horizontal: %d\nflip_vertical: %d\nplayback: %s\n}"
                             id (str/join \newline imgs) fps flip-horiz flip-vert playback))
                 (gen/tuple animation-name (gen/not-empty (gen/vector image)) fps flip flip playback )))

(def atlas (gen/fmap (fn [[margin borders animations images]]
                       (format "%s\n%s\nmargin: %d\nextrude_borders: %d\n"
                         (str/join \newline images) (str/join \newline animations) margin borders))
             (gen/tuple gen/pos-int gen/pos-int (gen/vector animation) (gen/vector image))))

(defn <-text
  [project-node text-format]
  (ds/transactional
    (ds/in project-node
      (let [atlas (ds/add (n/construct atlas/AtlasNode))]
        (atlas/construct-ancillary-nodes atlas (StringReader. text-format))
        atlas))))

(defn ->text
  [atlas]
  (n/get-node-value atlas :text-format))

(n/defnode WildcardImageResourceNode
  (inherits n/OutlineNode)
  (output outline-label s/Str (fnk [filename] (t/local-name filename)))
  (property filename (s/protocol t/PathManipulation) (visible false))
  (output content Image :cached (fnk [filename] (assoc image/placeholder-image :path (t/local-path filename)))))

(defn test-project
  [image-resource-node-type]
  (ds/transactional
    (let [eproj (mock-iproject {})
          project-node (ds/add (n/construct p/Project :eclipse-project eproj))]
      (ds/in project-node
        (p/register-editor "atlas" #'editors.atlas/on-edit)
        (p/register-node-type "atlas" editors.atlas/AtlasNode)
        (p/register-node-type "png" image-resource-node-type)
        (p/register-node-type "jpg" image-resource-node-type)
        project-node))))

(defn round-trip
  [random-atlas]
  (with-clean-world
    (let [project-node (test-project WildcardImageResourceNode)
          first-gen    (->text (<-text project-node random-atlas))
          second-gen   (->text (<-text project-node first-gen))]
      (= first-gen second-gen))))

(defspec round-trip-preserves-fidelity
  10
  (prop/for-all* [atlas] round-trip))

(deftest compilation-to-binary
  (testing "Doesn't throw an exception"
    (with-clean-world
      (let [project-node (test-project WildcardImageResourceNode)
            atlas        (<-text project-node (first (gen/sample (gen/resize 5 atlas) 1)))
            txname       "random-mcnally"
            texturesetc  (tempfile txname "texturesetc" true)
            texturec     (tempfile txname "texturec" true)
            compiler     (ds/transactional
                           (ds/add
                             (n/construct atlas/TextureSave
                               :texture-name        txname
                               :texture-filename    (file/native-path (.getPath texturec))
                               :textureset-filename (file/native-path (.getPath texturesetc)))))]
        (ds/transactional (ds/connect atlas :textureset   compiler :textureset))
        (ds/transactional (ds/connect atlas :packed-image compiler :packed-image))
        (is (= :ok (n/get-node-value compiler :texturec)))
        (is (= :ok (n/get-node-value compiler :texturesetc)))))))

(defn builtin-fixture [fixture-name]
  (fixture "com.dynamo.cr.builtins" (str "/test/resources/" fixture-name)))

(defnk image-from-fixture [this filename]
  (let [filename-str (t/local-path filename)]
    (if-let [img (ImageIO/read (io/input-stream (builtin-fixture filename-str)))]
      (image/make-image filename-str img))))

(n/defnode FixtureImageResourceNode
  (inherits n/OutlineNode)
  (output outline-label s/Str (fnk [filename] (t/local-name filename)))
  (property filename (s/protocol t/PathManipulation) (visible false))
  (output content Image :cached :substitute-value image/placeholder-image image-from-fixture))

(defn atlas-from-fixture
  [project-node atlas-text]
  (ds/transactional
    (ds/in project-node
      (let [atlas (ds/add (n/construct atlas/AtlasNode))]
        (atlas/construct-ancillary-nodes atlas (StringReader. atlas-text))
        atlas))))

(defn matches-fixture? [fixture-name output-file]
  (let [actual   (slurp output-file)
        expected (slurp (builtin-fixture fixture-name))]
    (= actual expected)))

(defn verify-atlas-artifacts [fixture-basename]
  (with-clean-world
    (let [project-node (test-project FixtureImageResourceNode)
          atlas-text   (slurp (builtin-fixture (str fixture-basename ".atlas")))
          atlas        (atlas-from-fixture project-node atlas-text)
          texturesetc  (tempfile fixture-basename ".texturesetc" true)
          texturec     (tempfile fixture-basename ".texturec" true)
          compiler     (ds/transactional
                         (ds/add
                           (n/construct atlas/TextureSave
                             :texture-name        (str fixture-basename ".texturesetc")
                             :textureset-filename (file/native-path (.getPath texturesetc))
                             :texture-filename    (file/native-path (.getPath texturec)))))]
      (ds/transactional (ds/connect atlas :textureset   compiler :textureset))
      (ds/transactional (ds/connect atlas :packed-image compiler :packed-image))

      ; TODO: fails when placeholder image is used
      #_(is (= atlas-text (n/get-node-value atlas :text-format)))
      (is (= :ok (n/get-node-value compiler :texturec)))
      (is (= :ok (n/get-node-value compiler :texturesetc)))
      (is (matches-fixture? (str "build/default/" fixture-basename ".texturesetc") texturesetc))
      (is (matches-fixture? (str "build/default/" fixture-basename ".texturec")    texturec)))))

(deftest expected-atlas-artifacts
  (verify-atlas-artifacts "atlases/empty")
  (verify-atlas-artifacts "atlases/single-image")
  ; TODO: fails sometimes due to non-deterministic layout/sort order of images in output texture
  #_(verify-atlas-artifacts "atlases/single-animation")
  (verify-atlas-artifacts "atlases/empty-animation")
  (verify-atlas-artifacts "atlases/missing-image")
  (verify-atlas-artifacts "atlases/missing-image-in-animation")
  ; TODO: fails sometimes due to non-deterministic layout/sort order of images in output texture
  #_(verify-atlas-artifacts "atlases/complex")
  (verify-atlas-artifacts "atlases/single-image-multiple-references")
  (verify-atlas-artifacts "atlases/single-image-multiple-references-in-animation")
  (verify-atlas-artifacts "atlases/missing-image-multiple-references")
  (verify-atlas-artifacts "atlases/missing-image-multiple-references-in-animation"))

(defn simple-outline [outline-tree]
  [(:label outline-tree) (map simple-outline (:children outline-tree))])

(deftest outline
  (with-clean-world
    (let [project-node (test-project FixtureImageResourceNode)
          atlas-text   (slurp (builtin-fixture "atlases/complex.atlas"))
          atlas        (atlas-from-fixture project-node atlas-text)]
      (is (= ["Atlas" [["frame-01.png" []]
                       ["frame-02.png" []]
                       ["small.png" []]
                       ["large.png" []]
                       ["anim1" [["frame-01.png" []]]]
                       ["anim2" [["frame-02.png" []]]]
                       ["anim3" [["frame-03.png" []]]]
                       ["anim4" [["frame-01.png" []]
                                 ["frame-02.png" []]
                                 ["frame-03.png" []]]]]]
            (simple-outline (n/get-node-value atlas :outline-tree))))))
  (with-clean-world
    (let [project-node (test-project FixtureImageResourceNode)
          atlas-text   (slurp (builtin-fixture "atlases/single-animation.atlas"))
          atlas        (atlas-from-fixture project-node atlas-text)
          anim1        (ffirst (ds/sources-of (:graph @world-ref) atlas :animations))
          img-frame-01 (t/lookup project-node "/images/frame-01.png")
          img-frame-02 (t/lookup project-node "/images/frame-02.png")

          ; initial load
          outline1     (n/get-node-value atlas :outline-tree)

          ; disconnect image from anim
          atlas        (ds/transactional (ds/disconnect img-frame-02 :content anim1 :images) atlas)
          outline2     (n/get-node-value atlas :outline-tree)

          ; disconnect anim
          atlas        (ds/transactional (ds/disconnect anim1 :animation atlas :animations) atlas)
          outline3     (n/get-node-value atlas :outline-tree)

          ; connect existing image
          atlas        (ds/transactional (ds/connect img-frame-01 :content atlas :images) atlas)
          outline4     (n/get-node-value atlas :outline-tree)

          ; disconnect image
          atlas        (ds/transactional (ds/disconnect img-frame-01 :content atlas :images) atlas)
          outline5     (n/get-node-value atlas :outline-tree)

          ; add anim
          anim2        (ds/transactional (ds/add (n/construct atlas/AnimationGroupNode :id "anim2")))
          atlas        (ds/transactional (ds/connect anim2 :animation atlas :animations) atlas)
          outline6     (n/get-node-value atlas :outline-tree)

          ; connect image to anim
          img-small    (ds/transactional (ds/in project-node (ds/add (t/node-for-path project-node (file/make-project-path project-node "/images/small.png")))))
          atlas        (ds/transactional (ds/connect img-small    :content anim2 :images) atlas)
          atlas        (ds/transactional (ds/connect img-frame-01 :content anim2 :images) atlas)
          outline7     (n/get-node-value atlas :outline-tree)

          ; connect missing (placeholder) image
          img-missing  (ds/transactional (ds/in project-node (ds/add (t/node-for-path project-node (file/make-project-path project-node "/images/missing.png")))))
          atlas        (ds/transactional (ds/connect img-missing :content anim2 :images) atlas)
          outline8     (n/get-node-value atlas :outline-tree)

          ; connect another missing (placeholder) image
          img-missing2 (ds/transactional (ds/in project-node (ds/add (t/node-for-path project-node (file/make-project-path project-node "/images/missing2.png")))))
          atlas        (ds/transactional (ds/connect img-missing2 :content anim2 :images) atlas)
          outline9     (n/get-node-value atlas :outline-tree)

          ; disconnect placeholder
          atlas        (ds/transactional (ds/disconnect img-missing :content anim2 :images) atlas)
          outline10    (n/get-node-value atlas :outline-tree)

          ; connect duplicate existing image
          atlas        (ds/transactional (ds/connect img-frame-01 :content anim2 :images) atlas)
          outline11    (n/get-node-value atlas :outline-tree)

          ; disconnect duplicate existing image
          atlas        (ds/transactional (ds/disconnect img-frame-01 :content anim2 :images) atlas)
          outline12    (n/get-node-value atlas :outline-tree)

          ; connect duplicate missing (placeholder) image
          atlas        (ds/transactional (ds/connect img-missing2 :content anim2 :images) atlas)
          outline13    (n/get-node-value atlas :outline-tree)

          ; disconnect duplicate missing (placeholder) image
          atlas        (ds/transactional (ds/disconnect img-missing2 :content anim2 :images) atlas)
          outline14    (n/get-node-value atlas :outline-tree)]
      (are [outline-tree expected] (= expected (simple-outline outline-tree))
        outline1  ["Atlas" [["anim1" [["frame-01.png" []] ["frame-02.png" []] ["frame-03.png" []]]]]]
        outline2  ["Atlas" [["anim1" [["frame-01.png" []] ["frame-03.png" []]]]]]
        outline3  ["Atlas" []]
        outline4  ["Atlas" [["frame-01.png" []]]]
        outline5  ["Atlas" []]
        outline6  ["Atlas" [["anim2" []]]]
        outline7  ["Atlas" [["anim2" [["small.png" []] ["frame-01.png" []]]]]]
        outline8  ["Atlas" [["anim2" [["small.png" []] ["frame-01.png" []] ["missing.png" []]]]]]
        outline9  ["Atlas" [["anim2" [["small.png" []] ["frame-01.png" []] ["missing.png" []] ["missing2.png" []]]]]]
        outline10 ["Atlas" [["anim2" [["small.png" []] ["frame-01.png" []] ["missing2.png" []]]]]]
        outline11 ["Atlas" [["anim2" [["small.png" []] ["frame-01.png" []] ["missing2.png" []] ["frame-01.png" []]]]]]
        outline12 ["Atlas" [["anim2" [["small.png" []] ["frame-01.png" []] ["missing2.png" []]]]]]
        outline13 ["Atlas" [["anim2" [["small.png" []] ["frame-01.png" []] ["missing2.png" []] ["missing2.png" []]]]]]
        outline14 ["Atlas" [["anim2" [["small.png" []] ["frame-01.png" []] ["missing2.png" []]]]]]))))
