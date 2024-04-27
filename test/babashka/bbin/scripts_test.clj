(ns babashka.bbin.scripts-test
  (:require [babashka.bbin.dirs :as dirs]
            [babashka.bbin.scripts :as scripts]
            [babashka.bbin.scripts.common :as common]
            [babashka.bbin.test-util :refer [bbin-dirs-fixture
                                             bbin-private-keys-fixture
                                             reset-test-dir
                                             test-dir]]
            [babashka.bbin.util :as util]
            [babashka.fs :as fs]
            [babashka.process :refer [sh]]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]])
  (:import (clojure.lang ExceptionInfo)))

(use-fixtures :once
  (bbin-dirs-fixture)
  (bbin-private-keys-fixture))

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

(def test-script
  (common/insert-script-header "#!/usr/bin/env bb" bbin-test-lib))

(deftest load-scripts-test
  (let [cli-opts {}]
    (reset-test-dir)
    (dirs/ensure-bbin-dirs cli-opts)
    (is (= {} (scripts/load-scripts (dirs/bin-dir nil))))
    (spit (fs/file (dirs/bin-dir nil) "test-script") test-script)
    (is (= {'test-script bbin-test-lib} (scripts/load-scripts (dirs/bin-dir nil))))))

(def portal-script-url
  (str "https://gist.githubusercontent.com"
       "/rads/da8ecbce63fe305f3520637810ff9506"
       "/raw/e83305656f2d145430085d5414e2c3bff776b6e8/portal.clj"))

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
        {:keys [out]} (sh args {:err :inherit})]
    (str/trim out)))

(deftest install-from-qualified-lib-name-public-test
  (testing "install */* (public Git repo)"
    (reset-test-dir)
    (dirs/ensure-bbin-dirs {})
    (let [cli-opts {:script/lib "io.github.rads/bbin-test-lib"}
          out (run-install cli-opts)
          bin-file (fs/file (dirs/bin-dir nil) "hello")]
      (is (= bbin-test-lib out))
      (is (fs/exists? bin-file))
      (is (= "Hello world!" (run-bin-script 'hello))))))

(deftest install-from-qualified-lib-name-no-tag-test
  (testing "install */* (public Git repo, no tags)"
    (reset-test-dir)
    (dirs/ensure-bbin-dirs {})
    (let [cli-opts {:script/lib "io.github.rads/bbin-test-lib-no-tag"}
          out (run-install cli-opts)
          bin-file (fs/file (dirs/bin-dir nil) "hello")]
      (is (= bbin-test-lib-no-tag out))
      (is (fs/exists? bin-file))
      (is (= "Hello world!" (run-bin-script 'hello))))))

(deftest install-from-qualified-lib-name-private-test
  (testing "install */* (private Git repo)"
    (reset-test-dir)
    (dirs/ensure-bbin-dirs {})
    (let [cli-opts {:script/lib "io.bitbucket.radsmith/bbin-test-lib-private"}
          out (run-install cli-opts)
          bin-file (fs/file (dirs/bin-dir nil) "hello")]
      (is (= bbin-test-lib-private out))
      (is (fs/exists? bin-file))
      (is (= "Hello world!" (run-bin-script 'hello))))))

(def git-http-url-lib
  '{:lib org.babashka.bbin/script-1039504783-https-github-com-rads-bbin-test-lib-git
    :coords {:git/url "https://github.com/rads/bbin-test-lib.git"
             :git/sha "cefb15e3320dd4c599e8be62f7a01a00b07e2e72"}})

(deftest install-from-git-http-url-test
  (testing "install https://*.git"
    (reset-test-dir)
    (dirs/ensure-bbin-dirs {})
    (let [cli-opts {:script/lib (get-in git-http-url-lib [:coords :git/url])}
          out (run-install cli-opts)
          bin-file (fs/file (dirs/bin-dir nil) "hello")]
      (is (= git-http-url-lib out))
      (is (fs/exists? bin-file))
      (is (= "Hello world!" (run-bin-script 'hello))))))

(def git-ssh-url-lib
  '{:lib org.babashka.bbin/script-1166637990-git-bitbucket-org-radsmith-bbin-test-lib-private-git
    :coords {:git/url "git@bitbucket.org:radsmith/bbin-test-lib-private.git"
             :git/sha "cefb15e3320dd4c599e8be62f7a01a00b07e2e72"}})

(deftest install-from-git-ssh-url-test
  (testing "install git@*:*.git"
    (reset-test-dir)
    (dirs/ensure-bbin-dirs {})
    (let [cli-opts {:script/lib (get-in git-ssh-url-lib [:coords :git/url])}
          out (run-install cli-opts)
          bin-file (fs/file (dirs/bin-dir nil) "hello")]
      (is (= git-ssh-url-lib out))
      (is (fs/exists? bin-file))
      (is (= "Hello world!" (run-bin-script 'hello))))))

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

(deftest install-from-lib-local-root-dir-test
  (testing "install */* --local/root *"
    (reset-test-dir)
    (dirs/ensure-bbin-dirs {})
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
        (is (fs/exists? (fs/file (dirs/bin-dir nil) "foo")))))))

(deftest invalid-bin-config-test
  (testing "install */* --local/root * (invalid bin config)"
    (reset-test-dir)
    (dirs/ensure-bbin-dirs {})
    (let [local-root (str (fs/file test-dir "foo"))
          invalid-config 123]
      (fs/create-dir local-root)
      (spit (fs/file local-root "bb.edn") (pr-str {:bbin/bin invalid-config}))
      (spit (fs/file local-root "deps.edn") (pr-str {}))
      (let [cli-opts {:script/lib "babashka/foo"
                      :local/root local-root}]
        (is (thrown-with-msg? ExceptionInfo #"Spec failed"
                              (run-install cli-opts)))))))

(deftest install-from-no-lib-local-root-dir-test
  (testing "install ./"
    (reset-test-dir)
    (dirs/ensure-bbin-dirs {})
    (let [local-root (str (fs/file test-dir "foo"))]
      (fs/create-dir local-root)
      (spit (fs/file local-root "bb.edn")
            (pr-str {:bbin/bin {'foo {:main-opts ["-m" "babashka/foo"]}}}))
      (spit (fs/file local-root "deps.edn") (pr-str {}))
      (let [cli-opts {:script/lib local-root}
            script-url (str "file://" local-root)
            out (run-install cli-opts)]
        (is (= {:coords {:bbin/url script-url}} out))
        (is (fs/exists? (fs/file (dirs/bin-dir nil) "foo")))
        (is (= {'foo {:coords {:bbin/url script-url}}} (run-ls)))))))

(deftest install-from-local-root-clj-test
  (testing "install ./*.clj (with shebang)"
    (reset-test-dir)
    (dirs/ensure-bbin-dirs {})
    (let [script-file (doto (fs/file test-dir "hello.clj")
                        (spit "#!/usr/bin/env bb\n(println \"Hello world\")"))
          script-url (str "file://" script-file)
          cli-opts {:script/lib (str script-file)}
          out (run-install cli-opts)]
      (is (= {:coords {:bbin/url (str "file://" script-file)}} out))
      (is (= "Hello world" (run-bin-script :hello)))
      (is (= {'hello {:coords {:bbin/url script-url}}} (run-ls)))))
  (testing "install ./*.clj (without shebang)"
    (reset-test-dir)
    (dirs/ensure-bbin-dirs {})
    (let [script-file (doto (fs/file test-dir "hello.clj")
                        (spit "(println \"Hello world\")"))
          script-url (str "file://" script-file)
          cli-opts {:script/lib (str script-file)}
          out (run-install cli-opts)]
      (is (= {:coords {:bbin/url script-url}} out))
      (is (= "Hello world" (run-bin-script :hello)))
      (is (= {'hello {:coords {:bbin/url script-url}}} (run-ls))))))

(deftest install-from-local-root-bb-test
  (testing "install ./*.bb (with shebang)"
    (reset-test-dir)
    (dirs/ensure-bbin-dirs {})
    (let [script-file (doto (fs/file test-dir "hello.bb")
                        (spit "#!/usr/bin/env bb\n(println \"Hello world\")"))
          script-url (str "file://" script-file)
          cli-opts {:script/lib (str script-file)}
          out (run-install cli-opts)]
      (is (= {:coords {:bbin/url (str "file://" script-file)}} out))
      (is (= "Hello world" (run-bin-script :hello)))
      (is (= {'hello {:coords {:bbin/url script-url}}} (run-ls)))))
  (testing "install ./*.bb (without shebang)"
    (reset-test-dir)
    (dirs/ensure-bbin-dirs {})
    (let [script-file (doto (fs/file test-dir "hello.bb")
                        (spit "(println \"Hello world\")"))
          script-url (str "file://" script-file)
          cli-opts {:script/lib (str script-file)}
          out (run-install cli-opts)]
      (is (= {:coords {:bbin/url (str "file://" script-file)}} out))
      (is (= "Hello world" (run-bin-script :hello)))
      (is (= {'hello {:coords {:bbin/url script-url}}} (run-ls))))))

(deftest install-from-local-root-no-extension-test
  (testing "install ./* (no extension, with shebang)"
    (reset-test-dir)
    (dirs/ensure-bbin-dirs {})
    (let [script-file (doto (fs/file test-dir "hello")
                        (spit "#!/usr/bin/env bb\n(println \"Hello world\")"))
          script-url (str "file://" script-file)
          cli-opts {:script/lib (str script-file)}
          out (run-install cli-opts)]
      (is (= {:coords {:bbin/url (str "file://" script-file)}} out))
      (is (= "Hello world" (run-bin-script :hello)))
      (is (= {'hello {:coords {:bbin/url script-url}}} (run-ls)))))
  (testing "install ./* (no extension, without shebang)"
    (reset-test-dir)
    (dirs/ensure-bbin-dirs {})
    (let [script-file (doto (fs/file test-dir "hello")
                        (spit "(println \"Hello world\")"))
          script-url (str "file://" script-file)
          cli-opts {:script/lib (str script-file)}
          out (run-install cli-opts)]
      (is (= {:coords {:bbin/url (str "file://" script-file)}} out))
      (is (= "Hello world" (run-bin-script :hello)))
      (is (= {'hello {:coords {:bbin/url script-url}}} (run-ls))))))

(deftest install-from-local-root-jar-test
  (testing "install ./*.jar"
    (reset-test-dir)
    (dirs/ensure-bbin-dirs {})
    (let [script-jar (str "test-resources" fs/file-separator "hello.jar")
          cli-opts {:script/lib script-jar}
          out (run-install cli-opts)]
      (is (= {:coords {:bbin/url (str "file://" (fs/canonicalize script-jar {:nofollow-links true}))}}
             out))
      (is (= "Hello JAR" (run-bin-script :hello)))))
  (testing "install ./*.jar (no main class)"
    (reset-test-dir)
    (dirs/ensure-bbin-dirs {})
    (let [script-jar (str "test-resources" fs/file-separator "hello-no-main-class.jar")
          cli-opts {:script/lib script-jar}]
      (is (thrown-with-msg? ExceptionInfo #"jar has no Main-Class" (run-install cli-opts)))
      (is (not (fs/exists? (fs/file (dirs/bin-dir nil) "hello-no-main-class"))))
      (is (not (fs/exists? (fs/file (dirs/jars-dir nil) "hello-no-main-class.jar")))))))

(deftest install-from-url-clj-test
  (testing "install https://*.clj"
    (reset-test-dir)
    (dirs/ensure-bbin-dirs {})
    (let [cli-opts {:script/lib portal-script-url}
          out (run-install cli-opts)]
      (is (= {:coords {:bbin/url portal-script-url}} out))
      (is (fs/exists? (fs/file (dirs/bin-dir nil) "portal")))
      (is (= {'portal {:coords {:bbin/url portal-script-url}}} (run-ls))))))

(def hello-jar-url "https://raw.githubusercontent.com/rads/bbin-test-lib/main/hello.jar")

(deftest install-from-url-jar-test
  (testing "install https://*.jar"
    (reset-test-dir)
    (dirs/ensure-bbin-dirs {})
    (let [cli-opts {:script/lib hello-jar-url}
          out (run-install cli-opts)]
      (is (= {:coords {:bbin/url hello-jar-url}} out))
      (is (= "Hello JAR" (run-bin-script :hello)))))
  (testing "install https://*.jar (reinstall)"
    (let [cli-opts {:script/lib hello-jar-url}
          out (run-install cli-opts)]
      (is (= {:coords {:bbin/url hello-jar-url}} out))
      (is (= "Hello JAR" (run-bin-script :hello)))
      (is (= {'hello {:coords {:bbin/url hello-jar-url}}} (run-ls))))))

(deftest install-tool-from-local-root-test
  (testing "install ./ --tool"
    (reset-test-dir)
    (dirs/ensure-bbin-dirs {})
    (let [opts {:script/lib "bbin/foo"
                :local/root (str "test-resources" fs/file-separator "local-tool")
                :as "footool"
                :tool true}
          full-path (str (fs/canonicalize (:local/root opts) {:nofollow-links true}))
          _ (run-install opts)]
      (is (fs/exists? (fs/file (dirs/bin-dir nil) "footool")))
      (let [usage-out (run-bin-script "footool")]
        (is (every? #(str/includes? usage-out %) ["`keys`" "`vals`"])))
      (is (str/includes? (run-bin-script "footool" "k" ":a" "1") "(:a)"))
      (is (str/includes? (run-bin-script "footool" "v" ":a" "1") "(1)"))
      (is (= {'footool {:coords {:local/root full-path}
                        :lib 'bbin/foo}}
             (run-ls))))))

(deftest uninstall-test
  (testing "uninstall foo"
    (reset-test-dir)
    (dirs/ensure-bbin-dirs {})
    (let [script-file (fs/file (dirs/bin-dir nil) "foo")]
      (spit script-file "#!/usr/bin/env bb")
      (let [cli-opts {:script/lib "foo"}
            out (str/trim (with-out-str (scripts/uninstall cli-opts)))]
        (is (= (str "Removing " script-file) out))
        (is (not (fs/exists? script-file)))))))

(comment
  (clojure.test/run-tests))
