(ns euporious.secrets
  (:require [com.biffweb :as biff :refer [q]]
            [euporious.middleware :as mid]
            [euporious.ui :as ui]
            [reitit.core :as reitit]
            [rum.core :as rum]
            [xtdb.api :as xt])
  (:import [java.util UUID]))

;; In-memory atom to store secrets ephemerally
(defonce secrets (atom {}))

(defn secrets-home [{:keys [params] :as ctx}]
  (ui/page
   ctx
   [:div.max-w-4xl.mx-auto.p-6
    [:h1.text-4xl.font-bold.mb-4 "One-Time Secrets"]
    [:p.mb-6.text-lg.text-gray-600
     "Teilen Sie vertrauliche Informationen sicher mit einmalig abrufbaren Links."]
    [:div.space-y-4
     [:a.btn.btn-primary.btn-lg {:href "/ots/new"} "Neues Secret erstellen"]
     [:div.text-sm.text-gray-500
      [:p "Secrets werden nach 2 Stunden gelöscht"]
      [:p "Jedes Secret kann nur einmal abgerufen werden"]]]]))

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
    (let [base-url (str (name (:scheme ctx))
                        "://"
                        (:server-name ctx)
                        (when-not (contains? #{443 80} (:server-port ctx)) (str ":"(:server-port ctx))))
          retrieve-path (-> (reitit/match-by-name router ::retrieve-secret {:uuid secret-id})
                            :path)
          secret-link (str base-url retrieve-path)]
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

(defn retrieve-secret-confirmation [{:keys [path-params] :as ctx}]
  (let [secret-id (:uuid path-params)
        secret-data (get @secrets secret-id)]
    (if secret-data
      (let [now (java.time.Instant/now)
            expires-at (:expires-at secret-data)
            expired? (.isAfter now expires-at)]
        (if expired?
          (do
            (swap! secrets dissoc secret-id)
            (ui/page
             {}
             [:div.max-w-2xl.mx-auto.p-6
              [:h1.text-3xl.font-bold.mb-6 "Secret Expired"]
              [:div.alert.alert-error
               [:span "This secret has expired and is no longer available."]]]))
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
             {:action (str "/ots/reveal/" secret-id) :method "POST"}
             #_(biff/csrf-token)
             [:button.btn.btn-primary.btn-lg
              {:type "submit"}
              "Reveal Secret"])])))
      (ui/page
       {}
       [:div.max-w-2xl.mx-auto.p-6
        [:h1.text-3xl.font-bold.mb-6 "Secret Not Found"]
        [:div.alert.alert-error
         [:span "This secret does not exist or has already been viewed."]]]))))

(defn reveal-secret [{:keys [path-params]}]
  (let [secret-id (:uuid path-params)
        secret-data (get @secrets secret-id)]
    (if secret-data
      (let [now (java.time.Instant/now)
            expires-at (:expires-at secret-data)
            expired? (.isAfter now expires-at)]
        (if expired?
          (do
            (swap! secrets dissoc secret-id)
            (ui/page
             {}
             [:div.max-w-2xl.mx-auto.p-6
              [:h1.text-3xl.font-bold.mb-6 "Secret Expired"]
              [:div.alert.alert-error
               [:span "This secret has expired and is no longer available."]]]))
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
               "Copy to Clipboard"]]))))
      (ui/page
       {}
       [:div.max-w-2xl.mx-auto.p-6
        [:h1.text-3xl.font-bold.mb-6 "Secret Not Found"]
        [:div.alert.alert-error
         [:span "This secret does not exist or has already been viewed."]]]))))

(def module
  {:routes [["/" {:get #'secrets-home}]
            ["/ots" {:middleware [mid/wrap-signed-in]}
             ["/new" {:get #'new-secret-page
                      :post #'create-secret
                      :name ::new-secret}]]
            ["/ots/retrieve/:uuid" {:get #'retrieve-secret-confirmation
                                    :name ::retrieve-secret}]
            ["/ots/reveal/:uuid" {:post #'reveal-secret
                                  :name ::reveal-secret}]]})
