(ns babashka.bbin.util
  (:require [babashka.fs :as fs]))

(defn bbin-root [cli-opts]
  (str (or (some-> (:bbin/root cli-opts) (fs/canonicalize {:nofollow-links true}))
           (fs/expand-home "~/.bbin"))))

(defn trust-dir [cli-opts]
  (fs/file (bbin-root cli-opts) "trust"))

(defn bin-dir [cli-opts]
  (fs/file (bbin-root cli-opts) "bin"))

(defn canonicalized-cli-opts [parsed-args]
  (merge (:opts parsed-args)
         (when-let [v (:local/root (:opts parsed-args))]
           {:local/root (str (fs/canonicalize v {:nofollow-links true}))})))

(defn ensure-bbin-dirs [cli-opts]
  (fs/create-dirs (bin-dir cli-opts))
  (fs/create-dirs (trust-dir cli-opts)))