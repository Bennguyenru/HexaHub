;; Copyright 2020-2022 The Defold Foundation
;; Copyright 2014-2020 King
;; Copyright 2009-2014 Ragnar Svensson, Christian Murray
;; Licensed under the Defold License version 1.0 (the "License"); you may not use
;; this file except in compliance with the License.
;; 
;; You may obtain a copy of the License, together with FAQs at
;; https://www.defold.com/license
;; 
;; Unless required by applicable law or agreed to in writing, software distributed
;; under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
;; CONDITIONS OF ANY KIND, either express or implied. See the License for the
;; specific language governing permissions and limitations under the License.

(ns editor.dialogs
  (:require [cljfx.api :as fx]
            [cljfx.fx.group :as fx.group]
            [cljfx.fx.h-box :as fx.h-box]
            [cljfx.fx.image-view :as fx.image-view]
            [cljfx.fx.label :as fx.label]
            [cljfx.fx.progress-bar :as fx.progress-bar]
            [cljfx.fx.region :as fx.region]
            [cljfx.fx.scene :as fx.scene]
            [cljfx.fx.v-box :as fx.v-box]
            [clojure.java.io :as io]
            [clojure.string :as string]
            [dynamo.graph :as g]
            [editor.core :as core]
            [editor.error-reporting :as error-reporting]
            [editor.field-expression :as field-expression]
            [editor.fuzzy-text :as fuzzy-text]
            [editor.fxui :as fxui]
            [editor.github :as github]
            [editor.handler :as handler]
            [editor.progress :as progress]
            [editor.resource :as resource]
            [editor.ui :as ui]
            [editor.util :as util]
            [service.log :as log])
  (:import [clojure.lang Named]
           [java.io File]
           [java.nio.file Path Paths]
           [java.util Collection List]
           [javafx.application Platform]
           [javafx.event Event]
           [javafx.geometry Pos]
           [javafx.scene Node Parent Scene]
           [javafx.scene.control Button ListView TextField]
           [javafx.scene.input KeyCode]
           [javafx.scene.layout HBox VBox]
           [javafx.scene.text Text TextFlow]
           [javafx.stage DirectoryChooser FileChooser FileChooser$ExtensionFilter Stage Window]
           [org.apache.commons.io FilenameUtils]))

(set! *warn-on-reflection* true)

(defn- dialog-stage
  "Dialog `:stage` that manages scene graph itself and provides layout common
  for many dialogs.

  Scene graph is configured using these keys:
  - `:size` (optional, default `:default`) - a dialog width, either `:small`,
    `:default` or `:large`
  - `:header` (required, fx description) - header of a dialog, padded
  - `:content` (optional, nil allowed) - content of a dialog, not padded, you
    can use \"dialog-content-padding\" style class to set desired padding (or
    \"text-area-with-dialog-content-padding\" for text areas)
  - `:footer` (required, fx description) - footer of a dialog, padded"
  [{:keys [size header content footer]
    :or {size :default}
    :as props}]
  (-> props
      (dissoc :size :header :content :footer)
      (assoc :fx/type fxui/dialog-stage
             :scene {:fx/type fx.scene/lifecycle
                     :stylesheets ["dialogs.css"]
                     :root {:fx/type fx.v-box/lifecycle
                            :style-class ["dialog-body" (case size
                                                          :small "dialog-body-small"
                                                          :default "dialog-body-default"
                                                          :large "dialog-body-large")]
                            :children (if (some? content)
                                        [{:fx/type fx.v-box/lifecycle
                                          :style-class "dialog-with-content-header"
                                          :children [header]}
                                         {:fx/type fx.v-box/lifecycle
                                          :style-class "dialog-content"
                                          :children [content]}
                                         {:fx/type fx.v-box/lifecycle
                                          :style-class "dialog-with-content-footer"
                                          :children [footer]}]
                                        [{:fx/type fx.v-box/lifecycle
                                          :style-class "dialog-without-content-header"
                                          :children [header]}
                                         {:fx/type fx.region/lifecycle :style-class "dialog-no-content"}
                                         {:fx/type fx.v-box/lifecycle
                                          :style-class "dialog-without-content-footer"
                                          :children [footer]}])}})))

(defn- confirmation-dialog-header->fx-desc [header]
  (if (string? header)
    {:fx/type fxui/label
     :variant :header
     :text header}
    header))

(defn- dialog-buttons [props]
  (-> props
      (assoc :fx/type fx.h-box/lifecycle)
      (fxui/provide-defaults :alignment :center-right)
      (fxui/add-style-classes "spacing-smaller")))

(defn- confirmation-dialog [{:keys [buttons icon]
                             :or {icon ::no-icon}
                             :as props}]
  (let [button-descs (mapv (fn [button-props]
                             (let [button-desc (-> button-props
                                                   (assoc :fx/type fxui/button
                                                          :on-action {:result (:result button-props)})
                                                   (dissoc :result))]
                               (if (:default-button button-props)
                                 {:fx/type fxui/ext-focused-by-default
                                  :desc (fxui/provide-defaults button-desc :variant :primary)}
                                 button-desc)))
                           buttons)]
    (-> props
        (assoc :fx/type dialog-stage
               :showing (fxui/dialog-showing? props)
               :footer {:fx/type dialog-buttons
                        :children button-descs}
               :on-close-request {:result (:result (some #(when (:cancel-button %) %) buttons))})
        (dissoc :buttons :icon ::fxui/result)
        (update :header (fn [header]
                          (let [header-desc (confirmation-dialog-header->fx-desc header)]
                            (if (= icon ::no-icon)
                              header-desc
                              {:fx/type fx.h-box/lifecycle
                               :style-class "spacing-smaller"
                               :alignment :center-left
                               :children [(if (keyword? icon)
                                            {:fx/type fxui/icon :type icon}
                                            icon)
                                          header-desc]})))))))

(defn make-confirmation-dialog
  "Shows a dialog and blocks current thread until users selects one option.

  `props` is a prop map for `editor.dialogs/dialog-stage`, but instead of
  `:footer` you use `:buttons`

  Additional keys:
  - `:buttons` (optional) - a coll of button descriptions. Button
  description is a prop map for `editor.fxui/button` with few caveats:
    * you don't have to specify `:fx/type`
    * it should have `:result` key, it's value will be returned from this
      function (default `nil`)
    * if you specify `:default-button`, it will be styled as primary and receive
      focus by default
    * if you specify `:cancel-button`, closing window using `x` button will
      return `:result` from that button (and `nil` otherwise)
  - `:icon` (optional) - a keyword valid as `:type` for `editor.fxui/icon`, if
    present, will add an icon to the left of a header"
  [props]
  (fxui/show-dialog-and-await-result!
    :event-handler (fn [state event]
                     (assoc state ::fxui/result (:result event)))
    :description (assoc props :fx/type confirmation-dialog)))

(defn- info-dialog-text-area [props]
  (-> props
      (assoc :fx/type fxui/text-area)
      (fxui/add-style-classes "text-area-with-dialog-content-padding")
      (fxui/provide-defaults
        :pref-row-count (max 3 (count (string/split (:text props "") #"\n" 10)))
        :variant :borderless
        :editable false)))

(defn make-info-dialog
  "Shows a dialog with selectable text content and blocks current thread until
  user closes it.

  `props` is a map to configure the dialog, supports all options from
  `editor.dialogs/make-confirmation-dialog` with these changes:
  - `:buttons` have a close button by default
  - `:content` can be:
    * fx description (a map with `:fx/type` key) - used as is
    * prop map (map without `:fx/type` key) for `editor.fxui/text-area` -
      readonly by default to allow user select and copy text, `:text` prop is
      required
    * string - text for readonly text area"
  [props]
  (make-confirmation-dialog
    (-> props
        (update :content (fn [content]
                           (cond
                             (:fx/type content)
                             content

                             (map? content)
                             (assoc content :fx/type info-dialog-text-area)

                             (string? content)
                             {:fx/type info-dialog-text-area :text content})))
        (fxui/provide-defaults :buttons [{:text "Close"
                                          :cancel-button true
                                          :default-button true}]))))

(defn- digit-string? [^String x]
  (and (string? x)
       (pos? (.length x))
       (every? #(Character/isDigit ^char %) x)))

(defn resolution-dialog [{:keys [width-text height-text] :as props}]
  (let [width-valid (digit-string? width-text)
        height-valid (digit-string? height-text)]
    {:fx/type dialog-stage
     :showing (fxui/dialog-showing? props)
     :on-close-request {:event-type :cancel}
     :title "Set Custom Resolution"
     :size :small
     :header {:fx/type fx.v-box/lifecycle
              :children [{:fx/type fxui/label
                          :variant :header
                          :text "Set custom game resolution"}
                         {:fx/type fxui/label
                          :text "Game window will be resized to this size"}]}
     :content {:fx/type fxui/two-col-input-grid-pane
               :style-class "dialog-content-padding"
               :children [{:fx/type fxui/label
                           :text "Width"}
                          {:fx/type fxui/text-field
                           :variant (if width-valid :default :error)
                           :text width-text
                           :on-text-changed {:event-type :set-width}}
                          {:fx/type fxui/label
                           :text "Height"}
                          {:fx/type fxui/text-field
                           :variant (if height-valid :default :error)
                           :text height-text
                           :on-text-changed {:event-type :set-height}}]}
     :footer {:fx/type dialog-buttons
              :children [{:fx/type fxui/button
                          :cancel-button true
                          :on-action {:event-type :cancel}
                          :text "Cancel"}
                         {:fx/type fxui/button
                          :variant :primary
                          :disable (or (not width-valid) (not height-valid))
                          :default-button true
                          :text "Set Resolution"
                          :on-action {:event-type :confirm}}]}}))

(defn make-resolution-dialog []
  (fxui/show-dialog-and-await-result!
    :initial-state {:width-text "320"
                    :height-text "420"}
    :event-handler (fn [state {:keys [fx/event event-type]}]
                     (case event-type
                       :set-width (assoc state :width-text event)
                       :set-height (assoc state :height-text event)
                       :cancel (assoc state ::fxui/result nil)
                       :confirm (assoc state ::fxui/result {:width (field-expression/to-int (:width-text state))
                                                            :height (field-expression/to-int (:height-text state))})))
    :description {:fx/type resolution-dialog}))

(defn make-update-failed-dialog [^Stage owner]
  (let [result (make-confirmation-dialog
                 {:title "Update Failed"
                  :owner owner
                  :icon :icon/triangle-error
                  :header {:fx/type fx.v-box/lifecycle
                           :children [{:fx/type fxui/label
                                       :variant :header
                                       :text "An error occurred during update installation"}
                                      {:fx/type fxui/label
                                       :text "You probably should perform a fresh install"}]}
                  :buttons [{:text "Quit"
                             :cancel-button true
                             :result false}
                            {:text "Open defold.com"
                             :default-button true
                             :result true}]})]
    (when result
      (ui/open-url "https://www.defold.com/"))))

(defn make-download-update-or-restart-dialog [^Stage owner]
  (make-confirmation-dialog
    {:title "Install Update?"
     :icon :icon/circle-info
     :size :large
     :owner owner
     :header {:fx/type fx.v-box/lifecycle
              :children [{:fx/type fxui/label
                          :variant :header
                          :text "Update is ready, but there is even newer version available"}
                         {:fx/type fxui/label
                          :text "You can install downloaded update or download newer one"}]}
     :buttons [{:text "Not Now"
                :cancel-button true
                :result :cancel}
               {:text "Install and Restart"
                :result :restart}
               {:text "Download Newer Version"
                :result :download}]}))

(defn make-platform-no-longer-supported-dialog [^Stage owner]
  (make-confirmation-dialog
    {:title "Platform not supported"
     :icon :icon/circle-sad
     :owner owner
     :header {:fx/type fx.v-box/lifecycle
              :children [{:fx/type fxui/label
                          :variant :header
                          :text "Updates are no longer provided for this platform"}
                         {:fx/type fxui/label
                          :text "Supported platforms are 64-bit Linux, macOS and Windows"}]}
     :buttons [{:text "Close"
                :cancel-button true
                :default-button true}]}))

(defn make-download-update-dialog [^Stage owner]
  (make-confirmation-dialog
    {:title "Download Update?"
     :header "A newer version of Defold is available!"
     :icon :icon/circle-happy
     :owner owner
     :buttons [{:text "Not Now"
                :cancel-button true
                :result false}
               {:text "Download Update"
                :default-button true
                :result true}]}))

(defn- messages
  [ex-map]
  (->> (tree-seq :via :via ex-map)
       (drop 1)
       (map (fn [{:keys [message type]}]
              (let [type-name (cond
                                (instance? Class type) (.getName ^Class type)
                                (instance? Named type) (name type)
                                :else (str type))]
                (format "%s: %s" type-name (or message "Unknown")))))
       (string/join "\n")))

(defn- unexpected-error-dialog [{:keys [ex-map] :as props}]
  {:fx/type dialog-stage
   :showing (fxui/dialog-showing? props)
   :on-close-request {:result false}
   :title "Error"
   :header {:fx/type fx.h-box/lifecycle
            :style-class "spacing-smaller"
            :alignment :center-left
            :children [{:fx/type fxui/icon
                        :type :icon/triangle-sad}
                       {:fx/type fxui/label
                        :variant :header
                        :text "An error occurred"}]}
   :content {:fx/type info-dialog-text-area
             :text (messages ex-map)}
   :footer {:fx/type fx.v-box/lifecycle
            :style-class "spacing-smaller"
            :children [{:fx/type fxui/label
                        :text "You can help us fix this problem by reporting it and providing more information about what you were doing when it happened."}
                       {:fx/type dialog-buttons
                        :children [{:fx/type fxui/button
                                    :cancel-button true
                                    :on-action {:result false}
                                    :text "Dismiss"}
                                   {:fx/type fxui/ext-focused-by-default
                                    :desc {:fx/type fxui/button
                                           :variant :primary
                                           :default-button true
                                           :on-action {:result true}
                                           :text "Report"}}]}]}})

(defn make-unexpected-error-dialog [ex-map sentry-id-promise]
  (when (fxui/show-dialog-and-await-result!
          :event-handler (fn [state event]
                           (assoc state ::fxui/result (:result event)))
          :description {:fx/type unexpected-error-dialog
                        :ex-map ex-map})
    (let [sentry-id (deref sentry-id-promise 100 nil)
          fields (if sentry-id
                   {"Error" (format "<a href='https://sentry.io/organizations/defold/issues/?query=id%%3A\"%s\"'>%s</a>"
                                    sentry-id sentry-id)}
                   {})]
      (ui/open-url (github/new-issue-link fields)))))

(defn- load-project-dialog [{:keys [progress] :as props}]
  {:fx/type dialog-stage
   :showing (fxui/dialog-showing? props)
   :on-close-request (fn [_] (Platform/exit))
   :header {:fx/type fx.h-box/lifecycle
            :style-class "spacing-default"
            :alignment :center-left
            :children [{:fx/type fx.group/lifecycle
                        :children [{:fx/type fx.image-view/lifecycle
                                    :scale-x 0.25
                                    :scale-y 0.25
                                    :image "logo.png"}]}
                       {:fx/type fxui/label
                        :variant :header
                        :text "Loading project"}]}
   :content {:fx/type fx.v-box/lifecycle
             :style-class ["dialog-content-padding" "spacing-smaller"]
             :children [{:fx/type fxui/label
                         :wrap-text false
                         :text (:message progress)}
                        {:fx/type fx.progress-bar/lifecycle
                         :max-width Double/MAX_VALUE
                         :progress (progress/fraction progress)}]}
   :footer {:fx/type dialog-buttons
            :children [{:fx/type fxui/button
                        :disable true
                        :text "Cancel"}]}})

(defn make-load-project-dialog [worker-fn]
  (ui/run-now
    (let [state-atom (atom {:progress (progress/make "Loading" 1 0)})
          renderer (fx/create-renderer
                     :error-handler error-reporting/report-exception!
                     :middleware (fx/wrap-map-desc assoc :fx/type load-project-dialog))
          render-progress! #(swap! state-atom assoc :progress %)
          _ (future
              (try
                (swap! state-atom assoc ::fxui/result (worker-fn render-progress!))
                (catch Throwable e
                  (log/error :exception e)
                  (swap! state-atom assoc ::fxui/result e))))
          ret (fxui/mount-renderer-and-await-result! state-atom renderer)]
      (if (instance? Throwable ret)
        (throw ret)
        ret))))

(defn make-gl-support-error-dialog [support-error]
  (let [root ^VBox (ui/load-fxml "gl-error.fxml")
        stage (ui/make-dialog-stage)
        scene (Scene. root)
        result (atom :quit)]
    (ui/with-controls root [message ^Button quit ^Button continue glgenbuffers-link opengl-linux-link]
      (when-not (util/is-linux?)
        (.. root getChildren (remove opengl-linux-link)))
      (ui/context! root :dialog {:stage stage} nil)
      (ui/title! stage "Insufficient OpenGL Support")
      (ui/text! message support-error)
      (ui/on-action! continue (fn [_] (reset! result :continue) (ui/close! stage)))
      (.setDefaultButton continue true)
      (ui/bind-action! quit ::close)
      (.setCancelButton quit true)
      (ui/on-action! glgenbuffers-link (fn [_] (ui/open-url (github/glgenbuffers-link))))
      (ui/on-action! opengl-linux-link (fn [_] (ui/open-url "https://www.defold.com/faq/#_linux_issues")))
      (.setScene stage scene)
      ;; We want to show this dialog before the main ui is up running
      ;; so we can't use ui/show-and-wait! which does some extra menu
      ;; update magic.
      (.showAndWait stage)
      @result)))

(defn make-file-dialog
  ^File [title filter-descs ^File initial-file ^Window owner-window]
  (let [chooser (FileChooser.)
        initial-directory (some-> initial-file .getParentFile)
        initial-file-name (some-> initial-file .getName)
        extension-filters (map (fn [filter-desc]
                                 (let [description ^String (first filter-desc)
                                       extensions ^List (vec (rest filter-desc))]
                                   (FileChooser$ExtensionFilter. description extensions)))
                               filter-descs)]
    (when (and (some? initial-directory) (.exists initial-directory))
      (.setInitialDirectory chooser initial-directory))
    (when (some? (not-empty initial-file-name))
      (.setInitialFileName chooser initial-file-name))
    (.addAll (.getExtensionFilters chooser) ^Collection extension-filters)
    (.setTitle chooser title)
    (.showOpenDialog chooser owner-window)))

(handler/defhandler ::confirm :dialog
  (enabled? [selection]
            (seq selection))
  (run [^Stage stage selection]
       (ui/user-data! stage ::selected-items selection)
       (ui/close! stage)))

(handler/defhandler ::close :dialog
  (run [^Stage stage]
       (ui/close! stage)))

(handler/defhandler ::focus :dialog
  (active? [user-data] (if-let [active-fn (:active-fn user-data)]
                         (active-fn nil)
                         true))
  (run [^Stage stage user-data]
       (when-let [^Node node (:node user-data)]
         (ui/request-focus! node))))

(declare sanitize-folder-name)

(defn- new-folder-dialog
  [{:keys [name validate] :as props}]
  (let [sanitized-name ^String (sanitize-folder-name name)
        path-empty (zero? (.length sanitized-name))
        error-msg (validate sanitized-name)
        invalid (boolean (seq error-msg))]
    {:fx/type dialog-stage
     :showing (fxui/dialog-showing? props)
     :on-close-request {:event-type :cancel}
     :title "New Folder"
     :size :small
     :header {:fx/type fxui/label
              :variant :header
              :text "Enter New Folder Name"}
     :content {:fx/type fxui/two-col-input-grid-pane
               :style-class "dialog-content-padding"
               :children [{:fx/type fxui/label
                           :text "Name"}
                          {:fx/type fxui/text-field
                           :text ""
                           :variant (if invalid :error :default)
                           :on-text-changed {:event-type :set-folder-name}}
                          {:fx/type fxui/label
                           :text "Preview"}
                          {:fx/type fxui/text-field
                           :editable false
                           :text (or error-msg sanitized-name)}]}
     :footer {:fx/type dialog-buttons
              :children [{:fx/type fxui/button
                          :text "Cancel"
                          :cancel-button true
                          :on-action {:event-type :cancel}}
                         {:fx/type fxui/button
                          :disable (or invalid path-empty)
                          :text "Create Folder"
                          :variant :primary
                          :default-button true
                          :on-action {:event-type :confirm}}]}}))

(defn make-new-folder-dialog
  [^String base-dir {:keys [validate]}]
  (fxui/show-dialog-and-await-result!
    :initial-state {:name ""
                    :validate (or validate (constantly nil))}
    :event-handler (fn [state {:keys [fx/event event-type]}]
                     (case event-type
                       :set-folder-name (assoc state :name event)
                       :cancel (assoc state ::fxui/result nil)
                       :confirm (assoc state ::fxui/result (sanitize-folder-name (:name state)))))
    :description {:fx/type new-folder-dialog}))

(defn- target-ip-dialog [{:keys [msg ^String ip] :as props}]
  (let [ip-valid (pos? (.length ip))]
    {:fx/type dialog-stage
     :showing (fxui/dialog-showing? props)
     :on-close-request {:event-type :cancel}
     :title "Enter Target IP"
     :size :small
     :header {:fx/type fxui/label
              :variant :header
              :text msg}
     :content {:fx/type fxui/two-col-input-grid-pane
               :style-class "dialog-content-padding"
               :children [{:fx/type fxui/label
                           :text "Target IP Address"}
                          {:fx/type fxui/text-field
                           :variant (if ip-valid :default :error)
                           :text ip
                           :on-text-changed {:event-type :set-ip}}]}
     :footer {:fx/type dialog-buttons
              :children [{:fx/type fxui/button
                          :text "Cancel"
                          :cancel-button true
                          :on-action {:event-type :cancel}}
                         {:fx/type fxui/button
                          :disable (not ip-valid)
                          :text "Add Target IP"
                          :variant :primary
                          :default-button true
                          :on-action {:event-type :confirm}}]}}))

(defn make-target-ip-dialog [ip msg]
  (fxui/show-dialog-and-await-result!
    :initial-state {:ip (or ip "")}
    :event-handler (fn [state {:keys [fx/event event-type]}]
                     (case event-type
                       :set-ip (assoc state :ip event)
                       :cancel (assoc state ::fxui/result nil)
                       :confirm (assoc state ::fxui/result (:ip state))))
    :description {:fx/type target-ip-dialog
                  :msg (or msg "Enter Target IP Address")}))

(defn- sanitize-common [name]
  (-> name
      string/trim
      (string/replace #"[/\\]" "") ; strip path separators
      (string/replace #"[\"']" "") ; strip quotes
      (string/replace #"^\.+" "") ; prevent hiding files (.dotfile)
      (string/replace #"[<>:|?*]" ""))) ; Additional Windows forbidden characters

(defn sanitize-file-name [extension name]
  (-> name
      sanitize-common
      (#(if (empty? extension) (string/replace % #"\..*" "") %)) ; disallow adding extension = resource type
      (#(if (and (seq extension) (seq %))
          (str % "." extension)
          %)))) ; append extension if there was one

(defn sanitize-folder-name [name]
  (sanitize-common name))

(defn sanitize-path [path]
  (string/join \/
               (into []
                     (comp
                       (remove empty?)
                       (map sanitize-folder-name))
                     (string/split path #"[\\\/]"))))

(defn- rename-dialog [{:keys [initial-name name title label validate sanitize] :as props}]
  (let [sanitized (some-> (not-empty name) sanitize)
        validation-msg (some-> sanitized validate)
        invalid (or (empty? sanitized) (some? validation-msg))]
    {:fx/type dialog-stage
     :showing (fxui/dialog-showing? props)
     :on-close-request {:event-type :cancel}
     :title title
     :size :small
     :header {:fx/type fxui/label
              :variant :header
              :text (str "Rename " initial-name)}
     :content {:fx/type fxui/two-col-input-grid-pane
               :style-class "dialog-content-padding"
               :children [{:fx/type fx.label/lifecycle
                           :text label}
                          {:fx/type fxui/text-field
                           :text name
                           :variant (if invalid :error :default)
                           :on-text-changed {:event-type :set-name}}
                          {:fx/type fx.label/lifecycle
                           :text "Preview"}
                          {:fx/type fxui/text-field
                           :editable false
                           :text (or validation-msg sanitized)}]}
     :footer {:fx/type dialog-buttons
              :children [{:fx/type fxui/button
                          :text "Cancel"
                          :cancel-button true
                          :on-action {:event-type :cancel}}
                         {:fx/type fxui/button
                          :variant :primary
                          :default-button true
                          :disable invalid
                          :text "Rename"
                          :on-action {:event-type :confirm}}]}}))

(defn make-rename-dialog
  "Shows rename dialog

  Options expect keys:
  - `:title`
  - `:label`
  - `:validate`
  - `:sanitize`"
  ^String [name options]
  (let [sanitize (:sanitize options)]
    (fxui/show-dialog-and-await-result!
      :initial-state {:name name}
      :event-handler (fn [state event]
                       (case (:event-type event)
                         :set-name (assoc state :name (:fx/event event))
                         :cancel (assoc state ::fxui/result nil)
                         :confirm (assoc state ::fxui/result (-> state
                                                                 :name
                                                                 sanitize
                                                                 not-empty))))
      :description (assoc options :fx/type rename-dialog
                                  :initial-name (sanitize name)))))

(defn- relativize [^File base ^File path]
  (let [[^Path base ^Path path] (map #(Paths/get (.toURI ^File %)) [base path])]
    (str ""
         (when (.startsWith path base)
           (-> base
             (.relativize path)
             (.toString))))))

(defn- new-file-dialog
  [{:keys [^File base-dir ^File location type ext name] :as props}]
  (let [sanitized-name (sanitize-file-name ext name)
        empty (empty? sanitized-name)
        relative-path (FilenameUtils/separatorsToUnix (relativize base-dir location))
        location-exists (.exists location)
        valid-input (and (not empty) location-exists)]
    {:fx/type dialog-stage
     :showing (fxui/dialog-showing? props)
     :on-close-request {:event-type :cancel}
     :title (str "New " type)
     :size :small
     :header {:fx/type fxui/label
              :variant :header
              :text (str "Enter " type " File Name")}
     :content {:fx/type fxui/two-col-input-grid-pane
               :style-class "dialog-content-padding"
               :children [{:fx/type fxui/label
                           :text "Name"}
                          {:fx/type fxui/text-field
                           :text ""
                           :variant (if empty :error :default)
                           :on-text-changed {:event-type :set-file-name}}
                          {:fx/type fxui/label
                           :text "Location"}
                          {:fx/type fx.h-box/lifecycle
                           :spacing 4
                           :children [{:fx/type fxui/text-field
                                       :h-box/hgrow :always
                                       :variant (if location-exists :default :error)
                                       :on-text-changed {:event-type :set-location}
                                       :text relative-path}
                                      {:fx/type fxui/button
                                       :variant :icon
                                       :on-action {:event-type :pick-location}
                                       :text "…"}]}
                          {:fx/type fxui/label
                           :text "Preview"}
                          {:fx/type fxui/text-field
                           :editable false
                           :text (if valid-input
                                   (str relative-path \/ sanitized-name)
                                   "")}]}
     :footer {:fx/type dialog-buttons
              :children [{:fx/type fxui/button
                          :text "Cancel"
                          :cancel-button true
                          :on-action {:event-type :cancel}}
                         {:fx/type fxui/button
                          :disable (not valid-input)
                          :text (str "Create " type)
                          :variant :primary
                          :default-button true
                          :on-action {:event-type :confirm}}]}}))

(defn make-new-file-dialog
  [^File base-dir ^File location type ext]
  (fxui/show-dialog-and-await-result!
    :initial-state {:name ""
                    :base-dir base-dir
                    :location location
                    :type type
                    :ext ext}
    :event-handler (fn [state {:keys [fx/event event-type]}]
                     (case event-type
                       :set-file-name (assoc state :name event)
                       :set-location (assoc state :location (io/file base-dir (sanitize-path event)))
                       :pick-location (assoc state :location
                                             (let [window (.getWindow (.getScene ^Node (.getSource ^Event event)))
                                                   previous-location (:location state)
                                                   initial-dir (if (.exists ^File previous-location)
                                                                 previous-location
                                                                 base-dir)
                                                   path (-> (doto (DirectoryChooser.)
                                                              (.setInitialDirectory initial-dir)
                                                              (.setTitle "Set Path"))
                                                            (.showDialog window))]
                                               (if path
                                                 (io/file base-dir (relativize base-dir path))
                                                 previous-location)))
                       :cancel (assoc state ::fxui/result nil)
                       :confirm (assoc state ::fxui/result
                                       (-> (io/file (:location state) (sanitize-file-name ext (:name state)))
                                           ;; Canonical path turns Windows path
                                           ;; into the correct case. We need
                                           ;; this to be able to match internal
                                           ;; resource maps, which are case
                                           ;; sensitive unlike the NTFS file
                                           ;; system.
                                           (.getCanonicalFile)))))
    :description {:fx/type new-file-dialog}))

(handler/defhandler ::rename-conflicting-files :dialog
  (run [^Stage stage]
    (ui/user-data! stage ::file-conflict-resolution-strategy :rename)
    (ui/close! stage)))

(handler/defhandler ::overwrite-conflicting-files :dialog
  (run [^Stage stage]
    (ui/user-data! stage ::file-conflict-resolution-strategy :overwrite)
    (ui/close! stage)))

(defn ^:dynamic make-resolve-file-conflicts-dialog
  [src-dest-pairs]
  (let [^Parent root (ui/load-fxml "resolve-file-conflicts.fxml")
        scene (Scene. root)
        ^Stage stage (doto (ui/make-dialog-stage (ui/main-stage))
                       (ui/title! "Name Conflict")
                       (.setScene scene))
        controls (ui/collect-controls root ["message" "rename" "overwrite" "cancel"])]
    (ui/context! root :dialog {:stage stage} nil)
    (ui/bind-action! (:rename controls) ::rename-conflicting-files)
    (ui/bind-action! (:overwrite controls) ::overwrite-conflicting-files)
    (ui/bind-action! (:cancel controls) ::close)
    (.setCancelButton ^Button (:cancel controls) true)
    (ui/text! (:message controls) (let [conflict-count (count src-dest-pairs)]
                                    (if (= 1 conflict-count)
                                      "The destination has an entry with the same name."
                                      (format "The destination has %d entries with conflicting names." conflict-count))))
    (ui/show-and-wait! stage)
    (ui/user-data stage ::file-conflict-resolution-strategy)))

(def ext-with-selection-props
  (fx/make-ext-with-props
    {:selection fxui/text-input-selection-prop}))

(defn make-target-log-dialog [log-atom clear! restart!]
  (let [renderer-ref (volatile! nil)
        renderer (fx/create-renderer
                   :error-handler error-reporting/report-exception!
                   :opts {:fx.opt/map-event-handler
                          #(case (:event-type %)
                             :clear (clear!)
                             :restart (restart!)
                             :cancel (fx/unmount-renderer log-atom @renderer-ref))}
                   :middleware
                   (fx/wrap-map-desc
                     (fn [log]
                       {:fx/type dialog-stage
                        :title "Target Discovery Log"
                        :modality :none
                        :showing true
                        :size :large
                        :header {:fx/type fxui/label
                                 :variant :header
                                 :text "Target discovery log"}
                        :content (let [str (string/join "\n" log)]
                                   {:fx/type ext-with-selection-props
                                    :props {:selection [(count str) (count str)]}
                                    :desc {:fx/type info-dialog-text-area
                                           :pref-row-count 20
                                           :text str}})
                        :footer {:fx/type dialog-buttons
                                 :children [{:fx/type fxui/button
                                             :text "Close"
                                             :cancel-button true
                                             :on-action {:event-type :cancel}}
                                            {:fx/type fxui/button
                                             :text "Clear Log"
                                             :on-action {:event-type :clear}}
                                            {:fx/type fxui/button
                                             :text "Restart Discovery"
                                             :on-action {:event-type :restart}}]}})))]
    (vreset! renderer-ref renderer)
    (fx/mount-renderer log-atom renderer)))
