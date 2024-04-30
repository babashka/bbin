(ns babashka.bbin.scripts.git-dir-test
  (:require [babashka.bbin.dirs :as dirs]
            [babashka.bbin.test-util :as tu]
            [babashka.fs :as fs]
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

(deftest install-from-qualified-lib-name-public-test
  (testing "install */* (public Git repo)"
    (tu/reset-test-dir)
    (dirs/ensure-bbin-dirs {})
    (let [cli-opts {:script/lib "io.github.rads/bbin-test-lib"}
          out (tu/run-install cli-opts)
          bin-file (fs/file (dirs/bin-dir nil) "hello")]
      (is (= bbin-test-lib out))
      (is (fs/exists? bin-file))
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

(deftest install-from-git-http-url-test
  (testing "install https://*.git"
    (tu/reset-test-dir)
    (dirs/ensure-bbin-dirs {})
    (let [cli-opts {:script/lib (get-in git-http-url-lib [:coords :git/url])}
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
