(ns babashka.bbin.migrate
  (:require [babashka.bbin.dirs :as dirs]
            [babashka.bbin.scripts :as scripts]
            [babashka.bbin.util :as util]
            [babashka.fs :as fs]
            [clojure.string :as str]))

(defmulti print-template (fn [k & _] k))

(defmethod print-template :confirm [& _]
  (println (str "We found a legacy bin dir at ~/.babashka/bbin/bin."))
  (println)
  (print "Please confirm if you'd like to start the migration steps: (yes/no) "))

(defmethod print-template :found-scripts [& _]
  (println "We found scripts in ~/.babashka/bbin/bin."))

(defmethod print-template :printable-scripts [_ & {:keys [cli-opts scripts]}]
  (scripts/print-scripts (scripts/printable-scripts scripts) cli-opts))

(defmethod print-template :prompt-move [& _]
  (println "Would you like to move these to ~/.local/bin? (yes/no)")
  (print "> "))

(defmethod print-template :migrating [& _]
  (println "Migrating..."))

(defmethod print-template :up-to-date [& _]
  (println "Up-to-date."))

(defmethod print-template :script-migrated [_ & {:keys [src dest]}]
  (println src "->" dest))

(defmethod print-template :backup [_ & {:keys [src dest]}]
  (println src "->" dest))

(defmethod print-template :cancel [& _]
  (println "Migration canceled."))

(defmethod print-template :done [& _]
  (println "Migration complete."))

(defn backup-path [s]
  (str s ".backup-" (inst-ms (util/now))))

(defn- migrate-backup [t]
  (let [src (str (dirs/legacy-bin-dir))
        dest (backup-path (dirs/legacy-bin-dir))]
    (fs/move src dest)
    (t :backup {:src src :dest dest}))
  (when (fs/exists? (dirs/legacy-jars-dir))
    (let [src (str (dirs/legacy-jars-dir))
          dest (backup-path (dirs/legacy-jars-dir))]
      (fs/move src dest)
      (t :backup {:src src :dest dest}))))

(defn- migrate-script [script-name cli-opts t]
  (let [bin-src (str (fs/file (dirs/legacy-bin-dir) (name script-name)))
        bin-dest (str (fs/file (dirs/xdg-bin-dir cli-opts) (name script-name)))
        jar-src (str (fs/file (dirs/legacy-jars-dir) (str script-name ".jar")))
        jar-dest (str (fs/file (dirs/xdg-jars-dir cli-opts) (str script-name ".jar")))]
    (fs/copy bin-src bin-dest {:replace-existing (:overwrite cli-opts)})
    (t :script-migrated {:src bin-src :dest bin-dest})
    (when (fs/exists? jar-src)
      (spit bin-dest (str/replace (slurp bin-dest) jar-src jar-dest))
      (fs/copy jar-src jar-dest {:replace-existing (:overwrite cli-opts)})
      (t :script-migrated {:src jar-src :dest jar-dest}))))

(defn migrate-auto [cli-opts]
  (let [t (if (:edn cli-opts)
            (fn [k & {:as opts}] (prn (if opts [k opts] [k])))
            print-template)]
    (if-not (dirs/using-legacy-paths?)
      (t :up-to-date)
      (let [scripts (scripts/load-scripts (dirs/legacy-bin-dir))]
        (if-not (seq scripts)
          (t :confirm)
          (do
            (println)
            (t :printable-scripts {:cli-opts cli-opts
                                   :scripts scripts})
            (println)
            (t :found-scripts)
            (println)
            (t :prompt-move)))
        (flush)
        (if-not (= "yes" (str/trim (read-line)))
          (do
            (println)
            (t :cancel))
          (do
            (dirs/ensure-xdg-dirs cli-opts)
            (if-not (seq scripts)
              (do
                (println)
                (t :migrating)
                (migrate-backup cli-opts)
                (println)
                (t :done))
              (do
                (flush)
                (println)
                (t :migrating)
                (doseq [[script-name _] scripts]
                  (migrate-script script-name cli-opts t))
                (migrate-backup t)
                (println)
                (t :done)))))))))

(defn migrate-help [_]
  (println (str/triml "
In bbin 0.2.0, we now use the XDG Base Directory Specification by default.
This means the ~/.babashka/bbin/bin path is deprecated in favor of ~/.local/bin.

To migrate your scripts automatically, run `bbin migrate auto`. We won't make
any changes without asking first.

Otherwise, you can either a) migrate manually or b) override:

  a) Migrate manually:
    - Move files in ~/.babashka/bbin/bin to ~/.local/bin
    - Move files in ~/.babashka/bbin/jars to ~/.cache/babashka/bbin/jars
    - For each script that uses a JAR, edit the line containing
      `(def script-jar ...)` to use the new \"~/.cache/babashka/bbin/jars\" path.

  b) Override:
    - Set the BABASHKA_BBIN_BIN_DIR env variable to \"$HOME/.babashka/bbin\"
    - Set the BABASHKA_BBIN_JARS_DIR env variable to \"$HOME/.babashka/jars\"
")))

(defn migrate
  ([cli-opts] (migrate :root cli-opts))
  ([command cli-opts]
   (case command
     :root (migrate-help cli-opts)
     :auto (migrate-auto cli-opts))))
