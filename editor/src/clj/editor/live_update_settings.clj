(ns editor.live-update-settings
  (:require [amazonica.aws.s3 :as s3]
            [dynamo.graph :as g]
            [editor.defold-project :as project]
            [editor.workspace :as workspace]
            [editor.graph-util :as gu]
            [editor.util :as util]
            [editor.settings :as settings]
            [editor.settings-core :as settings-core]
            [service.log :as log]))

(def live-update-icon "icons/32/Icons_04-Project-file.png")

(def get-bucket-names
  (memoize (fn [cred]
             (try
               (mapv :name (s3/list-buckets cred))
               (catch Exception e
                 (log/warn :exception e)
                 [])))))

(defn- get-config-file-profiles []
  (try
    (let [pcf (com.amazonaws.auth.profile.ProfilesConfigFile.)]
      (vec (keys (.getAllBasicProfiles pcf))))
    (catch Exception e
      (log/warn :exception e)
      [])))

(defn- update-section-setting [section path f]
  (if-let [bucket-index (first (util/positions #(= path (:path %)) (get section :fields)))]
    (update-in section [:fields bucket-index] f)
    section))

(defn- update-form-setting [form-data path f]
  (update form-data :sections (fn [section] (mapv #(update-section-setting % path f) section))))

(g/defnode LiveUpdateSettingsNode
  (inherits project/ResourceNode)

  (input settings-map g/Any)

  (output amazon-creds g/Any :cached
          (g/fnk [settings-map]
                 (let [profile (get settings-map ["liveupdate" "amazon-credential-profile"])]
                   (when (and profile (pos? (count profile)))
                     {:profile profile}))))
                 
  (output amazon-buckets g/Any :cached (g/fnk [amazon-creds] (if amazon-creds (get-bucket-names amazon-creds) [])))

  (input form-data g/Any)
  (output form-data g/Any :cached
          (g/fnk [form-data amazon-buckets]
                 (-> form-data
                     (update-form-setting ["liveupdate" "amazon-credential-profile"]
                                          #(assoc %
                                                  :type :choicebox
                                                  :from-string str :to-string str
                                                  :options (mapv (fn [profile] [profile profile]) (get-config-file-profiles))))
                     (update-form-setting ["liveupdate" "amazon-bucket"]
                                          #(assoc %
                                                  :type :choicebox
                                                  :from-string str
                                                  :to-string str
                                                  :options (mapv (fn [bucket] [bucket bucket]) amazon-buckets)))
                     (update-form-setting ["liveupdate" "mode"]
                                          #(assoc %
                                                  :type :choicebox
                                                  :options [["Zip" "Zip"] ["Amazon" "Amazon"]])))))

  (input save-data-content g/Any)
  (output save-data g/Any :cached
          (g/fnk [resource save-data-content]
                 {:resource resource
                  :content save-data-content}))
  (output build-targets g/Any :cached (g/constantly [])))

(def ^:private basic-meta-info (with-open [r (-> "live-update-meta.edn"
                                                 settings-core/resource-reader
                                                 settings-core/pushback-reader)]
                                 (settings-core/load-meta-info r)))

(defn- load-live-update-settings [project self input]
  (let [graph-id (g/node-id->graph-id self)]
    (concat
      (g/make-nodes graph-id [settings-node settings/SettingsNode]
                    (g/connect settings-node :_node-id self :nodes)
                    (g/connect settings-node :settings-map self :settings-map)
                    (g/connect settings-node :save-data-content self :save-data-content)
                    (g/connect settings-node :form-data self :form-data)
                    (settings/load-settings-node settings-node input basic-meta-info nil)))))

(defn register-resource-types [workspace]
  (workspace/register-resource-type workspace
                                    :textual? true
                                    :ext "settings"
                                    :label "Live Update Settings"
                                    :node-type LiveUpdateSettingsNode
                                    :load-fn load-live-update-settings
                                    :icon live-update-icon
                                    :view-types [:form-view :text]))
