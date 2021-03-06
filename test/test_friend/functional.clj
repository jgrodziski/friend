(ns test-friend.functional
  (:require [clj-http.client :as http])
  (:use clojure.test
        ring.adapter.jetty
        [slingshot.slingshot :only (throw+ try+)]
        [test-friend.mock-app :only (mock-app mock-app-realm users
                                      page-bodies missles-fired?)]))

(declare test-port)

(defn start-app []
  (ring.adapter.jetty/run-jetty mock-app {:port 57150 :join? false}))

(defn run-test-app
  [app f]
  (let [server (ring.adapter.jetty/run-jetty app {:port 0 :join? false})
        port (-> server .getConnectors first .getLocalPort)]
    (def test-port port)  ;; would use with-redefs, but can't test on 1.2
    (reset! missles-fired? false)
    (try
      (f)
      (finally
        (.stop server)))))

(use-fixtures :once (partial run-test-app #'mock-app))

(defn url
  [uri]
  (str "http://localhost:" test-port uri))

(defn urls
  [& uris]
  (map vector uris (map url uris)))

(deftest access-anons
  (doseq [[uri url] (urls "/" "/login")
          :let [resp (http/get url)]]
    (is (http/success? resp))
    (is (= (page-bodies uri) (:body resp))))

  (let [api-resp (http/get (url "/free-api") {:as :json})]
    (is (http/success? api-resp))
    (is (= {:data 99} (:body api-resp)))))

(deftest ok-404
  (try+
    (http/get (url "/wat"))
    (assert false)
    (catch [:status 404] {:keys [body]}
      (is (= "404" body)))))

(deftest login-redirect
  (doseq [[uri url] (urls "/echo-roles" "/hook-admin"
                    "/user/account" "/user/private-page" "/admin")
          :let [resp (http/get url)]]
    (is (= (page-bodies "/login") (:body resp)) uri)))

(defn- check-user-role-access
  "Used to verify hierarchical determination of authorization; both
   admin and user roles should be able to access these URIs."
  []
  (are [uri] (is (= (page-bodies uri) (:body (http/get (url uri)))))
       "/user/account"
       "/user/private-page"))

(deftest user-login
  (binding [clj-http.core/*cookie-store* (clj-http.cookies/cookie-store)]
    (is (= (page-bodies "/login") (:body (http/get (url "/user/account?query-string=test")))))
    (let [resp (http/post (url "/login")
                 {:form-params {:username "jane" :password "user_password"} :follow-redirects false})]
      ; ensure that previously-requested page is redirected to upon redirecting authentication
      ; clj-http *should* redirect us, but isn't yet; working on it:
      ; https://github.com/dakrone/clj-http/issues/57
      (is (http/redirect? resp))
      (is (= (url "/user/account?query-string=test") (-> resp :headers (get "location")))))
    (check-user-role-access)
    (is (= {:roles ["test-friend.mock-app/user"]} (:body (http/get (url "/echo-roles") {:as :json}))))

    ; deny on admin role
    (try+
      (http/get (url "/admin"))
      (assert false) ; should never get here
      (catch [:status 403] _
        (is true)))

    (testing "logout blocks access to privileged routes"
      (is (= (page-bodies "/") (:body (http/get (url "/logout")))))
      (is (= (page-bodies "/login") (:body (http/get (url "/user/account"))))))))

(defn test-404 []
  (let [cs (clj-http.cookies/cookie-store)]
    (http/post "http://localhost:57150/login"
               {:form-params {:username "jane"
                              :password "user_password"
                              :remember-me false}
                :cookie-store cs
                :follow-redirects false})))

(deftest user-login-with-remember-me-cookie-set
  (let [cs (clj-http.cookies/cookie-store)]
    ;;login WITH remember me (get a ring-session cookie for binding the client to the server session storing the ::identity)
    (is (= "/" ( get (:headers (http/post (url "/login")
                                          {:form-params {:username "jane"
                                                         :password "user_password"
                                                         :remember-me true}
                                           :cookie-store cs
                                           :follow-redirects false})) "Location")))
    ;;now we can get the user account page correctly as we are loggued
    (is (= (page-bodies "/user/account") (:body (http/get (url "/user/account?query-string=test") {:cookie-store cs}))))
    ;;now we logout completely
    (is (= (page-bodies "/")) (http/get (url "/logout") {:cookie-store cs}))
    ;;ensure that we correctly logout
    (is (= (page-bodies "/login") (:body (http/get (url "/user/account?query-string=test") {:cookie-store cs}))))
    ;; now login WITH remember-me after clearing the cookie store
    (.clear cs)
    (http/post (url "/login") {:form-params {:username "jane" :password "user_password" :remember-me true}
                               :cookie-store cs
                               :follow-redirects false})
    ;;verify the remember-me cookie is present
    (is (not-empty (get (clj-http.cookies/get-cookies cs) "remember-me")))
    (are [uri] (is (= (page-bodies uri) (:body (http/get (url uri) {:cookie-store cs})))) "/user/account" "/user/private-page")
    (is (= {:roles ["test-friend.mock-app/user"]} (:body (http/get (url "/echo-roles") {:as :json :cookie-store cs}))))
    ;;then reset the session only with a dedicated request
    ;;not logging out as logout is expected to reset the remember-me token from server
    (http/post (url "/reset-session") {:cookie-store cs})
    ;;then verify we can actually get a protected page with only a remember-me cookie
    (is (= (page-bodies "/user/account") (:body (http/get (url "/user/account?query-string=test") {:cookie-store cs}))))
    ;;and get a login page if we do not provide the remember-me cookie
    ;;(is (= (page-bodies "/login") (:body (http/get (url "/user/account?query-string=test")))))

    ;;reset the remember-me store and provide a remember-me cookie to check everything goes right
    (http/post (url "/reset-rem-me") {:cookie-store cs})
    (http/post (url "/login") {:form-params {:username "jane" :password "user_password" :remember-me true}
                               :cookie-store cs
                               :follow-redirects false})

    ;;now logout to verify the remember-me cookie is correctly invalidated
    (testing "logout blocks access to privileged routes"
      (is (= (page-bodies "/") (:body (http/get (url "/logout") {:cookie-store cs}))))
      ;;(is (= (page-bodies "/login") (:body (http/get (url "/user/account") {:cookie-store cs}))))
      )
    ;;TODO check expiration time for cookie (either by issuing a very short lived cookie or indirecting the "now" fn on server in the future)
    ;;check cookie value modification invalidate the authentication
    ;; Deny on admin role
    ))


(deftest home-request-with-identify
  (let [cs (clj-http.cookies/cookie-store)]
    ;;ensure we get a remember-me cookie with an auto-generated id
    ))

(deftest session-integrity
  (testing (str "that session state set elsewhere is not disturbed by friend's operation, "
                "and that maintaining the friend identity doesn't disturb session state")
    (binding [clj-http.core/*cookie-store* (clj-http.cookies/cookie-store)]
      (let [post-session-data #(:body (http/post (url "/session-value")
                                                 {:form-params {:value %}}))
            get-session-data #(:body (http/get (url "/session-value")))]
        (is (= "session-data" (post-session-data "session-data")))
        (is (= "session-data" (get-session-data)))
        (http/post (url "/login")
                   {:form-params {:username "jane" :password "user_password"}})
        (check-user-role-access)
        (is (= "session-data" (get-session-data)))
        (is (= "auth-data" (post-session-data "auth-data")))
        (is (= "auth-data" (get-session-data)))
        (check-user-role-access)

        (http/get (url "/logout"))
        (let [should-be-login-redirect (http/get (url "/user/account")
                                                 {:follow-redirects false})]
          (is (= 302 (:status should-be-login-redirect)))
          (is (re-matches #"http://localhost:\d+/login"
                (-> should-be-login-redirect :headers (get "location")))))
        ; TODO should logout blow away the session completely?
        (is (= "auth-data" (get-session-data)))))))

(deftest hooked-authorization
  (binding [clj-http.core/*cookie-store* (clj-http.cookies/cookie-store)]
    (http/post (url "/login") {:form-params {:username "jane" :password "user_password"}})
    (try+
      (http/get (url "/hook-admin"))
      (assert false) ; should never get here
      (catch [:status 403] resp
        (is (= "Sorry, you do not have access to this resource." (:body resp)))))

    (http/post (url "/login") {:form-params {:username "root" :password "admin_password"}})
    (is (= (page-bodies "/hook-admin")) (http/get (url "/hook-admin")))))

(deftest authorization-failure-available-to-handler
  (binding [clj-http.core/*cookie-store* (clj-http.cookies/cookie-store)]
    (http/post (url "/login") {:form-params {:username "jane" :password "user_password"}})
    (try+
      (http/get (url "/fire-missles"))
      (is false "should not get here")
      (catch [:status 403] resp
        (is (= "403 message thrown with unauthorized stone" (:body resp)))))
    (is (not @missles-fired?))))

(deftest admin-login
  (binding [clj-http.core/*cookie-store* (clj-http.cookies/cookie-store)]
    (is (= (page-bodies "/login") (:body (http/get (url "/admin")))))

    (http/post (url "/login") {:form-params {:username "root" :password "admin_password"}})
    (is (= (page-bodies "/admin")) (http/get (url "/admin")))
    (check-user-role-access)
    (is (= {:roles ["test-friend.mock-app/admin"]} (:body (http/get (url "/echo-roles") {:as :json}))))))

(deftest admin-login-fn-role
  (binding [clj-http.core/*cookie-store* (clj-http.cookies/cookie-store)]
    (http/post (url "/login") {:form-params {:username "root-fn-role" :password "admin_password"}})
    (check-user-role-access)))

(deftest logout-only-on-correct-uri
  ;; logout middleware was previously being applied eagerly
  (binding [clj-http.core/*cookie-store* (clj-http.cookies/cookie-store)]
    (is (= (page-bodies "/login") (:body (http/get (url "/admin")))))
    (http/post (url "/login") {:form-params {:username "root" :password "admin_password"}})

    (try+
      (http/get (url "/wat"))
      (assert false)
      (catch [:status 404] e))
    (is (= (page-bodies "/admin")) (http/get (url "/admin")))

    (is (= (page-bodies "/")) (http/get (url "/logout")))
    (is (= (page-bodies "/login") (:body (http/get (url "/admin")))))))

;;;; TODO
; requires-scheme
; su
(defn test-ns-hook []
  ;;(run-test-app #'mock-app user-login)
  (run-test-app #'mock-app user-login-with-remember-me-cookie-set))
