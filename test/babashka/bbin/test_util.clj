(ns babashka.bbin.test-util
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.edn :as edn]
            [babashka.bbin :as bbin]))

(def test-dir
  (doto (str (fs/file (fs/temp-dir) "bbin-test"))
    (fs/delete-on-exit)))

(def bbin-root (str (fs/file test-dir "bbin")))

(defn reset-test-dir []
  (fs/delete-tree test-dir))

(defn bbin [cli-args & {:as opts}]
  (let [out (str/trim (with-out-str (apply bbin/-main cli-args)))]
    (if (#{:edn} (:out opts))
      (edn/read-string out)
      out)))