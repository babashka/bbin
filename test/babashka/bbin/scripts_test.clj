(ns babashka.bbin.scripts-test
  (:require [babashka.bbin.dirs :as dirs]
            [babashka.bbin.scripts :as scripts]
            [babashka.bbin.scripts.common :as common]
            [babashka.bbin.test-util :as tu]
            [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]))

(use-fixtures :once (tu/bbin-dirs-fixture))

(def bbin-test-lib
  '{:lib io.github.rads/bbin-test-lib,
    :coords {:git/url "https://github.com/rads/bbin-test-lib.git",
             :git/tag "v0.0.1",
             :git/sha "9140acfc12d8e1567fc6164a50d486de09433919"}})

(def test-script
  (common/insert-script-header "#!/usr/bin/env bb" bbin-test-lib))

(deftest load-scripts-test
  (let [cli-opts {}]
    (tu/reset-test-dir)
    (dirs/ensure-bbin-dirs cli-opts)
    (is (= {} (scripts/load-scripts (dirs/bin-dir nil))))
    (spit (fs/file (dirs/bin-dir nil) "test-script") test-script)
    (is (= {'test-script bbin-test-lib} (scripts/load-scripts (dirs/bin-dir nil))))))

(deftest uninstall-test
  (testing "uninstall foo"
    (tu/reset-test-dir)
    (dirs/ensure-bbin-dirs {})
    (let [script-file (fs/file (dirs/bin-dir nil) "foo")]
      (spit script-file "#!/usr/bin/env bb")
      (let [cli-opts {:script/lib "foo"}
            out (str/trim (with-out-str (scripts/uninstall cli-opts)))]
        (is (= (str "Removing " script-file) out))
        (is (not (fs/exists? script-file)))))))
