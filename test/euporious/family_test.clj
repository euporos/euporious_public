(ns euporious.family-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [com.biffweb.experimental.auth :as biff-auth]
            [euporious.family :as family]
            [euporious.family.tapes :as tapes]
            [euporious.sites :as sites]))

(deftest parse-emails-test
  (is (= #{"a@b.de" "c@d.de"} (sites/parse-emails " A@b.de, c@d.de ,,")))
  (is (= #{"a@b.de" "c@d.de"} (sites/parse-emails "a@b.de c@d.de")))
  (is (= #{} (sites/parse-emails nil)))
  (is (= #{"x@y.de"} (sites/allowed-emails {:family/emails "x@y.de"} :family/emails)))
  (is (= #{"z@y.de"} (sites/allowed-emails {} #{"z@y.de"}))))

(defn- auth-route
  "The [\"/auth\" data & children] route, unwrapped from the no-recaptcha wrapper."
  [module]
  (let [[path _ & children :as route] (first (:routes module))]
    (if (= "" path) (ffirst children) route)))

(deftest auth-module-test
  (let [module (sites/auth-module (:auth family/site))]
    (testing "reCAPTCHA hidden on the family site's auth routes"
      (is (= "" (ffirst (:routes module)))))
    (testing "verify-code replaced by the 10-minute handler"
      (let [verify (some #(when (= "/verify-code" (first %)) %) (drop 2 (auth-route module)))]
        (is (some? verify))
        (is (fn? (get-in verify [1 :post])))
        (is (not= biff-auth/verify-code-handler (get-in verify [1 :post]))))))
  (testing "other sites keep Biff's routes untouched"
    (let [module (sites/auth-module {:emails #{"a@b.de"}})
          verify (some #(when (= "/verify-code" (first %)) %) (drop 2 (auth-route module)))]
      (is (= "/auth" (ffirst (:routes module))))
      (is (= biff-auth/verify-code-handler (get-in verify [1 :post]))))))

(deftest formatting-test
  (is (= "3:04" (family/fmt-time 184.68)))
  (is (= "1:02:03" (family/fmt-time 3723)))
  (is (= "21 Min." (family/fmt-minutes 1231.32)))
  (is (= "11 Std. 24 Min." (family/fmt-total (* 60 684))))
  (is (= "48 Minuten" (family/fmt-total (* 60 48)))))

(deftest content-disposition-test
  (let [cd (family/content-disposition "02 Hochzeit Susanne-Toby Grünwald.mp4")]
    (is (str/includes? cd "filename=\"02 Hochzeit Susanne-Toby Gr_nwald.mp4\""))
    (is (str/includes? cd "filename*=UTF-8''02%20Hochzeit%20Susanne-Toby%20Gr%C3%BCnwald.mp4"))))

(deftest video-file-test
  (let [id (:id (first tapes/tapes))]
    (testing "unknown files are 404"
      (is (= 404 (:status (family/video-file {:path-params {:file "99.mp4"}}))))
      (is (= 404 (:status (family/video-file {:path-params {:file "../etc/passwd"}})))))
    (testing "prod hands the file to nginx"
      (let [resp (family/video-file {:path-params {:file (str id ".mp4")}
                                     :family/x-accel? true})]
        (is (= (str "/_video/" id ".mp4") (get-in resp [:headers "x-accel-redirect"])))
        (is (= "video/mp4" (get-in resp [:headers "content-type"])))
        (is (nil? (get-in resp [:headers "content-disposition"])))))
    (testing "signed-out requests never reach the file"
      (let [handler (family/wrap-signed-in-file (fn [_] {:status 200}))]
        (is (= 403 (:status (handler {:session {}}))))
        (is (= 200 (:status (handler {:session {:uid (random-uuid)}}))))))))

(deftest dev-range-test
  (let [f (java.io.File/createTempFile "family" ".bin")]
    (try
      (spit f "0123456789")
      (let [resp (family/serve-file-dev f "video/mp4" {} {:headers {"range" "bytes=2-5"}})]
        (is (= 206 (:status resp)))
        (is (= "bytes 2-5/10" (get-in resp [:headers "content-range"])))
        (is (= "2345" (slurp (:body resp)))))
      (let [resp (family/serve-file-dev f "video/mp4" {} {:headers {"range" "bytes=7-"}})]
        (is (= "789" (slurp (:body resp)))))
      (is (= 416 (:status (family/serve-file-dev f "video/mp4" {} {:headers {"range" "bytes=10-"}}))))
      (finally (.delete f)))))

(deftest family-host-routing-test
  (let [handler (sites/handler [family/site])]
    (is (= 404 (:status (handler {:headers {"host" "tobys-archiv.de"} :scheme :https
                                  :request-method :get :uri "/"}))))
    (testing "the dev host only in dev"
      (is (= 404 (:status (handler {:headers {"host" "family.localhost:8080"} :scheme :http
                                    :request-method :get :uri "/robots.txt"}))))
      (is (= 200 (:status (handler {:headers {"host" "family.localhost:8080"} :scheme :http
                                    :euporious/dev-hosts? true
                                    :biff.middleware/cookie-secret "AAAAAAAAAAAAAAAAAAAAAA=="
                                    :request-method :get :uri "/robots.txt"})))))))

(deftest protokoll-admin-only-test
  (testing "signed-in family members who are not the admin get a 404"
    (with-redefs [family/user-email (constantly "susannemotz@gmx.de")]
      (is (= 404 (:status (family/protokoll-page {:session {:uid (random-uuid)}}))))
      (is (not (family/admin? {})))))
  (testing "only the admin address counts as admin"
    (with-redefs [family/user-email (constantly "services@olivermotz.com")]
      (is (family/admin? {})))))
