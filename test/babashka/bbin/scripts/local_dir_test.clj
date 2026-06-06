(ns babashka.bbin.scripts.local-dir-test
  (:require [babashka.bbin.dirs :as dirs]
            [babashka.bbin.scripts :as scripts]
            [babashka.bbin.test-util :as tu]
            [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]])
  (:import (clojure.lang ExceptionInfo)))

(use-fixtures :once (tu/bbin-dirs-fixture))

(defn- bin-file [script-name]
  (fs/file (dirs/bin-dir nil) script-name))

(defn- bin-contents [script-name]
  (slurp (bin-file script-name)))

(defn- write-main! [local-root source-dir ns-name out]
  (fs/create-dirs (fs/file local-root source-dir))
  (spit (fs/file local-root source-dir (str ns-name ".clj"))
        (str "(ns " ns-name ")\n"
             "(defn -main [& args] (println \"" out "\" (vec args)))\n")))

(defn- assert-legacy-local-wrapper [contents]
  (is (str/includes? contents "{:deps {local/deps {:local/root "))
  (is (str/includes? contents "[\"bb\" \"--deps-root\" script-root \"--config\" (str tmp-edn)]"))
  (is (not (str/includes? contents "(def script-config")))
  (is (not (str/includes? contents "\"bb.edn\""))))

(defn- assert-config-wrapper [contents config-file]
  (let [config-path (str (fs/canonicalize config-file {:nofollow-links true}))]
    (is (str/includes? contents (str "(def script-config " (pr-str config-path) ")")))
    (is (str/includes? contents "[\"bb\" \"--config\" script-config]"))
    (is (not (str/includes? contents "(def tmp-edn")))
    (is (not (str/includes? contents "\"--deps-root\"")))))

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
        (is (thrown-with-msg? ExceptionInfo #"123 - failed: map\? spec: :babashka.bbin.specs/bin"
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

(deftest install-dir-with-bbin-map-uses-bb-edn-test
  (testing "top-level :bbin opts into bb.edn as the source of truth"
    (tu/reset-test-dir)
    (dirs/ensure-bbin-dirs {})
    (let [local-root (str (fs/file tu/test-dir "bbin-opt-in"))]
      (write-main! local-root "src" "optin" "bb-edn-ok")
      (write-main! local-root "other" "optin" "deps-edn-wrong")
      (spit (fs/file local-root "deps.edn") (pr-str {:paths ["other"]}))
      (spit (fs/file local-root "bb.edn")
            (pr-str {:paths ["src"]
                     :bbin {:bin {'optin {:main-opts ["-m" "optin"]}}}}))
      (tu/run-install {:script/lib local-root})
      (assert-config-wrapper (bin-contents "optin")
                             (fs/file local-root "bb.edn"))
      (is (str/includes? (tu/run-bin-script "optin" "a")
                         "bb-edn-ok [a]")))))

(deftest install-dir-with-task-only-bb-edn-keeps-deps-edn-test
  (testing "bb.edn without :bbin keeps the legacy deps.edn classpath"
    (tu/reset-test-dir)
    (dirs/ensure-bbin-dirs {})
    (let [local-root (str (fs/file tu/test-dir "task-only-bb-edn"))]
      (write-main! local-root "src" "legacy" "bb-edn-wrong")
      (write-main! local-root "other" "legacy" "deps-edn-ok")
      (spit (fs/file local-root "deps.edn") (pr-str {:paths ["other"]}))
      (spit (fs/file local-root "bb.edn") (pr-str {:paths ["src"]
                                                   :tasks {}}))
      (tu/run-install {:script/lib local-root
                       :main-opts "[\"-m\" \"legacy\"]"
                       :as "legacy"})
      (assert-legacy-local-wrapper (bin-contents "legacy"))
      (is (str/includes? (tu/run-bin-script "legacy" "a")
                         "deps-edn-ok [a]")))))

(deftest install-from-bb-edn-only-dir-test
  (testing "bb.edn-only projects use bb.edn"
    (tu/reset-test-dir)
    (dirs/ensure-bbin-dirs {})
    (let [local-root (str (fs/file tu/test-dir "bb-edn-only"))]
      (write-main! local-root "src" "bbonly" "bb-only-ok")
      (spit (fs/file local-root "bb.edn")
            (pr-str {:paths ["src"]
                     :bbin/bin {'bb-only {:main-opts ["-m" "bbonly"]}}}))
      (tu/run-install {:script/lib local-root})
      (assert-config-wrapper (bin-contents "bb-only")
                             (fs/file local-root "bb.edn"))
      (is (str/includes? (tu/run-bin-script "bb-only" "a")
                         "bb-only-ok [a]")))))

(deftest install-dir-without-bb-edn-or-deps-edn-test
  (testing "install ./ with neither bb.edn nor deps.edn throws a friendly error"
    (tu/reset-test-dir)
    (dirs/ensure-bbin-dirs {})
    (let [local-root (str (fs/file tu/test-dir "no-edn-files"))]
      (fs/create-dir local-root)
      (is (thrown-with-msg?
            ExceptionInfo
            (re-pattern
              (str "No bb.edn or deps.edn found in " local-root
                   ". Add a bb.edn with :paths"))
            (tu/run-install {:script/lib local-root
                             :main-opts "[\"-m\" \"missing\"]"
                             :as "missing"}))))))

(deftest install-dir-with-config-override-test
  (testing "--config overrides the automatic source-of-truth policy"
    (tu/reset-test-dir)
    (dirs/ensure-bbin-dirs {})
    (let [local-root (str (fs/file tu/test-dir "config-override"))]
      (write-main! local-root "src" "override" "config-ok")
      (write-main! local-root "other" "override" "deps-edn-wrong")
      (spit (fs/file local-root "deps.edn") (pr-str {:paths ["other"]}))
      (spit (fs/file local-root "bb.edn") (pr-str {:paths ["src"]}))
      (let [config-file (str (fs/canonicalize (fs/file local-root "bb.edn")
                                              {:nofollow-links true}))]
        (tu/run-install {:script/lib local-root
                         :main-opts "[\"-m\" \"override\"]"
                         :as "override"
                         :config "bb.edn"})
        (let [contents (bin-contents "override")
              metadata (scripts/parse-script contents)]
          (assert-config-wrapper contents config-file)
          (is (= config-file (:bbin/config metadata)))
          (is (str/includes? (tu/run-bin-script "override" "a")
                             "config-ok [a]")))))))

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
