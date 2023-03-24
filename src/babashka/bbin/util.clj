(ns babashka.bbin.util
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [babashka.bbin.meta :as meta]
            [clojure.pprint :as pprint]
            [clojure.string :as str]
            [taoensso.timbre :as log]
            [clojure.edn :as edn]))

(defn user-home []
  (System/getProperty "user.home"))

(defn sh [cmd & {:as opts}]
  (doto (p/sh cmd (merge {:err :inherit} opts))
    p/check))

(defn set-logging-config! [{:keys [debug]}]
  (log/merge-config! {:min-level (if debug :debug :warn)}))

(defn pprint [x & _]
  (pprint/pprint x))

(defn upgrade-enabled? []
  (some-> (System/getenv "BABASHKA_BBIN_FLAG_UPGRADE")
          edn/read-string))

(def help-commands
  (->> [{:command "bbin install" :doc "Install a script"}
        (when (upgrade-enabled?)
          {:command "bbin upgrade" :doc "Upgrade a script"})
        {:command "bbin uninstall" :doc "Remove a script"}
        {:command "bbin ls" :doc "List installed scripts"}
        {:command "bbin bin" :doc "Display bbin bin folder"}
        {:command "bbin version" :doc "Display bbin version"}
        {:command "bbin help" :doc "Display bbin help"}]
       (remove nil?)))

(defn print-help [& _]
  (let [max-width (apply max (map #(count (:command %)) help-commands))
        lines (->> help-commands
                   (map (fn [{:keys [command doc]}]
                          (format (str "  %-" (inc max-width) "s %s") command doc))))]
    (println (str "Usage: bbin <command>\n\n" (str/join "\n" lines)))))

(def ^:dynamic *bin-dir* nil)

(defn print-legacy-path-warning []
  (binding [*out* *err*]
    (println (str/triml "
WARNING: The ~/.babashka/bbin/bin path is deprecated in favor of ~/.local/bin.
WARNING:
WARNING: To remove this message, you can either:
WARNING:
WARNING: Migrate:
WARNING:   - Move files in ~/.babashka/bbin/bin to ~/.local/bin
WARNING:   - Move files in ~/.babashka/bbin/jars to ~/.cache/babashka/bbin/jars (if it exists)
WARNING:
WARNING: OR
WARNING:
WARNING: Override:
WARNING:   - Set the BABASHKA_BBIN_BIN_DIR env variable to \"$HOME/.babashka/bbin\"
"))))

(defn- xdg-flag-enabled? []
  (some-> (System/getenv "BABASHKA_BBIN_FLAG_XDG")
          edn/read-string))

(defn- using-legacy-paths? []
  (and (xdg-flag-enabled?)
       (fs/exists? (fs/file (user-home) ".babashka" "bbin" "bin"))))

(defn check-legacy-paths []
  (when (using-legacy-paths?)
    (print-legacy-path-warning)))

(defn- override-dir []
  (some-> (or (System/getenv "BABASHKA_BBIN_DIR")
              (some-> (System/getenv "XDG_DATA_HOME") (fs/file ".babashka" "bbin")))
          (fs/canonicalize {:nofollow-links true})))

(defn bin-dir-base [_]
  (if (xdg-flag-enabled?)
    (if-let [override (System/getenv "BABASHKA_BBIN_BIN_DIR")]
      (fs/file override)
      (fs/file (user-home) ".local" "bin"))
    (if-let [override (override-dir)]
      (fs/file override "bin")
      (fs/file (user-home) ".babashka" "bbin" "bin"))))

(defn bin-dir [opts]
  (or *bin-dir* (bin-dir-base opts)))

(defn- xdg-cache-home []
  (if-let [override (System/getenv "XDG_CACHE_HOME")]
    (fs/file override)
    (fs/file (user-home) ".cache")))

(def ^:dynamic *jars-dir* nil)

(defn jars-dir-base [_]
  (if (xdg-flag-enabled?)
    (if-let [override (System/getenv "BABASHKA_BBIN_JARS_DIR")]
      (fs/file override)
      (fs/file (xdg-cache-home) "babashka" "bbin" "jars"))
    (if-let [override (override-dir)]
      (fs/file override "jars")
      (fs/file (user-home) ".babashka" "bbin" "jars"))))

(defn jars-dir [opts]
  (or *jars-dir* (jars-dir-base opts)))

(defn canonicalized-cli-opts [cli-opts]
  (merge cli-opts
         (when-let [v (:local/root cli-opts)]
           {:local/root (str (fs/canonicalize v {:nofollow-links true}))})))

(defn ensure-bbin-dirs [cli-opts]
  (fs/create-dirs (bin-dir cli-opts)))

(def windows?
  (some-> (System/getProperty "os.name")
    (str/lower-case)
    (str/index-of "win")))

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

(defn snake-case [s]
  (str/replace s "_" "-"))

(defn valid? [spec form]
  ((requiring-resolve 'babashka.bbin.specs/valid?) spec form))

(defn explain-str [spec form]
  ((requiring-resolve 'babashka.bbin.specs/explain-str) spec form))
