(ns babashka.bbin.scripts.local-dir-test
  (:require [babashka.bbin.dirs :as dirs]
            [babashka.bbin.test-util :as tu]
            [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]])
  (:import (clojure.lang ExceptionInfo)))

(use-fixtures :once (tu/bbin-dirs-fixture))

(defn- bin-contents [script-name]
  (slurp (fs/file (dirs/bin-dir nil) script-name)))

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

(deftest install-from-deps-edn-only-dir-test
  (testing "install ./ for a project with deps.edn but no bb.edn"
    (tu/reset-test-dir)
    (dirs/ensure-bbin-dirs {})
    (let [local-root (str (fs/file tu/test-dir "deps-only"))]
      (fs/create-dirs (fs/file local-root "src"))
      (spit (fs/file local-root "deps.edn") (pr-str {:paths ["src"]}))
      (spit (fs/file local-root "src" "deps_only_script.clj")
            "(ns deps-only-script)\n(defn -main [& args] (println \"deps-only-ok\" (vec args)))\n")
      ;; --main-opts is provided as a string (as the CLI passes it) to exercise
      ;; edn parsing of main-opts on the dir path.
      (let [cli-opts {:script/lib local-root
                      :main-opts "[\"-m\" \"deps-only-script\"]"
                      :as "deps-only"}]
        (tu/run-install cli-opts)
        (is (fs/exists? (fs/file (dirs/bin-dir nil) "deps-only")))
        (is (str/includes? (tu/run-bin-script "deps-only" "a" "b")
                           "deps-only-ok [a b]"))))))

(deftest install-from-bb-edn-only-dir-test
  (testing "install ./ for a project with bb.edn but no deps.edn"
    (tu/reset-test-dir)
    (dirs/ensure-bbin-dirs {})
    (let [local-root (str (fs/file tu/test-dir "bb-only"))]
      (fs/create-dirs (fs/file local-root "src"))
      (spit (fs/file local-root "bb.edn") (pr-str {:paths ["src"]}))
      (spit (fs/file local-root "src" "bb_only_script.clj")
            "(ns bb-only-script)\n(defn -main [& args] (println \"bb-only-ok\" (vec args)))\n")
      (let [cli-opts {:script/lib local-root
                      :main-opts "[\"-m\" \"bb-only-script\"]"
                      :as "bb-only"}]
        (tu/run-install cli-opts)
        (is (str/includes? (tu/run-bin-script "bb-only" "a" "b")
                           "bb-only-ok [a b]"))
        (let [contents (bin-contents "bb-only")]
          (is (str/includes? contents "(def script-config"))
          (is (str/includes? contents "[\"bb\" \"--config\" script-config]")))))))

(deftest install-from-dir-without-config-files-test
  (testing "install ./ for a project with neither bb.edn nor deps.edn"
    (tu/reset-test-dir)
    (dirs/ensure-bbin-dirs {})
    (let [local-root (str (fs/file tu/test-dir "no-config"))]
      (fs/create-dir local-root)
      (spit (fs/file local-root "main.clj")
            "(println \"no-config-ok\" (vec *command-line-args*))\n")
      (let [cli-opts {:script/lib local-root
                      :main-opts "[\"-f\" \"main.clj\"]"
                      :as "no-config"}]
        (tu/run-install cli-opts)
        (is (str/includes? (tu/run-bin-script "no-config" "a" "b")
                           "no-config-ok [a b]"))
        (is (not (str/includes? (bin-contents "no-config") "\"--config\"")))))))

(deftest install-from-both-config-dir-test
  (testing "install ./ prefers deps.edn when both deps.edn and bb.edn exist"
    (tu/reset-test-dir)
    (dirs/ensure-bbin-dirs {})
    (let [local-root (str (fs/file tu/test-dir "both-config"))]
      (fs/create-dirs (fs/file local-root "deps-src"))
      (fs/create-dirs (fs/file local-root "bb-src"))
      (spit (fs/file local-root "deps.edn") (pr-str {:paths ["deps-src"]}))
      (spit (fs/file local-root "bb.edn") (pr-str {:paths ["bb-src"]}))
      (spit (fs/file local-root "deps-src" "both_script.clj")
            "(ns both-script)\n(defn -main [& _] (println \"deps-config-ok\"))\n")
      (spit (fs/file local-root "bb-src" "both_script.clj")
            "(ns both-script)\n(defn -main [& _] (println \"bb-config-ok\"))\n")
      (tu/run-install {:script/lib local-root
                       :main-opts "[\"-m\" \"both-script\"]"
                       :as "both-default"})
      (is (str/includes? (tu/run-bin-script "both-default")
                         "deps-config-ok"))
      (is (str/includes? (bin-contents "both-default") "{:local/root"))
      (is (not (str/includes? (bin-contents "both-default")
                              "(def script-config")))

      (testing "CLI --bb-opts can select bb.edn"
        (tu/run-install {:script/lib local-root
                         :main-opts "[\"-m\" \"both-script\"]"
                         :bb-opts "[\"--config\" \"bb.edn\"]"
                         :as "both-cli-config"})
        (is (str/includes? (tu/run-bin-script "both-cli-config")
                           "bb-config-ok"))
        (is (str/includes? (bin-contents "both-cli-config")
                           "[\"bb\" \"--config\" script-config]")))

      (testing "author :bbin/bb-opts can select bb.edn"
        (spit (fs/file local-root "bb.edn")
              (pr-str {:paths ["bb-src"]
                       :bbin/bb-opts ["--config" "bb.edn"]}))
        (tu/run-install {:script/lib local-root
                         :main-opts "[\"-m\" \"both-script\"]"
                         :as "both-author-config"})
        (is (str/includes? (tu/run-bin-script "both-author-config")
                           "bb-config-ok"))
        (is (str/includes? (bin-contents "both-author-config")
                           "[\"bb\" \"--config\" script-config]"))))))

(deftest install-dir-without-main-opts-test
  (testing "install ./ with no :bbin/bin and no --main-opts throws"
    (tu/reset-test-dir)
    (dirs/ensure-bbin-dirs {})
    (let [local-root (str (fs/file tu/test-dir "no-opts"))]
      (fs/create-dir local-root)
      (spit (fs/file local-root "bb.edn") (pr-str {}))
      (spit (fs/file local-root "deps.edn") (pr-str {}))
      (is (thrown-with-msg? ExceptionInfo #"Main opts not found"
                            (tu/run-install {:script/lib local-root}))))))

(deftest install-invalid-coordinates-test
  (testing "install of a nonexistent path throws a friendly error"
    (tu/reset-test-dir)
    (dirs/ensure-bbin-dirs {})
    (let [missing (str (fs/file tu/test-dir "does-not-exist"))]
      (is (thrown-with-msg? ExceptionInfo #"Invalid script coordinates"
                            (tu/run-install {:script/lib missing}))))))

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

(deftest install-tool-with-ns-default-override-test
  (testing "install ./ --tool --ns-default overrides the derived ns-default"
    (tu/reset-test-dir)
    (dirs/ensure-bbin-dirs {})
    (let [local-root (str (fs/file tu/test-dir "ns-override"))]
      (fs/create-dirs (fs/file local-root "src" "bbin"))
      (spit (fs/file local-root "deps.edn") (pr-str {}))
      (spit (fs/file local-root "bb.edn") (pr-str {:paths ["src"]}))
      ;; The real code lives at `bbin.api`, but the lib `bbin/foo` derives a
      ;; default ns-default of `bbin.foo`; `--ns-default` must point dispatch
      ;; at `bbin.api` instead.
      (spit (fs/file local-root "src" "bbin" "api.clj")
            (str "(ns bbin.api)\n"
                 "(defn k \"just `keys`\" [m] (prn (keys m)))\n"))
      (tu/run-install {:script/lib "bbin/foo"
                       :local/root local-root
                       :as "footool"
                       :tool true
                       :ns-default "bbin.api"})
      (is (fs/exists? (fs/file (dirs/bin-dir nil) "footool")))
      (is (str/includes? (tu/run-bin-script "footool" "k" ":a" "1") "(:a)")))))

(deftest install-tool-mode-from-bbin-bin-test
  (testing "install ./ with :bbin/bin :ns-default and no --tool"
    (tu/reset-test-dir)
    (dirs/ensure-bbin-dirs {})
    (let [local-root (str (fs/file tu/test-dir "auto-tool"))]
      (fs/create-dirs (fs/file local-root "src" "bbin"))
      (spit (fs/file local-root "deps.edn") (pr-str {}))
      (spit (fs/file local-root "bb.edn")
            (pr-str {:bbin/bin {'foo {:ns-default 'bbin.foo}}}))
      (spit (fs/file local-root "src" "bbin" "foo.clj")
            (str "(ns bbin.foo)\n"
                 "(defn k \"just `keys`\" [m] (prn (keys m)))\n"
                 "(defn v \"just `vals`\" [m] (prn (vals m)))\n"))
      (tu/run-install {:script/lib local-root})
      (is (fs/exists? (fs/file (dirs/bin-dir nil) "foo")))
      (let [usage-out (tu/run-bin-script "foo")]
        (is (every? #(str/includes? usage-out %) ["`keys`" "`vals`"])))
      (is (str/includes? (tu/run-bin-script "foo" "k" ":a" "1") "(:a)"))
      (is (str/includes? (tu/run-bin-script "foo" "v" ":a" "1") "(1)")))))
