(ns rads.bbin
  (:require [babashka.fs :as fs]
            [babashka.cli :as cli]
            [rads.bbin.deps :as bbin-deps]
            [rads.bbin.scripts :as scripts]
            [rads.bbin.util :as util]
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
  bbin bin        Display bbin bin folder")))

(declare print-commands)

(defn ensure-bbin-dirs [cli-opts]
  (fs/create-dirs (util/bin-dir cli-opts)))

(defn run-install [parsed-args]
  (if-not (get-in parsed-args [:opts :script/lib])
    (print-help parsed-args)
    (do
      (ensure-bbin-dirs (:opts parsed-args))
      (let [cli-opts (bbin-deps/canonicalized-cli-opts parsed-args)
            {:keys [procurer]} (bbin-deps/summary cli-opts)]
        (case procurer
          :http (scripts/install-http cli-opts)
          :maven (scripts/install-deps-maven cli-opts)
          :git (scripts/install-deps-git-or-local cli-opts)
          :local (scripts/install-deps-git-or-local cli-opts))))))

(defn run-uninstall [parsed-args]
  (if-not (get-in parsed-args [:opts :script/lib])
    (print-help parsed-args)
    (do
      (ensure-bbin-dirs (:opts parsed-args))
      (let [cli-opts (:opts parsed-args)
            script-name (:script/lib cli-opts)
            script-file (fs/canonicalize (fs/file (util/bin-dir cli-opts) script-name) {:nofollow-links true})]
        (when (fs/delete-if-exists script-file)
          (println "Removing" (str script-file)))))))

(defn run-ls [{:keys [opts]}]
  (pprint (scripts/load-scripts opts) opts))

(defn run-bin [{:keys [opts]}]
  (println (str (util/bin-dir opts))))

(def commands
  [{:cmds ["commands"] :fn #(print-commands %)}
   {:cmds ["help"] :fn print-help}
   {:cmds ["install"] :fn run-install :args->opts [:script/lib]}
   {:cmds ["uninstall"] :fn run-uninstall :args->opts [:script/lib]}
   {:cmds ["ls"] :fn run-ls}
   {:cmds ["bin"] :fn run-bin}
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
