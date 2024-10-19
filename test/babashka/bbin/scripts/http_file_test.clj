(ns babashka.bbin.scripts.http-file-test
  (:require [babashka.bbin.dirs :as dirs]
            [babashka.bbin.test-util :as tu]
            [babashka.fs :as fs]
            [clojure.test :refer [deftest is testing use-fixtures]]))

(use-fixtures :once
  (tu/bbin-dirs-fixture)
  (tu/http-server-fixture))

(def hello-script-url
  (format "http://localhost:%d/hello.clj" tu/http-port))

(deftest install-from-url-clj-test
  (testing "install https://*.clj"
    (tu/reset-test-dir)
    (dirs/ensure-bbin-dirs {})
    (let [cli-opts {:script/lib hello-script-url}
          script-file (fs/file tu/http-public-dir "hello.clj")
          _ (spit script-file "#!/usr/bin/env bb\n(println \"Hello world\")")
          out (tu/run-install cli-opts)]
      (is (= {:coords {:bbin/url hello-script-url}} out))
      (is (fs/exists? (fs/file (dirs/bin-dir nil) "hello")))
      (is (= "Hello world" (tu/run-bin-script :hello)))
      (is (= {'hello {:coords {:bbin/url hello-script-url}}} (tu/run-ls))))))

(deftest upgrade-http-file-test
  (testing "upgrade (http file)"
    (tu/reset-test-dir)
    (dirs/ensure-bbin-dirs {})
    (let [script-file (fs/file tu/http-public-dir "hello.clj")]
      (spit script-file "#!/usr/bin/env bb\n(println \"Hello world\")")
      (tu/run-install {:script/lib hello-script-url})
      (is (= "Hello world" (tu/run-bin-script :hello)))
      (spit script-file "#!/usr/bin/env bb\n(println \"Upgraded\")")
      (tu/run-upgrade {:script/lib "hello"})
      (is (= "Upgraded" (tu/run-bin-script :hello))))))
