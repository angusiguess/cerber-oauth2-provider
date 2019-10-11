-- :name find-authcode :? :1
-- :doc Returns authcode bound with given code
select id, client_id, login, code, scope, redirect_uri, expires_at, created_at, code_challenge_method, code_challenge
  from authcodes
 where code = :code;

-- :name insert-authcode :! :1
-- :doc Inserts new authcode
insert into authcodes (code, redirect_uri, client_id, login, scope, expires_at, created_at, code_challenge_method, code_challenge)
values (:code, :redirect-uri, :client-id, :login, :scope, :expires-at, :created-at, :code-challenge-method, :code-challenge);

-- :name delete-authcode :! :1
-- :doc Deletes authcode bound with given code
delete from authcodes where code = :code;

-- :name clear-authcodes :! :1
-- :doc Purges authcodes table
delete from authcodes;

-- :name clear-expired-authcodes :! :1
-- :doc Purges authcodes table from expired token
delete from authcodes where expires_at < :date
