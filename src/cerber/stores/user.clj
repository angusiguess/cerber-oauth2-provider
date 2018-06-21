(ns cerber.stores.user
  (:require [mount.core :refer [defstate]]
            [cerber
             [db :as db]
             [helpers :as helpers]
             [config :refer [app-config]]
             [store :refer :all]]
            [failjure.core :as f])
  (:import [org.mindrot.jbcrypt BCrypt]))

(declare ->map init-users)

(defrecord User [id login email name password roles permissions enabled? created-at modified-at activated-at blocked-at])

(defrecord SqlUserStore []
  Store
  (fetch-one [this [login]]
    (->map (first (db/find-user {:login login}))))
  (revoke-one! [this [login]]
    (db/delete-user {:login login}))
  (store! [this k user]
    (when (= 1 (db/insert-user user)) user))
  (modify! [this k user]
    (if (:enabled? user)
      (db/enable-user user)
      (db/disable-user user)))
  (purge! [this]
    (db/clear-users)))

(defmulti ^:no-doc create-user-store identity)

(defmacro with-user-store
  "Changes default binding to default users store."
  [store & body]
  `(binding [*user-store* ~store] ~@body))

(defstate ^:dynamic *user-store*
  :start (create-user-store (-> app-config :users :store)))

(defstate ^:no-doc defined-users
  :start (init-users (-> app-config :users :defined)))

(defmethod create-user-store :in-memory [_]
  (->MemoryStore "users" (atom {})))

(defmethod create-user-store :redis [_]
  (->RedisStore "users" (:redis-spec app-config)))

(defmethod create-user-store :sql [_]
  (->SqlUserStore))

(defn bcrypt
  "Performs BCrypt hashing of password."
  [password]
  (BCrypt/hashpw password (BCrypt/gensalt)))

(defn create-user
  "Creates new user"
  ([user password]
   (create-user user password nil nil))
  ([user password roles permissions]
   (let [enabled (:enabled? user true)
         merged  (merge-with
                  #(or %2 %1)
                  user
                  {:id (helpers/uuid)
                   :name nil
                   :email nil
                   :enabled? enabled
                   :password (and password (bcrypt password))
                   :roles (helpers/coll->str roles)
                   :permissions (helpers/coll->str permissions)
                   :activated-at (when enabled (helpers/now))
                   :created-at (helpers/now)})]

     (when (store! *user-store* [:login] merged)
       (map->User merged)))))

(defn find-user
  "Returns users with given login, if found or nil otherwise."
  [login]
  (if-let [found (and login (fetch-one *user-store* [login]))]
    (map->User found)))

(defn revoke-user
  "Removes user from store"
  [user]
  (revoke-one! *user-store* [(:login user)]))

(defn enable-user
  "Enables user. Returns true if user has been enabled successfully or false otherwise."
  [user]
  (= 1 (modify! *user-store* [:login] (assoc user :enabled? true :activated-at (helpers/now)))))

(defn disable-user
  "Disables user. Returns true if user has been disabled successfully or false otherwise."
  [user]
  (= 1 (modify! *user-store* [:login] (assoc user :enabled? false :blocked-at (helpers/now)))))

(defn purge-users
  "Removes users from store. Used for tests only."
  []
  (purge! *user-store*))

(defn init-users
  "Initializes configured users."
  [users]
  (f/try*
   (reduce (fn [reduced {:keys [login email name permissions roles enabled? password]}]
             (conj reduced (create-user (map->User {:login login
                                                    :email email
                                                    :name name
                                                    :enabled? enabled?})
                                        password
                                        roles
                                        permissions)))
           {}
           users)))

(defn valid-password?
  "Verify that candidate password matches the hashed bcrypted password"
  [candidate hashed]
  (and candidate hashed (BCrypt/checkpw candidate hashed)))

(defn ->map [result]
  (when-let [{:keys [roles permissions created_at modified_at activated_at blocked_at enabled]} result]
    (-> result
        (assoc  :enabled? enabled
                :created-at created_at
                :modified-at modified_at
                :activated-at activated_at
                :blocked-at blocked_at
                :roles (helpers/str->coll #{} roles)
                :permissions (helpers/str->coll #{} permissions))
        (dissoc :enabled
                :created_at
                :modified_at
                :confirmed_at
                :activated_at
                :blocked_at))))

(alter-meta! #'map->SqlUserStore assoc :private true)
