(ns babashka.bbin.scripts-test
  (:require [clojure.test :refer [deftest is]]
            [babashka.bbin.test-util :refer [bbin-root reset-test-dir]]
            [babashka.bbin.scripts :as scripts]
            [babashka.fs :as fs]
            [babashka.bbin.util :as util]))

(def bbin-test-lib
  '{:lib io.github.rads/bbin-test-lib,
    :coords {:git/url "https://github.com/rads/bbin-test-lib",
             :git/tag "v0.0.1",
             :git/sha "9140acfc12d8e1567fc6164a50d486de09433919"}})

(def test-script
  (scripts/insert-script-header "#!/usr/bin/env bb" bbin-test-lib))

(deftest load-scripts-test
  (let [cli-opts {:bbin/root bbin-root}]
    (reset-test-dir)
    (util/ensure-bbin-dirs cli-opts)
    (is (= {} (scripts/load-scripts cli-opts)))
    (spit (fs/file (util/bin-dir cli-opts) "test-script") test-script)
    (is (= '{test-script
             {:lib io.github.rads/bbin-test-lib,
              :coords {:git/url "https://github.com/rads/bbin-test-lib",
                       :git/tag "v0.0.1",
                       :git/sha "9140acfc12d8e1567fc6164a50d486de09433919"}}}
           (scripts/load-scripts cli-opts)))))
