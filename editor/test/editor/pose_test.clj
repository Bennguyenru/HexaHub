(ns editor.pose-test
  (:require [clojure.test :refer :all]
            [editor.math :as math]
            [editor.pose :as pose])
  (:import [javax.vecmath Matrix4d Quat4d Vector3d]))

(set! *warn-on-reflection* true)
(set! *unchecked-math* :warn-on-boxed)

(defn- round-numbers [numbers]
  (mapv #(math/round-with-precision % 0.001)
        numbers))

(deftest pose?-test
  (is (false? (pose/pose? nil)))
  (is (false? (pose/pose? {:translation [0.0 0.0 0.0]
                           :rotation [0.0 0.0 0.0 1.0]
                           :scale [1.0 1.0 1.0]})))
  (is (true? (pose/pose? (pose/make [0.0 0.0 0.0]
                                    [0.0 0.0 0.0 1.0]
                                    [1.0 1.0 1.0]))))
  (is (true? (pose/pose? (-> (pose/make [0.0 0.0 0.0]
                                        [0.0 0.0 0.0 1.0]
                                        [1.0 1.0 1.0])
                             (assoc :extra "Extra Data"))))))

(deftest make-test
  (is (pose/pose? (pose/make nil nil nil)))
  (is (pose/pose? (pose/make [1.0 0.0 0.0] [0.0 0.707 0.707 0.0] [2.0 1.0 1.0])))
  (is (pose/pose? (pose/make [1 0 0] [0 1 0 1] [2 1 1])))
  (is (pose/pose? (pose/make (vector-of :float 1.0 0.0 0.0) (vector-of :float 0.0 0.707 0.707 0.0) (vector-of :float 2.0 1.0 1.0))))
  (is (thrown? Throwable (pose/make [1.0 0.0] nil nil))) ; Too few components in translation.
  (is (thrown? Throwable (pose/make [1.0 0.0 0.0 0.0] nil nil))) ; Too many components in translation.
  (is (thrown? Throwable (pose/make nil [0.0 0.707 0.707] nil))) ; Too few components in rotation.
  (is (thrown? Throwable (pose/make nil [0.0 0.707 0.707 0.0 0.0] nil))) ; Too many components in rotation.
  (is (thrown? Throwable (pose/make nil nil [2.0 1.0]))) ; Too few components in scale.
  (is (thrown? Throwable (pose/make nil nil [2.0 1.0 1.0 1.0]))) ; Too many components in scale.
  (is (thrown? Throwable (pose/make (Vector3d. 1.0 0.0 0.0) (Quat4d. 0.0 0.707 0.707 0.0) (Vector3d. 2.0 1.0 1.0)))))

(deftest from-translation-test
  (is (thrown? Throwable (pose/from-translation [1.0 2.0])))
  (is (thrown? Throwable (pose/from-translation [1.0 2.0 3.0 4.0])))
  (is (identical? pose/default (pose/from-translation nil)))
  (is (identical? pose/default (pose/from-translation [0.0 0.0 0.0])))
  (is (identical? pose/default (pose/from-translation [0 0 0])))
  (let [pose (pose/from-translation [1.0 2.0 3.0])]
    (is (= [1.0 2.0 3.0] (:translation pose)))
    (is (identical? pose/default-rotation (:rotation pose)))
    (is (identical? pose/default-scale (:scale pose))))
  (let [pose (pose/from-translation [1 2 3])]
    (is (= [1.0 2.0 3.0] (:translation pose)))
    (is (identical? pose/default-rotation (:rotation pose)))
    (is (identical? pose/default-scale (:scale pose)))))

(deftest from-rotation-test
  (is (thrown? Throwable (pose/from-rotation [1.0 2.0 3.0])))
  (is (thrown? Throwable (pose/from-rotation [1.0 2.0 3.0 4.0 5.0])))
  (is (identical? pose/default (pose/from-rotation nil)))
  (is (identical? pose/default (pose/from-rotation [0.0 0.0 0.0 1.0])))
  (is (identical? pose/default (pose/from-rotation [0 0 0 1])))
  (let [pose (pose/from-rotation [1.0 2.0 3.0 4.0])]
    (is (identical? pose/default-translation (:translation pose)))
    (is (= [1.0 2.0 3.0 4.0] (:rotation pose)))
    (is (identical? pose/default-scale (:scale pose))))
  (let [pose (pose/from-rotation [1 2 3 4])]
    (is (identical? pose/default-translation (:translation pose)))
    (is (= [1.0 2.0 3.0 4.0] (:rotation pose)))
    (is (identical? pose/default-scale (:scale pose)))))

(deftest from-euler-rotation-test
  (is (thrown? Throwable (pose/from-euler-rotation [1.0 2.0])))
  (is (thrown? Throwable (pose/from-euler-rotation [1.0 2.0 3.0 4.0])))
  (is (identical? pose/default (pose/from-euler-rotation nil)))
  (is (identical? pose/default (pose/from-euler-rotation [0.0 0.0 0.0])))
  (is (identical? pose/default (pose/from-euler-rotation [0 0 0])))
  (let [pose (pose/from-euler-rotation [0.0 90.0 0.0])]
    (is (identical? pose/default-translation (:translation pose)))
    (is (= [0.0 0.707 0.0 0.707] (round-numbers (:rotation pose))))
    (is (identical? pose/default-scale (:scale pose)))))

(deftest from-scale-test
  (is (thrown? Throwable (pose/from-scale [1.0 2.0])))
  (is (thrown? Throwable (pose/from-scale [1.0 2.0 3.0 4.0])))
  (is (identical? pose/default (pose/from-scale nil)))
  (is (identical? pose/default (pose/from-scale [1.0 1.0 1.0])))
  (is (identical? pose/default (pose/from-scale [1 1 1])))
  (let [pose (pose/from-scale [1.0 2.0 3.0])]
    (is (identical? pose/default-translation (:translation pose)))
    (is (identical? pose/default-rotation (:rotation pose)))
    (is (= [1.0 2.0 3.0] (:scale pose))))
  (let [pose (pose/from-scale [1 2 3])]
    (is (identical? pose/default-translation (:translation pose)))
    (is (identical? pose/default-rotation (:rotation pose)))
    (is (= [1.0 2.0 3.0] (:scale pose)))))

(deftest translation-pose-test
  (is (thrown? Throwable (pose/translation-pose nil nil nil)))
  (is (identical? pose/default (pose/translation-pose 0.0 0.0 0.0)))
  (let [pose (pose/translation-pose 1.0 2.0 3.0)]
    (is (= [1.0 2.0 3.0] (:translation pose)))
    (is (identical? pose/default-rotation (:rotation pose)))
    (is (identical? pose/default-scale (:scale pose))))
  (let [pose (pose/translation-pose 1 2 3)]
    (is (= [1.0 2.0 3.0] (:translation pose)))
    (is (identical? pose/default-rotation (:rotation pose)))
    (is (identical? pose/default-scale (:scale pose)))))

(deftest rotation-pose-test
  (is (thrown? Throwable (pose/rotation-pose nil nil nil nil)))
  (is (identical? pose/default (pose/rotation-pose 0.0 0.0 0.0 1.0)))
  (let [pose (pose/rotation-pose 1.0 2.0 3.0 4.0)]
    (is (identical? pose/default-translation (:translation pose)))
    (is (= [1.0 2.0 3.0 4.0] (:rotation pose)))
    (is (identical? pose/default-scale (:scale pose))))
  (let [pose (pose/rotation-pose 1 2 3 4)]
    (is (identical? pose/default-translation (:translation pose)))
    (is (= [1.0 2.0 3.0 4.0] (:rotation pose)))
    (is (identical? pose/default-scale (:scale pose)))))

(deftest euler-rotation-pose-test
  (is (thrown? Throwable (pose/euler-rotation-pose nil nil nil)))
  (is (identical? pose/default (pose/euler-rotation-pose 0.0 0.0 0.0)))
  (let [pose (pose/euler-rotation-pose 90.0 90.0 90.0)]
    (is (identical? pose/default-translation (:translation pose)))
    (is (= [0.707 0.707 0.0 0.0] (round-numbers (:rotation pose))))
    (is (identical? pose/default-scale (:scale pose))))
  (let [pose (pose/euler-rotation-pose 90 0 90)]
    (is (identical? pose/default-translation (:translation pose)))
    (is (= [0.5 0.5 0.5 0.5] (round-numbers (:rotation pose))))
    (is (identical? pose/default-scale (:scale pose)))))

(deftest scale-pose-test
  (is (thrown? Throwable (pose/scale-pose nil nil nil)))
  (is (identical? pose/default (pose/scale-pose 1.0 1.0 1.0)))
  (let [pose (pose/scale-pose 1.0 2.0 3.0)]
    (is (identical? pose/default-translation (:translation pose)))
    (is (identical? pose/default-rotation (:rotation pose)))
    (is (= [1.0 2.0 3.0] (:scale pose))))
  (let [pose (pose/scale-pose 1 2 3)]
    (is (identical? pose/default-translation (:translation pose)))
    (is (identical? pose/default-rotation (:rotation pose)))
    (is (= [1.0 2.0 3.0] (:scale pose)))))

(deftest pre-multiply-test
  (is (thrown? Throwable (pose/pre-multiply pose/default nil)))
  (is (thrown? Throwable (pose/pre-multiply nil pose/default)))
  (is (identical? pose/default (pose/pre-multiply pose/default pose/default)))
  (let [original (pose/make [1.0 0.0 0.0]
                            [0.0 0.707 0.707 1.0]
                            [2.0 1.0 1.0])]
    (is (identical? original (pose/pre-multiply original (pose/make [0.0 0.0 0.0]
                                                                    [0.0 0.0 0.0 1.0]
                                                                    [1.0 1.0 1.0])))))
  (is (= (pose/translation-pose 3.0 0.0 0.0)
         (pose/pre-multiply (pose/translation-pose 1.0 0.0 0.0)
                            (pose/translation-pose 2.0 0.0 0.0))))
  (is (= (pose/make [6.0 20.0 56.0]
                    nil
                    [2.0 4.0 8.0])
         (pose/pre-multiply (pose/from-translation [3.0 5.0 7.0])
                            (pose/from-scale [2.0 4.0 8.0]))))
  (is (= (pose/euler-rotation-pose 40.0 0.0 0.0)
         (pose/pre-multiply (pose/euler-rotation-pose 10.0 0.0 0.0)
                            (pose/euler-rotation-pose 30.0 0.0 0.0))))
  (is (= (pose/euler-rotation-pose 0.0 -40.0 0.0)
         (pose/pre-multiply (pose/euler-rotation-pose 0.0 -10.0 0.0)
                            (pose/euler-rotation-pose 0.0 -30.0 0.0))))
  (is (= (pose/euler-rotation-pose 45.0 45.0 45.0)
         (-> (pose/euler-rotation-pose 0.0 45.0 0.0)
             (pose/pre-multiply (pose/euler-rotation-pose 0.0 0.0 45.0))
             (pose/pre-multiply (pose/euler-rotation-pose 45.0 0.0 0.0)))))
  (let [pose (pose/pre-multiply pose/default
                                (pose/euler-rotation-pose -90.0 180.0 0.0))]
    (is (= [0.0 0.0 0.0] (:translation pose)))
    (is (= [0.0 0.707 0.707 0.0] (round-numbers (:rotation pose))))
    (is (identical? pose/default-scale (:scale pose))))
  (let [pose (pose/pre-multiply (pose/euler-rotation-pose 0.0 0.0 90.0)
                                (pose/euler-rotation-pose 0.0 0.0 90.0))]
    (is (= [0.0 0.0 0.0] (:translation pose)))
    (is (= [0.0 0.0 1.0 0.0] (round-numbers (:rotation pose))))
    (is (identical? pose/default-scale (:scale pose))))
  (let [pose (pose/pre-multiply (pose/translation-pose 10.0 20.0 30.0)
                                (pose/make [3.0 5.0 7.0]
                                           (pose/euler-rotation 90.0 0.0 0.0)
                                           [2.0 4.0 8.0]))]
    (is (= [23.0 -235.0 87.0] (round-numbers (:translation pose))))
    (is (= (pose/euler-rotation 90.0 0.0 0.0) (:rotation pose)))
    (is (= [2.0 4.0 8.0] (:scale pose)))))

(deftest to-map-test
  (is (thrown? Throwable (pose/to-mat4 {:translation "Not a pose"})))
  (is (= {:t [0.0 0.0 0.0]
          :r [0.0 0.0 0.0 1.0]
          :s [1.0 1.0 1.0]}
         (pose/to-map pose/default :t :r :s)))
  (is (= {:position [1.0 0.0 0.0]
          :rotation [0.0 0.707 0.707 0.0]
          :scale3 [2.0 1.0 1.0]}
         (pose/to-map (pose/make [1.0 0.0 0.0]
                                 [0.0 0.707 0.707 0.0]
                                 [2.0 1.0 1.0])
                      :position
                      :rotation
                      :scale3))))

(deftest to-mat4-test
  (is (thrown? Throwable (pose/to-mat4 {:translation "Not a pose"})))
  (is (= (doto (Matrix4d.) (.setIdentity))
         (pose/to-mat4 pose/default)))
  (is (= (math/clj->mat4 [1.0 2.0 3.0]
                         [0.0 0.707 0.707 0.0]
                         [2.0 1.0 1.0])
         (pose/to-mat4 (pose/make [1.0 2.0 3.0]
                                  [0.0 0.707 0.707 0.0]
                                  [2.0 1.0 1.0])))))
