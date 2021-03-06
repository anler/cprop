(ns cprop.test.core
  (:require [cprop.core :refer [load-config cursor]]
            [cprop.source :refer [merge* from-stream from-file from-resource from-props-file]]
            [clojure.edn :as edn]
            [clojure.test :refer :all]))

(deftest should-slurp-and-provide
  (testing "should read config from -Dconfig.var"
    (let [c (load-config)]
      (is (= (c :answer) 42))))
  (testing "should be able to naviage nested props"
    (let [c (load-config)]
      (is (= (get-in c [:source :account :rabbit :vhost]) "/z-broker")))))

(deftest should-create-cursors
  (testing "should create a rabbit cursor"
    (let [c (load-config)]
      (is (= ((cursor c :source :account :rabbit) :vhost) "/z-broker"))
      (is (= ((cursor c)) c)))))

(deftest should-compose-cursors
  (testing "should compose one level"
    (let [c (load-config)]
      (is (= ((cursor c (cursor c :source) :account) :rabbit :vhost) "/z-broker"))
      (is (= ((cursor c (cursor c) :source :account) :rabbit :vhost) "/z-broker"))))
  (testing "should compose nested cursors"
    (let [c (load-config)]
      (is (= ((cursor c (cursor c (cursor c :source) :account) :rabbit) :vhost) "/z-broker")))))

(defn- read-test-env []
  (->> {"DATOMIC__URL" "\"datomic:sql://?jdbc:postgresql://localhost:5432/datomic?user=datomic&password=datomic\""
        "AWS__ACCESS_KEY" "\"AKIAIOSFODNN7EXAMPLE\""
        "AWS__SECRET_KEY" "\"wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY\""
        "AWS__REGION" "\"ues-east-1\""
        "IO__HTTP__POOL__CONN_TIMEOUT" "60000"
        "IO__HTTP__POOL__MAX_PER_ROUTE" "10"
        "OTHER_THINGS" "[1 2 3 \"42\"]"}
       (map (fn [[k v]] [(#'cprop.source/env->path k)
                         (#'cprop.source/str->value v)]))
       (into {})))

(deftest from-source
  (is (map? (from-stream "dev-resources/config.edn")))
  (is (map? (from-file "dev-resources/config.edn")))
  (is (map? (from-resource "config.edn")))
  (is (map? (load-config :file "dev-resources/config.edn")))
  (is (map? (load-config :resource "config.edn")))
  (is (map? (load-config :resource "config.edn"
                         :file "dev-resources/fill-me-in.edn"))))

(deftest with-merge
  (is (= (load-config :resource "config.edn"
                      :merge [{:source {:account {:rabbit {:port 4242}}}}])
         (assoc-in (load-config) [:source :account :rabbit :port] 4242)))
  (is (= (load-config :file "dev-resources/config.edn"
                      :merge [{:source {:account {:rabbit {:port 4242}}}}
                              {:datomic {:url :foo}}])
         (assoc-in (assoc-in (load-config) [:source :account :rabbit :port] 4242)
                   [:datomic :url] :foo)))
  (is (= (load-config :resource "config.edn"
                      :file "dev-resources/config.edn"
                      :merge [{:source {:account {:rabbit {:port 4242}}}}
                              {:datomic {:url :foo}}
                              {:datomic {:url :none}}])
         (assoc-in (assoc-in (load-config) [:source :account :rabbit :port] 4242)
                   [:datomic :url] :none))))

(deftest should-merge-with-env
  (let [config (edn/read-string
                 (slurp "dev-resources/fill-me-in.edn"))
        merged (merge* config (read-test-env))]

    (is (= {:datomic {:url "datomic:sql://?jdbc:postgresql://localhost:5432/datomic?user=datomic&password=datomic"},
            :aws {:access-key "AKIAIOSFODNN7EXAMPLE",
                  :secret-key "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY",
                  :region "ues-east-1",
                  :visiblity-timeout-sec 30,
                  :max-conn 50,
                  :queue "cprop-dev"},
            :io
            {:http
             {:pool
              {:socket-timeout 600000,
               :conn-timeout 60000,
               :conn-req-timeout 600000,
               :max-total 200,
               :max-per-route 10}}},
            :other-things [1 2 3 "42"]}

           merged))))

(deftest should-merge-with-sys-props
  (let [props {"datomic_url" "sys-url"
               "aws_access.key" "sys-key"
               "io_http_pool_socket.timeout" "4242"}
        _      (doseq [[k v] props] (System/setProperty k v))
        config (load-config :resource "fill-me-in.edn"
                            :file "dev-resources/fill-me-in.edn")]

    (is (= {:datomic {:url "sys-url"},
            :aws
            {:access-key "sys-key",
             :secret-key "ME TOO",
             :region "FILL ME IN AS WELL",
             :visiblity-timeout-sec 30,
             :max-conn 50,
             :queue "cprop-dev"},
            :io
            {:http
             {:pool
              {:socket-timeout 4242,
               :conn-timeout :I-SHOULD-BE-A-NUMBER,
               :conn-req-timeout 600000,
               :max-total 200,
               :max-per-route :ME-ALSO}}},
            :other-things
            ["I am a vector and also like to place the substitute game"]}

           config))

    (doseq [[k _] props] (System/clearProperty k))))

(deftest should-merge-with-props-file
  (let [config (load-config :file "dev-resources/fill-me-in.edn"
                            :merge [(from-props-file "dev-resources/overrides.properties")])]

    (is (= {:datomic
            {:url "datomic:sql://?jdbc:postgresql://localhost:5432/datomic?user=datomic&password=datomic"},
            :source
            {:account
             {:rabbit
              {:host "localhost",
               :port 5672,
               :vhost "/z-broker",
               :username "guest",
               :password "guest"}}},
            :answer 42,
            :aws
            {:access-key "super secret key",
             :secret-key "super secret s3cr3t!!!",
             :region "us-east-2",
             :visiblity-timeout-sec 30,
             :max-conn 50,
             :queue "cprop-dev"},
            :io
            {:http
             {:pool
              {:socket-timeout 600000,
               :conn-timeout 42,
               :conn-req-timeout 600000,
               :max-total 200,
               :max-per-route 42}}},
            :other-things ["1" "2" "3" "4" "5" "6" "7"]}

           config))))

(deftest should-merge-booleans
  (let [props {"datomic_url" "true"
               "aws_access.key" "false"
               "io_http_pool_socket.timeout" "True"
               "io_http_pool_conn.timeout" ".true"
               "io_http_pool_max.total" "truee"}
        _      (doseq [[k v] props] (System/setProperty k v))
        config (load-config :resource "fill-me-in.edn"
                            :file "dev-resources/fill-me-in.edn")]
    (is (true? (get-in config [:datomic :url])))
    (is (false? (get-in config [:aws :access-key])))
    (is (string? (get-in config [:io :http :pool :socket-timeout])))
    (is (string? (get-in config [:io :http :pool :conn-timeout])))
    (is (string? (get-in config [:io :http :pool :max-total])))
    (doseq [[k _] props] (System/clearProperty k))))

(deftest should-throw-on-resource-not-found
  (is (thrown-with-msg? java.util.MissingResourceException
                        #"resource \"empty\" not found on the resource path"
                        (load-config :resource "empty"))))

(deftest should-throw-on-file-not-found
  (is (thrown-with-msg? java.util.MissingResourceException
                        #"can't find a configuration file path: \"not-here\". besides providing it via \"\(load-config :file <path>\)\", it could also be set via \"conf\" system property \(i.e. -Dconf=<path>\)"
                        (load-config :resource "empty.edn" :file "not-here"))))

(deftest should-throw-when-empty-config
  (is (thrown-with-msg? java.lang.RuntimeException
                        #"could not find a non empty configuration file to load. looked in the classpath \(as a \"resource\"\) and on a file system via \"conf\" system property"
                        (load-config :resource "empty.edn" :file "dev-resources/empty.edn"))))
