(ns euporious.email
  (:require [clj-http.client :as http]
            [euporious.settings :as settings]
            [clojure.tools.logging :as log]
            [rum.core :as rum]))

(defn signin-link [{:keys [to url user-exists]}]
  (let [[subject action action-infinitive] (if user-exists
                           [(str "Anmelden bei " settings/app-name) "anmelden" "anzumelden"]
                           [(str "Registrieren für " settings/app-name) "registrieren" "zu registrieren"])]
    {:to [{:email to}]
     :subject subject
     :html (rum/render-static-markup
            [:html
             [:body
              [:p "Wir haben eine Anfrage erhalten, sich " action-infinitive " bei " settings/app-name
               " mit dieser E-Mail-Adresse. Klicken Sie auf diesen Link, um sich " action-infinitive ":"]
              [:p [:a {:href url :target "_blank"} "Hier klicken, um sich " action-infinitive "."]]
              [:p "Dieser Link läuft in einer Stunde ab. "
               "Falls Sie diesen Link nicht angefordert haben, können Sie diese E-Mail ignorieren."]]])
     :text (str "Wir haben eine Anfrage erhalten, sich " action-infinitive " bei " settings/app-name
                " mit dieser E-Mail-Adresse. Klicken Sie auf diesen Link, um sich " action-infinitive ":\n"
                "\n"
                url "\n"
                "\n"
                "Dieser Link läuft in einer Stunde ab. Falls Sie diesen Link nicht angefordert haben, "
                "können Sie diese E-Mail ignorieren.")}))

(defn signin-code [{:keys [to code user-exists]}]
  (let [[subject action action-infinitive] (if user-exists
                           [(str "Anmelden bei " settings/app-name) "anmelden" "anzumelden"]
                           [(str "Registrieren für " settings/app-name) "registrieren" "zu registrieren"])]
    {:to [{:email to}]
     :subject subject
     :html (rum/render-static-markup
            [:html
             [:body
              [:p "Wir haben eine Anfrage erhalten, sich " action-infinitive " bei " settings/app-name
               " mit dieser E-Mail-Adresse. Geben Sie den folgenden Code ein, um sich " action-infinitive ":"]
              [:p {:style {:font-size "2rem"}} code]
              [:p
               "Dieser Code läuft in drei Minuten ab. "
               "Falls Sie diesen Code nicht angefordert haben, können Sie diese E-Mail ignorieren."]]])
     :text (str "Wir haben eine Anfrage erhalten, sich " action-infinitive " bei " settings/app-name
                " mit dieser E-Mail-Adresse. Geben Sie den folgenden Code ein, um sich " action-infinitive ":\n"
                "\n"
                code "\n"
                "\n"
                "Dieser Code läuft in drei Minuten ab. Falls Sie diesen Code nicht angefordert haben, "
                "können Sie diese E-Mail ignorieren.")}))

(defn template [k opts]
  ((case k
     :signin-link signin-link
     :signin-code signin-code)
   opts))

(defn send-mailersend [{:keys [biff/secret mailersend/from mailersend/reply-to]} form-params]
  (let [result (http/post "https://api.mailersend.com/v1/email"
                          {:oauth-token (secret :mailersend/api-key)
                           :content-type :json
                           :throw-exceptions false
                           :as :json
                           :form-params (merge {:from {:email from :name settings/app-name}
                                                :reply_to {:email reply-to :name settings/app-name}}
                                               form-params)})
        success (< (:status result) 400)]
    (when-not success
      (log/error (:body result)))
    success))

(defn send-console [_ctx form-params]
  (println "AN:" (:to form-params))
  (println "BETREFF:" (:subject form-params))
  (println)
  (println (:text form-params))
  (println)
  (println "Um E-Mails zu versenden, anstatt sie in der Konsole auszugeben, fügen Sie Ihre"
           "API-Schlüssel für MailerSend und Recaptcha zu config.env hinzu.")
  true)

(defn send-email [{:keys [biff/secret recaptcha/site-key] :as ctx} opts]
  (let [form-params (if-some [template-key (:template opts)]
                      (template template-key opts)
                      opts)]
    (if (every? some? [(secret :mailersend/api-key)
                       (secret :recaptcha/secret-key)
                       site-key])
      (send-mailersend ctx form-params)
      (send-console ctx form-params))))
