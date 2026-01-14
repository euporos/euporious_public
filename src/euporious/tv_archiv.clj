(ns euporious.tv-archiv
  (:require
   [cheshire.core :as cheshire]
   [clojure.string :as str]
   [com.biffweb :as biff]
   [euporious.tv-archiv.db-interaction :as db]
   [euporious.ui :as ui]
   [reitit.coercion.malli]
   [ring.util.response :as response]
   [rum.core :as rum]))

;; Malli Schemas for Query Parameters

(def query-params-schema
  "Schema for query parameters with proper coercion and defaults"
  [:map
   [:genre {:optional true} [:maybe :string]]
   [:actor {:optional true} [:maybe :string]]
   [:director {:optional true} [:maybe :string]]
   [:country {:optional true} [:maybe :string]]
   [:search {:optional true} [:maybe :string]]
   [:sort-by {:optional true, :default "title"} [:enum "title" "year" "rating" "tmdb_rating"]]
   [:sort-dir {:optional true, :default "asc"} [:enum "asc" "desc"]]
   [:page {:optional true, :default 1} [:int {:min 1}]]
   [:per-page {:optional true, :default 50} [:int {:min 1, :max 200}]]])

(defn coerce-query-params
  "Transform coerced Malli params into our internal format, removing nils"
  [params]
  (let [remove-nils (fn [m] (into {} (filter (comp some? val) m)))]
    (remove-nils
     {:genre (:genre params)
      :actor (:actor params)
      :director (:director params)
      :country (:country params)
      :search (:search params)
      :sort-by (:sort-by params)
      :sort-dir (:sort-dir params)
      :page (:page params)
      :per-page (:per-page params)})))

(defn build-query-string
  "Build a query string from params map"
  [params]
  (let [param-pairs (for [[k v] params
                          :when (some? v)]
                      [(str (name k) "=" (java.net.URLEncoder/encode (str v) "UTF-8"))])
        flat-pairs (flatten param-pairs)]
    (if (seq flat-pairs)
      (str "?" (str/join "&" flat-pairs))
      "")))

(defn format-rating  [num]
  (cond
    (pos? num) (repeat num "+")
    (neg? num) (repeat num "-")
    :else "+/-"))

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
      [:span.rating.ml-2.text-yellow-600 " " (format-rating rating)])]

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
                          (dissoc (keyword type))
                          (assoc :page 1))
        query-string (build-query-string remove-params)]
    [:span.filter-chip.inline-flex.items-center.gap-1.px-3.py-1.bg-blue-100.text-blue-800.rounded-full.text-sm.mr-2.mb-2
     [:span value]
     [:a.remove-chip.hover:text-blue-900.font-bold
      {:href (str "/tv-archiv" query-string)}
      "×"]]))

(defn active-filters
  "Display active filters as removable chips"
  [params]
  (let [{:keys [genre actor director country search]} params
        has-filters? (or genre actor director country search)
        clear-params {:page 1 :sort-by (:sort-by params) :sort-dir (:sort-dir params) :per-page (:per-page params)}
        clear-query-string (build-query-string clear-params)]
    (when has-filters?
      [:div.active-filters.mb-4.p-3.bg-gray-50.rounded
       [:div.flex.items-center.justify-between.mb-2
        [:span.font-semibold.text-sm.text-gray-700 "Active Filters:"]
        [:a.text-sm.text-blue-600.hover:text-blue-800
         {:href (str "/tv-archiv" clear-query-string)}
         "Clear all"]]
       [:div.flex.flex-wrap
        (when genre
          (filter-chip "genre" genre params))
        (when actor
          (filter-chip "actor" actor params))
        (when director
          (filter-chip "director" director params))
        (when country
          (filter-chip "country" country params))
        (when search
          (let [remove-search-params (-> params
                                         (dissoc :search)
                                         (assoc :page 1))
                query-string (build-query-string remove-search-params)]
            [:span.filter-chip.inline-flex.items-center.gap-1.px-3.py-1.bg-blue-100.text-blue-800.rounded-full.text-sm.mr-2.mb-2
             [:span "Search: " search]
             [:a.remove-chip.hover:text-blue-900.font-bold
              {:href (str "/tv-archiv" query-string)}
              "×"]]))]])))

(defn pagination-controls
  "Render pagination controls"
  [{:keys [page total-pages]} params]
  (when (> total-pages 1)
    (let [prev-query-string (build-query-string (assoc params :page (dec page)))
          next-query-string (build-query-string (assoc params :page (inc page)))]
      [:div.pagination.flex.items-center.justify-center.gap-4.mt-6.mb-4
       (when (> page 1)
         [:a.page-btn.px-4.py-2.bg-blue-500.text-white.rounded.hover:bg-blue-600.inline-block.text-center
          {:href (str "/tv-archiv" prev-query-string)}
          "← Previous"])

       [:span.page-info.text-gray-700
        (format "Page %d of %d" page total-pages)]

       (when (< page total-pages)
         [:a.page-btn.px-4.py-2.bg-blue-500.text-white.rounded.hover:bg-blue-600.inline-block.text-center
          {:href (str "/tv-archiv" next-query-string)}
          "Next →"])])))

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
  [{:keys [parameters]}]
  (let [query-params (coerce-query-params (:query parameters))
        result (db/filter-and-sort-movies query-params)]
    (biff/render (movie-list-with-pagination result query-params))))

(defn list-page
  "Main TV archive page"
  [{:keys [parameters] :as ctx}]
  (let [query-params (:query parameters)
        result (db/filter-and-sort-movies query-params)]
    (ui/page
     ctx
     [:div.tv-archiv
      [:h1.text-3xl.font-bold.mb-6 "TV Archive"]

      [:form#filters.mb-6.space-y-4
       {:method "get"
        :action "/tv-archiv"}

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
       [:div.flex.gap-4.flex-wrap
        [:div.flex-1 {:style {:min-width "200px"}}
         [:label.block.text-sm.font-medium.text-gray-700.mb-1 {:for "sort-by"} "Sort By"]
         [:select.filter-select.w-full.px-4.py-2.border.border-gray-300.rounded
          {:name "sort-by" :id "sort-by" :onchange "this.form.submit()"}
          [:option {:value "title" :selected (= (:sort-by query-params) "title")} "Title"]
          [:option {:value "year" :selected (= (:sort-by query-params) "year")} "Year"]
          [:option {:value "rating" :selected (= (:sort-by query-params) "rating")} "My Rating"]
          [:option {:value "tmdb_rating" :selected (= (:sort-by query-params) "tmdb_rating")} "TMDB Rating"]]]

        [:div.w-40
         [:label.block.text-sm.font-medium.text-gray-700.mb-1 {:for "sort-dir"} "Direction"]
         [:select.filter-select.w-full.px-4.py-2.border.border-gray-300.rounded
          {:name "sort-dir" :id "sort-dir" :onchange "this.form.submit()"}
          [:option {:value "asc" :selected (= (:sort-dir query-params) "asc")} "Ascending ↑"]
          [:option {:value "desc" :selected (= (:sort-dir query-params) "desc")} "Descending ↓"]]]

        [:div.w-32
         [:label.block.text-sm.font-medium.text-gray-700.mb-1 {:for "per-page"} "Per Page"]
         [:select.filter-select.w-full.px-4.py-2.border.border-gray-300.rounded
          {:name "per-page" :id "per-page" :onchange "this.form.submit()"}
          [:option {:value "25" :selected (= (:per-page query-params) 25)} "25"]
          [:option {:value "50" :selected (= (:per-page query-params) 50)} "50"]
          [:option {:value "100" :selected (= (:per-page query-params) 100)} "100"]]]]

       [:div.flex.gap-2
        [:button.px-4.py-2.bg-blue-500.text-white.rounded.hover:bg-blue-600
         {:type "submit"}
         "Search"]
        [:a.px-4.py-2.bg-gray-300.text-gray-700.rounded.hover:bg-gray-400
         {:href "/tv-archiv"}
         "Clear"]]

       ;; Advanced filters
       [:div.advanced-filters.space-y-4.mt-4.p-4.bg-gray-50.rounded
        [:h3.font-semibold.text-gray-700.mb-2 "Advanced Filters"]

        ;; Genre select dropdown
        [:div.genre-filter
         [:label.block.text-sm.font-medium.text-gray-700.mb-1 {:for "genre"} "Genre"]
         [:select.filter-select.w-full.px-4.py-2.border.border-gray-300.rounded
          {:name "genre" :id "genre" :onchange "this.form.submit()"}
          [:option {:value "" :selected (nil? (:genre query-params))} "All Genres"]
          (for [genre (:genres (db/get-filter-options))]
            [:option {:value genre :selected (= (:genre query-params) genre)} genre])]]

        ;; Actor autocomplete
        [:div.actor-filter
         [:label.block.text-sm.font-medium.text-gray-700.mb-1 {:for "actor-search"} "Actor (single selection)"]
         [:div.autocomplete-wrapper.relative
          [:input.autocomplete-input.w-full.px-4.py-2.border.border-gray-300.rounded
           {:type "text"
            :id "actor-search"
            :placeholder "Type to search actors..."
            :autocomplete "off"
            :hx-get "/tv-archiv/filter-options?type=actors"
            :hx-trigger "keyup changed delay:300ms"
            :hx-target "#actor-results"
            :hx-include "[name='actor']"
            :name "actor-search"}]
          (when-let [actor (:actor query-params)]
            (when (not (str/blank? actor))
              [:input.hidden-filter-value
               {:type "hidden"
                :name "actor"
                :value actor}]))
          [:div#actor-results.autocomplete-results.absolute.z-10.w-full.bg-white.border.border-gray-300.rounded.mt-1.max-h-60.overflow-y-auto]]]

;; Director autocomplete
        [:div.director-filter
         [:label.block.text-sm.font-medium.text-gray-700.mb-1 {:for "director-search"} "Director (single selection)"]
         [:div.autocomplete-wrapper.relative
          [:input.autocomplete-input.w-full.px-4.py-2.border.border-gray-300.rounded
           {:type "text"
            :id "director-search"
            :placeholder "Type to search directors..."
            :autocomplete "off"
            :hx-get "/tv-archiv/filter-options?type=directors"
            :hx-trigger "keyup changed delay:300ms"
            :hx-target "#director-results"
            :hx-include "[name='director']"
            :name "director-search"}]
          (when-let [director (:director query-params)]
            (when (not (str/blank? director))
              [:input.hidden-filter-value
               {:type "hidden"
                :name "director"
                :value director}]))
          [:div#director-results.autocomplete-results.absolute.z-10.w-full.bg-white.border.border-gray-300.rounded.mt-1.max-h-60.overflow-y-auto]]]]]

;; Movie list container (HTMX swap target)
      [:div#movie-list-container
       (movie-list-with-pagination result query-params)]])))

(defn autocomplete-result-item
  "Render a single autocomplete result item"
  [option filter-name]
  [:div.autocomplete-result-item.px-4.py-2.hover:bg-gray-100.cursor-pointer
   {:hx-get (str "/tv-archiv?" filter-name "=" (java.net.URLEncoder/encode option "UTF-8"))
    :hx-target "body"
    :hx-push-url "true"
    :onclick (str "document.querySelector('input[name=" filter-name "]').value='" (str/replace option "'" "\\'") "';")
    :style {:cursor "pointer"}}
   option])

(defn filter-options
  "HTMX endpoint for autocomplete suggestions - returns HTML"
  [{:keys [params]}]
  (let [type (keyword (:type params))
        query-param (or (:genre-search params) (:actor-search params) (:director-search params) "")
        query (str/lower-case query-param)
        all-options (db/get-filter-options)
        options (get all-options type)
        filtered (if (and (seq query) (>= (count query) 2))
                   (filter #(str/includes? (str/lower-case %) query) options)
                   [])
        limited (take 20 filtered)
        filter-name (case type
                      :genres "genre"
                      :actors "actor"
                      :directors "director"
                      "")]
    (if (empty? limited)
      {:status 200
       :headers {"content-type" "text/html"}
       :body ""}
      (biff/render
       [:div
        (map #(autocomplete-result-item % filter-name) limited)]))))

;; Module definition

;; Initialize database when module loads
(db/build-db)

(defn wrap-remove-empty-query-params [handler]
  (fn [request]
    (let [cleaned-query-params (into {} (remove (comp str/blank? val) (:query-params request)))
          cleaned-params (into {} (remove (comp str/blank? val) (:params request)))]
      (handler (assoc request
                      :query-params cleaned-query-params
                      :params cleaned-params)))))

(def module
  {:routes ["/tv-archiv"
            {:middleware [wrap-remove-empty-query-params]}
            ["" {:get {:handler #'list-page
                       :coercion reitit.coercion.malli/coercion
                       :parameters {:query query-params-schema}}}]
            ["/list" {:get {:handler #'filtered-list
                            :coercion reitit.coercion.malli/coercion
                            :parameters {:query query-params-schema}}}]
            ["/filter-options" {:get #'filter-options}]]})
