(ns babashka.bbin.cli
  (:require [babashka.cli :as cli]
            [babashka.bbin.scripts :as scripts]
            [babashka.bbin.util :as util]
            [clojure.string :as str]))

(declare print-commands)

(defn- run [command-fn {:keys [opts]}]
  (if (:version opts)
    (util/print-version)
    (command-fn opts)))

(defn- add-global-aliases [commands]
  (map #(assoc-in % [:aliases :h] :help) commands))

(defn- base-commands
  [& {:keys [install-fn uninstall-fn ls-fn bin-fn]}]
  [{:cmds ["commands"]
    :fn #(run print-commands %)}

   {:cmds ["help"]
    :fn #(run util/print-help %)}

   {:cmds ["install"]
    :fn #(run install-fn %)
    :args->opts [:script/lib]
    :aliases {:T :tool}}

   {:cmds ["uninstall"]
    :fn #(run uninstall-fn %)
    :args->opts [:script/lib]}

   {:cmds ["ls"]
    :fn #(run ls-fn %)}

   {:cmds ["bin"]
    :fn #(run bin-fn %)}

   {:cmds []
    :fn #(run util/print-help %)}])

(defn- full-commands [& {:as run-opts}]
  (add-global-aliases (base-commands run-opts)))

(defn- print-commands [_]
  (println (str/join " " (keep #(first (:cmds %)) (full-commands)))))

(def default-run-opts
  {:install-fn scripts/install
   :uninstall-fn scripts/uninstall
   :ls-fn scripts/ls
   :bin-fn scripts/bin
   :version-fn util/print-version})

(defn bbin [main-args & {:as run-opts}]
  (let [run-opts' (merge default-run-opts run-opts)]
    (util/set-logging-config! (cli/parse-opts main-args))
    (cli/dispatch (full-commands run-opts') main-args {})))

(defn -main [& args]
  (bbin args))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
