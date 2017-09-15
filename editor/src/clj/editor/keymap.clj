(ns editor.keymap
  (:require
   [editor.ui :as ui]
   [editor.util :as util])
  (:import
   (javafx.scene Scene)
   (javafx.scene.input KeyCombination KeyCombination$ModifierValue KeyCodeCombination KeyCharacterCombination KeyEvent)
   (com.defold.editor Platform)))

(set! *warn-on-reflection* true)

(def host-platform-shortcut-key
  (case (.. Platform getHostPlatform getOs)
    "darwin" :meta-down?
    "win32"  :control-down?
    "linux"  :control-down?))

(def default-key-bindings
  [["A"                     :add]
   ["Alt+Backspace"         :delete-prev-word]
   ["Alt+DOWN"              :move-down]
   ["Alt+Delete"            :delete-next-word]
   ["Alt+Left"              :prev-word]
   ["Alt+Right"             :next-word]
   ["Alt+Shortcut+E"        :replace-next]
   ["Alt+UP"                :move-up]
   ["Backspace"             :delete-backward]
   ["Ctrl+A"                :beginning-of-line]
   ["Ctrl+E"                :end-of-line]
   ["Ctrl+K"                :cut-to-end-of-line]
   ["Ctrl+Left"             :prev-word]
   ["Ctrl+Right"            :next-word]
   ["Ctrl+Space"            :proposals]
   ["Delete"                :delete]
   ["Down"                  :down]
   ["E"                     :rotate-tool]
   ["End"                   :end-of-line]
   ["Enter"                 :enter]
   ["F"                     :frame-selection]
   ["F1"                    :documentation]
   ["F5"                    :reload-stylesheet]
   ["Home"                  :beginning-of-line-text]
   ["Left"                  :left]
   ["PERIOD"                :realign-camera]
   ["Page-Down"             :page-down]
   ["Page-Up"               :page-up]
   ["R"                     :scale-tool]
   ["Right"                 :right]
   ["Shift+A"               :add-secondary]
   ["Shift+Alt+Left"        :select-prev-word]
   ["Shift+Alt+Right"       :select-next-word]
   ["Shift+Ctrl+A"          :select-beginning-of-line]
   ["Shift+Ctrl+E"          :select-end-of-line]
   ["Shift+Ctrl+Left"       :select-prev-word]
   ["Shift+Ctrl+Right"      :select-next-word]
   ["Shift+Down"            :select-down]
   ["Shift+E"               :erase-tool]
   ["Shift+End"             :select-end-of-line]
   ["Shift+Home"            :select-beginning-of-line-text]
   ["Shift+Left"            :select-left]
   ["Shift+Page-Down"       :select-page-down]
   ["Shift+Page-Up"         :select-page-up]
   ["Shift+Right"           :select-right]
   ["Shift+Shortcut+Alt+X"  :profile-show]
   ["Shift+Shortcut+Delete" :delete-to-end-of-line]
   ["Shift+Shortcut+Down"   :select-end-of-file]
   ["Shift+Shortcut+F"      :search-in-files]
   ["Shift+Shortcut+G"      :find-prev]
   ["Shift+Shortcut+Left"   :select-beginning-of-line-text]
   ["Shift+Shortcut+R"      :open-asset]
   ["Shift+Shortcut+Right"  :select-end-of-line]
   ["Shift+Shortcut+Up"     :select-beginning-of-file]
   ["Shift+Shortcut+W"      :close-all]
   ["Shift+Tab"             :backwards-tab-trigger]
   ["Shift+Up"              :select-up]
   ["Shortcut+A"            :select-all]
   ["Shortcut+Alt+X"        :profile]
   ["Shortcut+B"            :build]
   ["Shortcut+C"            :copy]
   ["Shortcut+COMMA"        :preferences]
   ["Shortcut+D"            :delete-line]
   ["Shortcut+Delete"       :delete-to-beginning-of-line]
   ["Shortcut+Down"         :end-of-file]
   ["Shortcut+E"            :replace-text]
   ["Shortcut+F"            :find-text]
   ["Shortcut+G"            :find-next]
   ["Shortcut+I"            :indent]
   ["Shortcut+L"            :goto-line]
   ["Shortcut+Left"         :beginning-of-line-text]
   ["Shortcut+N"            :new-file]
   ["Shortcut+O"            :open]
   ["Shortcut+Q"            :quit]
   ["Shortcut+R"            :hot-reload]
   ["Shortcut+Right"        :end-of-line]
   ["Shortcut+S"            :save-all]
   ["Shortcut+Shift+B"      :rebuild]
   ["Shortcut+Shift+Z"      :redo]
   ["Shortcut+Slash"        :toggle-comment]
   ["Shortcut+T"            :scene-stop]
   ["Shortcut+U"            :synchronize]
   ["Shortcut+Up"           :beginning-of-file]
   ["Shortcut+V"            :paste]
   ["Shortcut+W"            :close]
   ["Shortcut+X"            :cut]
   ["Shortcut+Z"            :undo]
   ["Space"                 :toggle]
   ["Tab"                   :tab]
   ["Up"                    :up]
   ["W"                     :move-tool]])

(defprotocol KeyComboData
  (key-combo->map* [this] "returns a data representation of a KeyCombination."))

(extend-protocol KeyComboData
  javafx.scene.input.KeyCodeCombination
  (key-combo->map* [key-combo]
    {:key            (.. key-combo getCode getName)
     :alt-down?      (= KeyCombination$ModifierValue/DOWN (.getAlt key-combo))
     :control-down?  (= KeyCombination$ModifierValue/DOWN (.getControl key-combo))
     :meta-down?     (= KeyCombination$ModifierValue/DOWN (.getMeta key-combo))
     :shift-down?    (= KeyCombination$ModifierValue/DOWN (.getShift key-combo))
     :shortcut-down? (= KeyCombination$ModifierValue/DOWN (.getShortcut key-combo))})

  javafx.scene.input.KeyCharacterCombination
  (key-combo->map* [key-combo]
    {:key            (.getCharacter key-combo)
     :alt-down?      (= KeyCombination$ModifierValue/DOWN (.getAlt key-combo))
     :control-down?  (= KeyCombination$ModifierValue/DOWN (.getControl key-combo))
     :meta-down?     (= KeyCombination$ModifierValue/DOWN (.getMeta key-combo))
     :shift-down?    (= KeyCombination$ModifierValue/DOWN (.getShift key-combo))
     :shortcut-down? (= KeyCombination$ModifierValue/DOWN (.getShortcut key-combo))})  )

(defn- key-combo->map [s]
  (let [key-combo (KeyCombination/keyCombination s)]
    (key-combo->map* key-combo)))

(defn- key-binding-data
  [key-bindings platform-shortcut-key]
  (map (fn [[shortcut command]]
         (let [key-combo (KeyCombination/keyCombination shortcut)
               key-combo-data (key-combo->map shortcut)
               canonical-key-combo-data (-> key-combo-data
                                            (update platform-shortcut-key #(or % (:shortcut-down? key-combo-data)))
                                            (dissoc :shortcut-down?))]
           {:shortcut shortcut
            :command command
            :key-combo key-combo
            :key-combo-data key-combo-data
            :canonical-key-combo-data canonical-key-combo-data}))
       key-bindings))

(defn- remove-shortcut-key-overlaps
  "Given a sequence of key-binding data, find all bindings where the modifiers
  used are in conflict with the `Shortcut` key, and remove them. For example, on
  platforms where the shortcut key is `Ctrl`, the bindings `Ctrl+A` and
  `Shortcut+A` are in conflict as they would resolve to the same binding. In
  such cases, we prefer the binding with the `Shortcut` key."
  [key-bindings-data]
  (->> (group-by :canonical-key-combo-data key-bindings-data)
       (vals)
       (mapcat (fn [overlapping-key-bindings]
                 (or (seq (filter (comp :shortcut-down? :key-combo-data) overlapping-key-bindings))
                     overlapping-key-bindings)))))

(defn- key-binding-data->keymap
  [key-bindings-data valid-command?]
  (reduce (fn [ret {:keys [canonical-key-combo-data shortcut command key-combo]}]
            (cond
              (not (valid-command? command))
              (update ret :errors conj {:type :unknown-command
                                        :command command
                                        :shortcut shortcut})

              (some? (get-in ret [:keymap canonical-key-combo-data]))
              (update ret :errors into [{:type :duplicate-shortcut
                                         :command command
                                         :shortcut shortcut}
                                        {:type :duplicate-shortcut
                                         :command (get-in ret [:keymap canonical-key-combo-data])
                                         :shortcut shortcut}])

              :else
              (update ret :keymap assoc canonical-key-combo-data {:command command
                                                                  :shortcut shortcut
                                                                  :key-combo key-combo})))
          {:keymap {}
           :errors #{}}
          key-bindings-data))

(defn- make-keymap*
  [key-bindings platform-shortcut-key valid-command?]
  (-> (key-binding-data key-bindings platform-shortcut-key)
      (remove-shortcut-key-overlaps)
      (key-binding-data->keymap valid-command?)))

(defn make-keymap
  ([key-bindings]
   (make-keymap key-bindings nil))
  ([key-bindings {:keys [valid-command?
                         platform-shortcut-key
                         throw-on-error?]
                  :or   {valid-command?        (constantly true)
                         platform-shortcut-key host-platform-shortcut-key
                         throw-on-error?       false}
                  :as   opts}]
   (let [{:keys [errors keymap]} (make-keymap* key-bindings platform-shortcut-key valid-command?)]
     (if (and (seq errors) throw-on-error?)
       (throw (ex-info "Keymap has errors"
                       {:errors       errors
                        :key-bindings key-bindings}))
       keymap))))

(defn command->shortcut
  [keymap]
  (reduce-kv (fn [ret k v]
               (assoc ret (:command v) (:shortcut v)))
             {}
             keymap))

(defn- execute-command
  [command]
  ;; It is imperative that the handler is invoked using run-later as
  ;; this avoids a JVM crash on some macOS versions. Prior to macOS
  ;; Sierra, the order in which native menu events are delivered
  ;; triggered a segfault in the native menu implementation when the
  ;; stage is changed during the event dispatch. This happens for
  ;; example when we have a shortcut triggering the opening of a
  ;; dialog.
  (ui/run-later (ui/invoke-handler command)))

(defn install-key-bindings!
  [^Scene scene keymap]
  (let [accelerators (.getAccelerators scene)]
    (run! (fn [{:keys [key-combo command]}]
            (.put accelerators key-combo #(execute-command command)))
          (vals keymap))))
