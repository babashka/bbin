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

(def help-text
  "Serves static assets using web server.")

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
                            help-text))
      (is (= `{~'http-server ~maven-lib} (tu/run-ls))))))

(def upgraded-lib
  (assoc-in maven-lib [:coords :mvn/version] "0.1.13"))

(deftest upgrade-maven-jar-test
  (testing "upgrade (maven jar)"
    (tu/reset-test-dir)
    (dirs/ensure-bbin-dirs {})
    (let [out (tu/run-install {:script/lib (str (:lib maven-lib))
                               :mvn/version (-> maven-lib :coords :mvn/version)})
          out2 (tu/run-upgrade {:script/lib "http-server"})]
      (is (= maven-lib out))
      (is (= upgraded-lib out2))
      (is (fs/exists? (fs/file (dirs/bin-dir nil) (name (:lib upgraded-lib)))))
      (is (str/starts-with? (tu/run-bin-script (:lib upgraded-lib) "--help")
                            help-text))
      (is (= {'http-server upgraded-lib} (tu/run-ls))))))
