(ns babashka.bbin.migrate
  (:require [babashka.bbin.dirs :as dirs]
            [babashka.bbin.scripts :as scripts]
            [babashka.bbin.util :as util]
            [babashka.fs :as fs]
            [clojure.string :as str]
            [taoensso.timbre :as log]))

(def templates
  {:found-scripts
   (fn [{:keys [cli-opts]}]
     (let [{:keys [overwrite]} cli-opts]
       (println (util/bold "We found scripts in ~/.babashka/bbin/bin." cli-opts))
       (if overwrite
         (println "The --overwrite option was enabled. We'll continue the migration without prompting.")
         (do
           (println "We'll ask you to confirm each script individually before overwriting.")
           (println "Re-run this command with --overwrite to migrate all scripts without confirming.")))))

   :printable-scripts
   (fn [{:keys [scripts cli-opts]}]
     (util/print-scripts (util/printable-scripts scripts) cli-opts))

   :prompt-move
   (fn [{:keys [cli-opts]}]
     (println (util/bold "Would you like to move your scripts to ~/.local/bin? (yes/no)" cli-opts))
     (print "> "))

   :migrating
   (fn [{:keys [cli-opts]}]
     (println (util/bold "Migrating..." cli-opts)))

   :up-to-date
   (fn [_]
     (println "Up-to-date."))

   :copying
   (fn [{:keys [src dest]}]
     (println "Copying" src "to" dest))

   :moving
   (fn [{:keys [src dest]}]
     (println "Moving" src "to" dest))

   :canceled
   (fn [{:keys [cli-opts]}]
     (println (util/bold "Migration canceled." cli-opts)))

   :done
   (fn [{:keys [cli-opts]}]
     (println (util/bold "Migration complete." cli-opts)))

   :confirm-overwrite
   (fn [{:keys [dest cli-opts]}]
     (println (util/bold (str dest " already exists. Overwrite? (yes/no) ")
                         cli-opts))
     (print "> ")
     (flush))

   :skipping
   (fn [{:keys [src]}]
     (println "Skipping" src))})

(defn- printer [cli-opts]
  (if (:edn cli-opts)
    (fn [k & {:as opts}] (prn (if opts [k opts] [k])))
    (fn [k & {:as opts}] ((get templates k) (assoc opts :cli-opts cli-opts)))))

(defn backup-path [s]
  (str s ".backup-" (inst-ms (util/now))))

(defn- create-backup [t]
  (let [src (str (dirs/legacy-bin-dir))
        dest (backup-path (dirs/legacy-bin-dir))]
    (t :moving {:src src :dest dest})
    (fs/move src dest))
  (when (fs/exists? (dirs/legacy-jars-dir))
    (let [src (str (dirs/legacy-jars-dir))
          dest (backup-path (dirs/legacy-jars-dir))]
      (fs/move src dest)
      (t :moving {:src src :dest dest}))))

(defn- confirm-one-script [script-name {:keys [overwrite] :as cli-opts} t]
  (let [bin-dest (str (fs/file (dirs/xdg-bin-dir cli-opts) (name script-name)))]
    (if (or overwrite (not (fs/exists? bin-dest)))
      true
      (do
        (println)
        (t :confirm-overwrite {:dest bin-dest})
        (= "yes" (str/trim (read-line)))))))

(defn- confirm-all-scripts [scripts cli-opts t]
  (->> scripts
       (map (fn [[script-name _]]
              [script-name (confirm-one-script script-name cli-opts t)]))
       (remove (fn [[_ confirmed]] (nil? confirmed)))
       doall))

(defn- copy-script [script-name confirmed cli-opts t]
  (let [bin-src (str (fs/file (dirs/legacy-bin-dir) (name script-name)))
        bin-dest (str (fs/file (dirs/xdg-bin-dir cli-opts) (name script-name)))
        jar-src (str (fs/file (dirs/legacy-jars-dir) (str script-name ".jar")))
        jar-dest (str (fs/file (dirs/xdg-jars-dir cli-opts) (str script-name ".jar")))
        copy-jar #(when (fs/exists? jar-src)
                    (t :copying {:src jar-src :dest jar-dest})
                    (spit bin-dest (str/replace (slurp bin-dest) jar-src jar-dest))
                    (fs/copy jar-src jar-dest {:replace-existing true}))]
    (if-not confirmed
      (t :skipping {:src bin-src})
      (do
        (t :copying {:src bin-src :dest bin-dest})
        (fs/copy bin-src bin-dest {:replace-existing true})
        (copy-jar)))))

(defn migrate-auto [{:keys [overwrite] :as cli-opts}]
  (let [t (printer cli-opts)]
    (if-not (dirs/using-legacy-paths?)
      (t :up-to-date)
      (let [scripts (scripts/load-scripts (dirs/legacy-bin-dir))]
        (if (seq scripts)
          (do
            (println)
            (t :printable-scripts {:scripts scripts})
            (println)
            (t :found-scripts)
            (when-not overwrite
              (println)
              (t :prompt-move))))
        (flush)
        (if (or overwrite (= "yes" (str/trim (read-line))))
          (do
            (dirs/ensure-xdg-dirs cli-opts)
            (if-not (seq scripts)
              (do
                (println)
                (t :migrating)
                (println)
                (create-backup cli-opts)
                (println)
                (t :done)
                (println))
              (do
                (flush)
                (let [confirm-results (confirm-all-scripts scripts cli-opts t)]
                  (println)
                  (t :migrating)
                  (doseq [[script-name confirmed] confirm-results]
                    (copy-script script-name confirmed cli-opts t)))
                (create-backup t)
                (println)
                (t :done)
                (println))))
          (do
            (println)
            (t :canceled)
            (println)))))))

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
