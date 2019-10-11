(ns cerber.oauth2.authorization
  (:require [cerber.error :as error]
            [cerber.form :as form]
            [cerber.oauth2.context :as ctx]
            [cerber.oauth2.response :as response]
            [cerber.oauth2.pkce :as pkce]
            [cerber.stores.token :as token]
            [failjure.core :as f]))

(defmulti authorization-request-handler (comp :response_type :params))

(defmulti token-request-handler (comp :grant_type :params))

;; authorization request handler for Authorization Code grant type


;; code challenge
;; code challenge method

;; Store the code challenge and code challenge method
;;

(defmethod authorization-request-handler "code"
  [req]
  (let [result (f/attempt-> req
                            (ctx/client-valid?)
                            (ctx/redirect-allowed?)
                            (ctx/state-allowed?)
                            (ctx/scopes-allowed?)
                            (pkce/code-challenge-valid?)
                            (ctx/user-authenticated?)
                            (ctx/request-approved?))]
    (if (f/failed? result)
      result
      (response/redirect-with-code result))))

;; authorization request handler for Implict grant type

(defmethod authorization-request-handler "token"
  [req]
  (let [result (f/attempt-> req
                            (ctx/client-valid?)
                            (ctx/redirect-allowed?)
                            (ctx/grant-allowed? "token")
                            (ctx/state-allowed?)
                            (ctx/scopes-allowed?)
                            (ctx/user-authenticated?))]
    (if (f/failed? result)
      result
      (response/redirect-with-token result))))

;; default response handler for unknown grant types

(defmethod authorization-request-handler :default
  [req]
  error/unsupported-response-type)

;; token request handler for Authorization Code grant type

;; Check if PKCE code_challenge is required
;; If it is, lookup code_challenge_method to work out how to check it
;; If plain then compare directly
;; If S256 then hash/combine code verifier and compare it with the stored code_challenge

(defmethod token-request-handler "authorization_code"
  [req]
  (let [result (f/attempt-> req
                            (ctx/client-authenticated?)
                            (ctx/grant-allowed? "authorization_code")
                            (ctx/authcode-valid?)
                            (pkce/code-verifier-valid?)
                            (ctx/redirect-valid?)
                            (ctx/user-valid?))]
    (if (f/failed? result)
      result
      (response/access-token-response result))))

;; token request handler for Resource Owner Password Credentials grant

(defmethod token-request-handler "password"
  [req]
  (let [result (f/attempt-> req
                            (ctx/client-authenticated?)
                            (ctx/grant-allowed? "password")
                            (ctx/scopes-allowed?)
                            (ctx/user-password-valid? form/default-authenticator))]
    (if (f/failed? result)
      result
      (response/access-token-response result))))

;; token request handler for Client Credentials grant

(defmethod token-request-handler "client_credentials"
  [req]
  (let [result (f/attempt-> req
                            (ctx/client-authenticated?)
                            (ctx/grant-allowed? "client_credentials")
                            (ctx/scopes-allowed?))]
    (if (f/failed? result)
      result
      (response/access-token-response result))))

;; refresh-token request handler

(defmethod token-request-handler "refresh_token"
  [req]
  (let [result (f/attempt-> req
                            (ctx/client-authenticated?)
                            (ctx/refresh-token-valid?)
                            (ctx/scopes-allowed?))]
    (if (f/failed? result)
      result
      (response/refresh-token-response result))))

;; default response handler for unknown token requests

(defmethod token-request-handler :default
  [req]
  error/unsupported-grant-type)

(defn authorize! [req]
  (let [{:keys [client scopes] :as response} (authorization-request-handler req)]
    (condp = (:error response)
      "unapproved"   (response/approval-form-response req client scopes)
      "unauthorized" (response/authentication-form-response req)
      response)))

(defn unauthorize! [req]
  (let [user (::ctx/user req)
        client (::ctx/client req)]

    (when client
      (token/revoke-client-tokens client user))

    (if (or user client)
      (response/redirect-with-session "/" nil)
      error/unauthorized)))

(defn approve! [req]
  (authorize! (ctx/approve-authorization req)))

(defn refuse! [req]
  error/access-denied)

(defn issue-token! [req]
  (token-request-handler req))
