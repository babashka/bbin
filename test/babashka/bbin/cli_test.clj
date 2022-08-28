(ns babashka.bbin.cli-test
  (:require [clojure.test :refer [deftest is testing]]
            [babashka.bbin.test-util :refer [bbin bbin-root]]
            [clojure.string :as str]
            [babashka.fs :as fs]))

(deftest bin-test
  (testing "bin"
    (let [out (bbin ["bin"] :out :string)]
      (is (= (str (fs/expand-home "~/.bbin/bin")) out)))
    (let [out (bbin ["bin" "--bbin/root" bbin-root] :out :string)]
      (is (= (str (fs/file bbin-root "bin")) out)))))

(deftest help-test
  (doseq [args [["help"] ["install"] ["uninstall"] ["trust"] ["revoke"]]]
    (let [out (bbin args :out :string)]
      (is (str/starts-with? out "Usage: bbin <command>")))))

(deftest install-test
  (let [calls (atom [])
        args ["install" "io.github.rads/watch"
              "--bbin/root" bbin-root]]
    (bbin args
          :out :string
          :install-fn #(swap! calls conj %))
    (is (= [{:script/lib "io.github.rads/watch"
             :bbin/root bbin-root}]
           @calls))))

(deftest uninstall-test
  (let [calls (atom [])
        args ["uninstall" "watch"
              "--bbin/root" bbin-root]]
    (bbin args
          :out :string
          :uninstall-fn #(swap! calls conj %))
    (is (= [{:script/lib "watch"
             :bbin/root bbin-root}]
           @calls))))

(deftest trust-test
  (let [calls (atom [])
        args ["trust" "--github/user" "foo"
              "--bbin/root" bbin-root]]
    (bbin args
          :out :string
          :trust-fn #(swap! calls conj %))
    (is (= [{:github/user "foo"
             :bbin/root bbin-root}]
           @calls))))

(deftest revoke-test
  (let [calls (atom [])
        args ["revoke" "--github/user" "foo"
              "--bbin/root" bbin-root]]
    (bbin args
          :out :string
          :revoke-fn #(swap! calls conj %))
    (is (= [{:github/user "foo"
             :bbin/root bbin-root}]
           @calls))))

(comment
  (clojure.test/run-tests))
