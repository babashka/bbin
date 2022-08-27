(ns babashka.bbin.trust-test
  (:require [clojure.test :refer [deftest is]]
            [babashka.bbin.trust :as trust]))

(deftest allowed-lib-test
  (let [cli-opts {}]
    (is (not (trust/allowed-lib? "io.github.foo/bar" cli-opts)))))
