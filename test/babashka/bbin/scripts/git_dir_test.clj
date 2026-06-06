(ns babashka.bbin.scripts.git-dir-test
  (:require [babashka.bbin.dirs :as dirs]
            [babashka.bbin.test-util :as tu]
            [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]))

(use-fixtures :once
  (tu/bbin-dirs-fixture)
  (tu/bbin-private-keys-fixture))

(def bbin-test-lib
  '{:lib io.github.rads/bbin-test-lib,
    :coords {:git/url "https://github.com/rads/bbin-test-lib.git",
             :git/tag "v0.0.1",
             :git/sha "9140acfc12d8e1567fc6164a50d486de09433919"}})

(def bbin-test-lib-no-tag
  '{:lib io.github.rads/bbin-test-lib-no-tag,
    :coords {:git/url "https://github.com/rads/bbin-test-lib-no-tag.git",
             :git/sha "cefb15e3320dd4c599e8be62f7a01a00b07e2e72"}})

(def bbin-test-lib-private
  '{:lib io.bitbucket.radsmith/bbin-test-lib-private,
    :coords {:git/url "git@bitbucket.org:radsmith/bbin-test-lib-private.git"
             :git/tag "v0.0.1",
             :git/sha "9140acfc12d8e1567fc6164a50d486de09433919"}})

(defn- git! [repo args]
  (p/check (p/sh (into ["git"] args) {:dir repo :out :string :err :string})))

(defn- commit-repo! [repo]
  (doseq [args [["init"]
                ["config" "user.email" "bbin@example.invalid"]
                ["config" "user.name" "bbin test"]
                ["add" "."]
                ["commit" "-m" "init"]]]
    (git! repo args))
  (str/trim (:out (git! repo ["rev-parse" "HEAD"]))))

(defn- write-main! [repo source-dir ns-name out]
  (fs/create-dirs (fs/file repo source-dir))
  (spit (fs/file repo source-dir (str ns-name ".clj"))
        (str "(ns " ns-name ")\n"
             "(defn -main [& args] (println \"" out "\" (vec args)))\n")))

(defn- assert-git-wrapper-embeds-coords [bin-file {:keys [lib coords]}]
  (let [contents (slurp bin-file)]
    (is (str/includes? contents "(def script-root "))
    (is (str/includes? contents (str "(def script-lib '" lib ")")))
    (is (str/includes? contents (:git/url coords)))
    (is (str/includes? contents (:git/sha coords)))
    (is (str/includes? contents "(spit (str \"{:deps {\" script-lib script-coords \"}}\"))"))
    (is (str/includes? contents "[\"bb\" \"--deps-root\" script-root \"--config\" (str tmp-edn)]"))
    (is (not (str/includes? contents "(def script-config")))
    (is (not (str/includes? contents "\"bb.edn\"")))
    (is (not (str/includes? contents "\"deps.edn\"")))))

(defn- assert-git-bb-edn-wrapper [bin-file]
  (let [contents (slurp bin-file)]
    (is (str/includes? contents "(def script-config "))
    (is (str/includes? contents "/bb.edn"))
    (is (str/includes? contents "[\"bb\" \"--config\" script-config]"))
    (is (not (str/includes? contents "(def tmp-edn")))
    (is (not (str/includes? contents "\"--deps-root\"")))))

(deftest install-from-local-git-repo-without-bbin-keeps-legacy-test
  (testing "git repo without top-level :bbin uses legacy git coords"
    (tu/reset-test-dir)
    (dirs/ensure-bbin-dirs {})
    (let [repo (str (fs/file tu/test-dir "git-legacy"))
          lib 'local/git-legacy]
      (write-main! repo "src" "gitlegacy" "git-deps-ok")
      (write-main! repo "other" "gitlegacy" "git-bb-edn-wrong")
      (spit (fs/file repo "deps.edn") (pr-str {:paths ["src"]}))
      (spit (fs/file repo "bb.edn") (pr-str {:paths ["other"]
                                             :tasks {}}))
      (let [sha (commit-repo! repo)
            repo-url (str (fs/canonicalize repo {:nofollow-links true}))
            expected {:lib lib
                      :coords {:git/url repo-url
                               :git/sha sha}}
            out (tu/run-install {:script/lib (str lib)
                                 :git/url repo-url
                                 :git/sha sha
                                 :main-opts "[\"-m\" \"gitlegacy\"]"
                                 :as "git-legacy"})
            bin-file (fs/file (dirs/bin-dir nil) "git-legacy")]
        (is (= expected out))
        (is (fs/exists? bin-file))
        (assert-git-wrapper-embeds-coords bin-file expected)
        (is (str/includes? (tu/run-bin-script "git-legacy" "a")
                           "git-deps-ok [a]"))))))

(deftest install-from-local-git-repo-with-bbin-map-uses-bb-edn-test
  (testing "git repo with top-level :bbin uses the procured bb.edn"
    (tu/reset-test-dir)
    (dirs/ensure-bbin-dirs {})
    (let [repo (str (fs/file tu/test-dir "git-bbin"))
          lib 'local/git-bbin]
      (write-main! repo "src" "gitoptin" "git-bb-edn-ok")
      (write-main! repo "other" "gitoptin" "git-deps-edn-wrong")
      (spit (fs/file repo "deps.edn") (pr-str {:paths ["other"]}))
      (spit (fs/file repo "bb.edn")
            (pr-str {:paths ["src"]
                     :bbin {:bin {'gitoptin {:main-opts ["-m" "gitoptin"]}}}}))
      (let [sha (commit-repo! repo)
            repo-url (str (fs/canonicalize repo {:nofollow-links true}))
            expected {:lib lib
                      :coords {:git/url repo-url
                               :git/sha sha}}
            out (tu/run-install {:script/lib (str lib)
                                 :git/url repo-url
                                 :git/sha sha})
            bin-file (fs/file (dirs/bin-dir nil) "gitoptin")]
        (is (= expected out))
        (is (fs/exists? bin-file))
        (assert-git-bb-edn-wrapper bin-file)
        (is (str/includes? (tu/run-bin-script "gitoptin" "a")
                           "git-bb-edn-ok [a]"))))))

(deftest install-from-qualified-lib-name-public-test
  (testing "install */* (public Git repo)"
    (tu/reset-test-dir)
    (dirs/ensure-bbin-dirs {})
    (let [cli-opts {:script/lib "io.github.rads/bbin-test-lib"}
          out (tu/run-install cli-opts)
          bin-file (fs/file (dirs/bin-dir nil) "hello")]
      (is (= bbin-test-lib out))
      (is (fs/exists? bin-file))
      (assert-git-wrapper-embeds-coords bin-file bbin-test-lib)
      (is (= "Hello world!" (tu/run-bin-script 'hello))))))

(deftest install-from-qualified-lib-name-no-tag-test
  (testing "install */* (public Git repo, no tags)"
    (tu/reset-test-dir)
    (dirs/ensure-bbin-dirs {})
    (let [cli-opts {:script/lib "io.github.rads/bbin-test-lib-no-tag"}
          out (tu/run-install cli-opts)
          bin-file (fs/file (dirs/bin-dir nil) "hello")]
      (is (= bbin-test-lib-no-tag out))
      (is (fs/exists? bin-file))
      (is (= "Hello world!" (tu/run-bin-script 'hello))))))

(deftest install-from-qualified-lib-name-private-test
  (testing "install */* (private Git repo)"
    (tu/reset-test-dir)
    (dirs/ensure-bbin-dirs {})
    (let [cli-opts {:script/lib "io.bitbucket.radsmith/bbin-test-lib-private"}
          out (tu/run-install cli-opts)
          bin-file (fs/file (dirs/bin-dir nil) "hello")]
      (is (= bbin-test-lib-private out))
      (is (fs/exists? bin-file))
      (is (= "Hello world!" (tu/run-bin-script 'hello))))))

(def git-http-url-lib
  '{:lib org.babashka.bbin/script-1039504783-https-github-com-rads-bbin-test-lib-git
    :coords {:git/url "https://github.com/rads/bbin-test-lib.git"
             :git/sha "cefb15e3320dd4c599e8be62f7a01a00b07e2e72"}})

(deftest install-from-git-http-url-with-suffix-test
  (testing "install https://*.git"
    (tu/reset-test-dir)
    (dirs/ensure-bbin-dirs {})
    (let [cli-opts {:script/lib (get-in git-http-url-lib [:coords :git/url])}
          out (tu/run-install cli-opts)
          bin-file (fs/file (dirs/bin-dir nil) "hello")]
      (is (= git-http-url-lib out))
      (is (fs/exists? bin-file))
      (is (= "Hello world!" (tu/run-bin-script 'hello))))))

(deftest
  ^{:github-issue-numbers [100]}
  install-from-git-http-url-no-suffix-test
  (testing "install https://*"
    (tu/reset-test-dir)
    (dirs/ensure-bbin-dirs {})
    (let [cli-opts {:script/lib "https://github.com/rads/bbin-test-lib"}
          out (tu/run-install cli-opts)
          bin-file (fs/file (dirs/bin-dir nil) "hello")]
      (is (= git-http-url-lib out))
      (is (fs/exists? bin-file))
      (is (= "Hello world!" (tu/run-bin-script 'hello))))))

(def git-ssh-url-lib
  '{:lib org.babashka.bbin/script-1166637990-git-bitbucket-org-radsmith-bbin-test-lib-private-git
    :coords {:git/url "git@bitbucket.org:radsmith/bbin-test-lib-private.git"
             :git/sha "cefb15e3320dd4c599e8be62f7a01a00b07e2e72"}})

(deftest install-from-git-ssh-url-test
  (testing "install git@*:*.git"
    (tu/reset-test-dir)
    (dirs/ensure-bbin-dirs {})
    (let [cli-opts {:script/lib (get-in git-ssh-url-lib [:coords :git/url])}
          out (tu/run-install cli-opts)
          bin-file (fs/file (dirs/bin-dir nil) "hello")]
      (is (= git-ssh-url-lib out))
      (is (fs/exists? bin-file))
      (is (= "Hello world!" (tu/run-bin-script 'hello))))))

(deftest install-lib-fail-if-provider-not-found
  (testing "install lib name fails provider lookup"
    (tu/reset-test-dir)
    (dirs/ensure-bbin-dirs {})
    (let [cli-opts {:script/lib "something-broken"}]
      (is (thrown? Exception "Unable to find an appropriate provider" (tu/run-install cli-opts)))
      )))
