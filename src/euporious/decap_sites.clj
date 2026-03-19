(ns euporious.decap-sites
  "GitHub OAuth backend for Decap CMS supporting multiple static sites.

   Each site is configured in config.edn under :decap/sites with its own
   GitHub OAuth credentials. Routes use site-id path parameter."
  (:require [clj-http.client :as http]
            [clojure.tools.logging :as log]
            [ring.util.response :as response]
            [cheshire.core :as json]))

;; =============================================================================
;; Site Configuration
;; =============================================================================

(defn get-site
  "Retrieve site config from context by site-id."
  [{:keys [decap/sites]} site-id]
  (get sites (keyword site-id)))

;; =============================================================================
;; OAuth Response Pages
;; =============================================================================

(defn oauth-success-page
  "Generate HTML page that sends token to Decap CMS via postMessage."
  [token]
  (format "<!DOCTYPE html>
<html>
<head><title>Authorization Complete</title></head>
<body>
<p>Authorizing...</p>
<script>
(function() {
  var token = %s;
  var message = 'authorization:github:success:' + JSON.stringify({token: token, provider: 'github'});
  if (window.opener) {
    window.opener.postMessage(message, '*');
    window.close();
  } else {
    document.body.innerHTML = '<p>Authorization successful. You can close this window.</p>';
  }
})();
</script>
</body>
</html>"
          (json/generate-string token)))

(defn oauth-error-page
  "Generate HTML page that sends error to Decap CMS via postMessage."
  [error-message]
  (format "<!DOCTYPE html>
<html>
<head><title>Authorization Failed</title></head>
<body>
<p>Authorization failed</p>
<script>
(function() {
  var error = %s;
  var message = 'authorization:github:error:' + JSON.stringify({error: error});
  if (window.opener) {
    window.opener.postMessage(message, '*');
    window.close();
  } else {
    document.body.innerHTML = '<p>Authorization failed: ' + error + '</p>';
  }
})();
</script>
</body>
</html>"
          (json/generate-string error-message)))

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
  "Exchange GitHub OAuth code for access token and send to CMS via postMessage."
  [{:keys [path-params params biff/secret] :as ctx}]
  (let [{:keys [site-id]} path-params
        site-config (get-site ctx site-id)]
    (if-let [client-id (:client-id site-config)]
      (let [client-secret-key (:client-secret-key site-config)
            client-secret (secret client-secret-key)
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
              {:status 200
               :headers {"Content-Type" "text/html"}
               :body (oauth-success-page (-> resp :body :access_token))}
              (do
                (log/error "OAuth token exchange failed:" (:body resp))
                {:status 200
                 :headers {"Content-Type" "text/html"}
                 :body (oauth-error-page "Token exchange failed")})))
          {:status 200
           :headers {"Content-Type" "text/html"}
           :body (oauth-error-page "Missing code or configuration")}))
      {:status 200
       :headers {"Content-Type" "text/html"}
       :body (oauth-error-page "Site not found")})))

;; =============================================================================
;; Module Definition
;; =============================================================================

(def module
  {:api-routes [["/api/decap/:site-id/oauth/auth" {:get oauth-auth}]
                ["/api/decap/:site-id/oauth/callback" {:get oauth-callback}]]})
