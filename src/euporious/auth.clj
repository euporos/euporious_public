(ns euporious.auth
  "Per-site adjustments to Biff's email auth (com.biffweb.experimental.auth).

  - `verify-code-handler`: Biff's handler with the code lifetime as a parameter
    (Biff hard-codes 3 minutes, too short for older relatives).
  - `wrap-no-recaptcha`: Biff checks reCAPTCHA whenever :recaptcha/secret-key is
    set, which is process-wide. This hides the key from one site's auth routes."
  (:require [com.biffweb :as biff]
            [com.biffweb.experimental :as biffx]
            [com.biffweb.experimental.auth :as biff-auth]
            [tick.core :as tick])
  (:import [java.util UUID]))

(def max-failed-attempts 3)

(defn- uuid-from [s]
  (UUID/nameUUIDFromBytes (.getBytes s)))

(defn verify-code-handler
  "Copy of biff-auth/verify-code-handler (Biff cbc0be3) with a configurable
  lifetime. Keep in sync when bumping Biff."
  [code-minutes]
  (fn [{:keys [biff.auth/app-path
               biff.auth/new-user-tx
               biff.auth/get-user-id
               biff/node
               params
               session]
        :as   ctx}]
    (let [email            (biff/normalize-email (:email params))
          code-id          (uuid-from email)

          [{:biff.auth.code/keys [code created-at failed-attempts]}]
          (biffx/q node
                   {:select [:biff.auth.code/code
                             :biff.auth.code/created-at
                             :biff.auth.code/failed-attempts]
                    :from 'biff.auth/code
                    :where [:= :xt/id code-id]})

          success          (and (biff-auth/passed-recaptcha? ctx)
                                (some? code)
                                (< failed-attempts max-failed-attempts)
                                (< (tick/between created-at (tick/zoned-date-time) :minutes)
                                   code-minutes)
                                (= (:code params) code))
          existing-user-id (when success (get-user-id node email))
          user-tx          (when (and success (not existing-user-id))
                             (new-user-tx ctx email))
          ;; Biff looks the new user up again after the tx, which can miss the
          ;; just-written doc: the first sign-in then ends up without :uid.
          ;; Take the id from the new user doc instead.
          new-user-id      (some (fn [[op _table doc]]
                                   (when (= :put-docs op) (:xt/id doc)))
                                 user-tx)
          tx               (cond
                             success
                             (concat [[:delete-docs :biff.auth/code code-id]]
                                     user-tx)

                             (and (some? code)
                                  (< failed-attempts max-failed-attempts))
                             [{:update 'biff.auth/code
                               :set {:biff.auth.code/failed-attempts
                                     [:+ :biff.auth.code/failed-attempts 1]}
                               :where [:= :xt/id code-id]}])]
      (biffx/submit-tx ctx tx)
      (if success
        {:status  303
         :headers {"location" app-path}
         :session (assoc session :uid (or existing-user-id
                                          new-user-id
                                          (get-user-id node email)))}
        {:status  303
         :headers {"location" (str "/verify-code?error=invalid-code&email=" email)}}))))

(defn wrap-no-recaptcha [handler]
  (fn [{:keys [biff/secret] :as ctx}]
    (handler (assoc ctx
                    ::no-recaptcha true   ; euporious.email/send-email reads this
                    :biff/secret (fn [k]
                                   (when-not (= k :recaptcha/secret-key)
                                     (secret k)))))))

(defn replace-verify-code
  "Swaps Biff's POST /auth/verify-code handler in the auth module's routes.
  Throws if the route is not where it is expected, so a Biff upgrade that
  reshapes the routes cannot silently bring back the 3-minute default."
  [routes code-minutes]
  (let [replaced (volatile! 0)
        routes   (mapv (fn [[path data & children]]
                         (into [path data]
                               (map (fn [[child-path :as child]]
                                      (if (= "/verify-code" child-path)
                                        (do (vswap! replaced inc)
                                            ["/verify-code" {:post (verify-code-handler code-minutes)}])
                                        child)))
                               children))
                       routes)]
    (when-not (= 1 @replaced)
      (throw (ex-info "Biff auth routes changed: /verify-code not found" {:routes routes})))
    routes))
