(ns babashka.bbin.scripts.common-test
  (:require [babashka.bbin.scripts.common :as common]
            [babashka.fs :as fs]
            [clojure.test :refer [deftest is testing]]))

(defn- canonical-str [path]
  (str (fs/canonicalize path {:nofollow-links true})))

(deftest default-script-config-test
  (testing "qualified lib derives top-segment.name as the main namespace"
    (is (= {:main-opts ["-m" "borkdude.quickblog"]
            :ns-default "borkdude.quickblog"}
           (common/default-script-config 'io.github.borkdude/quickblog))))

  (testing "unqualified lib falls back to the lib name without NPEing"
    (is (= {:main-opts ["-m" "foo"]
            :ns-default "foo"}
           (common/default-script-config 'foo)))))

(deftest bb-opts->config-test
  (is (nil? (common/bb-opts->config nil)))
  (is (nil? (common/bb-opts->config ["--classpath" "src"])))
  (is (= "bb.edn"
         (common/bb-opts->config ["--classpath" "src" "--config" "bb.edn"]))))

(deftest dir-config-strategy-test
  (testing "deps.edn only uses generated deps config"
    (fs/with-temp-dir [dir {}]
      (spit (fs/file dir "deps.edn") (pr-str {:paths ["src"]}))
      (is (= {:strategy :generated}
             (common/dir-config-strategy dir nil)))))

  (testing "bb.edn only points directly at bb.edn"
    (fs/with-temp-dir [dir {}]
      (let [bb-edn (fs/file dir "bb.edn")]
        (spit bb-edn (pr-str {:paths ["src"]}))
        (is (= {:strategy :explicit-config
                :config (canonical-str bb-edn)}
               (common/dir-config-strategy dir nil))))))

  (testing "neither config file runs without --config"
    (fs/with-temp-dir [dir {}]
      (is (= {:strategy :no-config}
             (common/dir-config-strategy dir nil)))))

  (testing "deps.edn wins when both files exist"
    (fs/with-temp-dir [dir {}]
      (spit (fs/file dir "deps.edn") (pr-str {:paths ["deps-src"]}))
      (spit (fs/file dir "bb.edn") (pr-str {:paths ["bb-src"]}))
      (is (= {:strategy :generated}
             (common/dir-config-strategy dir nil)))))

  (testing "CLI --config override wins over project files"
    (fs/with-temp-dir [dir {}]
      (let [bb-edn (fs/file dir "bb.edn")]
        (spit (fs/file dir "deps.edn") (pr-str {:paths ["deps-src"]}))
        (spit bb-edn (pr-str {:paths ["bb-src"]}))
        (is (= {:strategy :explicit-config
                :config (canonical-str bb-edn)}
               (common/dir-config-strategy dir "bb.edn"))))))

  (testing "author :bbin/bb-opts override wins over deps.edn"
    (fs/with-temp-dir [dir {}]
      (let [bb-edn (fs/file dir "bb.edn")]
        (spit (fs/file dir "deps.edn") (pr-str {:paths ["deps-src"]}))
        (spit bb-edn (pr-str {:paths ["bb-src"]
                              :bbin/bb-opts ["--config" "bb.edn"]}))
        (is (= {:strategy :explicit-config
                :config (canonical-str bb-edn)}
               (common/dir-config-strategy dir nil)))))))
