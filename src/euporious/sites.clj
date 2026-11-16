(ns euporious.sites
  "Host-based multi-site support.

  Every site served by this process is described by one map, defined next to
  its feature code (e.g. `euporious.tv-archiv/site`) and listed in
  `euporious/sites`:

    {:id        :tv-archiv                 ; keyword, unique
     :hosts     #{\"tobys-archiv.de\"}     ; exact Host matches (no port), prod
     :dev-hosts #{\"tv.localhost\"}        ; only honoured when :euporious/dev-hosts? is true
     :modules   [home/module ...]          ; Biff modules whose :routes/:api-routes this site serves
     :name      \"Familienvideos\"         ; optional: shown in sign-in emails (default settings/app-name)
     :auth      {:emails #{\"a@b.c\"}}     ; optional: email sign-in, restricted to this allowlist
                                           ;   (see auth-module for :recaptcha?, :code-minutes)
     :email-templates {:signin-code f}     ; optional: per-site mail templates (euporious.email)
     :on-error  (fn [ctx] response)        ; optional: own 404/500 pages (default ui/on-error)
     :headers   {\"X-Robots-Tag\" \"noindex\"}} ; optional: added to every response of the site

  Everything else is derived from that list: host lookup, one Reitit handler
  per site, the per-site Biff auth module, and the global module list Biff uses
  for schema, tasks, queues and :on-tx."
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [com.biffweb :as biff]
            [com.biffweb.experimental.auth :as biff-auth]
            [euporious.auth :as auth]
            [euporious.middleware :as mid]
            [reitit.ring.coercion :as coercion]))

(defn strip-port [host]
  (some-> host (str/replace #":\d+$" "") str/lower-case))

(defn host-index
  "Map of host -> site. Dev hosts are only included when `dev?`. Throws if two
  sites claim the same host."
  [sites dev?]
  (reduce (fn [index {:keys [id hosts dev-hosts] :as site}]
            (reduce (fn [index host]
                      (when-some [other (get index host)]
                        (throw (ex-info (str "Host " host " claimed by both " (:id other) " and " id)
                                        {:host host})))
                      (assoc index host site))
                    index
                    (concat hosts (when dev? dev-hosts))))
          {}
          sites))

(defn parse-emails
  "Comma/whitespace separated addresses (a config.env value) -> lower-case set."
  [s]
  (into #{}
        (comp (map str/trim) (remove str/blank?) (map str/lower-case))
        (str/split (or s "") #"[,\s]+")))

(defn allowed-emails
  "`emails` is either a set, or a config key whose value is a comma-separated
  list (config.env is only read at startup: changing it needs a service restart,
  not a code change)."
  [ctx emails]
  (if (keyword? emails)
    (parse-emails (get ctx emails))
    emails))

(defn auth-module
  "Biff's email auth module for one site; only allowlisted emails can sign in.

  Site-level options next to the biff.auth ones:
    :emails        set of addresses, or a config key (see `allowed-emails`)
    :recaptcha?    false to skip reCAPTCHA on this site (default true)
    :code-minutes  lifetime of the sign-in code (Biff's default is 3)"
  [{:keys [emails recaptcha? code-minutes] :or {recaptcha? true} :as auth}]
  (let [module (biff-auth/module
                (merge (dissoc auth :emails :recaptcha? :code-minutes)
                       {:euporious.auth/code-minutes (or code-minutes 3)
                        :biff.auth/email-validator
                        (fn [ctx email]
                          (and (biff-auth/email-valid? ctx email)
                               (contains? (allowed-emails ctx emails) email)))}))]
    (cond-> module
      code-minutes      (update :routes auth/replace-verify-code code-minutes)
      (not recaptcha?)  (update :routes (fn [routes]
                                          [["" {:middleware [auth/wrap-no-recaptcha]}
                                            routes]])))))

(defn site-modules
  "The modules whose routes a site serves (including its auth module)."
  [{:keys [modules auth]}]
  (cond-> (vec modules)
    auth (conj (auth-module auth))))

(def ^:private biff-auth-schema-module
  (select-keys (biff-auth/module {}) [:schema]))

(defn all-modules
  "The global module list for Biff (schema, tasks, queues, :on-tx).
  `base-modules` are modules without routes that every deployment needs.
  The per-site auth modules all carry the same schema, so a single
  allowlist-less instance stands in for them here; its routes are never served."
  [base-modules sites]
  (vec (distinct (concat base-modules
                         (when (some :auth sites)
                           [biff-auth-schema-module])
                         (mapcat :modules sites)))))

(defn wrap-site-headers [handler {:keys [headers]}]
  (if (empty? headers)
    handler
    (fn [req]
      (some-> (handler req) (update :headers merge headers)))))

(defn wrap-site-error
  "With a site :on-error, exceptions render the site's own error page (the
  global biff on-error in wrap-base-defaults would use the shared ui frame)."
  [handler {:keys [on-error]}]
  (if-not on-error
    handler
    (fn [req]
      (try
        (handler req)
        (catch Throwable t
          (log/error t "Exception while handling request")
          (on-error (assoc req :status 500 :ex t)))))))

(defn site-routes [site]
  (let [modules (site-modules site)]
    [["" {:middleware [mid/wrap-site-defaults
                       coercion/coerce-request-middleware
                       coercion/coerce-response-middleware]}
      (keep :routes modules)]
     ["" {:middleware [mid/wrap-api-defaults]}
      (keep :api-routes modules)]]))

(defn site-handlers
  "Map of site id -> compiled Ring handler."
  [sites]
  (into {}
        (for [site sites]
          [(:id site) (-> (biff/reitit-handler {:routes   (site-routes site)
                                                :on-error (:on-error site)})
                          (wrap-site-error site)
                          (wrap-site-headers site))])))

(def not-found
  {:status 404
   :headers {"content-type" "text/plain; charset=utf-8"}
   :body "Not found"})

(defn handler
  "Dispatches on the Host header. Unknown hosts get a plain 404.

  Also sets :biff/base-url to the requested site, so absolute URLs (e.g. the
  sign-in links Biff puts into emails) point back to the site the user is on.
  This is only safe because the host has to be a registered one."
  [sites]
  (let [handlers (site-handlers sites)
        index    {true  (host-index sites true)
                  false (host-index sites false)}]
    (fn [{:keys [euporious/dev-hosts? scheme headers] :as req}]
      (let [raw-host (get headers "host")
            site     (get-in index [(boolean dev-hosts?) (strip-port raw-host)])]
        (if-not site
          not-found
          ((handlers (:id site))
           (assoc req
                  :site site
                  :biff/base-url (str (name scheme) "://" (str/lower-case raw-host)))))))))
