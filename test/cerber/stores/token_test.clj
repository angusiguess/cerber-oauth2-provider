(ns cerber.stores.token-test
  (:require [midje.sweet :refer :all]
            [cerber.store :refer [now-plus-seconds expired?]]
            [cerber.stores
             [client :as c]
             [user :as u]]
            [cerber.common :refer :all]
            [cerber.stores.token :refer :all])
  (:import  [cerber.stores.token Token]))

(defonce client (c/create-client "http://foo.com" ["http://foo.com/callback"] ["photo:read"]  nil ["moderator"] false))
(defonce user (u/create-user {:login "nioh"} "alamakota"))

(def token-scope "photo:read")

(fact "Newly created token is returned with user/client ids and secret filled in."
      (with-token-store (create-token-store :in-memory)
        ;; given
        (let [token (create-token client user token-scope)]

          ;; then
          token => (instance-of Token)
          token => (has-secret :secret)
          token => (contains {:client-id (:id client)
                              :user-id (:id user)
                              :login "nioh"
                              :scope token-scope}))))

(tabular
 (with-state-changes [(before :contents (.start redis))
                      (after  :contents (.stop redis))]
   (fact "Token found in a store is returned with user/client ids and secret filled in."
         (with-token-store (create-token-store ?store)
           (purge-tokens)

           ;; given
           (let [token (create-token client user token-scope)
                 found (find-access-token (:secret token))]

             ;; then
             found => (instance-of Token)
             found => (has-secret :secret)
             found => (contains {:client-id (:id client)
                                 :user-id (:id user)
                                 :login "nioh"
                                 :scope token-scope})))))
 ?store :in-memory :sql :redis)

(tabular
 (with-state-changes [(before :contents (.start redis))
                      (after  :contents (.stop redis))]
   (fact "Revoked token is not returned from store."
         (with-token-store (create-token-store ?store)
           (purge-tokens)

           ;; given
           (let [token (create-token client user token-scope)
                 secret (:secret token)]

             ;; then
             (find-access-token secret) => (instance-of Token)
             (revoke-access-token token)
             (find-access-token secret) => nil))))

 ?store :in-memory :sql :redis)

(tabular
 (with-state-changes [(before :contents (.start redis))
                      (after  :contents (.stop redis))]
   (fact "Refreshing re-generates access/refresh tokens and revokes old ones from store."
         (with-token-store (create-token-store ?store)
           (purge-tokens)

           ;; given
           (let [client-id (:id client)
                 access-token (generate-access-token client user token-scope {:refresh? true})
                 refresh-token (find-refresh-token client-id (:refresh_token access-token) nil)]

             ;; when
             (let [new-token (refresh-access-token refresh-token)]

               ;; then
               (= (:access_token new-token) (:access_token access-token)) => false
               (= (:refresh_token new-token) (:refresh_token access-token)) => false
               (find-refresh-token client-id (:secret refresh-token) nil) => nil
               (:expires-at refresh-token) => nil)))))

 ?store :in-memory :sql :redis)

(fact "Tokens with expires-at date in the past are considered as expired ones."
      (with-token-store (create-token-store :in-memory)
        (expired?
         (create-token client user token-scope {:ttl -10})) => true))
