(ns babashka.bbin.scripts.http-jar-test
  (:require [babashka.bbin.dirs :as dirs]
            [babashka.bbin.test-util :as tu]
            [clojure.test :refer [deftest is testing use-fixtures]]))

(use-fixtures :once (tu/bbin-dirs-fixture))

(def hello-jar-url "https://raw.githubusercontent.com/rads/bbin-test-lib/main/hello.jar")

(deftest install-from-url-jar-test
  (testing "install https://*.jar"
    (tu/reset-test-dir)
    (dirs/ensure-bbin-dirs {})
    (let [cli-opts {:script/lib hello-jar-url}
          out (tu/run-install cli-opts)]
      (is (= {:coords {:bbin/url hello-jar-url}} out))
      (is (= "Hello JAR" (tu/run-bin-script :hello)))))
  (testing "install https://*.jar (reinstall)"
    (let [cli-opts {:script/lib hello-jar-url}
          out (tu/run-install cli-opts)]
      (is (= {:coords {:bbin/url hello-jar-url}} out))
      (is (= "Hello JAR" (tu/run-bin-script :hello)))
      (is (= {'hello {:coords {:bbin/url hello-jar-url}}} (tu/run-ls))))))
