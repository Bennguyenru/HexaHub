(ns editor.client
  (:require [editor.protobuf :as protobuf]
            [editor.prefs :as prefs])
  (:import [com.defold.editor.client DefoldAuthFilter]
           [com.defold.editor.providers ProtobufProviders ProtobufProviders$ProtobufMessageBodyReader ProtobufProviders$ProtobufMessageBodyWriter]
           [com.sun.jersey.api.client Client ClientResponse WebResource WebResource$Builder]
           [com.sun.jersey.api.client.config ClientConfig DefaultClientConfig]
           [java.net URI]
           [javax.ws.rs.core MediaType]))

(set! *warn-on-reflection* true)

(defn- make-client-config []
  (let [cc (DefaultClientConfig.)]
    (.add (.getClasses cc) ProtobufProviders$ProtobufMessageBodyReader)
    (.add (.getClasses cc) ProtobufProviders$ProtobufMessageBodyWriter)
    cc))

(defn make-client [prefs]
  (let [client (Client/create (make-client-config))
        email (prefs/get-prefs prefs "email" nil)
        token (prefs/get-prefs prefs "token" nil)]
    (when (and email token)
      (.addFilter client (DefoldAuthFilter. email token nil)))
    {:client client
     :prefs prefs}))

; NOTE: Version without exceptions. Haven't decided yet...
#_(defn rget [client path entity-class]
   (let [server-url (prefs/get-prefs (:prefs client) "server-url" "http://cr.defold.com")
         resource (.resource (:client client) (URI. server-url))]
     (let [^ClientResponse cr (-> resource
                                (.path path)
                                (.accept (into-array MediaType [ProtobufProviders/APPLICATION_XPROTOBUF_TYPE]))
                                (.get ClientResponse))]
       { :status (.getStatus cr)
         :entity (when (< (.getStatus cr) 300) (.getEntity cr entity-class))})))

(def ^"[Ljavax.ws.rs.core.MediaType;" ^:private
  media-types
  (into-array MediaType [ProtobufProviders/APPLICATION_XPROTOBUF_TYPE]))

(defn- server-url
  ^URI [prefs]
  (let [server-url (URI. (prefs/get-prefs prefs "server-url" "https://cr.defold.com"))]
    (when-not (= "https" (.getScheme server-url))
      (throw (ex-info (format "Invalid 'server-url' preference: must use https scheme: was '%s'" server-url)
                      {:server-url server-url})))
    server-url))

; Protobuf version as json
(defn rget [client ^String path ^Class entity-class]
  (let [resource   (.resource ^Client (:client client) (server-url (:prefs client)))
        builder    (.accept (.path resource path) media-types)]
    (protobuf/pb->map (.get builder entity-class))))
