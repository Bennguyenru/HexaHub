(ns editor.keymap-test
  (:require [clojure.test :refer [are deftest is testing]]
            [editor.keymap :as keymap]))

(def shortcut-keys {:control-down? "Ctrl"
                    :meta-down?    "Meta"})

(deftest default-bindings-are-valid
  (doseq [[_ key-bindings] keymap/platform->default-key-bindings]
    (is (keymap/make-keymap key-bindings {:valid-command? (constantly true)
                                          :throw-on-error? true}))))

(defn- make-keymap-errors [key-bindings]
  (try
    (keymap/make-keymap key-bindings {:throw-on-error? true})
    (catch Exception ex (:errors (ex-data ex)))))

(deftest disallow-typable-shortcuts
  ;; We don't test keymap/allowed-typable-shortcuts
  (doseq [platform (keys keymap/platform->default-key-bindings)]
    (testing platform
      (let [errors (make-keymap-errors [["S" :s]
                                        ["Alt+T" :t]
                                        ["Ctrl+Alt+U" :u]
                                        ["Ctrl+Alt+V" :v]
                                        ["Shift+Alt+X" :x]])]
        (is (= errors #{{:type :typable-shortcut
                         :command :s
                         :shortcut "S"}
                        {:type :typable-shortcut
                         :command :t
                         :shortcut "Alt+T"}
                        {:type :typable-shortcut
                         :command :u
                         :shortcut "Ctrl+Alt+U"}
                        {:type :typable-shortcut
                         :command :v
                         :shortcut "Ctrl+Alt+V"}
                        {:type :typable-shortcut
                         :command :x
                         :shortcut "Shift+Alt+X"}}))))))

(deftest keymap-does-not-allow-shortcut-key
  (make-keymap-errors [["Shortcut+A" :a]]))

(deftest make-keymap-test
  #_(testing "canonicalizes shortcut keys correctly"
      (doseq [[shortcut-key _] shortcut-keys]
        (are [shortcut command expected-key expected-modifiers]
            (= [{:key           expected-key
                 :alt-down?     (boolean (expected-modifiers :alt-down?))
                 :control-down? (boolean (expected-modifiers :control-down?))
                 :meta-down?    (boolean (expected-modifiers :meta-down?))
                 :shift-down?   (boolean (expected-modifiers :shift-down?))}
                [{:command command
                  :shortcut shortcut
                  :key-combo (KeyCombination/keyCombination shortcut)}]]
               (first (keymap/make-keymap [[shortcut command]]
                                          {:platform-shortcut-key shortcut-key
                                           :throw-on-error? true
                                           :valid-command? (constantly true)})))
          ;; Here we exploit that 'A' is a default allowed typable shortcut
          "A"               :a "A" #{}
          "Shortcut+A"      :a "A" #{shortcut-key}
          "Ctrl+A"          :a "A" #{:control-down?}
          "Meta+A"          :a "A" #{:meta-down?}
          "Shift+A"         :a "A" #{:shift-down?}
          "Ctrl+Shortcut+A" :a "A" (hash-set :control-down? shortcut-key)
          "Meta+Shortcut+A" :a "A" (hash-set :meta-down? shortcut-key))))

  #_(testing "prefers shortcut key to the corresponding platform modifier key"
      (doseq [[shortcut-key shortcut-name] shortcut-keys]
        (let [km (keymap/make-keymap [["Shortcut+A" :shortcut]
                                      [(str shortcut-name "+A") :platform-modifier]]
                                     {:platform-shortcut-key shortcut-key})]
          (is (= 1 (count km)))
          (is (= :shortcut (-> km first val first :command)))))))
