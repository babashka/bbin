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
                  (with-in-str "")
                  with-out-str
                  parse-edn-out))))

    (testing "scenario: changes needed, user declines"
      (reset-test-dir)
      (fs/create-dirs (dirs/legacy-bin-dir))
      (let [test-script (doto (fs/file test-dir "hello.clj")
                          (spit "#!/usr/bin/env bb\n(println \"Hello world\")"))]
        (with-out-str (scripts/install {:script/lib (str (fs/canonicalize test-script))}))
        (fs/create-dirs (dirs/legacy-bin-dir))
        (let [parsed-script (scripts/parse-script
                              (slurp (fs/file (dirs/legacy-bin-dir) "hello")))]
          (is (= [[:printable-scripts {:scripts {'hello parsed-script}}]
                  [:found-scripts]
                  [:prompt-move]
                  [:canceled]]
                 (->> (migrate/migrate :auto {:edn true})
                      (with-in-str "no\n")
                      with-out-str
                      parse-edn-out))))))

    (testing "scenario: changes needed, user accepts, no conflicts"
      (reset-test-dir)
      (fs/create-dirs (dirs/legacy-bin-dir))
      (let [test-script (doto (fs/file test-dir "hello.clj")
                          (spit "#!/usr/bin/env bb\n(println \"Hello world\")"))]
        (with-out-str (scripts/install {:script/lib (str (fs/canonicalize test-script))}))
        (binding [util/*now* (Instant/ofEpochSecond 123)]
          (let [parsed-script (scripts/parse-script
                                (slurp (fs/file (dirs/legacy-bin-dir) "hello")))]
            (is (= [[:printable-scripts {:scripts {'hello parsed-script}}]
                    [:found-scripts]
                    [:prompt-move]
                    [:migrating]
                    [:copying {:src (str (fs/file (dirs/legacy-bin-dir) "hello"))
                               :dest (str (fs/file (dirs/xdg-bin-dir nil) "hello"))}]
                    [:moving {:src (str (dirs/legacy-bin-dir))
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
                        parse-edn-out)))))))

    (testing "scenario: changes needed, user accepts, script exists, skip"
      (reset-test-dir)
      (fs/create-dirs (dirs/legacy-bin-dir))
      (let [test-script (doto (fs/file test-dir "hello.clj")
                          (spit "#!/usr/bin/env bb\n(println \"Hello world\")"))]
        (with-out-str (scripts/install {:script/lib (str (fs/canonicalize test-script))}))
        (dirs/ensure-xdg-dirs nil)
        (fs/copy (fs/file (dirs/legacy-bin-dir) "hello") (fs/file (dirs/xdg-bin-dir nil) "hello"))
        (binding [util/*now* (Instant/ofEpochSecond 123)]
          (let [parsed-script (scripts/parse-script
                                (slurp (fs/file (dirs/legacy-bin-dir) "hello")))]
            (is (= [[:printable-scripts {:scripts {'hello parsed-script}}]
                    [:found-scripts]
                    [:prompt-move]
                    [:confirm-overwrite {:dest (str (fs/file (dirs/xdg-bin-dir nil) "hello"))}]
                    [:migrating]
                    [:skipping {:src (str (fs/file (dirs/legacy-bin-dir) "hello"))}]
                    [:moving {:src (str (dirs/legacy-bin-dir))
                              :dest (migrate/backup-path (dirs/legacy-bin-dir))}]
                    [:done]]
                   (->> (migrate/migrate :auto {:edn true})
                        (with-in-str "yes\nno\n")
                        with-out-str
                        parse-edn-out)))
            (is (= [[:up-to-date]]
                   (->> (migrate/migrate :auto {:edn true})
                        (with-in-str "yes\n")
                        with-out-str
                        parse-edn-out)))))))

    (testing "scenario: changes needed, user accepts, script exists, overwrite"
      (reset-test-dir)
      (fs/create-dirs (dirs/legacy-bin-dir))
      (let [test-script (doto (fs/file test-dir "hello.clj")
                          (spit "#!/usr/bin/env bb\n(println \"Hello world\")"))]
        (with-out-str (scripts/install {:script/lib (str (fs/canonicalize test-script))}))
        (dirs/ensure-xdg-dirs nil)
        (fs/copy (fs/file (dirs/legacy-bin-dir) "hello") (fs/file (dirs/xdg-bin-dir nil) "hello"))
        (binding [util/*now* (Instant/ofEpochSecond 123)]
          (let [parsed-script (scripts/parse-script
                                (slurp (fs/file (dirs/legacy-bin-dir) "hello")))]
            (is (= [[:printable-scripts {:scripts {'hello parsed-script}}]
                    [:found-scripts]
                    [:prompt-move]
                    [:confirm-overwrite {:dest (str (fs/file (dirs/xdg-bin-dir nil) "hello"))}]
                    [:migrating]
                    [:copying {:src (str (fs/file (dirs/legacy-bin-dir) "hello"))
                               :dest (str (fs/file (dirs/xdg-bin-dir nil) "hello"))}]
                    [:moving {:src (str (dirs/legacy-bin-dir))
                              :dest (migrate/backup-path (dirs/legacy-bin-dir))}]
                    [:done]]
                   (->> (migrate/migrate :auto {:edn true})
                        (with-in-str "yes\nyes\n")
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
