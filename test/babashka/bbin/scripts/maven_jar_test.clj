(ns babashka.bbin.scripts.maven-jar-test
  (:require [babashka.bbin.dirs :as dirs]
            [babashka.bbin.test-util :as tu]
            [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]))

(use-fixtures :once (tu/bbin-dirs-fixture))

(def maven-lib
  {:lib 'org.babashka/http-server
   :coords {:mvn/version "0.1.11"}})

(deftest install-from-mvn-version-test
  (testing "install */* --mvn/version *"
    (tu/reset-test-dir)
    (dirs/ensure-bbin-dirs {})
    (let [cli-opts {:script/lib (str (:lib maven-lib))
                    :mvn/version (-> maven-lib :coords :mvn/version)}
          out (tu/run-install cli-opts)]
      (is (= maven-lib out))
      (is (fs/exists? (fs/file (dirs/bin-dir nil) (name (:lib maven-lib)))))
      (is (str/starts-with? (tu/run-bin-script (:lib maven-lib) "--help")
                            "Serves static assets using web server."))
      (is (= '{http-server {:lib org.babashka/http-server
                            :coords {:mvn/version "0.1.11"}}}
             (tu/run-ls))))))
