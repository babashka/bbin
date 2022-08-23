(ns rads.bbin-test
  (:require [clojure.test :refer [deftest is testing]]
            [rads.bbin :as bbin]
            [rads.bbin.test-util :refer [bbin bbin-root reset-test-dir test-dir]]
            [clojure.string :as str]
            [babashka.fs :as fs]
            [babashka.process :refer [sh]]))

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

(def test-lib
  {:lib 'io.github.rads/bbin-test-lib
   :coords {:git/url "https://github.com/rads/bbin-test-lib"
            :git/tag "v0.0.1"
            :git/sha "9140acfc12d8e1567fc6164a50d486de09433919"}})

(def portal-script-url
  (str "https://gist.githubusercontent.com"
       "/rads/da8ecbce63fe305f3520637810ff9506"
       "/raw/e83305656f2d145430085d5414e2c3bff776b6e8/portal.clj"))

(defn run-bin-script [script-name & script-args]
  (let [args (cons (str "bin/" (name script-name)) script-args)
        {:keys [out]} (sh args {:dir bbin-root :err :inherit})]
    (str/trim out)))

(deftest install-from-qualified-lib-name-test
  (testing "install */*"
    (reset-test-dir)
    (bbin/ensure-bbin-dirs {:bbin/root bbin-root})
    (let [args ["install" "io.github.rads/bbin-test-lib"
                "--bbin/root" bbin-root]
          out (bbin args :out :edn)
          bin-file (fs/file bbin-root "bin/hello")]
      (is (= test-lib out))
      (is (fs/exists? bin-file))
      (is (= "Hello world!" (run-bin-script 'hello))))))

(def maven-lib
  {:lib 'org.babashka/http-server
   :coords {:mvn/version "0.1.11"}})

(deftest install-from-mvn-version-test
  (testing "install */* --mvn/version *"
    (reset-test-dir)
    (bbin/ensure-bbin-dirs {:bbin/root bbin-root})
    (let [args ["install" (str (:lib maven-lib))
                "--mvn/version" (-> maven-lib :coords :mvn/version)
                "--bbin/root" bbin-root]
          out (bbin args :out :edn)]
      (is (= maven-lib out))
      (is (fs/exists? (fs/file bbin-root "bin" (name (:lib maven-lib)))))
      (is (str/starts-with? (run-bin-script (:lib maven-lib) "--help")
                            "Serves static assets using web server.")))))

(deftest install-from-local-root-dir-test
  (testing "install ./"
    (reset-test-dir)
    (bbin/ensure-bbin-dirs {:bbin/root bbin-root})
    (let [local-root (str (fs/file test-dir "foo"))]
      (fs/create-dir local-root)
      (spit (fs/file local-root "bb.edn") (pr-str {}))
      (spit (fs/file local-root "deps.edn") (pr-str {}))
      (let [args ["install" "rads/foo"
                  "--local/root" local-root
                  "--bbin/root" bbin-root]
            out (bbin args :out :edn)]
        (is (= {:lib 'rads/foo
                :coords {:local/root local-root}}
               out))
        (is (fs/exists? (fs/file bbin-root "bin/foo")))))))

(deftest install-from-local-root-clj-test
  (testing "install ./*.clj"))

(deftest install-from-local-root-jar-test
  (testing "install ./*.jar"))

(deftest install-from-url-clj-test
  (testing "install https://*.clj"
    (reset-test-dir)
    (bbin/ensure-bbin-dirs {:bbin/root bbin-root})
    (let [args ["install" portal-script-url
                "--bbin/root" bbin-root]
          out (bbin args :out :edn)]
      (is (= {:coords {:http/url portal-script-url}} out))
      (is (fs/exists? (fs/file bbin-root "bin/portal"))))))

(deftest install-from-url-jar-test
  (testing "install https://*.jar"))

(deftest uninstall-test
  (testing "uninstall foo"
    (reset-test-dir)
    (bbin/ensure-bbin-dirs {:bbin/root bbin-root})
    (let [script-file (fs/file bbin-root "bin/foo")]
      (spit script-file "#!/usr/bin/env bb")
      (let [args ["uninstall" "foo"
                  "--bbin/root" bbin-root]
            out (bbin args :out :string)]
        (is (= (str "Removing " script-file) out))
        (is (not (fs/exists? script-file)))))))

(comment
  (clojure.test/run-tests))
