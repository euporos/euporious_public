(ns euporious.email
  (:require [euporious.settings :as settings]
            [clojure.tools.logging :as log]
            [rum.core :as rum])
  (:import [jakarta.mail Authenticator Message$RecipientType PasswordAuthentication
            Session Transport]
           [jakarta.mail.internet InternetAddress MimeBodyPart MimeMessage MimeMultipart]
           [java.util Properties]))

(defn signin-link [{:keys [to url user-exists]}]
  (let [[subject action action-infinitive] (if user-exists
                           [(str "Anmelden bei " settings/app-name) "anmelden" "anzumelden"]
                           [(str "Registrieren für " settings/app-name) "registrieren" "zu registrieren"])]
    {:to to
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
    {:to to
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

(defn incoming-secret [{:keys [to url label]}]
  {:to to
   :subject (str "Neues eingegangenes Secret" (when label (str ": " label)))
   :html (rum/render-static-markup
          [:html
           [:body
            [:p "Es wurde ein neues One-Time Secret an Sie übermittelt"
             (when label [:span " (Label: " label ")"]) "."]
            [:p [:a {:href url :target "_blank"} "Hier klicken, um das Secret einmalig anzuzeigen."]]
            [:p "Sie müssen bei " settings/app-name " angemeldet sein, um das Secret abzurufen. "
             "Der Link wird nach einmaligem Aufruf zerstört und läuft spätestens in 2 Stunden ab."]]])
   :text (str "Es wurde ein neues One-Time Secret an Sie übermittelt"
              (when label (str " (Label: " label ")")) ".\n"
              "\n"
              url "\n"
              "\n"
              "Sie müssen bei " settings/app-name " angemeldet sein, um das Secret abzurufen. "
              "Der Link wird nach einmaligem Aufruf zerstört und läuft spätestens in 2 Stunden ab.")})

(defn template
  "A site can override templates with :email-templates {template-key (fn [opts] …)}
  in its site map (the family site has its own sign-in mails)."
  [ctx k opts]
  ((or (get-in ctx [:site :email-templates k])
       (case k
         :signin-link signin-link
         :signin-code signin-code
         :incoming-secret incoming-secret))
   opts))

(defn smtp-configured?
  "True once enough SMTP settings are present to actually send. The password is
   a #biff/secret, so it must be resolved through the `secret` fn."
  [{:keys [smtp/host smtp/user biff/secret]}]
  (every? some? [host user (secret :smtp/pass)]))

(defn- smtp-session
  "jakarta.mail Session for STARTTLS (default, port 587) or implicit TLS
   (:smtp/ssl true, port 465), authenticating as the configured user.
   jakarta.mail (Angus) on purpose, same as euporious2: never add postal /
   com.sun.mail next to it -- two providers collide on
   META-INF/javamail.default.providers in the uberjar (NoSuchProviderException: smtp)."
  [{:keys [smtp/host smtp/port smtp/user smtp/tls smtp/ssl biff/secret]}]
  (let [pass  (secret :smtp/pass)
        props (doto (Properties.)
                (.put "mail.smtp.host" host)
                (.put "mail.smtp.port" (str port))
                (.put "mail.smtp.auth" "true")
                (.put "mail.smtp.connectiontimeout" "15000")
                (.put "mail.smtp.timeout" "15000"))]
    (cond
      ssl (doto props
            (.put "mail.smtp.ssl.enable" "true")
            (.put "mail.smtp.ssl.checkserveridentity" "true"))
      (if (some? tls) tls true)
      (doto props
        (.put "mail.smtp.starttls.enable" "true")
        (.put "mail.smtp.starttls.required" "true")))
    (Session/getInstance
     props
     (proxy [Authenticator] []
       (getPasswordAuthentication []
         (PasswordAuthentication. user pass))))))

(defn send-smtp
  "Send one message over SMTP through the own mail account. Returns true on
   success; on any failure logs and returns false, so the auth flow shows a
   clean `send-failed` message instead of a 500."
  [{:keys [smtp/user smtp/from site] :as ctx} {:keys [to subject html text]}]
  (try
    (let [sender    (:name site settings/app-name)
          session   (smtp-session ctx)
          text-part (doto (MimeBodyPart.) (.setText text "utf-8"))
          body      (doto (MimeMultipart. "alternative") (.addBodyPart text-part))
          _         (when html
                      (.addBodyPart body (doto (MimeBodyPart.)
                                           (.setContent html "text/html; charset=utf-8"))))
          msg       (doto (MimeMessage. session)
                      (.setFrom (InternetAddress. (or from user) sender "utf-8"))
                      (.setRecipients Message$RecipientType/TO (InternetAddress/parse to))
                      (.setSubject subject "utf-8")
                      (.setContent body))]
      (Transport/send msg)
      true)
    (catch Exception e
      (log/error e "SMTP send failed for" to)
      false)))

(defn send-console [_ctx form-params]
  (println "AN:" (:to form-params))
  (println "BETREFF:" (:subject form-params))
  (println)
  (println (:text form-params))
  (println)
  (println "Um E-Mails zu versenden, anstatt sie in der Konsole auszugeben, fügen Sie Ihre"
           "SMTP-Zugangsdaten (SMTP_HOST/USER/PASS) und reCAPTCHA-Schlüssel zu config.env hinzu.")
  true)

(defn send-email [{:keys [biff/secret recaptcha/site-key] :as ctx} opts]
  (let [form-params (if-some [template-key (:template opts)]
                      (template ctx template-key opts)
                      opts)]
    ;; Real mail needs SMTP plus reCAPTCHA configured (Biff's dev
    ;; heuristic) — or a site that opted out of reCAPTCHA: its auth routes see no
    ;; recaptcha secret on purpose (euporious.auth/wrap-no-recaptcha).
    (if (and (smtp-configured? ctx)
             (or (:euporious.auth/no-recaptcha ctx)
                 (every? some? [(secret :recaptcha/secret-key) site-key])))
      (send-smtp ctx form-params)
      (send-console ctx form-params))))
