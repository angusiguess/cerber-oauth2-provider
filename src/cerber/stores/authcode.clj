(ns cerber.stores.authcode
  (:require [clojure.string :refer [join split]]
            [cerber.stores.user :as user]
            [cerber
             [db :as db]
             [error :as error]
             [helpers :as helpers]
             [config :refer [app-config]]
             [store :refer :all]]
            [failjure.core :as f]
            [mount.core :refer [defstate]]))

(defn default-valid-for []
  (-> app-config :authcodes :valid-for))

(defrecord AuthCode [client-id login code scope redirect-uri expires-at created-at])

(defrecord SqlAuthCodeStore [normalizer]
  Store
  (fetch-one [this [code]]
    (-> (db/find-authcode {:code code})
        first
        normalizer))
  (revoke-one! [this [code]]
    (db/delete-authcode {:code code}))
  (store! [this k authcode]
    (when (= 1 (db/insert-authcode authcode)) authcode))
  (purge! [this]
    (db/clear-authcodes)))

(defn normalize
  [authcode]
  (when-let [{:keys [client_id login code scope redirect_uri created_at expires_at]} authcode]
    (map->AuthCode {:client-id client_id
                    :login login
                    :code code
                    :scope scope
                    :redirect-uri redirect_uri
                    :expires-at expires_at
                    :created-at created_at})))

(defmulti create-authcode-store identity)

(defmethod create-authcode-store :in-memory [_]
  (->MemoryStore "authcodes" (atom {})))

(defmethod create-authcode-store :redis [_]
  (->RedisStore "authcodes" (:redis-spec app-config)))

(defmethod create-authcode-store :sql [_]
  (helpers/with-periodic-fn
    (->SqlAuthCodeStore normalize) db/clear-expired-authcodes 8000))

(defstate ^:dynamic *authcode-store*
  :start (create-authcode-store (-> app-config :authcodes :store))
  :stop  (helpers/stop-periodic *authcode-store*))

(defn revoke-authcode
  "Revokes previously generated authcode."
  [authcode]
  (revoke-one! *authcode-store* [(:code authcode)]) nil)

(defn create-authcode
  "Creates new auth code"
  [client user scope redirect-uri & [ttl]]
  (let [authcode (helpers/reset-ttl
                  {:client-id (:id client)
                   :login (:login user)
                   :scope scope
                   :code (helpers/generate-secret)
                   :redirect-uri redirect-uri
                   :created-at (helpers/now)}
                  (or ttl (default-valid-for)))]

    (if (store! *authcode-store* [:code] authcode)
      (map->AuthCode authcode)
      (error/internal-error "Cannot store authcode"))))

(defn find-authcode [code]
  (when-let [authcode (fetch-one *authcode-store* [code])]
    (when-not (helpers/expired? authcode)
      (map->AuthCode authcode))))

(defn purge-authcodes
  []
  "Removes auth code from store."
  (purge! *authcode-store*))

(defmacro with-authcode-store
  "Changes default binding to default authcode store."
  [store & body]
  `(binding [*authcode-store* ~store] ~@body))
