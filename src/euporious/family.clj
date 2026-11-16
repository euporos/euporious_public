(ns euporious.family
  "Private family video site (family.olivermotz.net).

  The digitised VHS tapes as one MP4 per tape (AV1 + Opus, remuxed from the
  archive encode, see projects/familienvideos). Tapes and chapters come from
  `euporious.family.tapes` (generated). Sign-in by emailed code, allowlist from
  FAMILY_EMAILS. Nothing on these pages loads from third parties.

  Videos and posters are never public files: /video/NN.mp4 checks the session,
  then nginx serves the file via X-Accel-Redirect from an internal location
  (prod) — or the app streams it itself (dev, :family/x-accel? false)."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [com.biffweb.experimental :as biffx]
            [euporious.family.tapes :as tapes]
            [euporious.settings :as settings]
            [euporious.ui :as ui]
            [ring.middleware.anti-forgery :as csrf]
            [rum.core :as rum]
            [tick.core :as tick])
  (:import [java.io File InputStream RandomAccessFile]
           [java.net URLEncoder]
           [java.nio.charset StandardCharsets]
           [java.time ZoneId]
           [java.time.format DateTimeFormatter]))

(def site-name "Familienvideos")
(def code-minutes 10)

(def tapes-by-id (into {} (map (juxt :id identity)) tapes/tapes))

;;; Formatting -----------------------------------------------------------------

(defn fmt-time
  "Seconds -> m:ss or h:mm:ss."
  [secs]
  (let [s  (long secs)
        h  (quot s 3600)
        m  (quot (rem s 3600) 60)
        ss (rem s 60)]
    (if (pos? h)
      (format "%d:%02d:%02d" h m ss)
      (format "%d:%02d" m ss))))

(defn fmt-minutes [secs]
  (str (max 1 (Math/round (/ (double secs) 60.0))) " Min."))

(defn fmt-total
  "Seconds -> \"11 Std. 24 Min.\" (or just minutes under an hour)."
  [secs]
  (let [m (Math/round (/ (double secs) 60.0))]
    (if (< m 60)
      (str m " Minuten")
      (str (quot m 60) " Std. " (rem m 60) " Min."))))

(defn form
  "Like biff/form (POST + CSRF token) but without its inline style attribute,
  which the site's Content-Security-Policy would block."
  [{:keys [hidden] :as opts} & body]
  (into [:form (-> (merge {:method "post"} opts) (dissoc :hidden))]
        (concat
         (for [[k v] (assoc hidden "__anti-forgery-token" csrf/*anti-forgery-token*)
               :when (some? v)]
           [:input {:type "hidden" :name (name k) :value v}])
         body)))

(def ^:private berlin (ZoneId/of "Europe/Berlin"))
(def ^:private date-time-fmt (DateTimeFormatter/ofPattern "dd.MM.yyyy HH:mm"))

(defn fmt-zdt [zdt]
  (.format date-time-fmt (.withZoneSameInstant zdt berlin)))

;;; Users and the access log ---------------------------------------------------

(defn user-email [{:keys [biff/node session]}]
  (when-some [uid (:uid session)]
    (-> (biffx/q node {:select [:user/email] :from :user :where [:= :xt/id uid]})
        first
        :user/email)))

(defn admin? [ctx]
  (= settings/admin-email (user-email ctx)))

(defn log-access!
  "One entry per player page view or download. The many range requests a
  playback makes are deliberately not logged. Never fails the request."
  [{:keys [session] :as ctx} tape-id action]
  (try
    (biffx/submit-tx ctx
      [[:put-docs :family-access
        {:xt/id                 (random-uuid)
         :family-access/user    (:uid session)
         :family-access/email   (or (user-email ctx) "?")
         :family-access/tape    tape-id
         :family-access/action  action
         :family-access/at      (tick/zoned-date-time)}]])
    (catch Exception e
      (log/error e "family: could not write access log entry"))))

(def schema
  {:family-access [:map {:closed true}
                   [:xt/id                :uuid]
                   [:family-access/user   :uuid]
                   [:family-access/email  :string]
                   [:family-access/tape   :string]
                   [:family-access/action [:enum "view" "download"]]
                   [:family-access/at     [:fn tick/zoned-date-time?]]]})

;;; Page frame -----------------------------------------------------------------

(defn page
  "The family site's own frame: own CSS/fonts/JS only, no CDN, no footer."
  [{:keys [title signed-in? admin? body-class]} & body]
  [:html {:lang "de"}
   [:head
    [:meta {:charset "utf-8"}]
    [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]
    [:meta {:name "robots" :content "noindex, nofollow"}]
    [:meta {:name "referrer" :content "no-referrer"}]
    [:meta {:name "color-scheme" :content "light dark"}]
    [:title (if title (str title " · " site-name) site-name)]
    [:link {:rel "icon" :href (ui/static-path "/img/family-icon.svg") :type "image/svg+xml"}]
    [:link {:rel "stylesheet" :href (ui/static-path "/css/family.css")}]
    [:script {:src (ui/static-path "/js/family.js") :defer "defer"}]]
   [:body (when body-class {:class body-class})
    [:header.masthead
     [:a.brand {:href "/"} [:span.brand-mark "▶"] site-name]
     (when signed-in?
       [:nav
        (when admin? [:a {:href "/protokoll"} "Protokoll"])
        (form {:action "/auth/signout" :class "inline"}
          [:button.linkish {:type "submit"} "Abmelden"])])]
    (into [:main] body)]])

(defn- render [& args]
  {:status  200
   :headers {"content-type" "text/html; charset=utf-8"}
   :body    (str "<!DOCTYPE html>\n" (rum/render-static-markup (apply page args)))})

(defn on-error
  "Error pages for this site (404s from routing, 500s from euporious.sites)."
  [{:keys [status] :as _ctx}]
  (assoc (render {:title "Fehler"}
                 [:section.narrow
                  [:h1 (if (= status 404) "Diese Seite gibt es nicht." "Da ist etwas schiefgegangen.")]
                  [:p [:a {:href "/"} "Zur Übersicht"]]])
         :status status))

;;; Sign-in --------------------------------------------------------------------

(def error-messages
  {"invalid-email"  (str "Diese Adresse ist nicht freigeschaltet. Bitte prüfe die Schreibweise "
                         "– oder frag nach, ob sie aufgenommen werden kann.")
   "send-failed"    "Die E-Mail konnte nicht verschickt werden. Bitte versuch es später noch einmal."
   "invalid-code"   (str "Der Code stimmt nicht oder ist abgelaufen (er gilt " code-minutes
                         " Minuten, höchstens drei Versuche). Fordere einfach einen neuen an.")
   "invalid-link"   "Der Link ist ungültig oder abgelaufen. Fordere einfach einen neuen Code an."
   "not-signed-in"  "Bitte melde dich zuerst an."})

(defn error-alert [{:keys [params]}]
  (when-some [error (:error params)]
    [:p.alert (get error-messages error "Es ist ein Fehler aufgetreten.")]))

(defn signin-page [ctx]
  (render {:title "Anmelden" :body-class "signin"}
          [:section.narrow
           [:h1 "Unsere Familienvideos"]
           [:p.lede "Die alten VHS-Kassetten, digitalisiert – zum Anschauen und Herunterladen."]
           (form {:action "/auth/send-code"
                       :hidden {:on-error "/"}
                       :class  "stack"}
             [:label {:for "email"} "Deine E-Mail-Adresse"]
             [:input#email {:name "email" :type "email" :autocomplete "email" :required true
                            :inputmode "email" :autofocus true}]
             [:button {:type "submit"} "Code anfordern"])
           (error-alert ctx)
           [:p.hint "Du bekommst einen 6-stelligen Code per E-Mail. Danach bleibst du auf "
            "diesem Gerät ein Jahr lang angemeldet."]]))

(defn enter-code-page [{:keys [params] :as ctx}]
  (let [email (:email params)]
    (render {:title "Code eingeben" :body-class "signin"}
            [:section.narrow
             [:h1 "Code eingeben"]
             [:p.lede "Wir haben dir einen Code an " [:strong email] " geschickt. "
              "Er gilt " code-minutes " Minuten."]
             (form {:action "/auth/verify-code"
                         :hidden {:email email}
                         :class  "stack"}
               [:label {:for "code"} "6-stelliger Code"]
               [:input#code.code {:name "code" :type "text" :inputmode "numeric"
                                  :autocomplete "one-time-code" :pattern "[0-9]{6}"
                                  :maxlength 6 :required true :autofocus true}]
               [:button {:type "submit"} "Anmelden"])
             (error-alert ctx)
             (form {:action "/auth/send-code"
                         :hidden {:email email :on-error "/"}
                         :class  "inline"}
               [:p.hint "Keine E-Mail bekommen? Schau auch im Spam-Ordner nach, oder "
                [:button.linkish {:type "submit"} "schick mir einen neuen Code"] "."])])))

(defn signin-code-email [{:keys [to code]}]
  (let [text (str "Hallo,\n\n"
                  "hier ist dein Code für die Familienvideos:\n\n"
                  code "\n\n"
                  "Gib ihn auf der Anmeldeseite ein. Er gilt " code-minutes " Minuten.\n\n"
                  "Wenn du keinen Code angefordert hast, kannst du diese E-Mail einfach ignorieren.")]
    {:to      [{:email to}]
     :subject (str "Dein Code für die Familienvideos: " code)
     :text    text
     :html    (rum/render-static-markup
               [:html
                [:body
                 [:p "Hallo,"]
                 [:p "hier ist dein Code für die Familienvideos:"]
                 [:p {:style {:font-size "2rem" :letter-spacing "0.15em" :font-weight "bold"}} code]
                 [:p "Gib ihn auf der Anmeldeseite ein. Er gilt " code-minutes " Minuten."]
                 [:p "Wenn du keinen Code angefordert hast, kannst du diese E-Mail einfach ignorieren."]]])}))

(defn signin-link-email [{:keys [to url]}]
  {:to      [{:email to}]
   :subject "Dein Anmeldelink für die Familienvideos"
   :text    (str "Hallo,\n\nmit diesem Link meldest du dich bei den Familienvideos an:\n\n"
                 url "\n\nEr gilt eine Stunde. Wenn du ihn nicht angefordert hast, "
                 "kannst du diese E-Mail einfach ignorieren.")
   :html    (rum/render-static-markup
             [:html
              [:body
               [:p "Hallo,"]
               [:p "mit diesem Link meldest du dich bei den Familienvideos an:"]
               [:p [:a {:href url} "Jetzt anmelden"]]
               [:p "Er gilt eine Stunde. Wenn du ihn nicht angefordert hast, "
                "kannst du diese E-Mail einfach ignorieren."]]])})

;;; Tapes ----------------------------------------------------------------------

(defn poster-url [id] (str "/video/" id ".jpg"))
(defn video-url [id] (str "/video/" id ".mp4"))
(defn tape-url [id] (str "/kassette/" id))

(defn nav-opts [ctx]
  {:signed-in? true :admin? (admin? ctx)})

(defn tape-list-page [ctx]
  (let [total (reduce + (map :duration tapes/tapes))]
    (render (nav-opts ctx)
            [:section.intro
             [:h1 "Unsere Familienvideos"]
             [:p.lede (count tapes/tapes) " Kassetten, zusammen "
              (fmt-total total) ". Jede Kassette hat Kapitel, "
              "zu denen du direkt springen kannst."]]
            [:ul.tapes
             (for [{:keys [id name disc duration chapters]} tapes/tapes]
               [:li
                [:a.tape {:href (tape-url id)}
                 [:img {:src (poster-url id) :alt "" :loading "lazy" :width 751 :height 576}]
                 [:span.tape-label
                  [:span.nr id]
                  [:span.name name]]
                 [:span.meta (fmt-minutes duration) " · "
                  (count chapters) " Kapitel"
                  " · " disc]]])])))

(defn tape-page [{:keys [path-params] :as ctx}]
  (if-some [{:keys [id name disc duration chapters]} (tapes-by-id (:id path-params))]
    (let [idx  (.indexOf ^java.util.List (mapv :id tapes/tapes) id)
          prev (get tapes/tapes (dec idx))
          next (get tapes/tapes (inc idx))]
      (log-access! ctx id "view")
      (render (assoc (nav-opts ctx) :title name :body-class "player-page")
              [:nav.crumbs [:a {:href "/"} "← Alle Kassetten"]]
              [:div.player
               [:div.screen
                [:video#video {:controls "controls" :preload "metadata" :playsinline "playsinline"
                               :poster (poster-url id) :src (video-url id)}]
                [:p#no-av1.alert {:hidden "hidden"}
                 "Dieses Gerät kann das Video leider nicht abspielen. Die Videos sind im "
                 "modernen AV1-Format gespeichert, das ältere iPhones, iPads und Macs nicht "
                 "können. Probier es mit einem anderen Gerät oder Browser (z. B. Firefox oder Chrome)."]
                [:div.tape-head
                 [:h1 [:span.nr id] name]
                 [:p.meta (fmt-time duration) " · " disc " · "
                  [:a {:href (str (video-url id) "?download=1") :download ""} "Herunterladen"]
                  " · "
                  [:button#copy-link.linkish {:type "button"} "Link zu dieser Stelle kopieren"]]]]
               [:aside.chapters
                [:h2 "Kapitel"]
                [:ol
                 (for [{:keys [start title]} chapters]
                   [:li
                    [:a.chapter {:href (str "#t=" (long start)) :data-start start}
                     [:span.time (fmt-time start)]
                     [:span.title title]]])]]]
              [:nav.prevnext
               (if prev
                 [:a {:href (tape-url (:id prev))} "← " (:name prev)]
                 [:span])
               (when next
                 [:a {:href (tape-url (:id next))} (:name next) " →"])]))
    (on-error (assoc ctx :status 404))))

;;; Video and poster files -----------------------------------------------------

(defn- bounded-stream
  "`len` bytes of `f` from `start` (dev only: prod lets nginx do ranges)."
  ^InputStream [^File f start len]
  (let [raf       (RandomAccessFile. f "r")
        remaining (volatile! len)]
    (.seek raf (long start))
    (proxy [InputStream] []
      (read
        ([]
         (if (pos? @remaining)
           (let [b (.read raf)]
             (when (>= b 0) (vswap! remaining dec))
             b)
           -1))
        ([^bytes buf]
         (.read ^InputStream this buf 0 (alength buf)))
        ([^bytes buf off n]
         (if (pos? @remaining)
           (let [r (.read raf buf (int off) (int (min n @remaining)))]
             (when (pos? r) (vswap! remaining - r))
             r)
           -1)))
      (close [] (.close raf)))))

(defn serve-file-dev
  "Streams a file with single-range support, enough for <video> seeking in dev."
  [^File f content-type extra-headers {:keys [headers]}]
  (if-not (.isFile f)
    {:status 404 :headers {"content-type" "text/plain"} :body "Not found"}
    (let [size       (.length f)
          [_ s e]    (some->> (get headers "range") (re-matches #"bytes=(\d+)-(\d*)"))
          start      (some-> s parse-long)
          end        (if (seq e) (min (parse-long e) (dec size)) (dec size))
          base       (merge {"content-type" content-type "accept-ranges" "bytes"} extra-headers)]
      (cond
        (nil? start)
        {:status 200 :headers (assoc base "content-length" (str size)) :body f}

        (>= start size)
        {:status 416 :headers {"content-range" (str "bytes */" size)} :body ""}

        :else
        {:status  206
         :headers (assoc base
                         "content-range"  (format "bytes %d-%d/%d" start end size)
                         "content-length" (str (inc (- end start))))
         :body    (bounded-stream f start (inc (- end start)))}))))

(defn content-disposition
  "attachment with a readable UTF-8 name and an ASCII fallback."
  [filename]
  (let [ascii (str/replace filename #"[^\x20-\x7e]|\"" "_")
        utf8  (-> (URLEncoder/encode ^String filename StandardCharsets/UTF_8)
                  (str/replace "+" "%20"))]
    (str "attachment; filename=\"" ascii "\"; filename*=UTF-8''" utf8)))

(defn video-file [{:keys [path-params params family/x-accel? family/video-dir] :as ctx}]
  (let [[_ id ext] (re-matches #"(\d\d)\.(mp4|jpg)" (:file path-params ""))
        tape       (tapes-by-id id)]
    (if-not tape
      {:status 404 :headers {"content-type" "text/plain"} :body "Not found"}
      (let [file         (str id "." ext)
            content-type (if (= ext "mp4") "video/mp4" "image/jpeg")
            download?    (and (= ext "mp4") (some? (:download params)))
            extra        (cond-> {"cache-control" "private, max-age=86400"}
                           download? (assoc "content-disposition"
                                            (content-disposition (str id " " (:name tape) ".mp4"))))]
        (when download? (log-access! ctx id "download"))
        (if x-accel?
          {:status 200
           :headers (assoc extra
                           "content-type" content-type
                           "x-accel-redirect" (str "/_video/" file))
           :body ""}
          (serve-file-dev (io/file video-dir file) content-type extra ctx))))))

;;; Access log page (admin only) -------------------------------------------------

(defn protokoll-page [{:keys [biff/node] :as ctx}]
  (if-not (admin? ctx)
    (on-error (assoc ctx :status 404))
    (let [entries (biffx/q node {:select   [:family-access/email :family-access/tape
                                            :family-access/action :family-access/at]
                                 :from     :family-access
                                 :order-by [[:family-access/at :desc]]
                                 :limit    500})
          by-email (->> entries
                        (group-by :family-access/email)
                        (sort-by (comp - count val)))]
      (render (assoc (nav-opts ctx) :title "Protokoll")
              [:section.intro
               [:h1 "Protokoll"]
               [:p.lede "Wer wann welche Kassette geöffnet oder heruntergeladen hat "
                "(die letzten " (count entries) " Einträge)."]]
              [:table.log
               [:thead [:tr [:th "Person"] [:th.num "Aufrufe"] [:th "Zuletzt"]]]
               [:tbody
                (for [[email es] by-email]
                  [:tr [:td email] [:td.num (count es)]
                   [:td (fmt-zdt (:family-access/at (first es)))]])]]
              [:table.log
               [:thead [:tr [:th "Zeit"] [:th "Person"] [:th "Kassette"] [:th "Aktion"]]]
               [:tbody
                (for [{:family-access/keys [email tape action at]} entries]
                  [:tr
                   [:td (fmt-zdt at)]
                   [:td email]
                   [:td tape " " (:name (tapes-by-id tape))]
                   [:td (if (= action "download") "heruntergeladen" "angesehen")]])]]))))

;;; Routes -----------------------------------------------------------------------

(defn wrap-signed-in [handler]
  (fn [{:keys [session] :as ctx}]
    (if (some? (:uid session))
      (handler ctx)
      {:status 303 :headers {"location" "/?error=not-signed-in"}})))

(defn wrap-signed-in-file [handler]
  (fn [{:keys [session] :as ctx}]
    (if (some? (:uid session))
      (handler ctx)
      {:status 403 :headers {"content-type" "text/plain"} :body "Forbidden"})))

(defn home [{:keys [session] :as ctx}]
  (if (some? (:uid session))
    (tape-list-page ctx)
    (signin-page ctx)))

(defn robots [_]
  {:status 200 :headers {"content-type" "text/plain"} :body "User-agent: *\nDisallow: /\n"})

(def module
  {:schema schema
   :routes [["/"            {:get home}]
            ["/robots.txt"  {:get robots}]
            ["/verify-code" {:get enter-code-page}]
            ["" {:middleware [wrap-signed-in]}
             ["/kassette/:id" {:get tape-page}]
             ["/protokoll"    {:get protokoll-page}]]
            ["/video/:file" {:middleware [wrap-signed-in-file]
                             :get video-file}]]})

(def site
  {:id              :family
   :name            site-name
   :hosts           #{"family.olivermotz.net"}
   :dev-hosts       #{"family.localhost"}
   :modules         [module]
   :auth            {:emails                      :family/emails
                     :recaptcha?                  false
                     :code-minutes                code-minutes
                     :biff.auth/app-path          "/"
                     :biff.auth/invalid-link-path "/?error=invalid-link"}
   :email-templates {:signin-code signin-code-email
                     :signin-link signin-link-email}
   :on-error        on-error
   :headers         {"X-Robots-Tag"            "noindex, nofollow"
                     "Referrer-Policy"         "no-referrer"
                     "Content-Security-Policy" (str "default-src 'self'; img-src 'self'; "
                                                    "media-src 'self'; style-src 'self'; "
                                                    "script-src 'self'; font-src 'self'; "
                                                    "form-action 'self'; frame-ancestors 'none'; "
                                                    "base-uri 'none'")}})
