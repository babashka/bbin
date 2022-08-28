(ns babashka.bbin.util
  (:require [babashka.fs :as fs]
            [clojure.pprint :as pprint]
            [clojure.string :as str]
            [taoensso.timbre :as log])
  (:import (java.util Date)))

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
  bbin trust      Trust an identity
  bbin revoke     Stop trusting an identity")))

(defn now []
  (Date.))

(defn bbin-root [cli-opts]
  (str (or (some-> (:bbin/root cli-opts) (fs/canonicalize {:nofollow-links true}))
           (fs/expand-home "~/.bbin"))))

(defn trust-dir [cli-opts]
  (fs/file (bbin-root cli-opts) "trust"))

(defn bin-dir [cli-opts]
  (fs/file (bbin-root cli-opts) "bin"))

(defn canonicalized-cli-opts [cli-opts]
  (merge cli-opts
         (when-let [v (:local/root cli-opts)]
           {:local/root (str (fs/canonicalize v {:nofollow-links true}))})))

(defn ensure-bbin-dirs [cli-opts]
  (fs/create-dirs (bin-dir cli-opts))
  (fs/create-dirs (trust-dir cli-opts)))
