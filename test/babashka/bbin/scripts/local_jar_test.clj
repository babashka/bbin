(ns babashka.bbin.scripts.local-jar-test
  (:require [babashka.bbin.dirs :as dirs]
            [babashka.bbin.test-util :as tu]
            [babashka.fs :as fs]
            [clojure.test :refer [deftest is testing use-fixtures]])
  (:import (clojure.lang ExceptionInfo)))

(use-fixtures :once (tu/bbin-dirs-fixture))

(deftest install-from-local-root-jar-test
  (testing "install ./*.jar"
    (tu/reset-test-dir)
    (dirs/ensure-bbin-dirs {})
    (let [script-jar (str "test-resources" fs/file-separator "hello.jar")
          cli-opts {:script/lib script-jar}
          out (tu/run-install cli-opts)]
      (is (= {:coords {:bbin/url (str "file://" (fs/canonicalize script-jar {:nofollow-links true}))}}
             out))
      (is (= "Hello JAR" (tu/run-bin-script :hello)))))
  (testing "install ./*.jar (no main class)"
    (tu/reset-test-dir)
    (dirs/ensure-bbin-dirs {})
    (let [script-jar (str "test-resources" fs/file-separator "hello-no-main-class.jar")
          cli-opts {:script/lib script-jar}]
      (is (thrown-with-msg? ExceptionInfo #"jar has no Main-Class" (tu/run-install cli-opts)))
      (is (not (fs/exists? (fs/file (dirs/bin-dir nil) "hello-no-main-class"))))
      (is (not (fs/exists? (fs/file (dirs/jars-dir nil) "hello-no-main-class.jar")))))))

(deftest upgrade-local-jar-test
  #_(testing "upgrade (local jar)"
      (tu/reset-test-dir)
      (dirs/ensure-bbin-dirs {})
      (let [script-jar (str "test-resources" fs/file-separator "hello.jar")
            upgraded-jar (str "test-resources" fs/file-separator "hello2.jar")
            tmp-jar (fs/file tu/test-dir "tmp" "hello.jar")]
        (fs/create-dirs (fs/file tu/test-dir "tmp"))
        (testing "before upgrade"
          (fs/copy script-jar tmp-jar)
          (tu/run-install {:script/lib (str tmp-jar)})
          (is (= "Hello JAR" (tu/run-bin-script :hello))))
        (testing "after upgrade"
          (fs/copy upgraded-jar tmp-jar {:replace-existing true})
          (tu/run-upgrade {:script/lib "hello"})
          (is (= "Hello JAR 2" (tu/run-bin-script :hello)))))))
