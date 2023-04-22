(ns babashka.bbin.migrate-test
  (:require [babashka.bbin.dirs :as dirs]
            [babashka.bbin.migrate :as migrate]
            [babashka.bbin.scripts :as scripts]
            [babashka.bbin.test-util :refer [bbin-dirs-fixture
                                             reset-test-dir test-dir]]
            [babashka.bbin.util :as util]
            [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]])
  (:import (java.time Instant)))

(use-fixtures :once (bbin-dirs-fixture))

(defn- parse-edn-out [s]
  (->> (str/split-lines s)
       (remove str/blank?)
       (mapv edn/read-string)))

(deftest migrate-test
  (testing "migrate"
    (is (with-out-str (migrate/migrate {}))))

  (testing "migrate auto"
    (testing "scenario: no changes needed"
      (reset-test-dir)
      (is (= [[:up-to-date]]
             (->> (migrate/migrate :auto {:edn true})
                  (with-in-str "yes\n")
                  with-out-str
                  parse-edn-out))))
    (testing "scenario: changes needed, user declines"
      (reset-test-dir)
      (dirs/ensure-bbin-dirs {})
      (fs/create-dirs (dirs/legacy-bin-dir))
      (let [test-script (doto (fs/file test-dir "hello.clj")
                          (spit "#!/usr/bin/env bb\n(println \"Hello world\")"))]
        (with-out-str (scripts/install {:script/lib (str (fs/canonicalize test-script))}))
        (fs/move (fs/file (dirs/bin-dir nil) "hello") (fs/file (dirs/legacy-bin-dir) "hello"))
        (let [parsed-script (scripts/parse-script
                              (slurp (fs/file (dirs/legacy-bin-dir) "hello")))]
          (is (= [[:printable-scripts
                   {:cli-opts {:edn true}
                    :scripts {'hello parsed-script}}]
                  [:found-scripts]
                  [:prompt-move]
                  [:cancel]]
                 (->> (migrate/migrate :auto {:edn true})
                      (with-in-str "no\n")
                      with-out-str
                      parse-edn-out))))))
    (testing "scenario: changes needed, user accepts"
      (reset-test-dir)
      (dirs/ensure-bbin-dirs {})
      (fs/create-dirs (dirs/legacy-bin-dir))
      (let [test-script (doto (fs/file test-dir "hello.clj")
                          (spit "#!/usr/bin/env bb\n(println \"Hello world\")"))]
        (with-out-str (scripts/install {:script/lib (str (fs/canonicalize test-script))}))
        (fs/move (fs/file (dirs/bin-dir nil) "hello") (fs/file (dirs/legacy-bin-dir) "hello"))
        (binding [util/*now* (Instant/ofEpochSecond 123)]
          (let [parsed-script (scripts/parse-script
                                (slurp (fs/file (dirs/legacy-bin-dir) "hello")))]
            (is (= [[:printable-scripts {:cli-opts {:edn true}
                                         :scripts {'hello parsed-script}}]
                    [:found-scripts]
                    [:prompt-move]
                    [:migrating]
                    [:script-migrated {:src (str (fs/file (dirs/legacy-bin-dir) "hello"))
                                       :dest (str (fs/file (dirs/xdg-bin-dir nil) "hello"))}]
                    [:backup {:src (str (dirs/legacy-bin-dir))
                              :dest (migrate/backup-path (dirs/legacy-bin-dir))}]
                    [:done]]
                   (->> (migrate/migrate :auto {:edn true})
                        (with-in-str "yes\n")
                        with-out-str
                        parse-edn-out)))
            (is (= [[:up-to-date]]
                   (->> (migrate/migrate :auto {:edn true})
                        (with-in-str "yes\n")
                        with-out-str
                        parse-edn-out)))))))))

(comment
  (clojure.test/run-test-var #'migrate-test)
  (clojure.test/run-tests))
