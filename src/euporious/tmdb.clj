(ns euporious.tmdb
  (:require
   [clj-http.client :as http]
   [clojure.tools.logging :as log]))

(def tmdb-base-url "https://api.themoviedb.org/3")

(defn fetch-movie-details
  "Fetch movie details from TMDB API using the TMDB movie ID.
   Optional language parameter defaults to 'de' (German)."
  ([api-key tmdb-id]
   (fetch-movie-details api-key tmdb-id "de"))
  ([api-key tmdb-id language]
   (when (and api-key tmdb-id)
     (try
       (let [url (str tmdb-base-url "/movie/" tmdb-id)
             response (http/get url
                               {:query-params {:api_key api-key
                                               :language language}
                                :as :json
                                :throw-exceptions false})]
         (if (= 200 (:status response))
           (:body response)
           (do
             (log/warn "TMDB API request failed:" (:status response) (:body response))
             nil)))
       (catch Exception e
         (log/error e "Error fetching TMDB movie details for ID:" tmdb-id)
         nil)))))

(defn get-movie-description
  "Extract the overview/description from TMDB movie details"
  [movie-details]
  (when movie-details
    (:overview movie-details)))
