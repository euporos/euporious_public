(ns euporious.tv-archiv
  (:require
   [cheshire.core :as cheshire]
   [clojure.string :as str]
   [com.biffweb :as biff]
   [euporious.tv-archiv.db-interaction :as db]
   [euporious.ui :as ui]
   [ring.util.response :as response]
   [rum.core :as rum]))

;; Helper functions

(defn parse-multi-param
  "Parse multiple values from query params (e.g., ?genre=Comedy&genre=Drama)"
  [params key]
  (let [val (get params key)]
    (cond
      (vector? val) (vec val)
      (string? val) [val]
      :else nil)))

(defn parse-int [s default]
  (try
    (Integer/parseInt s)
    (catch Exception _ default)))

(defn parse-sort
  "Parse sort param which can be 'field' or 'field-dir'"
  [sort-param]
  (let [sort-str (or sort-param "title")
        parts (str/split sort-str #"-")
        sort-by (first parts)
        sort-dir (if (= (second parts) "asc") "asc" "desc")]
    {:sort-by sort-by :sort-dir sort-dir}))

(defn parse-query-params
  "Extract and parse all query parameters for filtering/sorting"
  [params]
  (let [{:keys [sort-by sort-dir]} (parse-sort (:sort params))]
    {:genres (parse-multi-param params :genre)
     :actors (parse-multi-param params :actor)
     :directors (parse-multi-param params :director)
     :countries (parse-multi-param params :country)
     :search (:search params)
     :sort-by sort-by
     :sort-dir sort-dir
     :page (parse-int (:page params) 1)
     :per-page (parse-int (:per-page params) 50)}))

;; UI Components

(defn movie-item
  "Render a single movie as an expandable details element"
  [{:keys [id dads_title year rating runtime director
           genres actors countries imdb_id tmdb_id
           tmdb_title original_title tmdb_rating]}]
  [:details.movie-entry.border-b.border-gray-200.py-3
   {:data-id id}
   [:summary.movie-summary.cursor-pointer.hover:bg-gray-50.p-2.rounded
    [:span.title.font-semibold.text-lg dads_title]
    (when year
      [:span.year.text-gray-600.ml-2 "(" year ")"])
    (when rating
      [:span.rating.ml-2.text-yellow-600 "★ " rating])]

   [:div.movie-details.mt-3.ml-4.space-y-2.text-sm
    (when (and tmdb_title (not= tmdb_title dads_title))
      [:p [:strong.text-gray-700 "TMDB Title: "] [:span.text-gray-600 tmdb_title]])
    (when (and original_title
               (not= original_title dads_title)
               (not= original_title tmdb_title))
      [:p [:strong.text-gray-700 "Original Title: "] [:span.text-gray-600 original_title]])

    (when director
      [:p [:strong.text-gray-700 "Director: "] [:span.text-gray-600 director]])
    (when year
      [:p [:strong.text-gray-700 "Year: "] [:span.text-gray-600 year]])
    (when runtime
      [:p [:strong.text-gray-700 "Runtime: "] [:span.text-gray-600 runtime " min"]])
    (when (seq genres)
      [:p [:strong.text-gray-700 "Genres: "] [:span.text-gray-600 (str/join ", " genres)]])
    (when (seq actors)
      [:p [:strong.text-gray-700 "Actors: "] [:span.text-gray-600 (str/join ", " (take 8 actors))]])
    (when (seq countries)
      [:p [:strong.text-gray-700 "Countries: "] [:span.text-gray-600 (str/join ", " countries)]])

    [:p.text-xs.text-gray-500
     (when imdb_id [:span "IMDB: " imdb_id " "])
     (when tmdb_id [:span "TMDB: " tmdb_id])]
    (when tmdb_rating
      [:p [:strong.text-gray-700 "TMDB Rating: "] [:span.text-gray-600 (format "%.1f/10" tmdb_rating)]])

    [:div.description.mt-3.p-2.bg-gray-50.rounded.text-gray-500.italic
     "Description will be loaded from TMDB..."]]])

(defn filter-chip
  "Render a removable filter chip"
  [type value all-params]
  (let [remove-params (-> all-params
                          (update (keyword type) #(vec (remove #{value} %)))
                          (assoc :page 1))]
    [:span.filter-chip.inline-flex.items-center.gap-1.px-3.py-1.bg-blue-100.text-blue-800.rounded-full.text-sm.mr-2.mb-2
     [:span value]
     [:button.remove-chip.hover:text-blue-900.font-bold
      {:hx-get "/tv-archiv/list"
       :hx-target "#movie-list-container"
       :hx-push-url "true"
       :hx-vals (cheshire/generate-string remove-params)
       :type "button"}
      "×"]]))

(defn active-filters
  "Display active filters as removable chips"
  [params]
  (let [{:keys [genres actors directors countries search]} params
        has-filters? (or (seq genres) (seq actors) (seq directors) (seq countries) search)]
    (when has-filters?
      [:div.active-filters.mb-4.p-3.bg-gray-50.rounded
       [:div.flex.items-center.justify-between.mb-2
        [:span.font-semibold.text-sm.text-gray-700 "Active Filters:"]
        [:button.text-sm.text-blue-600.hover:text-blue-800
         {:hx-get "/tv-archiv/list"
          :hx-target "#movie-list-container"
          :hx-push-url "true"
          :hx-vals (cheshire/generate-string {:page 1 :sort-by (:sort-by params) :sort-dir (:sort-dir params) :per-page (:per-page params)})
          :type "button"}
         "Clear all"]]
       [:div.flex.flex-wrap
        (for [genre genres]
          (filter-chip "genre" genre params))
        (for [actor actors]
          (filter-chip "actor" actor params))
        (for [director directors]
          (filter-chip "director" director params))
        (for [country countries]
          (filter-chip "country" country params))
        (when search
          [:span.filter-chip.inline-flex.items-center.gap-1.px-3.py-1.bg-blue-100.text-blue-800.rounded-full.text-sm.mr-2.mb-2
           [:span "Search: " search]
           [:button.remove-chip.hover:text-blue-900.font-bold
            {:hx-get "/tv-archiv/list"
             :hx-target "#movie-list-container"
             :hx-push-url "true"
             :hx-vals (cheshire/generate-string (-> params
                                                    (dissoc :search)
                                                    (assoc :page 1)))
             :type "button"}
            "×"]])]])))

(defn pagination-controls
  "Render pagination controls"
  [{:keys [page total-pages]} params]
  (when (> total-pages 1)
    [:div.pagination.flex.items-center.justify-center.gap-4.mt-6.mb-4
     (when (> page 1)
       [:button.page-btn.px-4.py-2.bg-blue-500.text-white.rounded.hover:bg-blue-600
        {:hx-get "/tv-archiv/list"
         :hx-target "#movie-list-container"
         :hx-push-url "true"
         :hx-vals (cheshire/generate-string (assoc params :page (dec page)))
         :type "button"}
        "← Previous"])

     [:span.page-info.text-gray-700
      (format "Page %d of %d" page total-pages)]

     (when (< page total-pages)
       [:button.page-btn.px-4.py-2.bg-blue-500.text-white.rounded.hover:bg-blue-600
        {:hx-get "/tv-archiv/list"
         :hx-target "#movie-list-container"
         :hx-push-url "true"
         :hx-vals (cheshire/generate-string (assoc params :page (inc page)))
         :type "button"}
        "Next →"])]))

(defn movie-list-with-pagination
  "Render movie list with stats and pagination"
  [{:keys [movies page total-pages total-count start end] :as result} params]
  [:div
   [:div.stats-bar.text-sm.text-gray-600.mb-4
    (if (zero? total-count)
      "No movies found."
      (format "Showing %d-%d of %d movies" start end total-count))]

   (active-filters params)

   [:div#movie-list
    (if (empty? movies)
      [:p.empty-state.text-center.text-gray-500.py-8 "No movies found matching your filters."]
      (map movie-item movies))]

   (pagination-controls result params)])

;; HTTP Handlers

(defn filtered-list
  "HTMX endpoint - returns just the movie list HTML"
  [{:keys [params]}]
  (let [query-params (parse-query-params params)
        result (db/filter-and-sort-movies query-params)]
    (biff/render (movie-list-with-pagination result query-params))))

(defn list-page
  "Main TV archive page"
  [{:keys [params] :as ctx}]
  (let [query-params (parse-query-params params)
        result (db/filter-and-sort-movies query-params)]
    (ui/page
     ctx
     [:div.tv-archiv
      [:h1.text-3xl.font-bold.mb-6 "TV Archive"]

      [:form#filters.mb-6.space-y-4
       {:hx-get "/tv-archiv/list"
        :hx-target "#movie-list-container"
        :hx-push-url "true"
        ;; :hx-trigger "input delay:500ms from:.search-input, change from:.filter-select"
        }

       ;; Search box
       [:div.search-group
        [:label.block.text-sm.font-medium.text-gray-700.mb-1 {:for "search"} "Search Titles"]
        [:input.search-input.w-full.px-4.py-2.border.border-gray-300.rounded
         {:type "text"
          :name "search"
          :id "search"
          :placeholder "Nach Titel suchen…"
          :value (or (:search query-params) "")}]]

       ;; Sort and per-page controls
       #_[:div.flex.gap-4.flex-wrap
          [:div.flex-1 {:style "min-width: 200px"}
           [:label.block.text-sm.font-medium.text-gray-700.mb-1 {:for "sort"} "Sort By"]
           [:select.filter-select.w-full.px-4.py-2.border.border-gray-300.rounded
            {:name "sort" :id "sort"}
            [:option {:value "title" :selected (= (:sort-by query-params) "title")} "Title (A-Z)"]
            [:option {:value "year-desc" :selected (= (:sort-by query-params) "year-desc")} "Year (Newest First)"]
            [:option {:value "year-asc" :selected (= (:sort-by query-params) "year-asc")} "Year (Oldest First)"]
            [:option {:value "rating-desc" :selected (= (:sort-by query-params) "rating-desc")} "My Rating (Highest)"]
            [:option {:value "tmdb_rating-desc" :selected (= (:sort-by query-params) "tmdb_rating-desc")} "TMDB Rating (Highest)"]]]

          [:div.w-32
           [:label.block.text-sm.font-medium.text-gray-700.mb-1 {:for "per-page"} "Per Page"]
           [:select.filter-select.w-full.px-4.py-2.border.border-gray-300.rounded
            {:name "per-page" :id "per-page"}
            [:option {:value "25" :selected (= (:per-page query-params) 25)} "25"]
            [:option {:value "50" :selected (= (:per-page query-params) 50)} "50"]
            [:option {:value "100" :selected (= (:per-page query-params) 100)} "100"]]]]

       ;; TODO: Tag-based filter inputs will go here
       [:div.text-sm.text-gray-500.italic
        "Advanced filters (genres, actors, directors, countries) coming soon..."]]

      ;; Movie list container (HTMX swap target)
      [:div#movie-list-container
       (movie-list-with-pagination result query-params)]])))

(defn filter-options
  "JSON endpoint for autocomplete suggestions"
  [{:keys [params]}]
  (let [type (keyword (:type params))
        query (str/lower-case (or (:q params) ""))
        all-options (db/get-filter-options)
        options (get all-options type)
        filtered (if (seq query)
                   (filter #(str/includes? (str/lower-case %) query) options)
                   options)
        limited (take 20 filtered)]
    {:status 200
     :headers {"content-type" "application/json"}
     :body (cheshire/generate-string {:options (vec limited)})}))

;; Module definition

;; Initialize database when module loads
(db/build-db)

(def module
  {:routes ["/tv-archiv"
            ["" {:get list-page}]
            ["/list" {:get filtered-list}]
            ["/filter-options" {:get filter-options}]]})
