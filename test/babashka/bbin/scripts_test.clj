(ns babashka.bbin.scripts-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [babashka.bbin.test-util :refer [bbin-root bbin-root-fixture reset-test-dir test-dir]]
            [babashka.bbin.scripts :as scripts]
            [babashka.fs :as fs]
            [babashka.process :refer [sh]]
            [babashka.bbin.util :as util]
            [clojure.string :as str]
            [clojure.edn :as edn]))

(use-fixtures :once (bbin-root-fixture))

(def bbin-test-lib
  '{:lib io.github.rads/bbin-test-lib,
    :coords {:git/url "https://github.com/rads/bbin-test-lib",
             :git/tag "v0.0.1",
             :git/sha "9140acfc12d8e1567fc6164a50d486de09433919"}})

(def test-script
  (scripts/insert-script-header "#!/usr/bin/env bb" bbin-test-lib))

(deftest load-scripts-test
  (let [cli-opts {}]
    (reset-test-dir)
    (util/ensure-bbin-dirs cli-opts)
    (is (= {} (scripts/load-scripts cli-opts)))
    (spit (fs/file (util/bin-dir cli-opts) "test-script") test-script)
    (is (= '{test-script
             {:lib io.github.rads/bbin-test-lib,
              :coords {:git/url "https://github.com/rads/bbin-test-lib",
                       :git/tag "v0.0.1",
                       :git/sha "9140acfc12d8e1567fc6164a50d486de09433919"}}}
           (scripts/load-scripts cli-opts)))))

(def test-lib
  {:lib 'io.github.rads/bbin-test-lib
   :coords {:git/url "https://github.com/rads/bbin-test-lib"
            :git/tag "v0.0.1"
            :git/sha "9140acfc12d8e1567fc6164a50d486de09433919"}})

(def portal-script-url
  (str "https://gist.githubusercontent.com"
       "/rads/da8ecbce63fe305f3520637810ff9506"
       "/raw/e83305656f2d145430085d5414e2c3bff776b6e8/portal.clj"))

(defn run-install [cli-opts]
  (some-> (with-out-str (scripts/install cli-opts))
          edn/read-string))

(defn exec-cmd-line [script-name]
  (concat (when util/windows? ["cmd" "/c"]) 
    [(str "bin" fs/file-separator (name script-name))]))

(defn run-bin-script [script-name & script-args]
  (let [args (concat (exec-cmd-line script-name) script-args)
        {:keys [out]} (sh args {:dir bbin-root :err :inherit})]
    (str/trim out)))

(deftest install-from-qualified-lib-name-test
  (testing "install */*"
    (reset-test-dir)
    (util/ensure-bbin-dirs {})
    (let [cli-opts {:script/lib "io.github.rads/bbin-test-lib"}
          out (run-install cli-opts)
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
    (util/ensure-bbin-dirs {})
    (let [cli-opts {:script/lib (str (:lib maven-lib))
                    :mvn/version (-> maven-lib :coords :mvn/version)}
          out (run-install cli-opts)]
      (is (= maven-lib out))
      (is (fs/exists? (fs/file bbin-root "bin" (name (:lib maven-lib)))))
      (is (str/starts-with? (run-bin-script (:lib maven-lib) "--help")
                            "Serves static assets using web server.")))))

(deftest install-from-local-root-dir-test
  (testing "install ./"
    (reset-test-dir)
    (util/ensure-bbin-dirs {})
    (let [local-root (str (fs/file test-dir "foo"))]
      (fs/create-dir local-root)
      (spit (fs/file local-root "bb.edn") (pr-str {}))
      (spit (fs/file local-root "deps.edn") (pr-str {}))
      (let [cli-opts {:script/lib "babashka/foo"
                      :local/root local-root}
            out (run-install cli-opts)]
        (is (= {:lib 'babashka/foo
                :coords {:local/root local-root}}
               out))
        (is (fs/exists? (fs/file bbin-root "bin/foo")))))))

(deftest install-from-local-root-clj-test
  (testing "install ./*.clj"
    (reset-test-dir)
    (util/ensure-bbin-dirs {})
    (let [script-file (doto (fs/file test-dir "hello.clj")
                        (spit "#!/usr/bin/env bb\n(println \"Hello world\")"))
          cli-opts {:script/lib (str script-file)}
          out (run-install cli-opts)]
      (is (= {:coords {:bbin/url (str "file://" script-file)}} out))
      (is (= "Hello world" (run-bin-script :hello))))))

(deftest install-from-local-root-jar-test
  (testing "install ./*.jar"))

(deftest install-from-url-clj-test
  (testing "install https://*.clj"
    (reset-test-dir)
    (util/ensure-bbin-dirs {})
    (let [cli-opts {:script/lib portal-script-url}
          out (run-install cli-opts)]
      (is (= {:coords {:bbin/url portal-script-url}} out))
      (is (fs/exists? (fs/file bbin-root "bin/portal"))))))

(deftest install-from-url-jar-test
  (testing "install https://*.jar"))

(deftest install-tool-from-local-root-test
  (testing "install ./ --tool"
    (reset-test-dir)
    (util/ensure-bbin-dirs {})
    (let [opts {:script/lib "bbin/foo"
                :local/root (str "test-resources" fs/file-separator "local-tool")
                :as "footool"
                :tool true}
          _ (run-install opts)]
      (is (fs/exists? (fs/file bbin-root "bin/footool")))
      (let [usage-out (run-bin-script "footool")]
        (is (every? #(str/includes? usage-out %) ["`keys`" "`vals`"])))
      (is (str/includes? (run-bin-script "footool" "k" ":a" "1") "(:a)"))
      (is (str/includes? (run-bin-script "footool" "v" ":a" "1") "(1)")))))

(deftest uninstall-test
  (testing "uninstall foo"
    (reset-test-dir)
    (util/ensure-bbin-dirs {})
    (let [script-file (fs/file bbin-root "bin/foo")]
      (spit script-file "#!/usr/bin/env bb")
      (let [cli-opts {:script/lib "foo"}
            out (str/trim (with-out-str (scripts/uninstall cli-opts)))]
        (is (= (str "Removing " script-file) out))
        (is (not (fs/exists? script-file)))))))

(comment
  (clojure.test/run-tests))
