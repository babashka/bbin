(ns babashka.bbin.cli-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [babashka.bbin.test-util :refer [bbin bbin-root-fixture]]
            [clojure.string :as str]
            [babashka.bbin.meta :as meta]
            [babashka.bbin.util :as util]
            [clojure.set :as set]))

(use-fixtures :once (bbin-root-fixture))

(deftest bin-test
  (testing "bin"
    (let [out (bbin ["bin"] :out :string)]
      (is (= (str (util/bin-dir nil)) out)))))

(deftest help-test
  (doseq [args [["help"] ["install"] ["uninstall"]]]
    (let [out (bbin args :out :string)]
      (is (str/starts-with? out "Usage: bbin <command>")))))

(deftest version-test
  (let [out (bbin ["--version"] :out :string)]
    (is (str/starts-with? out (str "bbin " meta/version)))))

(def expected-commands
  #{"commands"
    "help"
    "install"
    "uninstall"
    "ls"
    "bin"})

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

(comment
  (clojure.test/run-tests))
