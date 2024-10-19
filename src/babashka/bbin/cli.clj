(ns babashka.bbin.cli
  (:require [babashka.bbin.dirs :as dirs]
            [babashka.bbin.migrate :as migrate]
            [babashka.bbin.scripts :as scripts]
            [babashka.bbin.util :as util]
            [babashka.cli :as cli]
            [clojure.string :as str]))

(declare print-commands)

(defn- run [command-fn parsed & {:keys [disable-legacy-paths-check]}]
  (let [cli-opts (:opts parsed)]
    (when-not disable-legacy-paths-check
      (dirs/check-legacy-paths))
    (if (and (:version cli-opts) (not (:help cli-opts)))
      (util/print-version)
      (command-fn cli-opts))))

(defn- add-global-aliases [commands]
  (map #(assoc-in % [:aliases :h] :help) commands))

(defn- base-commands
  [& {:as opts}]
  (->> [{:cmds ["commands"]
         :fn #(run print-commands %)}

        {:cmds ["help"]
         :fn #(run util/print-help %)}

        {:cmds ["install"]
         :fn #(run (:install-fn opts) %)
         :args->opts [:script/lib]
         :aliases {:T :tool}}

        {:cmds ["migrate" "auto"]
         :fn #(run (partial (:migrate-fn opts) :auto) %
                   :disable-legacy-paths-check true)}

        {:cmds ["migrate"]
         :fn #(run (:migrate-fn opts) %
                   :disable-legacy-paths-check true)}

        (when (util/upgrade-enabled?)
          {:cmds ["upgrade"]
           :fn #(run (:upgrade-fn opts) %)
           :args->opts [:script/lib]})

        {:cmds ["uninstall"]
         :fn #(run (:uninstall-fn opts) %)
         :args->opts [:script/lib]}

        {:cmds ["ls"]
         :fn #(run (:ls-fn opts) %)}

        {:cmds ["bin"]
         :fn #(run (:bin-fn opts) %)}

        {:cmds ["version"]
         :fn #(run util/print-version %)}

        {:cmds []
         :fn #(run util/print-help %)}]
       (remove nil?)))

(defn- full-commands [& {:as run-opts}]
  (add-global-aliases (base-commands run-opts)))

(defn- print-commands [_]
  (println (str/join " " (keep #(first (:cmds %)) (full-commands)))))

(def default-run-opts
  {:install-fn scripts/install
   :upgrade-fn scripts/upgrade
   :uninstall-fn scripts/uninstall
   :ls-fn scripts/ls
   :bin-fn scripts/bin
   :migrate-fn migrate/migrate})

(defn bbin [main-args & {:as run-opts}]
  (let [run-opts' (merge default-run-opts run-opts)]
    (util/set-logging-config! (cli/parse-opts main-args))
    (cli/dispatch (full-commands run-opts') main-args {})))

(defn -main [& args]
  (bbin args))

(when (= *file* (System/getProperty "babashka.file"))
  (util/check-min-bb-version)
  (apply -main *command-line-args*))
