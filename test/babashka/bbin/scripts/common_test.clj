(ns babashka.bbin.scripts.common-test
  (:require [babashka.bbin.scripts.common :as common]
            [clojure.test :refer [deftest is testing]]))

(deftest default-script-config-test
  (testing "qualified lib derives top-segment.name as the main namespace"
    (is (= {:main-opts ["-m" "borkdude.quickblog"]
            :ns-default "borkdude.quickblog"}
           (common/default-script-config 'io.github.borkdude/quickblog))))

  (testing "unqualified lib falls back to the lib name without NPEing"
    (is (= {:main-opts ["-m" "foo"]
            :ns-default "foo"}
           (common/default-script-config 'foo)))))
