(ns babashka.bbin.util
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [babashka.bbin.meta :as meta]
            [clojure.pprint :as pprint]
            [clojure.string :as str]
            [taoensso.timbre :as log])
  (:import (java.util Date)))

(defn sh [cmd & {:as opts}]
  (doto (p/sh cmd (merge {:err :inherit} opts))
    p/check))

(defn set-logging-config! [{:keys [debug]}]
  (log/merge-config! {:min-level (if debug :debug :warn)}))

(defn pprint [x _]
  (pprint/pprint x))

(defn print-help [& _]
  (println (str/trim "
Usage: bbin <command>

  bbin install    Install a script
  bbin uninstall  Remove a script
  bbin ls         List installed scripts
  bbin bin        Display bbin bin folder
  bbin version    Display bbin version")))

(defn now []
  (Date.))

(def ^:dynamic *bbin-root* (fs/expand-home "~/.bbin"))

(defn bbin-root [_]
  *bbin-root*)

(defn bin-dir [cli-opts]
  (fs/file (bbin-root cli-opts) "bin"))

(defn canonicalized-cli-opts [cli-opts]
  (merge cli-opts
         (when-let [v (:local/root cli-opts)]
           {:local/root (str (fs/canonicalize v {:nofollow-links true}))})))

(defn ensure-bbin-dirs [cli-opts]
  (fs/create-dirs (bin-dir cli-opts)))

(defn print-version [& {:as opts}]
  (if (:help opts)
    (print-help)
    (println "bbin" meta/version)))

(defn- parse-version [version]
  (mapv #(Integer/parseInt %)
        (-> version
            (str/replace "-SNAPSHOT" "")
            (str/split #"\."))))

(defn- satisfies-min-version? [current-version min-version]
  (let [[major-current minor-current patch-current] (parse-version current-version)
        [major-min minor-min patch-min] (parse-version min-version)]
    (or (> major-current major-min)
        (and (= major-current major-min)
             (or (> minor-current minor-min)
                 (and (= minor-current minor-min)
                      (>= patch-current patch-min)))))))

(defn check-min-bb-version []
  (let [current-bb-version (System/getProperty "babashka.version")]
    (when (and meta/min-bb-version (not= meta/min-bb-version :version-not-set))
      (when-not (satisfies-min-version? current-bb-version meta/min-bb-version)
        (binding [*out* *err*]
          (println (str "WARNING: this project requires babashka "
                        meta/min-bb-version " or newer, but you have: "
                        current-bb-version)))))))
