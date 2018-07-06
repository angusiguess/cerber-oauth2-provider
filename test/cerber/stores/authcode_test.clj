(ns cerber.stores.authcode-test
  (:require [cerber.stores.authcode :refer :all]
            [cerber.test-utils :refer [instance-of has-secret create-test-user create-test-client with-stores]]
            [midje.sweet :refer :all])
  (:import cerber.stores.authcode.AuthCode))

(def redirect-uri "http://localhost")
(def scope "photo:read")

(fact "Newly created authcode is returned with secret code filled in."
      (with-stores :in-memory

        ;; given
        (let [user     (create-test-user "")
              client   (create-test-client scope redirect-uri)
              authcode (create-authcode client user scope redirect-uri)]

          ;; then
          authcode => (instance-of AuthCode)
          authcode => (has-secret :code))))

(tabular
 (fact "Authcode found in a store is returned with secret code filled in."
       (with-stores ?store

         ;; given
         (let [user     (create-test-user "")
               client   (create-test-client scope redirect-uri)
               authcode (create-authcode client user scope redirect-uri)
               found    (find-authcode (:code authcode))]

           ;; then
           found => (instance-of AuthCode)
           found => (has-secret :code))))

 ?store :in-memory :sql :redis)

(tabular
 (fact "Revoked authcode is not returned from store."
       (with-stores ?store

         ;; given
         (let [user     (create-test-user "")
               client   (create-test-client scope redirect-uri)
               authcode (create-authcode client user scope redirect-uri)]

           ;; then
           (find-authcode (:code authcode)) => (instance-of AuthCode)
           (revoke-authcode authcode)
           (find-authcode (:code authcode)) => nil)))

 ?store :in-memory :sql :redis)

(tabular
 (fact "Expired authcodes are removed from store."
       (with-stores ?store

         ;; given
         (let [user     (create-test-user "")
               client   (create-test-client scope redirect-uri)
               authcode (create-authcode client user scope redirect-uri -1)]

           ;; then
           (find-authcode (:code authcode))) => nil))

 ?store :in-memory :sql :redis)
