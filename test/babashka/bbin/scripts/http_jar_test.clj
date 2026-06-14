(ns babashka.bbin.scripts.http-jar-test
  (:require [babashka.bbin.dirs :as dirs]
            [babashka.bbin.test-util :as tu]
            [babashka.fs :as fs]
            [clojure.test :refer [deftest is testing use-fixtures]]))

(use-fixtures :once
  (tu/bbin-dirs-fixture)
  (tu/http-server-fixture))

(def hello-jar-url
  (format "http://localhost:%d/hello.jar" tu/http-port))

(deftest install-from-url-jar-test
  (testing "install https://*.jar"
    (tu/reset-test-dir)
    (dirs/ensure-bbin-dirs {})
    (let [cli-opts {:script/lib hello-jar-url}
          _ (fs/copy (fs/file "test-resources" "hello.jar")
                     (fs/file tu/http-public-dir "hello.jar"))
          out (tu/run-install cli-opts)]
      (is (= {:coords {:bbin/url hello-jar-url}} out))
      (is (= "Hello JAR" (tu/run-bin-script :hello)))))
  (testing "install https://*.jar (reinstall)"
    (let [cli-opts {:script/lib hello-jar-url}
          out (tu/run-install cli-opts)]
      (is (= {:coords {:bbin/url hello-jar-url}} out))
      (is (= "Hello JAR" (tu/run-bin-script :hello)))
      (is (= {'hello {:coords {:bbin/url hello-jar-url}}} (tu/run-ls))))))

(deftest upgrade-http-jar-test
  #_(testing "upgrade (http jar)"
      (tu/reset-test-dir)
      (dirs/ensure-bbin-dirs {})
      (fs/copy (fs/file "test-resources" "hello.jar")
               (fs/file tu/http-public-dir "hello.jar"))
      (tu/run-install {:script/lib hello-jar-url})
      (is (= "Hello JAR" (tu/run-bin-script :hello)))
      (fs/copy (fs/file "test-resources" "hello2.jar")
               (fs/file tu/http-public-dir "hello.jar")
               {:replace-existing true})
      (tu/run-upgrade {:script/lib "hello"})
      (is (= "Hello JAR 2" (tu/run-bin-script :hello)))))
