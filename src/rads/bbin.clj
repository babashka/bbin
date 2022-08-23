(ns rads.bbin
  (:require [babashka.fs :as fs]
            [babashka.cli :as cli]
            [babashka.deps :as deps]
            [babashka.process :refer [sh]]
            [rads.bbin.infer :as infer]
            [clojure.string :as str]
            [taoensso.timbre :as log]
            [clojure.edn :as edn]
            [clojure.pprint :as pprint]
            [selmer.parser :as selmer]
            [selmer.util :as selmer-util]
            [rads.bbin.git :as git]
            [rads.bbin.trust :as trust]))

(defn bbin-root [cli-opts]
  (str (or (some-> (:bbin/root cli-opts) (fs/canonicalize {:nofollow-links true}))
           (fs/expand-home "~/.bbin"))))

(defn bin-dir [cli-opts]
  (fs/file (bbin-root cli-opts) "bin"))

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

(defn gitlib-path [cli-opts script-deps]
  (let [coords (val (first script-deps))]
    (fs/expand-home (str "~/.gitlibs/libs/" (:script/lib cli-opts) "/" (:git/sha coords)))))

(def git-or-local-template-str
  "#!/usr/bin/env bash
set -e

# :bbin/start
#
{{script/meta}}
#
# :bbin/end

SCRIPT_ROOT='{{script/root}}'
SCRIPT_LIB='{{script/lib}}'
SCRIPT_COORDS='{{script/coords}}'
SCRIPT_MAIN_OPTS_FIRST='{{script/main-opts.0}}'
SCRIPT_MAIN_OPTS_SECOND='{{script/main-opts.1}}'

exec bb \\
  --deps-root \"$SCRIPT_ROOT\" \\
  --config <(echo \"{:deps {$SCRIPT_LIB $SCRIPT_COORDS}}\") \\
  $SCRIPT_MAIN_OPTS_FIRST \"$SCRIPT_MAIN_OPTS_SECOND\" \\
  -- \"$@\"")

(defn ensure-bbin-dirs [cli-opts]
  (fs/create-dirs (bin-dir cli-opts)))

(defn- http-url->script-name [http-url]
  (first
    (str/split (last (str/split http-url #"/"))
               #"\.")))

(defn bb-shebang? [s]
  (str/starts-with? s "#!/usr/bin/env bb"))

(defn insert-script-header [script-contents header]
  (let [
        prev-lines (str/split-lines script-contents)
        [prefix [shebang & code]] (split-with #(not (bb-shebang? %)) prev-lines)
        next-lines (concat prefix [shebang]
                           [""
                            "; :bbin/start"
                            ";"]
                           (map #(str "; " %)
                                (str/split-lines
                                  (with-out-str
                                    (pprint/pprint header))))
                           [";"
                            "; :bbin/end"]
                           code)]
    (str/join "\n" next-lines)))

(defn run-install-http [cli-opts]
  (if-not (trust/allowed-url? (:script/lib cli-opts))
    (throw (ex-info (str "Script URL is not trusted") {:untrusted-url (:script/lib cli-opts)}))
    (let [http-url (:script/lib cli-opts)
          script-deps {:bbin/url http-url}
          header {:coords script-deps}
          _ (pprint header cli-opts)
          script-name (or (:as cli-opts) (http-url->script-name http-url))
          script-contents (-> (slurp (:bbin/url script-deps))
                              (insert-script-header header))
          script-file (fs/canonicalize (fs/file (bin-dir cli-opts) script-name)
                                       {:nofollow-links true})]
      (if (:dry-run cli-opts)
        (pprint {:script-file (str script-file)
                 :script-contents script-contents}
                cli-opts)
        (do
          (spit (str script-file) script-contents)
          (sh ["chmod" "+x" (str script-file)] {:err :inherit})
          nil)))))

(defn default-git-or-local-script-config [cli-opts]
  (let [[ns name] (str/split (:script/lib cli-opts) #"/")]
    {:main-opts ["-m" (str (git/clean-github-lib ns) "." name)]}))

(defn run-install-deps-git-or-local [cli-opts]
  (let [script-deps (infer/resolve-deps (:script/lib cli-opts) cli-opts)
        header {:lib (key (first script-deps))
                :coords (val (first script-deps))}
        _ (pprint header cli-opts)
        _ (deps/add-deps {:deps script-deps})
        script-root (fs/canonicalize (or (:local/root cli-opts) (gitlib-path cli-opts script-deps)) {:nofollow-links true})
        bb-edn (some-> (fs/file script-root "bb.edn") slurp edn/read-string)
        script-name (or (:as cli-opts)
                        (some-> (:bbin/bin bb-edn) first key str)
                        (second (str/split (:script/lib cli-opts) #"/")))
        script-config (or (some-> (:bbin/bin bb-edn) first val)
                          (default-git-or-local-script-config cli-opts))
        script-edn-out (with-out-str
                          (binding [*print-namespace-maps* false]
                            (clojure.pprint/pprint header)))
        main-opts (or (some-> (:main-opts cli-opts) edn/read-string)
                      (:main-opts script-config))
        template-opts {:script/meta (->> script-edn-out
                                          str/split-lines
                                          (map #(str "# " %))
                                          (str/join "\n"))
                       :script/root script-root
                       :script/lib (pr-str (key (first script-deps)))
                       :script/coords (binding [*print-namespace-maps* false] (pr-str (val (first script-deps))))
                       :script/main-opts [(first main-opts)
                                          (if (= "-f" (first main-opts))
                                            (fs/canonicalize (fs/file script-root (second main-opts))
                                                             {:nofollow-links true})
                                            (second main-opts))]}
        template-out (selmer-util/without-escaping
                       (selmer/render git-or-local-template-str template-opts))
        script-file (fs/canonicalize (fs/file (bin-dir cli-opts) script-name) {:nofollow-links true})]
    (if (:dry-run cli-opts)
      (pprint {:script-file (str script-file)
               :template-out template-out}
              cli-opts)
      (do
        (spit (str script-file) template-out)
        (sh ["chmod" "+x" (str script-file)] {:err :inherit})
        nil))))

(def maven-template-str
  "#!/usr/bin/env bash
set -e

# :bbin/start
#
{{script/meta}}
#
# :bbin/end

SCRIPT_LIB='{{script/lib}}'
SCRIPT_COORDS='{{script/coords}}'
SCRIPT_MAIN_OPTS_FIRST='{{script/main-opts.0}}'
SCRIPT_MAIN_OPTS_SECOND='{{script/main-opts.1}}'

exec bb \\
  --config <(echo \"{:deps {$SCRIPT_LIB $SCRIPT_COORDS}}\") \\
  $SCRIPT_MAIN_OPTS_FIRST \"$SCRIPT_MAIN_OPTS_SECOND\" \\
  -- \"$@\"")

(defn default-maven-script-config [cli-opts]
  (let [[ns name] (str/split (:script/lib cli-opts) #"/")
        top (last (str/split ns #"\."))]
    {:main-opts ["-m" (str top "." name)]}))

(defn run-install-deps-maven [cli-opts]
  (let [script-deps {(edn/read-string (:script/lib cli-opts))
                     (select-keys cli-opts [:mvn/version])}
        header {:lib (key (first script-deps))
                :coords (val (first script-deps))}
        _ (pprint header cli-opts)
        _ (deps/add-deps {:deps script-deps})
        script-root (fs/canonicalize (or (:local/root cli-opts) (gitlib-path cli-opts script-deps)) {:nofollow-links true})
        script-name (or (:as cli-opts) (second (str/split (:script/lib cli-opts) #"/")))
        script-config (default-maven-script-config cli-opts)
        script-edn-out (with-out-str
                         (binding [*print-namespace-maps* false]
                           (clojure.pprint/pprint header)))
        main-opts (or (some-> (:main-opts cli-opts) edn/read-string)
                      (:main-opts script-config))
        template-opts {:script/meta (->> script-edn-out
                                         str/split-lines
                                         (map #(str "# " %))
                                         (str/join "\n"))
                       :script/root script-root
                       :script/lib (pr-str (key (first script-deps)))
                       :script/coords (binding [*print-namespace-maps* false] (pr-str (val (first script-deps))))
                       :script/main-opts [(first main-opts)
                                          (if (= "-f" (first main-opts))
                                            (fs/canonicalize (fs/file script-root (second main-opts))
                                                             {:nofollow-links true})
                                            (second main-opts))]}
        template-out (selmer-util/without-escaping
                       (selmer/render maven-template-str template-opts))
        script-file (fs/canonicalize (fs/file (bin-dir cli-opts) script-name) {:nofollow-links true})]
    (if (:dry-run cli-opts)
      (pprint {:script-file (str script-file)
               :template-out template-out}
              cli-opts)
      (do
        (spit (str script-file) template-out)
        (sh ["chmod" "+x" (str script-file)] {:err :inherit})
        nil))))

(defn run-install [parsed-args]
  (if-not (get-in parsed-args [:opts :script/lib])
    (print-help parsed-args)
    (do
      (ensure-bbin-dirs (:opts parsed-args))
      (let [cli-opts (merge (:opts parsed-args)
                            (when-let [v (:local/root (:opts parsed-args))]
                              {:local/root (str (fs/canonicalize v {:nofollow-links true}))}))]
        (cond
          (re-seq #"^https?://" (:script/lib cli-opts))
          (run-install-http cli-opts)

          (:mvn/version cli-opts)
          (run-install-deps-maven cli-opts)

          :else
          (run-install-deps-git-or-local cli-opts))))))

(defn run-uninstall [parsed-args]
  (if-not (get-in parsed-args [:opts :script/lib])
    (print-help parsed-args)
    (do
      (ensure-bbin-dirs (:opts parsed-args))
      (let [cli-opts (:opts parsed-args)
            script-name (:script/lib cli-opts)
            script-file (fs/canonicalize (fs/file (bin-dir cli-opts) script-name) {:nofollow-links true})]
        (when (fs/delete-if-exists script-file)
          (println "Removing" (str script-file)))))))

(defn parse-script [s]
  (let [lines (str/split-lines s)
        prefix (if (str/ends-with? (first lines) "bb") ";" "#")]
    (->> lines
         (drop-while #(not (re-seq (re-pattern (str "^" prefix " *:bbin/start")) %)))
         next
         (take-while #(not (re-seq (re-pattern (str "^" prefix " *:bbin/end")) %)))
         (map #(str/replace % (re-pattern (str "^" prefix " *")) ""))
         (str/join "\n")
         edn/read-string)))

(defn load-scripts [cli-opts]
  (->> (file-seq (bin-dir cli-opts))
       (filter #(.isFile %))
       (map (fn [x] [(symbol (str (fs/relativize (bin-dir cli-opts) x)))
                     (parse-script (slurp x))]))
       (into {})))

(defn run-ls [{:keys [opts]}]
  (pprint (load-scripts opts) opts))

(defn run-bin [{:keys [opts]}]
  (println (str (bin-dir opts))))

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
