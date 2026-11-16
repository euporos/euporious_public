(ns euporious
  (:require [clojure.test :as test]
            [clojure.tools.logging :as log]
            [clojure.tools.namespace.repl :as tn-repl]
            [com.biffweb :as biff]
            [com.biffweb.experimental :as biffx]
            [euporious.decap-sites :as decap-sites]
            [euporious.email :as email]
            [euporious.middleware :as mid]
            [euporious.schema :as schema]
            [euporious.secrets :as secrets]
            [euporious.sites :as sites]
            [euporious.tv-archiv :as tv-archiv]
            [euporious.ui :as ui]
            [euporious.worker :as worker]
            [malli.core :as malc]
            [malli.registry :as malr]
            [nrepl.cmdline :as nrepl-cmd])
  (:gen-class))

;; Every site this process serves, selected by exact Host match.
;; See euporious.sites for the shape of a site map.
(def sites
  [tv-archiv/site
   secrets/site
   decap-sites/site])

;; Modules without routes that the whole process needs.
(def base-modules
  [schema/module
   worker/module])

;; Global module list: Biff takes schema, tasks, queues and :on-tx from here.
;; Routes are served per site by `handler`, never from this list.
(def modules (sites/all-modules base-modules sites))

(def handler (-> (sites/handler sites)
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
