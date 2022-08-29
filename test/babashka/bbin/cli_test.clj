(ns babashka.bbin.cli-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [babashka.bbin.test-util :refer [bbin bbin-root-fixture]]
            [clojure.string :as str]
            [babashka.bbin.util :as util]
            [clojure.set :as set]))

(use-fixtures :once (bbin-root-fixture))

(deftest bin-test
  (testing "bin"
    (let [out (bbin ["bin"] :out :string)]
      (is (= (str (util/bin-dir nil)) out)))))

(deftest help-test
  (doseq [args [["help"] ["install"] ["uninstall"] ["trust"] ["revoke"]]]
    (let [out (bbin args :out :string)]
      (is (str/starts-with? out "Usage: bbin <command>")))))

(def expected-commands
  #{"commands"
    "help"
    "install"
    "uninstall"
    "ls"
    "bin"
    "trust"
    "revoke"})

(deftest commands-test
  (let [out (bbin ["commands"] :out :string)
        commands (set (str/split out #" "))]
    (is (set/subset? expected-commands commands))))

(deftest install-test
  (let [calls (atom [])
        args ["install" "io.github.rads/watch"]]
    (bbin args
          :out :string
          :install-fn #(swap! calls conj %))
    (is (= [{:script/lib "io.github.rads/watch"}]
           @calls))))

(deftest uninstall-test
  (let [calls (atom [])
        args ["uninstall" "watch"]]
    (bbin args
          :out :string
          :uninstall-fn #(swap! calls conj %))
    (is (= [{:script/lib "watch"}]
           @calls))))

(deftest trust-test
  (let [calls (atom [])
        args ["trust" "--github/user" "foo"]]
    (bbin args
          :out :string
          :trust-fn #(swap! calls conj %))
    (is (= [{:github/user "foo"}]
           @calls))))

(deftest revoke-test
  (let [calls (atom [])
        args ["revoke" "--github/user" "foo"]]
    (bbin args
          :out :string
          :revoke-fn #(swap! calls conj %))
    (is (= [{:github/user "foo"}]
           @calls))))

(comment
  (clojure.test/run-tests))
