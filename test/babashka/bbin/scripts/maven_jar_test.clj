(ns babashka.bbin.scripts.maven-jar-test
  (:require [babashka.bbin.dirs :as dirs]
            [babashka.bbin.scripts :as scripts]
            [babashka.bbin.test-util
             :refer [bbin-dirs-fixture
                     bbin-private-keys-fixture
                     reset-test-dir]]
            [babashka.bbin.util :as util]
            [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]))

(use-fixtures :once
  (bbin-dirs-fixture)
  (bbin-private-keys-fixture))

(defn run-install [cli-opts]
  (some-> (with-out-str (scripts/install (assoc cli-opts :edn true)))
          edn/read-string))

(defn run-ls []
  (some-> (with-out-str (scripts/ls {:edn true}))
          edn/read-string))

(defn exec-cmd-line [script-name]
  (concat (when util/windows? ["cmd" "/c"])
          [(str (fs/canonicalize (fs/file (dirs/bin-dir nil) (name script-name)) {:nofollow-links true}))]))

(defn run-bin-script [script-name & script-args]
  (let [args (concat (exec-cmd-line script-name) script-args)
        {:keys [out]} (p/sh args {:err :inherit})]
    (str/trim out)))

(def maven-lib
  {:lib 'org.babashka/http-server
   :coords {:mvn/version "0.1.11"}})

(deftest install-from-mvn-version-test
  (testing "install */* --mvn/version *"
    (reset-test-dir)
    (dirs/ensure-bbin-dirs {})
    (let [cli-opts {:script/lib (str (:lib maven-lib))
                    :mvn/version (-> maven-lib :coords :mvn/version)}
          out (run-install cli-opts)]
      (is (= maven-lib out))
      (is (fs/exists? (fs/file (dirs/bin-dir nil) (name (:lib maven-lib)))))
      (is (str/starts-with? (run-bin-script (:lib maven-lib) "--help")
                            "Serves static assets using web server."))
      (is (= '{http-server {:lib org.babashka/http-server
                            :coords {:mvn/version "0.1.11"}}}
             (run-ls))))))
