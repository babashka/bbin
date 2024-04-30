(ns babashka.bbin.scripts.http-file-test
  (:require [babashka.bbin.dirs :as dirs]
            [babashka.bbin.test-util :as tu]
            [babashka.fs :as fs]
            [clojure.test :refer [deftest is testing use-fixtures]]))

(use-fixtures :once (tu/bbin-dirs-fixture))

(def portal-script-url
  (str "https://gist.githubusercontent.com"
       "/rads/da8ecbce63fe305f3520637810ff9506"
       "/raw/e83305656f2d145430085d5414e2c3bff776b6e8/portal.clj"))

(deftest install-from-url-clj-test
  (testing "install https://*.clj"
    (tu/reset-test-dir)
    (dirs/ensure-bbin-dirs {})
    (let [cli-opts {:script/lib portal-script-url}
          out (tu/run-install cli-opts)]
      (is (= {:coords {:bbin/url portal-script-url}} out))
      (is (fs/exists? (fs/file (dirs/bin-dir nil) "portal")))
      (is (= {'portal {:coords {:bbin/url portal-script-url}}} (tu/run-ls))))))
