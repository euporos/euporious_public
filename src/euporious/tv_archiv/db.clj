(ns euporious.tv-archiv.db-interaction
  (:require
   [clojure.string :as str]
   [com.biffweb.experimental :as biffx]
   [euporious :as main]
   [honey.sql :as sql]
   [next.jdbc :as jdbc]
   [next.jdbc.connection :as jdbc-conn]
   [next.jdbc.result-set :as rs]
   [orgmode.core :as org])
  (:import
   (java.util UUID)))

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

(comment
  (time (build-db))

  (time (filter  #(contains? (:genres %) "Komödie") (vals (:movies @db)))))
