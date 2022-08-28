(ns babashka.bbin-test
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
  (doseq [args [["help"] ["install"] ["uninstall"]]]
    (let [out (bbin args :out :string)]
      (is (str/starts-with? out "Usage: bbin <command>")))))

(comment
  (clojure.test/run-tests))
