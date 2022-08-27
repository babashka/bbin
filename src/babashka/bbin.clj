(ns babashka.bbin
  (:require [babashka.fs :as fs]
            [babashka.cli :as cli]
            [babashka.bbin.scripts :as scripts]
            [babashka.bbin.trust :as trust]
            [babashka.bbin.util :as util]
            [clojure.string :as str]
            [taoensso.timbre :as log]
            [clojure.pprint :as pprint]))

(defn pprint [x _]
  (pprint/pprint x))

(defn print-help [_]
  (println (str/trim "
Usage: bbin <command>

  bbin install    Install a script
  bbin uninstall  Remove a script
  bbin ls         List installed scripts
  bbin bin        Display bbin bin folder
  bbin trust      Trust an identity
  bbin revoke     Stop trusting an identity")))

(declare print-commands)

(defn run-install [parsed-args]
  (if-not (get-in parsed-args [:opts :script/lib])
    (print-help parsed-args)
    (scripts/install parsed-args)))

(defn run-uninstall [parsed-args]
  (if-not (get-in parsed-args [:opts :script/lib])
    (print-help parsed-args)
    (scripts/uninstall parsed-args)))

(defn run-ls [{:keys [opts]}]
  (pprint (scripts/load-scripts opts) opts))

(defn run-bin [{:keys [opts]}]
  (println (str (util/bin-dir opts))))

(defn run-trust [{:keys [opts]}]
  (util/ensure-bbin-dirs opts)
  (let [{:keys [path contents] :as plan} (trust/trust opts)]
    (pprint plan opts)
    (spit path (prn-str contents))))

(defn run-revoke [{:keys [opts]}]
  (let [{:keys [path]} (trust/revoke opts)]
    (when (fs/delete-if-exists path)
      (println "Removing" (str path)))))

(def commands
  [{:cmds ["commands"] :fn #(print-commands %)}
   {:cmds ["help"] :fn print-help}
   {:cmds ["install"]
    :fn run-install
    :args->opts [:script/lib]
    :aliases {:T :tool}}
   {:cmds ["uninstall"] :fn run-uninstall :args->opts [:script/lib]}
   {:cmds ["ls"] :fn run-ls}
   {:cmds ["bin"] :fn run-bin}
   {:cmds ["trust"] :fn run-trust}
   {:cmds ["revoke"] :fn run-revoke}
   {:cmds [] :fn print-help :aliases {:h :help}}])

(defn print-commands [_]
  (println (str/join " " (keep #(first (:cmds %)) commands)))
  nil)

(defn set-logging-config! [{:keys [debug]}]
  (log/merge-config! {:min-level (if debug :debug :warn)}))

(defn -main [& args]
  (set-logging-config! (cli/parse-opts args))
  (cli/dispatch commands args {}))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))

(comment
  (-main "help")
  (-main "install" "https://raw.githubusercontent.com/babashka/babashka/master/examples/portal.clj"))
