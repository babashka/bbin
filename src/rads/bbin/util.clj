(ns rads.bbin.util
  (:require [babashka.fs :as fs]))

(defn bbin-root [cli-opts]
  (str (or (some-> (:bbin/root cli-opts) (fs/canonicalize {:nofollow-links true}))
           (fs/expand-home "~/.bbin"))))

(defn bin-dir [cli-opts]
  (fs/file (bbin-root cli-opts) "bin"))
