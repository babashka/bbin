(ns babashka.bbin.cli
  (:require [babashka.bbin.dirs :as dirs]
            [babashka.bbin.meta :as meta]
            [babashka.bbin.migrate :as migrate]
            [babashka.bbin.scripts :as scripts]
            [babashka.bbin.util :as util]
            [babashka.cli :as cli]
            [clojure.string :as str]))

(declare print-commands base-commands)

(defn- legacy-path-warning
  "Always warn if legacy paths are in use, except for migrate commands"
  [{:keys [dispatch]}]
  (when-not (= "migrate" (first dispatch))
    (dirs/check-legacy-paths)))

(defn- command-help
  "A custom bb cli :help-fn to also include bbin version."
  [{:keys [tree dispatch prog inherit] :as data}]
  (legacy-path-warning data)
  (println "Version:" meta/version)
  (println)
  (println (cli/format-command-help
             {:table tree :cmds dispatch :prog prog :inherit inherit})))

(defn- command-error
  "A custom bb cli :error-fn so that we may call it too."
  [data]
  (legacy-path-warning data)
  (binding [*out* *err*]
    (println (cli/format-command-error data)))
  (cli/*exit-fn* {:exit 1}))

(def ^:private dispatch-opts
  {:prog "bbin" :help true
   :restrict true :restrict-args true
   :no-keyword-opts true ;; allow less to support less
   :help-fn command-help
   :error-fn command-error
   :inherit #{:color :plain}})

(defn- run
  "cli args have been successfully parsed and we are now running our command"
  [command-fn parsed & {:keys [post-validate]}]
  (let [cli-opts (:opts parsed)
        post-errors (when post-validate
                      (post-validate parsed))]
    (if (seq post-errors)
      (command-error (assoc parsed
                            :prog (:prog dispatch-opts)
                            :dispatch (:dispatch parsed)
                            :msg (first post-errors)
                            :tree (cli/table->tree (base-commands))))
      (do (legacy-path-warning parsed)
          (command-fn cli-opts)))))

(defn- opt->flag [k] (str "--" (subs (str k) 1)))

(defn- post-validate-incompatible-opts [{:keys [opts]}]
  (let [exclusive-rules [;; can specify sha and or OR latest-sha OR latest-tag
                         [[:git/sha :git/tag]
                          [:latest-sha]
                          [:latest-tag]]
                         ;; a project comes from one of: git, mvn or local, not a combo
                         [[:git/url :git/sha :git/tag :latest-sha :latest-tag]
                          [:mvn/version]
                          [:local/root]]]]

    (into []
          (keep (fn [rule]
                  (let [hits (keep #(keys (select-keys opts %)) rule)]
                    (when (> (count hits) 1)
                      (str "Cannot specify together: "
                           (str/join " with "
                                     (->> hits
                                          (map #(str/join ", " (map opt->flag %)))
                                          sort)))))))
          exclusive-rules)))

(defn post-validate-install [parsed]
  (->> (post-validate-incompatible-opts parsed)
       sort))

(defn- base-commands
  [& {:as run-opts}]
  (->> [{:cmds []
         :spec {:version {:coerce :boolean
                          :no-doc true}
                :plain {:coerce :boolean
                        :desc "Use plain output (no color, no table decorations)"}
                :color {:coerce :boolean
                        :desc "Use color/styling in output (--no-color to disable)"}}
         :fn (fn [{:keys [opts]}]
               (if (:version opts)
                 (cli/dispatch (base-commands run-opts) ["version"] dispatch-opts)
                 (cli/dispatch (base-commands run-opts) ["--help"] dispatch-opts)))}
        {:cmds ["commands"]
         :no-doc true
         :fn #(run (:commands-fn run-opts) %)}
        {:cmds ["install"]
         :doc "Install a script"
         :spec {:script/lib {:require true
                             :positional true
                             :ref "source"
                             :desc "URL/file/project to install"}
                :as {:coerce :string
                     :desc "Executable name to give installed script"}
                :git/url {:coerce :string
                          :desc "Git URL of project (by default, inferred from project)"}
                :git/tag {:coerce :string
                          :desc "Git tag for --git/url"}
                :git/sha {:coerce :string
                          :desc "Git sha for --git/url"}
                :latest-sha {:coerce :boolean
                             :desc "Use latest sha for --git/url"}
                :latest-tag {:coerce :boolean
                             :desc "Use latest tag for --git/url"}
                :mvn/version {:coerce :string
                              :desc "Maven version for project"}
                :local/root {:coerce :string
                             :desc "Local directory for project"}
                :tool {:coerce :boolean
                       :desc "Install script using tool mode"}
                :main-opts {:coerce :edn
                            :desc "Edn for :main-opts to use for the installed script"}
                :ns-default {:coerce :symbol
                             :desc ":ns-default to be used for the installed script for --tool mode only"}
                :edn {:coerce :boolean
                      :desc "Emit output in edn format"}}
         :order [:as :git/url :git/sha :git/tag :latest-sha
                 :mvn/version :local/root :tool
                 :ns-default :main-opts :edn]
         :epilog "See https://github.com/babashka/bbin README for example usages."
         :args->opts [:script/lib]
         :fn #(run (:install-fn run-opts) %
                   :post-validate post-validate-install)}
        (when (util/upgrade-enabled?)
          {:cmds ["upgrade"]
           :doc "Upgrade a script"
           :spec {:script/lib {:require true
                               :positional true
                               :ref "script"
                               :desc "Script to upgrade"}}
           :args->opts [:script/lib]
           :fn #(run (:upgrade-fn run-opts) %)})
        {:cmds ["uninstall"]
         :doc "Remove a script"
         :spec {:script/lib {:require true
                             :positional true
                             :ref "script"
                             :desc "Script to remove"}}
         :args->opts [:script/lib]
         :fn #(run (:uninstall-fn run-opts) %)}
        {:cmds ["ls"]
         :doc "List installed scripts"
         :spec {:edn {:coerce :boolean
                      :desc "Output in edn format"}}
         :fn #(run (:ls-fn run-opts) %)}
        {:cmds ["bin"]
         :doc "Display bbin bin folder"
         :fn #(run (:bin-fn run-opts) %)}
        {:cmds ["version"]
         :doc "Display bbin version"
         :fn #(run (:version-fn run-opts) %)}
        {:cmds ["help"]
         :no-doc true ;; we already have -h, --help, we hide, but leave this in for historical and convenience
         :fn (fn [_opts] (cli/dispatch (base-commands run-opts) ["--help"] dispatch-opts))}
        {:cmds ["migrate"] ;; this command only gives detailed help on migration
         :doc migrate/migrate-help
         :fn (fn [_opts] (cli/dispatch (base-commands run-opts) ["migrate" "--help"] dispatch-opts))}
        {:cmds ["migrate" "auto"] ;; does the actual migration for user
         :doc migrate/migrate-help
         :spec {:force {:coerce :boolean
                        :desc "Automatically answer yes to any prompts"}}
         :fn #(run (:migrate-fn run-opts) %)}]
       (remove nil?)))

(defn- print-commands [_]
  (println (str/join " " (keep #(first (:cmds %)) (base-commands)))))

(def default-run-opts
  "Configurable production command fns allows overrides in tests"
  {:commands-fn print-commands
   :install-fn scripts/install
   :upgrade-fn scripts/upgrade
   :uninstall-fn scripts/uninstall
   :ls-fn scripts/ls
   :bin-fn scripts/bin
   :version-fn util/print-version
   :migrate-fn migrate/migrate-auto})

(defn bbin [main-args & {:as run-opts}]
  (let [run-opts' (merge default-run-opts run-opts)]
    (util/set-logging-config! (cli/parse-opts main-args))
    (cli/dispatch (base-commands run-opts') main-args
                  dispatch-opts)))

(defn -main [& args]
  (bbin args))

(when (= *file* (System/getProperty "babashka.file"))
  (util/check-min-bb-version)
  (apply -main *command-line-args*))
