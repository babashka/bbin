(ns babashka.bbin.cli
  (:require [babashka.cli :as cli]
            [babashka.bbin.scripts :as scripts]
            [babashka.bbin.trust :as trust]
            [babashka.bbin.util :as util]
            [clojure.string :as str]))

(declare print-commands)

(defn- commands
  [& {:keys [install-fn uninstall-fn ls-fn bin-fn trust-fn revoke-fn]}]
  [{:cmds ["commands"]
    :fn #(print-commands %)}

   {:cmds ["help"]
    :fn util/print-help}

   {:cmds ["install"]
    :fn #(install-fn (:opts %))
    :args->opts [:script/lib]
    :aliases {:T :tool}}

   {:cmds ["uninstall"]
    :fn #(uninstall-fn (:opts %))
    :args->opts [:script/lib]}

   {:cmds ["ls"]
    :fn #(ls-fn (:opts %))}

   {:cmds ["bin"]
    :fn #(bin-fn (:opts %))}

   {:cmds ["trust"]
    :fn #(trust-fn (:opts %))}

   {:cmds ["revoke"]
    :fn #(revoke-fn (:opts %))}

   {:cmds []
    :fn util/print-help
    :aliases {:h :help}}])

(defn- print-commands [_]
  (println (str/join " " (keep #(first (:cmds %)) (commands nil)))))

(def default-run-opts
  {:install-fn scripts/install
   :uninstall-fn scripts/uninstall
   :ls-fn scripts/ls
   :bin-fn scripts/bin
   :trust-fn trust/trust
   :revoke-fn trust/revoke})

(defn bbin [main-args & {:as run-opts}]
  (let [run-opts' (merge default-run-opts run-opts)]
    (util/set-logging-config! (cli/parse-opts main-args))
    (cli/dispatch (commands run-opts') main-args {})))

(defn -main [& args]
  (bbin args))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
