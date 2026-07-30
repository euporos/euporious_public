(ns euporious.middleware
  (:require [clojure.string :as str]
            [com.biffweb :as biff]
            [muuntaja.middleware :as muuntaja]
            [ring.middleware.anti-forgery :as csrf]
            [ring.middleware.defaults :as rd]
            [ring.middleware.multipart-params.byte-array :as mp-bytes]
            [rum.core :as rum]))

(defn wrap-redirect-signed-in [handler]
  (fn [{:keys [session] :as ctx}]
    (if (some? (:uid session))
      {:status 303
       :headers {"location" "/app"}}
      (handler ctx))))

(defn wrap-signed-in [handler]
  (fn [{:keys [session] :as ctx}]
    (if (some? (:uid session))
      (handler ctx)
      {:status 303
       :headers {"location" "/signin?error=not-signed-in"}})))

;; Stick this function somewhere in your middleware stack below if you want to
;; inspect what things look like before/after certain middleware fns run.
(defn wrap-debug [handler]
  (fn [ctx]
    (let [response (handler ctx)]
      (println "REQUEST")
      (biff/pprint ctx)
      (def ctx* ctx)
      (println "RESPONSE")
      (biff/pprint response)
      (def response* response)
      response)))

(def max-upload-bytes (* 20 1024 1024))

;; Runs outside biff/wrap-render-rum (wrap-defaults is the outermost wrapper),
;; so the body must be fully rendered HTML, not a rum vector.
(defn file-too-large-handler
  ([_req]
   {:status 413
    :headers {"content-type" "text/html; charset=utf-8"}
    :body (rum/render-static-markup
           [:html
            [:body
             [:div {:style {:max-width "36rem" :margin "4rem auto" :font-family "sans-serif"}}
              [:h1 {:style {:font-size "1.25rem" :font-weight "bold"}}
               "File too large"]
              [:p "The uploaded file exceeds the 20 MiB limit."]
              [:p [:a {:href "javascript:history.back()"} "Go back"]]]]])})
  ([req respond _raise]
   (respond (file-too-large-handler req))))

(defn wrap-site-defaults [handler]
  (-> handler
      biff/wrap-render-rum
      biff/wrap-anti-forgery-websockets
      csrf/wrap-anti-forgery
      biff/wrap-session
      muuntaja/wrap-params
      muuntaja/wrap-format
      (rd/wrap-defaults (-> rd/site-defaults
                            (assoc-in [:security :anti-forgery] false)
                            (assoc-in [:responses :absolute-redirects] true)
                            (assoc :session false)
                            (assoc :static false)
                            ;; Uploads stay in memory (never touch disk) and are
                            ;; hard-capped; the OTS forms are the only multipart users.
                            (assoc-in [:params :multipart]
                                      {:store (mp-bytes/byte-array-store)
                                       :max-file-size max-upload-bytes
                                       :max-file-count 5
                                       :error-handler file-too-large-handler})))))

(defn wrap-api-defaults [handler]
  (-> handler
      muuntaja/wrap-params
      muuntaja/wrap-format
      (rd/wrap-defaults rd/api-defaults)))

(defn wrap-base-defaults [handler]
  (-> handler
      biff/wrap-https-scheme
      biff/wrap-resource
      biff/wrap-internal-error
      biff/wrap-ssl
      biff/wrap-log-requests))

(defn determine-site
  "Determines which site to serve based on the Host header.
  Returns :tv-archiv or :secrets."
  [host]
  (cond
    ;; Production domains
    (str/includes? host "tobys-archiv") :tv-archiv
    (str/includes? host "ots") :secrets
    ;; Development domains (tv.localhost, ots.localhost)
    (str/starts-with? host "tv.") :tv-archiv
    (str/starts-with? host "ots.") :secrets
    ;; Default fallback - shouldn't happen in normal operation
    :else :tv-archiv))

(defn wrap-site-context
  "Middleware that adds :site key to the request context based on Host header."
  [handler]
  (fn [req]
    (let [host (get-in req [:headers "host"] "")
          site (determine-site host)
          req-with-site (assoc req :site site)]
      (handler req-with-site))))
