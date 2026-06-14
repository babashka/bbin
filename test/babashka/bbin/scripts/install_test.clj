(ns babashka.bbin.scripts.install-test
  (:require [babashka.bbin.scripts.install :as install]
            [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing]])
  (:import (clojure.lang ExceptionInfo)))

(defn- canonical-str [path]
  (str (fs/canonicalize path {:nofollow-links true})))

(deftest default-script-config-test
  (testing "qualified lib derives top-segment.name as the main namespace"
    (is (= {:main-opts ["-m" "borkdude.quickblog"]
            :ns-default "borkdude.quickblog"}
           (install/default-script-config 'io.github.borkdude/quickblog))))

  (testing "unqualified lib falls back to the lib name without NPEing"
    (is (= {:main-opts ["-m" "foo"]
            :ns-default "foo"}
           (install/default-script-config 'foo)))))

(deftest bb-opts-test
  (testing "valid --bb-opts"
    (is (nil? (install/validate-bb-opts nil)))
    (is (nil? (install/validate-bb-opts [])))
    (is (= ["--config" "bb.edn"]
           (install/validate-bb-opts "[\"--config\" \"bb.edn\"]")))
    (is (= "bb.edn"
           (install/bb-opts->config ["--config" "bb.edn"]))))

  (testing "invalid --bb-opts"
    (doseq [bb-opts [["--classpath" "src"]
                     ["--config=bb.edn"]
                     ["--config" "bb.edn" "--classpath" "src"]
                     ["--config" 'bb.edn]
                     123]]
      (is (thrown-with-msg? ExceptionInfo
                            #"Only --config is supported by --bb-opts"
                            (install/validate-bb-opts bb-opts))))))

(deftest dir-config-strategy-test
  (testing "deps.edn only uses generated deps config"
    (fs/with-temp-dir [dir {}]
      (spit (fs/file dir "deps.edn") (pr-str {:paths ["src"]}))
      (is (= {:strategy :generated}
             (install/dir-config-strategy dir nil)))))

  (testing "bb.edn only points directly at bb.edn"
    (fs/with-temp-dir [dir {}]
      (let [bb-edn (fs/file dir "bb.edn")]
        (spit bb-edn (pr-str {:paths ["src"]}))
        (is (= {:strategy :explicit-config
                :config (canonical-str bb-edn)}
               (install/dir-config-strategy dir nil))))))

  (testing "neither config file runs without --config"
    (fs/with-temp-dir [dir {}]
      (is (= {:strategy :no-config}
             (install/dir-config-strategy dir nil)))))

  (testing "deps.edn wins when both files exist"
    (fs/with-temp-dir [dir {}]
      (spit (fs/file dir "deps.edn") (pr-str {:paths ["deps-src"]}))
      (spit (fs/file dir "bb.edn") (pr-str {:paths ["bb-src"]}))
      (is (= {:strategy :generated}
             (install/dir-config-strategy dir nil)))))

  (testing "CLI --bb-opts override wins over project files"
    (fs/with-temp-dir [dir {}]
      (let [bb-edn (fs/file dir "bb.edn")]
        (spit (fs/file dir "deps.edn") (pr-str {:paths ["deps-src"]}))
        (spit bb-edn (pr-str {:paths ["bb-src"]}))
        (is (= {:strategy :explicit-config
                :config (canonical-str bb-edn)
                :bb-opts ["--config" (canonical-str bb-edn)]}
               (install/dir-config-strategy dir ["--config" "bb.edn"]))))))

  (testing "author :bbin/bb-opts override wins over deps.edn"
    (fs/with-temp-dir [dir {}]
      (let [bb-edn (fs/file dir "bb.edn")]
        (spit (fs/file dir "deps.edn") (pr-str {:paths ["deps-src"]}))
        (spit bb-edn (pr-str {:paths ["bb-src"]
                              :bbin/bb-opts ["--config" "bb.edn"]}))
        (is (= {:strategy :explicit-config
                :config (canonical-str bb-edn)
                :bb-opts ["--config" (canonical-str bb-edn)]}
               (install/dir-config-strategy dir nil))))))

  (testing "invalid author :bbin/bb-opts is rejected unless CLI --bb-opts wins"
    (fs/with-temp-dir [dir {}]
      (let [bb-edn (fs/file dir "bb.edn")]
        (spit (fs/file dir "deps.edn") (pr-str {:paths ["deps-src"]}))
        (spit bb-edn (pr-str {:paths ["bb-src"]
                              :bbin/bb-opts ["--classpath" "src"]}))
        (is (thrown-with-msg? ExceptionInfo
                              #"Only --config is supported by --bb-opts"
                              (install/dir-config-strategy dir nil)))
        (is (= {:strategy :explicit-config
                :config (canonical-str bb-edn)
                :bb-opts ["--config" (canonical-str bb-edn)]}
               (install/dir-config-strategy dir ["--config" "bb.edn"])))))))

(deftest write-dry-run-test
  (testing "dry-run does not write the script"
    (fs/with-temp-dir [dir {}]
      (let [bin-dir (str (fs/file dir "bin"))
            script-file (str (fs/file bin-dir "hello"))
            out (with-out-str
                  (is (= {:write {:written {"hello" {:script-path script-file}}}}
                         (install/write!
                          {:input {:cli-opts {:dry-run true}}
                           :parse {:bin-dir bin-dir
                                   :jars-dir (str (fs/file dir "jars"))
                                   :header {:coords {:bbin/url "file:///tmp/hello.clj"}}}
                           :load {}
                           :analyze {}
                           :generate {:generated {"hello" {:script-contents "(println :hello)"}}}}))))]
        (is (= {:script-file script-file
                :script-contents "(println :hello)"}
               (edn/read-string out)))
        (is (not (fs/exists? script-file)))))))

(deftest synthetic-git-url-lib-does-not-name-script-test
  (testing "git URL installs without --as or :bbin/bin do not use the synthetic deps lib as the binary name"
    (fs/with-temp-dir [dir {}]
      (spit (fs/file dir "bb.edn") (pr-str {}))
      (is (thrown-with-msg?
           ExceptionInfo
           #"Script name not found"
           (#'install/analyze
            {:parse {:coords {:git/url "https://github.com/foo/bar.git"}
                     :lib 'org.babashka.bbin/script-367715620-https-github-com-foo-bar-git
                     :synthetic-lib true}
             :load {:artifact-path (str dir)}}))))))
