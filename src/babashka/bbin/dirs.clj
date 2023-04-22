(ns babashka.bbin.dirs
  (:require [babashka.fs :as fs]
            [clojure.string :as str]))

(defn user-home []
  (System/getProperty "user.home"))

(defn print-legacy-path-warning []
  (binding [*out* *err*]
    (println (str/triml "
WARNING: In bbin 0.2.0, we now use the XDG Base Directory Specification by
WARNING: default. This means the ~/.babashka/bbin/bin path is deprecated in
WARNING: favor of ~/.local/bin.
WARNING:
WARNING: To remove this message, run `bbin migrate` for further instructions.
WARNING: (We won't make any changes without asking you first.)
"))))

(def ^:dynamic *legacy-bin-dir* nil)

(defn- legacy-override-dir []
  (some-> (or (System/getenv "BABASHKA_BBIN_DIR")
              (some-> (System/getenv "XDG_DATA_HOME") (fs/file ".babashka" "bbin")))
          (fs/canonicalize {:nofollow-links true})))

(defn legacy-bin-dir-base []
  (if-let [override (legacy-override-dir)]
    (fs/file override "bin")
    (fs/file (user-home) ".babashka" "bbin" "bin")))

(defn legacy-bin-dir []
  (or *legacy-bin-dir* (legacy-bin-dir-base)))

(defn using-legacy-paths? []
  (fs/exists? (legacy-bin-dir)))

(def ^:dynamic *legacy-jars-dir* nil)

(defn legacy-jars-dir-base []
  (if-let [override (legacy-override-dir)]
    (fs/file override "jars")
    (fs/file (user-home) ".babashka" "bbin" "jars")))

(defn legacy-jars-dir []
  (or *legacy-jars-dir* (legacy-jars-dir-base)))

(defn check-legacy-paths []
  (when (using-legacy-paths?)
    (print-legacy-path-warning)))

(def ^:dynamic *xdg-bin-dir* nil)

(defn xdg-bin-dir [_]
  (or *xdg-bin-dir*
      (if-let [override (System/getenv "BABASHKA_BBIN_BIN_DIR")]
        (fs/file override)
        (fs/file (user-home) ".local" "bin"))))

(defn bin-dir [opts]
  (if (using-legacy-paths?)
    (legacy-bin-dir)
    (xdg-bin-dir opts)))

(def ^:dynamic *xdg-jars-dir* nil)

(defn xdg-jars-dir [_]
  (or *xdg-jars-dir*
      (if-let [override (System/getenv "BABASHKA_BBIN_JARS_DIR")]
        (fs/file override)
        (fs/file (fs/xdg-cache-home) "babashka" "bbin" "jars"))))

(defn jars-dir [opts]
  (if (using-legacy-paths?)
    (legacy-jars-dir)
    (xdg-jars-dir opts)))

(defn ensure-bbin-dirs [cli-opts]
  (fs/create-dirs (bin-dir cli-opts)))

(defn ensure-xdg-dirs [cli-opts]
  (fs/create-dirs (xdg-bin-dir cli-opts))
  (fs/create-dirs (xdg-jars-dir cli-opts)))
