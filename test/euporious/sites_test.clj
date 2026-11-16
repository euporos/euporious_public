(ns euporious.sites-test
  (:require [clojure.test :refer [deftest is testing]]
            [euporious.middleware :as mid]
            [euporious.sites :as sites]))

(def site-a {:id :a :hosts #{"a.example"} :dev-hosts #{"a.localhost"}})
(def site-b {:id :b :hosts #{"b.example"}})

(defn fake-handlers [sites]
  (into {} (for [{:keys [id]} sites]
             [id (fn [req] {:status 200 :body [id (:biff/base-url req)]})])))

(defn request [host & {:as extra}]
  (merge {:scheme :https :headers {"host" host}} extra))

(deftest host-index
  (is (= #{"a.example" "b.example"}
         (set (keys (sites/host-index [site-a site-b] false)))))
  (is (contains? (sites/host-index [site-a site-b] true) "a.localhost"))
  (is (thrown? clojure.lang.ExceptionInfo
               (sites/host-index [site-a (assoc site-b :hosts #{"a.example"})] false))))

(deftest dispatch
  (with-redefs [sites/site-handlers fake-handlers]
    (let [h (sites/handler [site-a site-b])]
      (testing "exact host, port ignored, base-url follows the request"
        (is (= [:a "https://a.example"] (:body (h (request "a.example")))))
        (is (= [:b "https://b.example"] (:body (h (request "B.example"))))))
      (testing "unknown and partially matching hosts are 404"
        (is (= 404 (:status (h (request "evil.example")))))
        (is (= 404 (:status (h (request "x.a.example")))))
        (is (= 404 (:status (h {:scheme :https :headers {}})))))
      (testing "dev hosts only with the dev flag"
        (is (= 404 (:status (h (request "a.localhost:8080")))))
        (is (= [:a "http://a.localhost:8080"]
               (:body (h (request "a.localhost:8080"
                                  :scheme :http
                                  :euporious/dev-hosts? true)))))))))

(deftest site-session
  (let [h (mid/wrap-site-session
           (fn [req] {:status 200 :uid (get-in req [:session :uid])
                      :session (:session req)}))
        call (fn [site session] (h {:site {:id site} :session session}))]
    (testing "a session from the same site is kept and stays tagged"
      (let [resp (call :a {:uid 1 :site :a})]
        (is (= 1 (:uid resp)))
        (is (= :a (get-in resp [:session :site])))))
    (testing "a session from another site or without a tag is ignored"
      (is (nil? (:uid (call :b {:uid 1 :site :a}))))
      (is (nil? (:uid (call :a {:uid 1})))))
    (testing "a fresh login gets tagged with the site"
      (let [login (mid/wrap-site-session (fn [_] {:status 303 :session {:uid 7}}))]
        (is (= {:uid 7 :site :a} (:session (login {:site {:id :a} :session {}}))))))))

(deftest all-modules
  (let [m1 {:routes ["/1"]} m2 {:tasks [:t]}
        mods (sites/all-modules [m2] [{:modules [m1]} {:modules [m1] :auth {:emails #{}}}])]
    (is (= 1 (count (filter #{m1} mods))) "shared modules appear once")
    (is (= 1 (count (filter :schema mods))) "one auth schema, however many auth sites")))
