(ns euporious.tv-archiv.db-interaction
  (:require
   [clojure.string :as str]
   [orgmode.core :as org]))

(defonce db (atom {}))

(defn split-list [s]
  (set (str/split s #", *")))

(defn build-entity  [{:keys [properties]}]
  (let [{:keys [id
                director
                dads_title
                genres
                tmdb_id
                countries
                original_language
                tmdb_confidence
                backfilled
                year
                imdb_id
                original_title
                actors
                tmdb_title
                runtime
                tmdb_rating
                rating]} (reduce-kv
                          (fn [m k v]
                            (when (seq v)
                              (assoc m k v)))
                          {}
                          properties)]
    {:id id
     :director director
     :dads_title dads_title
     :genres (when genres (split-list genres))
     :tmdb_id tmdb_id
     :countries (when countries (split-list countries))
     :original_language original_language
     :tmdb_confidence (when tmdb_confidence (Float/parseFloat tmdb_confidence))
     :backfilled (= backfilled "true")
     :year (when year (Integer/parseInt  year))
     :imdb_id imdb_id
     :original_title original_title
     :actors (when actors (split-list actors))
     :tmdb_title tmdb_title
     :runtime (when runtime (Integer/parseInt runtime))
     :tmdb_rating (when tmdb_rating (Float/parseFloat tmdb_rating))
     :rating (when rating (Integer/parseInt rating))}))

(defn build-db []
  (let [parsed-movies (reduce
                       (fn [sofar nextup]
                         (assoc sofar (:id (:properties nextup)) (build-entity nextup)))
                       {}
                       (-> "/media/lapdaten/ARBEITSSD/dev/euporious/euporious_public/resources/tv_liste.org"
                           org/parse
                           :content))
        more-info (reduce
                   (fn [sofar nextup]
                     (-> sofar
                         (update :genres into (:genres nextup))
                         (update  :actors into (:actors nextup))
                         (update  :countries into (:countries nextup))
                         (update  :directors conj (:director nextup))))

                   {:genres #{}
                    :actors #{}
                    :directors #{}
                    :countries #{}}
                   (vals parsed-movies))]
    (reset! db (assoc more-info :movies parsed-movies))))

(defn title-matches?
  "Check if any of the movie's titles match the search term (case-insensitive)"
  [movie search-term]
  (when search-term
    (let [search-lower (str/lower-case (str/trim search-term))]
      (or (when-let [dt (:dads_title movie)]
            (str/includes? (str/lower-case dt) search-lower))
          (when-let [tt (:tmdb_title movie)]
            (str/includes? (str/lower-case tt) search-lower))
          (when-let [ot (:original_title movie)]
            (str/includes? (str/lower-case ot) search-lower))))))

(defn apply-filters
  "Apply AND logic filters to movies"
  [movies {:keys [genres actors directors countries search]}]
  (let [genre-set (when (seq genres) (set genres))
        actor-set (when (seq actors) (set actors))
        director-set (when (seq directors) (set directors))
        country-set (when (seq countries) (set countries))]
    (filter
     (fn [movie]
       (and
        ;; Genre filter - movie must have at least one of the selected genres
        (if genre-set
          (some genre-set (:genres movie))
          true)
        ;; Actor filter - movie must have at least one of the selected actors
        (if actor-set
          (some actor-set (:actors movie))
          true)
        ;; Director filter - movie director must be one of selected
        (if director-set
          (director-set (:director movie))
          true)
        ;; Country filter - movie must have at least one of the selected countries
        (if country-set
          (some country-set (:countries movie))
          true)
        ;; Search filter - check all title fields
        (if search
          (title-matches? movie search)
          true)))
     movies)))

(defn sort-movies
  "Sort movies by the specified field and direction"
  [movies sort-by sort-dir]
  (let [sort-key (case sort-by
                   "year" :year
                   "rating" :rating
                   "tmdb_rating" :tmdb_rating
                   "title" :dads_title
                   :dads_title)
        comparator (if (= sort-dir "asc")
                     compare
                     #(compare %2 %1))
        ;; Handle nil values - push them to the end
        safe-compare (fn [a b]
                       (let [val-a (get a sort-key)
                             val-b (get b sort-key)]
                         (cond
                           (and (nil? val-a) (nil? val-b)) 0
                           (nil? val-a) 1
                           (nil? val-b) -1
                           (and (string? val-a) (string? val-b))
                           (comparator (str/lower-case val-a) (str/lower-case val-b))
                           :else (comparator val-a val-b))))]
    (sort safe-compare movies)))

(defn paginate
  "Apply pagination to a collection"
  [items page per-page]
  (let [page (max 1 (or page 1))
        per-page (or per-page 50)
        total (count items)
        total-pages (int (Math/ceil (/ total (double per-page))))
        page (min page total-pages)
        start (* (dec page) per-page)
        end (min (+ start per-page) total)]
    {:items (vec (take (- end start) (drop start items)))
     :page page
     :per-page per-page
     :total-count total
     :total-pages total-pages
     :start (if (zero? total) 0 (inc start))
     :end end}))

(defn filter-and-sort-movies
  "Main query function - filter, sort, and paginate movies"
  [{:keys [genres actors directors countries search
           sort-by sort-dir page per-page]
    :or {sort-by "title" sort-dir "asc" page 1 per-page 50}}]
  (let [all-movies (vals (:movies @db))
        filtered (apply-filters all-movies {:genres genres
                                            :actors actors
                                            :directors directors
                                            :countries countries
                                            :search search})
        sorted (sort-movies filtered sort-by sort-dir)
        result (paginate sorted page per-page)]
    (assoc result :movies (:items result))))

(defn get-filter-options
  "Get all unique values for filter dropdowns/autocomplete"
  []
  {:genres (sort (filter some? (:genres @db)))
   :actors (sort (filter some? (:actors @db)))
   :directors (sort (filter some? (:directors @db)))
   :countries (sort (filter some? (:countries @db)))})

(comment
  (time (build-db))

  (time (filter  #(contains? (:genres %) "Komödie") (vals (:movies @db))))

  ;; Test filtering and sorting
  (filter-and-sort-movies {:search "war" :page 1 :per-page 10})

  (filter-and-sort-movies {:genres ["Komödie"] :sort-by "year" :sort-dir "desc" :page 1 :per-page 25})

  (get-filter-options))
