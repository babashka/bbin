(ns babashka.bbin.scripts.local-dir-test
  (:require [babashka.bbin.dirs :as dirs]
            [babashka.bbin.test-util :as tu]
            [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]])
  (:import (clojure.lang ExceptionInfo)))

(use-fixtures :once (tu/bbin-dirs-fixture))

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
