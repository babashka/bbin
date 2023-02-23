(ns babashka.bbin.util
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [babashka.bbin.meta :as meta]
            [clojure.pprint :as pprint]
            [clojure.string :as str]
            [taoensso.timbre :as log]
            [clojure.edn :as edn]))

(defn is-tty
  [fd key]
  (-> ["test" "-t" (str fd)]
      (p/process {key :inherit :env {}})
      deref
      :exit
      (= 0)))

(defn user-home []
  (System/getProperty "user.home"))

(defn sh [cmd & {:as opts}]
  (doto (p/sh cmd (merge {:err :inherit} opts))
    p/check))

(defn set-logging-config! [{:keys [debug]}]
  (log/merge-config! {:min-level (if debug :debug :warn)}))

(defn pprint [x & _]
  (binding [*print-namespace-maps* false]
    (pprint/pprint x)))

(defn upgrade-enabled? []
  (some-> (System/getenv "BABASHKA_BBIN_FLAG_UPGRADE")
          edn/read-string))

(defn print-table
  "Print table to stdout.

  Examples:
  ;; extract columns from rows
  (print-table [{:a \"one\" :b \"two\"}])

  a    b
  ───  ───
  one  two

  ;; provide columns (as b is an empty column, it will be skipped)
  (print-table [:a :b] [{:a \"one\" :b nil}])

  a
  ───
  one

  ;; ensure all columns being shown:
  (print-table [:a :b] [{:a \"one\"}] {:show-empty-columns true})

  ;; provide columns with labels and apply column coercion
  (print-table {:a \"option A\" :b \"option B\"} [{:a \"one\" :b nil}]
               {:column-coercions {:b (fnil boolean false)}})

  option A  option B
  ────────  ────────
  one       false


  Options:
  - `column-coercions` (`{}`) fn that given a key `k` yields an fn to be applied to every `(k row)` *iff* row contains key `k`.
    See example above.
  - `skip-header` (`false`) don't print column names and divider (typically use this when stdout is no tty).
  - `show-empty-columns` (`false`) print every column, even if it results in empty columns.
  - `no-color` (`false`) prevent printing escape characters to stdout."
  ([rows] (print-table (keys (first rows)) rows nil))
  ([ks rows] (print-table ks rows nil))
  ([ks rows {:keys [show-empty-columns skip-header no-color column-coercions]
             :or   {show-empty-columns false skip-header false no-color false column-coercions {}}}]
   (let [wrap-bold            (fn [s] (if no-color s (str "\033[1m" s "\033[0m")))
         row-get              (fn [row k]
                                (when (contains? row k)
                                  ((column-coercions k identity) (k row))))
         key->label           (if (map? ks) ks #(subs (str (keyword %)) 1))
         header-keys          (if (map? ks) (keys ks) ks)
         ;; ensure all header-keys exist for every row and every value is a string
         rows                 (map (fn [row]
                                     (reduce (fn [acc k]
                                               (assoc acc k (str (row-get row k)))) {} header-keys)) rows)
         header-keys          (if show-empty-columns
                                header-keys
                                (let [non-empty-cols (remove
                                                      (fn [k] (every? str/blank? (map k rows)))
                                                      header-keys)]
                                  (filter (set non-empty-cols) header-keys)))
         header-labels        (map key->label header-keys)
         column-widths        (reduce (fn [acc k]
                                        (let [val-widths (map count (cons (key->label k) (map k rows)))]
                                          (assoc acc k (apply max val-widths)))) {} header-keys)
         row-fmt              (str/join "  " (map #(str "%-" (column-widths %) "s") header-keys))
         cells->formatted-row #(apply format row-fmt %)
         header-row           (wrap-bold
                               (cells->formatted-row header-labels))
         div-row              (wrap-bold
                               (cells->formatted-row
                                (map (fn [k]
                                       (apply str (take (column-widths k) (repeat \u2500)))) header-keys)))
         data-rows            (map #(cells->formatted-row (map % header-keys)) rows)]
     (when (seq header-keys)
       (let [header (if skip-header (vector) (vector header-row div-row))]
         (println (apply str (interpose \newline (into header data-rows)))))))))

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
