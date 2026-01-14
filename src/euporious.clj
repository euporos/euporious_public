(ns euporious
  (:require [clojure.test :as test]
            [clojure.tools.logging :as log]
            [clojure.tools.namespace.repl :as tn-repl]
            [com.biffweb :as biff]
            [com.biffweb.experimental :as biffx]
            [com.biffweb.experimental.auth :as biff-auth]
            [euporious.app :as app]
            [euporious.email :as email]
            [euporious.home :as home]
            [euporious.legal :as legal]
            [euporious.middleware :as mid]
            [euporious.schema :as schema]
            [euporious.secrets :as secrets]
            [euporious.tv-archiv :as tv-archiv]
            [euporious.ui :as ui]
            [euporious.worker :as worker]
            [malli.core :as malc]
            [malli.registry :as malr]
            [nrepl.cmdline :as nrepl-cmd]
            [reitit.ring.coercion :as coercion])
  (:gen-class))

(def modules
  [app/module
   (biff-auth/module {:biff.auth/email-validator
                      (fn [ctx email]
                        (and
                         (biff-auth/email-valid? ctx email)
                         (contains? #{"services@olivermotz.com"} email)))})
   home/module
   legal/module
   secrets/module
   tv-archiv/module
   schema/module
   worker/module])

;; Shared modules available on all sites
(def shared-modules
  [legal/module
   schema/module])

;; Site-specific modules (with shared modules already concatenated)
(def site-modules
  {:tv-archiv (concat shared-modules
                      [home/module
                       tv-archiv/module])
   :secrets   (concat shared-modules
                      [home/module
                       secrets/module
                       app/module
                       (biff-auth/module {:biff.auth/email-validator
                                          (fn [ctx email]
                                            (and
                                             (biff-auth/email-valid? ctx email)
                                             (contains? #{"services@olivermotz.com"} email)))})])})

(defn site-routes
  "Generates routes filtered by the site context."
  [site]
  (let [modules (get site-modules site [])]
    [["" {:middleware [mid/wrap-site-defaults
                       coercion/coerce-request-middleware
                       coercion/coerce-response-middleware]}
      (keep :routes modules)]
     ["" {:middleware [mid/wrap-api-defaults]}
      (keep :api-routes modules)]]))

(defn site-aware-handler
  "Handler that routes based on the :site key in the request."
  [req]
  (let [site (:site req :tv-archiv)  ; Default to tv-archiv if :site is missing
        routes (site-routes site)
        handler (biff/reitit-handler {:routes routes})]
    (handler req)))

(def handler (-> site-aware-handler
                 mid/wrap-site-context
                 mid/wrap-base-defaults))

(def static-pages (apply biff/safe-merge (map :static modules)))

(defn generate-assets! [_ctx]
  (biff/export-rum static-pages "target/resources/public")
  (biff/delete-old-files {:dir "target/resources/public"
                          :exts [".html"]}))

(defn on-save [ctx]
  (biff/add-libs ctx)
  (biff/eval-files! ctx)
  (generate-assets! ctx)
  (test/run-all-tests #"euporious.*-test"))

(def malli-opts
  {:registry (malr/composite-registry
              malc/default-registry
              (apply biff/safe-merge (keep :schema modules)))})

(def initial-system
  {:biff/modules #'modules
   :biff/send-email #'email/send-email
   :biff/handler #'handler
   :biff/malli-opts #'malli-opts
   :biff.beholder/on-save #'on-save
   :biff.middleware/on-error #'ui/on-error
   :biff.xtdb.listener/tables ["user" "msg"]
   :euporious/chat-clients (atom #{})})

(defonce system (atom {}))

(def components
  [biff/use-aero-config
   biffx/use-xtdb2
   biff/use-queues
   biffx/use-xtdb2-listener
   biff/use-htmx-refresh
   biff/use-jetty
   biff/use-chime
   biff/use-beholder])

(defn start []
  (let [new-system (reduce (fn [system component]
                             (log/info "starting:" (str component))
                             (component system))
                           initial-system
                           components)]
    (reset! system new-system)
    (generate-assets! new-system)
    (log/info "System started.")
    (log/info "Go to" (:biff/base-url new-system))
    new-system))

(defn -main []
  (java.util.TimeZone/setDefault (java.util.TimeZone/getTimeZone "UTC"))
  (let [{:keys [biff.nrepl/args]} (start)]
    (apply nrepl-cmd/-main args)))

(defn refresh []
  (doseq [f (:biff/stop @system)]
    (log/info "stopping:" (str f))
    (f))
  (tn-repl/refresh :after `start)
  :done)
