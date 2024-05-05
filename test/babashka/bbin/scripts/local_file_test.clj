(ns babashka.bbin.scripts.local-file-test
  (:require [babashka.bbin.dirs :as dirs]
            [babashka.bbin.test-util :as tu]
            [babashka.fs :as fs]
            [clojure.test :refer [deftest is testing use-fixtures]]))

(use-fixtures :once (tu/bbin-dirs-fixture))

(deftest install-from-local-root-clj-test
  (testing "install ./*.clj (with shebang)"
    (tu/reset-test-dir)
    (dirs/ensure-bbin-dirs {})
    (let [script-file (doto (fs/file tu/test-dir "hello.clj")
                        (spit "#!/usr/bin/env bb\n(println \"Hello world\")"))
          script-url (str "file://" script-file)
          cli-opts {:script/lib (str script-file)}
          out (tu/run-install cli-opts)]
      (is (= {:coords {:bbin/url (str "file://" script-file)}} out))
      (is (= "Hello world" (tu/run-bin-script :hello)))
      (is (= {'hello {:coords {:bbin/url script-url}}} (tu/run-ls)))))
  (testing "install ./*.clj (without shebang)"
    (tu/reset-test-dir)
    (dirs/ensure-bbin-dirs {})
    (let [script-file (doto (fs/file tu/test-dir "hello.clj")
                        (spit "(println \"Hello world\")"))
          script-url (str "file://" script-file)
          cli-opts {:script/lib (str script-file)}
          out (tu/run-install cli-opts)]
      (is (= {:coords {:bbin/url script-url}} out))
      (is (= "Hello world" (tu/run-bin-script :hello)))
      (is (= {'hello {:coords {:bbin/url script-url}}} (tu/run-ls))))))

(deftest install-from-local-root-bb-test
  (testing "install ./*.bb (with shebang)"
    (tu/reset-test-dir)
    (dirs/ensure-bbin-dirs {})
    (let [script-file (doto (fs/file tu/test-dir "hello.bb")
                        (spit "#!/usr/bin/env bb\n(println \"Hello world\")"))
          script-url (str "file://" script-file)
          cli-opts {:script/lib (str script-file)}
          out (tu/run-install cli-opts)]
      (is (= {:coords {:bbin/url (str "file://" script-file)}} out))
      (is (= "Hello world" (tu/run-bin-script :hello)))
      (is (= {'hello {:coords {:bbin/url script-url}}} (tu/run-ls)))))
  (testing "install ./*.bb (without shebang)"
    (tu/reset-test-dir)
    (dirs/ensure-bbin-dirs {})
    (let [script-file (doto (fs/file tu/test-dir "hello.bb")
                        (spit "(println \"Hello world\")"))
          script-url (str "file://" script-file)
          cli-opts {:script/lib (str script-file)}
          out (tu/run-install cli-opts)]
      (is (= {:coords {:bbin/url (str "file://" script-file)}} out))
      (is (= "Hello world" (tu/run-bin-script :hello)))
      (is (= {'hello {:coords {:bbin/url script-url}}} (tu/run-ls))))))

(deftest install-from-local-root-no-extension-test
  (testing "install ./* (no extension, with shebang)"
    (tu/reset-test-dir)
    (dirs/ensure-bbin-dirs {})
    (let [script-file (doto (fs/file tu/test-dir "hello")
                        (spit "#!/usr/bin/env bb\n(println \"Hello world\")"))
          script-url (str "file://" script-file)
          cli-opts {:script/lib (str script-file)}
          out (tu/run-install cli-opts)]
      (is (= {:coords {:bbin/url (str "file://" script-file)}} out))
      (is (= "Hello world" (tu/run-bin-script :hello)))
      (is (= {'hello {:coords {:bbin/url script-url}}} (tu/run-ls)))))
  (testing "install ./* (no extension, without shebang)"
    (tu/reset-test-dir)
    (dirs/ensure-bbin-dirs {})
    (let [script-file (doto (fs/file tu/test-dir "hello")
                        (spit "(println \"Hello world\")"))
          script-url (str "file://" script-file)
          cli-opts {:script/lib (str script-file)}
          out (tu/run-install cli-opts)]
      (is (= {:coords {:bbin/url (str "file://" script-file)}} out))
      (is (= "Hello world" (tu/run-bin-script :hello)))
      (is (= {'hello {:coords {:bbin/url script-url}}} (tu/run-ls))))))

(deftest upgrade-local-file-test
  (testing "upgrade (local file)"
    (tu/reset-test-dir)
    (dirs/ensure-bbin-dirs {})
    (let [script-file (fs/file tu/test-dir "hello.clj")
          script-url (str "file://" script-file)]
      (spit script-file "#!/usr/bin/env bb\n(println \"Hello world\")")
      (tu/run-install {:script/lib (str script-file)})
      (is (= "Hello world" (tu/run-bin-script :hello)))
      (spit script-file "#!/usr/bin/env bb\n(println \"Upgraded\")")
      (let [out (tu/run-upgrade {:script/lib "hello"})]
        (is (= {:coords {:bbin/url (str "file://" script-file)}} out))
        (is (= "Upgraded" (tu/run-bin-script :hello)))
        (is (= {'hello {:coords {:bbin/url script-url}}} (tu/run-ls)))))))
