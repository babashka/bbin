(ns babashka.bbin.trust-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [babashka.bbin.trust :as trust]
            [babashka.bbin.test-util :refer [bbin-root bbin-root-fixture]]
            [babashka.fs :as fs]
            [clojure.edn :as edn]
            [babashka.bbin.util :as util]
            [clojure.string :as str]))

(use-fixtures :once (bbin-root-fixture))

(deftest allowed-lib-test
  (let [cli-opts {}]
    (is (not (trust/allowed-lib? "io.github.foo/bar" cli-opts)))))

(def untrusted-script-url
  (str "https://gist.githubusercontent.com"
       "/foo/da8ecbce63fe305f3520637810ff9506"
       "/raw/e83305656f2d145430085d5414e2c3bff776b6e8/portal.clj"))

(deftest allowed-url-test
  (let [cli-opts {}]
    (is (not (trust/allowed-url? untrusted-script-url cli-opts)))))

(deftest trust-test
  (let [now (util/now)
        cli-opts {:github/user "foo"}
        out (edn/read-string (with-out-str (trust/trust cli-opts :trusted-at now)))
        trust-file (fs/file bbin-root "trust/github-user-foo.edn")
        contents {:trusted-at now}]
    (is (fs/exists? trust-file))
    (is (= {:path (str trust-file), :contents contents} out))
    (is (= contents (edn/read-string (slurp trust-file))))))

(deftest revoke-test
  (let [cli-opts {:github/user "foo"}
        trust-file (fs/file bbin-root "trust/github-user-foo.edn")
        _ (spit trust-file "{}")
        out (str/trim (with-out-str (trust/revoke cli-opts)))]
    (is (= out (str "Removing trust file:\n  " trust-file)))
    (is (not (fs/exists? trust-file)))))
