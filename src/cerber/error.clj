(ns cerber.error
  (:require [cerber.oauth2.settings :as settings]
            [cerber.helpers :as helpers]
            [failjure.core :as f]
            [ring.util.request :refer [request-url]]))

(defrecord HttpError [error message code])

(extend-protocol f/HasFailed
  HttpError
  (message [this] (:message this))
  (failed? [this] true))

(def invalid-scope
  (map->HttpError {:error "invalid_scope" :message "Invalid scope" :code 302}))

(def invalid-state
  (map->HttpError {:error "invalid_state" :message "Invalid state. Only alphanumeric characters are allowed." :code 302}))

(def access-denied
  (map->HttpError {:error "access_denied" :message "Authorization refused" :code 302}))

(def invalid-request
  (map->HttpError {:error "invalid_request" :message "Invalid request" :code 400}))

(def invalid-token
  (map->HttpError {:error "invalid_request" :message "Invalid token" :code 400}))

(def invalid-redirect-uri
  (map->HttpError {:error "invalid_redirect_uri" :message "Invalid redirect URI" :code 400}))

(def unapproved
  (map->HttpError {:error "unapproved" :message "Authorization not approved" :code 403}))

(def unauthorized
  (map->HttpError {:error "unauthorized" :message "Authorization failed" :code 401}))

(def forbidden
  (map->HttpError {:error "forbidden" :message "No permission to the resource" :code 403}))

(def unsupported-response-type
  (map->HttpError {:error "unsupported_response_type" :message "Unsupported response type" :code 400}))

(def unsupported-grant-type
  (map->HttpError {:error "unsupported_grant_type" :message "Unsupported grant type" :code 400}))

(def server-error
  (map->HttpError {:error "server_error" :message "Invalid request" :code 500}))

(defn internal-error [message]
  (map->HttpError {:error "server_error" :message message :code 500}))

(defn bad-request [message]
  (map->HttpError {:error "bad_request" :message message :code 400}))

(defn error->redirect
  "Tranforms error into http redirect response.
  Error info is added as query param as described in 4.1.2.1. Error Response of OAuth2 spec"

  [http-error req]
  (let [{:keys [code error message]} http-error
        {:keys [headers params]} req
        {:keys [redirect_uri state]} params]
    {:status 302
     :headers {"Location" (str redirect_uri
                               "?error=" error
                               "&state=" state)}}))

(defn error->edn
  "Tranforms error into http response.

  In case of 401 (unauthorized) and 403 (forbidden) error codes additional WWW-Authenticate
  header is returned as described in https://tools.ietf.org/html/rfc6750#section-3"

  [http-error req]
  (let [{:keys [code error message]} http-error
        {:keys [headers params]} req]
    (if (or (= code 401) (= code 403))
      (if (or (get headers "authorization")
              (helpers/ajax-request? headers))

        ;; oauth or ajax request
        {:status code
         :headers {"WWW-Authenticate" (str "Bearer realm=\"" (settings/realm)
                                           "\",error=\"" error
                                           "\",error_description=\"" message "\"")}}

        ;; browser-based requested
        {:status 302
         :headers {"Location" (settings/unauthorized-url)}
         :session {:landing-url (request-url req)}})

      ;; uups, something bad happened
      (let [state (:state params)]
        {:status (or code 500)
         :body (-> {:error (or error "server_error")
                    :error_description message}
                   (cond-> state
                     (assoc :state state)))}))))
