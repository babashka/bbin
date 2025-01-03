(ns babashka.bbin.scripts.local-dir-test
  (:require [babashka.bbin.dirs :as dirs]
            [babashka.bbin.test-util :as tu]
            [babashka.bbin.util :refer [whenbb]]
            [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]])
  (:import (clojure.lang ExceptionInfo)))

(use-fixtures :once (tu/bbin-dirs-fixture))

(deftest install-from-lib-local-root-dir-test
  (testing "install */* --local/root *"
    (tu/reset-test-dir)
    (dirs/ensure-bbin-dirs {})
    (let [local-root (str (fs/file tu/test-dir "foo"))]
      (fs/create-dir local-root)
      (spit (fs/file local-root "bb.edn") (pr-str {}))
      (spit (fs/file local-root "deps.edn") (pr-str {}))
      (let [cli-opts {:script/lib "babashka/foo"
                      :local/root local-root}
            out (tu/run-install cli-opts)]
        (is (= {:lib 'babashka/foo
                :coords {:local/root local-root}}
               out))
        (is (fs/exists? (fs/file (dirs/bin-dir nil) "foo")))))))

(deftest invalid-bin-config-test
  (testing "install */* --local/root * (invalid bin config)"
    (tu/reset-test-dir)
    (dirs/ensure-bbin-dirs {})
    (let [local-root (str (fs/file tu/test-dir "foo"))
          invalid-config 123]
      (fs/create-dir local-root)
      (spit (fs/file local-root "bb.edn") (pr-str {:bbin/bin invalid-config}))
      (spit (fs/file local-root "deps.edn") (pr-str {}))
      (let [cli-opts {:script/lib "babashka/foo"
                      :local/root local-root}]
        (is (thrown-with-msg? ExceptionInfo #"123 - failed: map\? spec: :bbin/bin"
                              (tu/run-install cli-opts)))))))

(deftest install-from-no-lib-local-root-dir-test
  (testing "install ./"
    (tu/reset-test-dir)
    (dirs/ensure-bbin-dirs {})
    (let [local-root (str (fs/file tu/test-dir "foo"))]
      (fs/create-dir local-root)
      (spit (fs/file local-root "bb.edn")
            (pr-str {:bbin/bin {'foo {:main-opts ["-m" "babashka/foo"]}}}))
      (spit (fs/file local-root "deps.edn") (pr-str {}))
      (let [cli-opts {:script/lib local-root}
            script-url (str "file://" local-root)
            out (tu/run-install cli-opts)]
        (is (= {:coords {:bbin/url script-url}} out))
        (is (fs/exists? (fs/file (dirs/bin-dir nil) "foo")))
        (is (= {'foo {:coords {:bbin/url script-url}}} (tu/run-ls)))))))

(deftest install-tool-from-local-root-test
  (testing "install ./ --tool"
    (tu/reset-test-dir)
    (dirs/ensure-bbin-dirs {})
    (let [opts {:script/lib "bbin/foo"
                :local/root (str "test-resources" fs/file-separator "local-tool")
                :as "footool"
                :tool true}
          full-path (str (fs/canonicalize (:local/root opts) {:nofollow-links true}))
          _ (tu/run-install opts)]
      (is (fs/exists? (fs/file (dirs/bin-dir nil) "footool")))
      (let [usage-out (tu/run-bin-script "footool")]
        (is (every? #(str/includes? usage-out %) ["`keys`" "`vals`"])))
      (is (str/includes? (tu/run-bin-script "footool" "k" ":a" "1") "(:a)"))
      (is (str/includes? (tu/run-bin-script "footool" "v" ":a" "1") "(1)"))
      (is (= {'footool {:coords {:local/root full-path}
                        :lib 'bbin/foo}}
             (tu/run-ls))))))
