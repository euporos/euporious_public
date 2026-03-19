(ns euporious.decap-sites
  "GitHub OAuth backend for Decap CMS supporting multiple static sites.

   Each site is configured in config.edn under :decap/sites with its own
   GitHub OAuth credentials. Routes use site-id path parameter."
  (:require [clj-http.client :as http]
            [clojure.tools.logging :as log]
            [ring.util.response :as response]))

;; =============================================================================
;; Site Configuration
;; =============================================================================

(defn get-site
  "Retrieve site config from context by site-id."
  [{:keys [decap/sites]} site-id]
  (get sites (keyword site-id)))

;; =============================================================================
;; GitHub OAuth Handlers
;; =============================================================================

(defn oauth-auth
  "Redirect to GitHub OAuth authorization page."
  [{:keys [path-params] :as ctx}]
  (let [{:keys [site-id]} path-params
        site-config (get-site ctx site-id)]
    (if-let [client-id (:client-id site-config)]
      (let [callback-url (:callback-url site-config)
            scope "repo,user"]
        (response/redirect
         (str "https://github.com/login/oauth/authorize"
              "?client_id=" client-id
              "&redirect_uri=" (java.net.URLEncoder/encode callback-url "UTF-8")
              "&scope=" scope)))
      {:status 404
       :headers {"Content-Type" "application/json"}
       :body "{\"error\":\"Site not found\"}"})))

(defn oauth-callback
  "Exchange GitHub OAuth code for access token and redirect back to CMS."
  [{:keys [path-params params biff/secret] :as ctx}]
  (let [{:keys [site-id]} path-params
        site-config (get-site ctx site-id)]
    (if-let [client-id (:client-id site-config)]
      (let [client-secret-key (:client-secret-key site-config)
            client-secret (secret client-secret-key)
            base-url (:base-url site-config)
            code (:code params)]
        (if (and client-secret code)
          (let [resp (http/post "https://github.com/login/oauth/access_token"
                                {:form-params {:client_id client-id
                                               :client_secret client-secret
                                               :code code}
                                 :headers {"Accept" "application/json"}
                                 :as :json
                                 :throw-exceptions false})]
            (if (and (= 200 (:status resp))
                     (-> resp :body :access_token))
              (response/redirect
               (str base-url "/admin/#access_token=" (-> resp :body :access_token)
                    "&token_type=bearer"))
              (do
                (log/error "OAuth token exchange failed:" (:body resp))
                {:status 401
                 :headers {"Content-Type" "application/json"}
                 :body "{\"error\":\"OAuth token exchange failed\"}"})))
          {:status 400
           :headers {"Content-Type" "application/json"}
           :body "{\"error\":\"Missing code or configuration\"}"}))
      {:status 404
       :headers {"Content-Type" "application/json"}
       :body "{\"error\":\"Site not found\"}"})))

;; =============================================================================
;; Module Definition
;; =============================================================================

(def module
  {:api-routes [["/api/decap/:site-id/oauth/auth" {:get oauth-auth}]
                ["/api/decap/:site-id/oauth/callback" {:get oauth-callback}]]})
