(ns euporious.secrets
  (:require [clojure.string :as str]
            [com.biffweb :as biff :refer [q]]
            [euporious.email :as email]
            [euporious.middleware :as mid]
            [euporious.ui :as ui]
            [reitit.core :as reitit]
            [rum.core :as rum]
            [xtdb.api :as xt])
  (:import [java.util UUID]))

;; In-memory atoms to store secrets and inbound requests ephemerally.
;; secrets:         secret-id  -> {:value :created-at :expires-at :admin-only?}
;; secret-requests: request-id -> {:label :created-at :expires-at}
(defonce secrets (atom {}))
(defonce secret-requests (atom {}))

(def ^:private admin-email "services@olivermotz.com")

(defn- base-url [ctx]
  (str (name (:scheme ctx))
       "://"
       (:server-name ctx)
       (when-not (contains? #{443 80} (:server-port ctx))
         (str ":" (:server-port ctx)))))

(defn- not-found-page []
  (ui/page
   {}
   [:div.max-w-2xl.mx-auto.p-6
    [:h1.text-3xl.font-bold.mb-6 "Secret Not Found"]
    [:div.alert.alert-error
     [:span "This secret does not exist or has already been viewed."]]]))

(defn- expired-page [secret-id]
  (swap! secrets dissoc secret-id)
  (ui/page
   {}
   [:div.max-w-2xl.mx-auto.p-6
    [:h1.text-3xl.font-bold.mb-6 "Secret Expired"]
    [:div.alert.alert-error
     [:span "This secret has expired and is no longer available."]]]))

(defn secrets-home [{:keys [session] :as ctx}]
  (let [signed-in? (some? (:uid session))]
    (ui/page
     (assoc ctx :base/title "Olis OTS")
     [:div.max-w-4xl.mx-auto.p-6
      [:h1.text-4xl.font-bold.mb-4 "One-Time Secrets"]
      [:p.mb-6.text-lg.text-gray-600
       "Teilen Sie vertrauliche Informationen sicher mit einmalig abrufbaren Links."]
      [:div.space-y-4
       [:div.flex.flex-wrap.gap-3
        [:a.btn.btn-primary.btn-lg {:href "/ots/new"} "Neues Secret erstellen"]
        (when signed-in?
          [:a.btn.btn-secondary.btn-lg {:href "/ots/request/new"} "Neue Anfrage erstellen"])]
       [:div.text-sm.text-gray-500
        [:p "Secrets werden nach 2 Stunden gelöscht"]
        [:p "Jedes Secret kann nur einmal abgerufen werden"]]]])))

(defn new-secret-page [{:keys [session] :as ctx}]
  (ui/page
   {}
   [:div.max-w-2xl.mx-auto.p-6
    [:h1.text-3xl.font-bold.mb-6 "Create One-Time Secret"]
    [:p.mb-4.text-gray-600
     "Create a secret that can be retrieved only once. The secret will expire in 2 hours."]
    (biff/form
     {:action "/ots/new" :method "POST"}
     [:div.form-control.w-full.mb-4
      [:label.label [:span.label-text "Secret"]]
      [:textarea.textarea.textarea-bordered.w-full
       {:name "secret"
        :rows 5
        :required true
        :placeholder "Enter your secret here..."}]]
     [:button.btn.btn-primary {:type "submit"} "Create Secret Link"])]))

(defn create-secret [{:keys [params reitit.core/router] :as ctx}]
  (let [secret-value (:secret params)
        secret-id (str (UUID/randomUUID))
        now (java.time.Instant/now)
        expires-at (.plusSeconds now 7200)] ;; 2 hours
    (swap! secrets assoc secret-id
           {:value secret-value
            :created-at now
            :expires-at expires-at})
    (let [retrieve-path (-> (reitit/match-by-name router ::retrieve-secret {:uuid secret-id})
                            :path)
          secret-link (str (base-url ctx) retrieve-path)]
      (ui/page
       {}
       [:div.max-w-2xl.mx-auto.p-6
        [:h1.text-3xl.font-bold.mb-6 "Secret Created"]
        [:div.alert.alert-success.mb-4
         [:span "Your one-time secret link has been created!"]]
        [:div.form-control.w-full.mb-4
         [:label.label [:span.label-text "Share this link:"]]
         [:input.input.input-bordered.w-full
          {:type "text"
           :readonly true
           :value secret-link
           :onclick "this.select()"}]]
        [:button.btn.btn-secondary.mb-4
         {:onclick "navigator.clipboard.writeText(this.previousElementSibling.querySelector('input').value)"}
         "Copy to Clipboard"]
        [:p.text-sm.text-gray-600.mb-4
         "This link will expire in 2 hours and can only be viewed once."]
        [:a.btn.btn-primary {:href "/ots/new"} "Create Another Secret"]]))))

(defn retrieve-secret-confirmation [{:keys [path-params reitit.core/match reitit.core/router] :as ctx}]
  (let [secret-id (:uuid path-params)
        secret-data (get @secrets secret-id)
        allow-admin-only? (-> match :data :allow-admin-only?)
        reveal-route (-> match :data :reveal-route)]
    (cond
      (nil? secret-data)
      (not-found-page)

      ;; Inbound (admin-only) secrets must only be accessible via the admin-gated
      ;; route. Anything else is treated as a non-existent secret — without
      ;; destroying it — so a path-rewriting interceptor cannot exfiltrate it.
      (and (:admin-only? secret-data) (not allow-admin-only?))
      (not-found-page)

      :else
      (let [now (java.time.Instant/now)
            expires-at (:expires-at secret-data)
            expired? (.isAfter now expires-at)]
        (if expired?
          (expired-page secret-id)
          (let [reveal-path (-> (reitit/match-by-name router reveal-route {:uuid secret-id})
                                :path)]
            (ui/page
             {}
             [:div.max-w-2xl.mx-auto.p-6
              [:h1.text-3xl.font-bold.mb-6 "View Secret"]
              [:div.alert.alert-warning.mb-4
               [:span "Warning: This secret can only be viewed once!"]]
              [:p.mb-6.text-gray-600
               "Once you click the button below, the secret will be revealed and immediately destroyed. "
               "Make sure you're ready to view it."]
              (biff/form
               {:action reveal-path :method "POST"}
               [:button.btn.btn-primary.btn-lg
                {:type "submit"}
                "Reveal Secret"])])))))))

(defn reveal-secret [{:keys [path-params reitit.core/match]}]
  (let [secret-id (:uuid path-params)
        secret-data (get @secrets secret-id)
        allow-admin-only? (-> match :data :allow-admin-only?)]
    (cond
      (nil? secret-data)
      (not-found-page)

      (and (:admin-only? secret-data) (not allow-admin-only?))
      (not-found-page)

      :else
      (let [now (java.time.Instant/now)
            expires-at (:expires-at secret-data)
            expired? (.isAfter now expires-at)]
        (if expired?
          (expired-page secret-id)
          (let [secret-value (:value secret-data)]
            (swap! secrets dissoc secret-id)
            (ui/page
             {}
             [:div.max-w-2xl.mx-auto.p-6
              [:h1.text-3xl.font-bold.mb-6 "Your Secret"]
              [:div.alert.alert-info.mb-4
               [:span "This secret has been destroyed and cannot be viewed again."]]
              [:div.form-control.w-full.mb-4
               [:label.label [:span.label-text "Secret content:"]]
               [:textarea.textarea.textarea-bordered.w-full.font-mono
                {:readonly true
                 :rows 10
                 :value secret-value}]]
              [:button.btn.btn-secondary
               {:onclick "navigator.clipboard.writeText(this.previousElementSibling.querySelector('textarea').value)"}
               "Copy to Clipboard"]])))))))

;; ---------------------------------------------------------------------------
;; Inbound: someone sends a secret TO the admin via a one-time request link.

(defn new-request-page [_ctx]
  (ui/page
   {}
   [:div.max-w-2xl.mx-auto.p-6
    [:h1.text-3xl.font-bold.mb-6 "Anfrage für eingehendes Secret"]
    [:p.mb-4.text-gray-600
     "Erzeugt einen einmalig nutzbaren Link, über den eine andere Person ein "
     "Secret an Sie übermitteln kann. Sie erhalten anschließend eine E-Mail "
     "mit einem geschützten Link zum Anzeigen des Secrets."]
    (biff/form
     {:action "/ots/request/new" :method "POST"}
     [:div.form-control.w-full.mb-4
      [:label.label [:span.label-text "Label (optional)"]]
      [:input.input.input-bordered.w-full
       {:type "text"
        :name "label"
        :placeholder "z. B. Stripe API Key von Acme"}]]
     [:button.btn.btn-primary {:type "submit"} "Anfragelink erstellen"])]))

(defn create-request [{:keys [params reitit.core/router] :as ctx}]
  (let [label (some-> (:label params) str/trim not-empty)
        request-id (str (UUID/randomUUID))
        now (java.time.Instant/now)
        expires-at (.plusSeconds now (* 7 24 3600))] ;; 7 days
    (swap! secret-requests assoc request-id
           {:label label
            :created-at now
            :expires-at expires-at})
    (let [submit-path (-> (reitit/match-by-name router ::submit-request {:uuid request-id})
                          :path)
          submit-link (str (base-url ctx) submit-path)]
      (ui/page
       {}
       [:div.max-w-2xl.mx-auto.p-6
        [:h1.text-3xl.font-bold.mb-6 "Anfrage erstellt"]
        [:div.alert.alert-success.mb-4
         [:span "Senden Sie diesen Link an die Person, die Ihnen ein Secret übermitteln soll."]]
        (when label
          [:p.mb-2 [:strong "Label: "] label])
        [:div.form-control.w-full.mb-4
         [:label.label [:span.label-text "Anfragelink:"]]
         [:input.input.input-bordered.w-full
          {:type "text"
           :readonly true
           :value submit-link
           :onclick "this.select()"}]]
        [:button.btn.btn-secondary.mb-4
         {:onclick "navigator.clipboard.writeText(this.previousElementSibling.querySelector('input').value)"}
         "Copy to Clipboard"]
        [:p.text-sm.text-gray-600.mb-4
         "Der Link ist 7 Tage gültig und kann nur einmal verwendet werden. "
         "Nach Übermittlung erhalten Sie eine E-Mail mit einem geschützten Link "
         "zum einmaligen Anzeigen des Secrets."]
        [:a.btn.btn-primary {:href "/ots/request/new"} "Weitere Anfrage erstellen"]]))))

(defn- request-not-found-page []
  (ui/page
   {}
   [:div.max-w-2xl.mx-auto.p-6
    [:h1.text-3xl.font-bold.mb-6 "Anfrage nicht gefunden"]
    [:div.alert.alert-error
     [:span "Diese Anfrage existiert nicht, ist abgelaufen oder wurde bereits verwendet."]]]))

(defn submit-secret-page [{:keys [path-params]}]
  (let [request-id (:uuid path-params)
        request-data (get @secret-requests request-id)]
    (if (nil? request-data)
      (request-not-found-page)
      (let [now (java.time.Instant/now)
            expired? (.isAfter now (:expires-at request-data))]
        (if expired?
          (do (swap! secret-requests dissoc request-id)
              (request-not-found-page))
          (ui/page
           {}
           [:div.max-w-2xl.mx-auto.p-6
            [:h1.text-3xl.font-bold.mb-6 "Secret übermitteln"]
            (when-let [label (:label request-data)]
              [:p.mb-4 [:strong "Anfrage: "] label])
            [:p.mb-4.text-gray-600
             "Geben Sie Ihr Secret unten ein. Es wird verschlüsselt übertragen "
             "und kann nur einmal vom Empfänger abgerufen werden. Der Empfänger "
             "wird per E-Mail benachrichtigt."]
            (biff/form
             {:action (str "/ots/submit/" request-id) :method "POST"}
             [:div.form-control.w-full.mb-4
              [:label.label [:span.label-text "Secret"]]
              [:textarea.textarea.textarea-bordered.w-full
               {:name "secret"
                :rows 5
                :required true
                :placeholder "Geben Sie hier Ihr Secret ein..."}]]
             [:button.btn.btn-primary {:type "submit"} "Sicher übermitteln"])]))))))

(defn accept-submitted-secret [{:keys [params path-params reitit.core/router] :as ctx}]
  (let [request-id (:uuid path-params)
        request-data (get @secret-requests request-id)]
    (if (nil? request-data)
      (request-not-found-page)
      (let [now (java.time.Instant/now)
            expired? (.isAfter now (:expires-at request-data))]
        (if expired?
          (do (swap! secret-requests dissoc request-id)
              (request-not-found-page))
          (let [secret-value (:secret params)
                secret-id (str (UUID/randomUUID))
                expires-at (.plusSeconds now 7200)
                inbox-path (-> (reitit/match-by-name router ::inbox-retrieve-secret {:uuid secret-id})
                               :path)
                inbox-link (str (base-url ctx) inbox-path)]
            (swap! secrets assoc secret-id
                   {:value secret-value
                    :created-at now
                    :expires-at expires-at
                    :admin-only? true})
            (swap! secret-requests dissoc request-id)
            (email/send-email ctx
                              {:template :incoming-secret
                               :to admin-email
                               :url inbox-link
                               :label (:label request-data)})
            (ui/page
             {}
             [:div.max-w-2xl.mx-auto.p-6
              [:h1.text-3xl.font-bold.mb-6 "Secret übermittelt"]
              [:div.alert.alert-success.mb-4
               [:span "Ihr Secret wurde sicher übermittelt. Der Empfänger wurde per E-Mail benachrichtigt."]]
              [:p.text-sm.text-gray-600
               "Sie können dieses Fenster jetzt schließen."]])))))))

(def module
  {:routes [["/" {:get #'secrets-home}]
            ["/ots" {:middleware [mid/wrap-signed-in]}
             ["/new" {:get #'new-secret-page
                      :post #'create-secret
                      :name ::new-secret}]
             ["/request/new" {:get #'new-request-page
                              :post #'create-request
                              :name ::new-request}]
             ["/inbox/:uuid" {:get #'retrieve-secret-confirmation
                              :reveal-route ::inbox-reveal-secret
                              :allow-admin-only? true
                              :name ::inbox-retrieve-secret}]
             ["/inbox/:uuid/reveal" {:post #'reveal-secret
                                     :allow-admin-only? true
                                     :name ::inbox-reveal-secret}]]
            ["/ots/submit/:uuid" {:get #'submit-secret-page
                                  :post #'accept-submitted-secret
                                  :name ::submit-request}]
            ["/ots/retrieve/:uuid" {:get #'retrieve-secret-confirmation
                                    :reveal-route ::reveal-secret
                                    :name ::retrieve-secret}]
            ["/ots/reveal/:uuid" {:post #'reveal-secret
                                  :name ::reveal-secret}]]})
