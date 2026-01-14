(ns euporious.home
  (:require [com.biffweb :as biff]
            [euporious.middleware :as mid]
            [euporious.ui :as ui]
            [euporious.settings :as settings]))

(def email-disabled-notice
  [:.alert.alert-info.mt-3
   [:span "Bis Sie API-Schlüssel für MailerSend und reCAPTCHA hinzufügen, wird Ihr Anmeldelink "
    "in der Konsole ausgegeben. Siehe config.edn."]])

(defn home-page [{:keys [recaptcha/site-key params] :as ctx}]
  (ui/page
   (assoc ctx ::ui/recaptcha true)
   (biff/form
    {:action "/auth/send-link"
     :id "signup"
     :hidden {:on-error "/"}}
    (biff/recaptcha-callback "submitSignup" "signup")
    [:h2.text-2xl.font-bold (str "Registrieren für " settings/app-name)]
    [:.h-3]
    [:.flex
     [:input#email.input.input-bordered {:name "email"
                                         :type "email"
                                         :autocomplete "email"
                                         :placeholder "E-Mail-Adresse eingeben"}]
     [:.w-3]
     [:button.btn.btn-primary.g-recaptcha
      (merge (when site-key
               {:data-sitekey site-key
                :data-callback "submitSignup"})
             {:type "submit"})
      "Registrieren"]]
    (when-some [error (:error params)]
      [:<>
       [:.h-1]
       [:.alert.alert-error
        [:span
         (case error
           "recaptcha" (str "Der reCAPTCHA-Test ist fehlgeschlagen. Versuchen Sie es erneut "
                            "und stellen Sie sicher, dass Sie keine Skripte von Google blockieren.")
           "invalid-email" "Ungültige E-Mail-Adresse. Versuchen Sie es mit einer anderen Adresse."
           "send-failed" (str "Wir konnten keine E-Mail an diese Adresse senden. "
                              "Falls das Problem weiterhin besteht, versuchen Sie eine andere Adresse.")
           "Es ist ein Fehler aufgetreten.")]]])
    [:.h-1]
    [:.text-sm "Haben Sie bereits ein Konto? " [:a.link {:href "/signin"} "Anmelden"] "."]
    [:.h-3]
    biff/recaptcha-disclosure
    email-disabled-notice)))

(defn link-sent [{:keys [params] :as ctx}]
  (ui/page
   ctx
   [:h2.text-xl.font-bold "Prüfen Sie Ihren Posteingang"]
   [:p "Wir haben einen Anmeldelink an " [:span.font-bold (:email params)] " gesendet."]))

(defn verify-email-page [{:keys [params] :as ctx}]
  (ui/page
   ctx
   [:h2.text-2xl.font-bold (str "Registrieren für " settings/app-name)]
   [:.h-3]
   (biff/form
    {:action "/auth/verify-link"
     :hidden {:token (:token params)}}
    [:div [:label {:for "email"}
           "Es sieht so aus, als hätten Sie diesen Link auf einem anderen Gerät oder Browser geöffnet als dem, "
           "auf dem Sie sich registriert haben. Zur Verifizierung geben Sie bitte die E-Mail-Adresse ein, mit der Sie sich registriert haben:"]]
    [:.h-3]
    [:.flex
     [:input#email.input.input-bordered {:name "email" :type "email"
                                         :placeholder "E-Mail-Adresse eingeben"}]
     [:.w-3]
     [:button.btn.btn-primary {:type "submit"}
      "Anmelden"]])
   (when-some [error (:error params)]
     [:<>
       [:.h-1]
       [:.alert.alert-error
        [:span
         (case error
           "incorrect-email" "Falsche E-Mail-Adresse. Versuchen Sie es erneut."
           "Es ist ein Fehler aufgetreten.")]]])))

(defn signin-page [{:keys [recaptcha/site-key params] :as ctx}]
  (ui/page
   (assoc ctx ::ui/recaptcha true)
   (biff/form
    {:action "/auth/send-code"
     :id "signin"
     :hidden {:on-error "/signin"}}
    (biff/recaptcha-callback "submitSignin" "signin")
    [:h2.text-2xl.font-bold "Anmelden bei " settings/app-name]
    [:.h-3]
    [:.flex
     [:input#email.input.input-bordered {:name "email"
                                         :type "email"
                                         :autocomplete "email"
                                         :placeholder "E-Mail-Adresse eingeben"}]
     [:.w-3]
     [:button.btn.btn-primary.g-recaptcha
      (merge (when site-key
               {:data-sitekey site-key
                :data-callback "submitSignin"})
             {:type "submit"})
      "Anmelden"]]
    (when-some [error (:error params)]
      [:<>
       [:.h-1]
       [:.alert.alert-error
        [:span
         (case error
           "recaptcha" (str "Der reCAPTCHA-Test ist fehlgeschlagen. Versuchen Sie es erneut "
                            "und stellen Sie sicher, dass Sie keine Skripte von Google blockieren.")
           "invalid-email" "Ungültige E-Mail-Adresse. Versuchen Sie es mit einer anderen Adresse."
           "send-failed" (str "Wir konnten keine E-Mail an diese Adresse senden. "
                              "Falls das Problem weiterhin besteht, versuchen Sie eine andere Adresse.")
           "invalid-link" "Ungültiger oder abgelaufener Link. Melden Sie sich an, um einen neuen Link zu erhalten."
           "not-signed-in" "Sie müssen angemeldet sein, um diese Seite anzusehen."
           "Es ist ein Fehler aufgetreten.")]]])
    [:.h-1]
    [:.text-sm "Haben Sie noch kein Konto? " [:a.link {:href "/"} "Registrieren"] "."]
    [:.h-3]
    biff/recaptcha-disclosure
    email-disabled-notice)))

(defn enter-code-page [{:keys [recaptcha/site-key params] :as ctx}]
  (ui/page
   (assoc ctx ::ui/recaptcha true)
   (biff/form
    {:action "/auth/verify-code"
     :id "code-form"
     :hidden {:email (:email params)}}
    (biff/recaptcha-callback "submitCode" "code-form")
    [:div [:label {:for "code"} "Geben Sie den 6-stelligen Code ein, den wir an "
           [:span.font-bold (:email params)] " gesendet haben"]]
    [:.h-1]
    [:.flex
     [:input#code.input.input-bordered {:name "code" :type "text"}]
     [:.w-3]
     [:button.btn.btn-primary.g-recaptcha
      (merge (when site-key
               {:data-sitekey site-key
                :data-callback "submitCode"})
             {:type "submit"})
      "Anmelden"]])
   (when-some [error (:error params)]
     [:<>
       [:.h-1]
       [:.alert.alert-error
        [:span
         (case error
           "invalid-code" "Ungültiger Code."
           "Es ist ein Fehler aufgetreten.")]]])
   [:.h-3]
   (biff/form
    {:action "/auth/send-code"
     :id "signin"
     :hidden {:email (:email params)
              :on-error "/signin"}}
    (biff/recaptcha-callback "submitSignin" "signin")
    [:button.link.g-recaptcha
     (merge (when site-key
              {:data-sitekey site-key
               :data-callback "submitSignin"})
            {:type "submit"})
     "Weiteren Code senden"])))

(def module
  {:routes [["" {:middleware [mid/wrap-redirect-signed-in]}
             ["/"                  {:get home-page}]]
            ["/link-sent"          {:get link-sent}]
            ["/verify-link"        {:get verify-email-page}]
            ["/signin"             {:get signin-page}]
            ["/verify-code"        {:get enter-code-page}]]})
