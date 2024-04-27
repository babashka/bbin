(ns babashka.bbin.cli-test
  (:require [babashka.bbin.dirs :as dirs]
            [babashka.bbin.meta :as meta]
            [babashka.bbin.test-util :refer [bbin bbin-dirs-fixture]]
            [babashka.bbin.util :as util]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]))

(use-fixtures :once (bbin-dirs-fixture))

(deftest bin-test
  (testing "bin"
    (let [out (bbin ["bin"] :out :string)]
      (is (= (str (dirs/bin-dir nil)) out)))))

(deftest help-test
  (doseq [args [["help"] ["install"] ["uninstall"]]]
    (let [out (bbin args :out :string)
          [version _ usage] (str/split-lines out)]
      (is (= version (str "Version: " meta/version)))
      (is (= usage "Usage: bbin <command>")))))

(deftest version-test
  (let [out (bbin ["--version"] :out :string)]
    (is (str/starts-with? out (str "bbin " meta/version)))))

(def expected-commands
  (cond->
   #{"commands"
     "help"
     "install"
     "uninstall"
     "migrate"
     "version"
     "ls"
     "bin"}
    (util/upgrade-enabled?) (conj "upgrade")))

(deftest commands-test
  (let [out (bbin ["commands"] :out :string)
        commands (set (str/split out #" "))]
    (is (= expected-commands commands))))

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

(deftest migrate-test
  (let [calls (atom [])
        args ["migrate"]]
    (bbin args
          :out :string
          :migrate-fn #(swap! calls conj %))
    (is (= [{}] @calls))))

(comment
  (clojure.test/run-tests))
