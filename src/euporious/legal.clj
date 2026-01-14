(ns euporious.legal
  (:require [euporious.ui :as ui]))

(defn impressum-page [ctx]
  (ui/page
   (assoc ctx :euporious.ui/noindex true)
   [:div.legal-page
    [:h1.text-3xl.font-bold.mb-6 "Impressum"]

    [:div.space-y-4.text-gray-200
     [:section
      [:h2.text-xl.font-semibold.mb-2 "Angaben gemäß § 5 TMG"]
      [:p "Oliver Motz"]
      [:p "Hummelblumenstraße 21"]
      [:p "80995 München"]]

     [:section
      [:h2.text-xl.font-semibold.mb-2 "Kontakt"]
      [:p "E-Mail: "
       [:a.text-blue-600.hover:text-blue-800.hover:underline
        {:href "mailto:services@olivermotz.com"}
        "services@olivermotz.com"]]]

     [:section
      [:h2.text-xl.font-semibold.mb-2 "Haftungsausschluss"]
      [:h3.text-lg.font-semibold.mt-4.mb-2 "Haftung für Inhalte"]
      [:p "Die Inhalte unserer Seiten wurden mit größter Sorgfalt erstellt. Für die Richtigkeit, Vollständigkeit und Aktualität der Inhalte können wir jedoch keine Gewähr übernehmen. Als Diensteanbieter sind wir gemäß § 7 Abs.1 TMG für eigene Inhalte auf diesen Seiten nach den allgemeinen Gesetzen verantwortlich. Nach §§ 8 bis 10 TMG sind wir als Diensteanbieter jedoch nicht verpflichtet, übermittelte oder gespeicherte fremde Informationen zu überwachen oder nach Umständen zu forschen, die auf eine rechtswidrige Tätigkeit hinweisen. Verpflichtungen zur Entfernung oder Sperrung der Nutzung von Informationen nach den allgemeinen Gesetzen bleiben hiervon unberührt. Eine diesbezügliche Haftung ist jedoch erst ab dem Zeitpunkt der Kenntnis einer konkreten Rechtsverletzung möglich. Bei Bekanntwerden von entsprechenden Rechtsverletzungen werden wir diese Inhalte umgehend entfernen."]]

     [:section
      [:h3.text-lg.font-semibold.mt-4.mb-2 "Haftung für Links"]
      [:p "Unser Angebot enthält Links zu externen Webseiten Dritter, auf deren Inhalte wir keinen Einfluss haben. Deshalb können wir für diese fremden Inhalte auch keine Gewähr übernehmen. Für die Inhalte der verlinkten Seiten ist stets der jeweilige Anbieter oder Betreiber der Seiten verantwortlich. Die verlinkten Seiten wurden zum Zeitpunkt der Verlinkung auf mögliche Rechtsverstöße überprüft. Rechtswidrige Inhalte waren zum Zeitpunkt der Verlinkung nicht erkennbar. Eine permanente inhaltliche Kontrolle der verlinkten Seiten ist jedoch ohne konkrete Anhaltspunkte einer Rechtsverletzung nicht zumutbar. Bei Bekanntwerden von Rechtsverletzungen werden wir derartige Links umgehend entfernen."]]

     [:section
      [:h3.text-lg.font-semibold.mt-4.mb-2 "Urheberrecht"]
      [:p "Die durch die Seitenbetreiber erstellten Inhalte und Werke auf diesen Seiten unterliegen dem deutschen Urheberrecht. Die Vervielfältigung, Bearbeitung, Verbreitung und jede Art der Verwertung außerhalb der Grenzen des Urheberrechtes bedürfen der schriftlichen Zustimmung des jeweiligen Autors bzw. Erstellers. Downloads und Kopien dieser Seite sind nur für den privaten, nicht kommerziellen Gebrauch gestattet. Soweit die Inhalte auf dieser Seite nicht vom Betreiber erstellt wurden, werden die Urheberrechte Dritter beachtet. Insbesondere werden Inhalte Dritter als solche gekennzeichnet. Sollten Sie trotzdem auf eine Urheberrechtsverletzung aufmerksam werden, bitten wir um einen entsprechenden Hinweis. Bei Bekanntwerden von Rechtsverletzungen werden wir derartige Inhalte umgehend entfernen."]]]]))

(defn datenschutz-page [ctx]
  (ui/page
   (assoc ctx :euporious.ui/noindex true)
   [:div.legal-page
    [:h1.text-3xl.font-bold.mb-6 "Datenschutzerklärung"]

    [:div.space-y-6.text-gray-200
     [:section
      [:h2.text-2xl.font-semibold.mb-3 "1. Datenschutz auf einen Blick"]
      [:h3.text-lg.font-semibold.mb-2 "Allgemeine Hinweise"]
      [:p "Diese Website erhebt grundsätzlich keine personenbezogenen Daten. Es werden keine Cookies gesetzt, keine Tracking-Tools eingesetzt und keine Formulare zur Datenerfassung verwendet (mit Ausnahme der optionalen E-Mail-Kontaktaufnahme)."]]

     [:section
      [:h2.text-2xl.font-semibold.mb-3 "2. Hosting und Server-Logs"]
      [:p.mb-2 "Diese Website wird bei einem externen Dienstleister gehostet. Der Hoster erhebt in sogenannten Server-Log-Dateien automatisch Informationen, die Ihr Browser übermittelt. Dies sind:"]
      [:ul.list-disc.list-inside.ml-4.space-y-1
       [:li "Browsertyp und Browserversion"]
       [:li "Verwendetes Betriebssystem"]
       [:li "Referrer URL (die zuvor besuchte Seite)"]
       [:li "Hostname des zugreifenden Rechners (IP-Adresse)"]
       [:li "Uhrzeit der Serveranfrage"]]
      [:p.mt-2 "Diese Daten sind nicht bestimmten Personen zuordenbar. Eine Zusammenführung dieser Daten mit anderen Datenquellen wird nicht vorgenommen. Die Daten werden nach einer statistischen Auswertung gelöscht."]]

     [:section
      [:h2.text-2xl.font-semibold.mb-3 "3. Datenerfassung auf dieser Website"]
      [:h3.text-lg.font-semibold.mb-2 "Cookies"]
      [:p.mb-4 "Diese Website verwendet technische Session-Cookies ausschließlich für die Funktionsfähigkeit der Webanwendung. Diese Cookies enthalten keine personenbezogenen Daten und dienen nicht dem Tracking. Die Cookies werden automatisch gelöscht, wenn Sie Ihren Browser schließen."]

      [:h3.text-lg.font-semibold.mb-2 "Kontaktaufnahme"]
      [:p "Wenn Sie uns per E-Mail kontaktieren, wird Ihre Anfrage inklusive aller daraus hervorgehenden personenbezogenen Daten (Name, Anfrage) zum Zwecke der Bearbeitung Ihres Anliegens bei uns gespeichert und verarbeitet. Diese Daten geben wir nicht ohne Ihre Einwilligung weiter."]
      [:p.mt-2 "Die Verarbeitung dieser Daten erfolgt auf Grundlage von Art. 6 Abs. 1 lit. b DSGVO, sofern Ihre Anfrage mit der Erfüllung eines Vertrags zusammenhängt oder zur Durchführung vorvertraglicher Maßnahmen erforderlich ist. In allen übrigen Fällen beruht die Verarbeitung auf unserem berechtigten Interesse an der effektiven Bearbeitung der an uns gerichteten Anfragen (Art. 6 Abs. 1 lit. f DSGVO) oder auf Ihrer Einwilligung (Art. 6 Abs. 1 lit. a DSGVO), sofern diese abgefragt wurde."]]

     [:section
      [:h2.text-2xl.font-semibold.mb-3 "4. Ihre Rechte"]
      [:p "Sie haben jederzeit das Recht auf unentgeltliche Auskunft über Ihre gespeicherten personenbezogenen Daten, deren Herkunft und Empfänger und den Zweck der Datenverarbeitung sowie ein Recht auf Berichtigung oder Löschung dieser Daten. Hierzu sowie zu weiteren Fragen zum Thema personenbezogene Daten können Sie sich jederzeit an uns wenden."]]

     [:section
      [:h2.text-2xl.font-semibold.mb-3 "5. Analyse-Tools und Tools von Drittanbietern"]
      [:p "Diese Website verwendet keine Analyse-Tools oder sonstige Tools von Drittanbietern zur Datenerfassung oder zum Tracking."]]

     [:section
      [:h2.text-2xl.font-semibold.mb-3 "6. Externe Links"]
      [:p "Diese Website kann Links zu externen Websites Dritter enthalten, auf deren Inhalte wir keinen Einfluss haben. Für die Inhalte der verlinkten Seiten ist stets der jeweilige Anbieter oder Betreiber der Seiten verantwortlich. Wir können daher keine Gewähr für diese fremden Inhalte übernehmen."]]

     [:section
      [:h2.text-2xl.font-semibold.mb-3 "7. Verantwortlicher"]
      [:p.mb-2 "Verantwortlich für die Datenverarbeitung auf dieser Website ist:"]
      [:p "Oliver Motz"]
      [:p "Hummelblumenstraße 21"]
      [:p "80995 München"]
      [:p "E-Mail: "
       [:a.text-blue-600.hover:text-blue-800.hover:underline
        {:href "mailto:services@olivermotz.com"}
        "services@olivermotz.com"]]]]]))

(def module
  {:routes [["/impressum" {:get impressum-page}]
            ["/datenschutz" {:get datenschutz-page}]]})
