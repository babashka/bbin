(ns babashka.bbin.scripts
  (:require [babashka.fs :as fs]
            [babashka.deps :as deps]
            [rads.deps-info.infer :as deps-info-infer]
            [rads.deps-info.summary :as deps-info-summary]
            [clojure.string :as str]
            [clojure.edn :as edn]
            [clojure.pprint :as pprint]
            [selmer.parser :as selmer]
            [selmer.util :as selmer-util]
            [selmer.filters :as filters]
            [babashka.bbin.util :as util :refer [sh]]))

(defn- pprint [x _]
  (pprint/pprint x))

(defn- gitlib-path [cli-opts script-deps]
  (let [coords (val (first script-deps))]
    (fs/expand-home (str/join fs/file-separator ["~" ".gitlibs" "libs" (:script/lib cli-opts) (:git/sha coords)]))))

; windows scripts are babashka/clojure instead of shell scripts, so we need a variable 
; comment character in the script meta
(def ^:private comment-char
  (if util/windows? "; " "# "))

(def windows-wrapper-extension ".bat")

; selmer filter for clojure escaping for e.g. files
(filters/add-filter! :pr-str (comp pr-str str))
(def ^:private tool-template-str
  (if util/windows?
    "#!/usr/bin/env bb
; :bbin/start
;
{{script/meta}}
; :bbin/end
(require '[babashka.process :as process]
         '[babashka.fs :as fs]
         '[clojure.string :as str])

(let [SCRIPT_ROOT        {{script/root|pr-str}}
      SCRIPT_LIB         '{{script/lib}}
      SCRIPT_COORDS      {{script/coords|str}}
      SCRIPT_NS_DEFAULT  '{{script/ns-default}}
      SCRIPT_NAME        (fs/file-name *file*)
      TMP_EDN            (doto (fs/file (fs/temp-dir) (str (gensym \"bbin\")))
                           (spit (str \"{:deps {\" SCRIPT_LIB SCRIPT_COORDS \"}}\")))
      [first-arg & rest-args] *command-line-args*]
  (if first-arg
    (babashka.process/exec
      (str/join \" \" (concat [\"bb --deps-root \" SCRIPT_ROOT \"--config\" (str TMP_EDN)
                               \"-x\" (str SCRIPT_NS_DEFAULT \"/\" first-arg)
                               \"--\"] rest-args)))
    (do
      (let [script (str \"(require '\" SCRIPT_NS_DEFAULT \")
                     (def fns (filter #(fn? (deref (val %))) (ns-publics '\" SCRIPT_NS_DEFAULT \")))
                     (def max-width (->> (keys fns) (map (comp count str)) (apply max)))
                     (defn pad-right [x] (format (str \\\"%-\\\" max-width \\\"s\\\") x))
                     (println (str \\\"Usage: \" SCRIPT_NAME \" <command>\\\"))
                     (newline)
                     (doseq [[k v] fns]
                       (println
                         (str \\\"  \" SCRIPT_NAME \" \\\" (pad-right k) \\\"  \\\"
                            (when (:doc (meta v))
                              (first (str/split-lines (:doc (meta v))))))))\")
             cmd-line     [\"bb\" \"--deps-root\" SCRIPT_ROOT \"--config\"  (str TMP_EDN)
                           \"-e\"  script]]
         (babashka.process/exec cmd-line)))))"
;    
; non-windows tool script
;
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
SCRIPT_NS_DEFAULT='{{script/ns-default}}'
SCRIPT_NAME=\"$(basename \"$0\")\"

if [ -z $1 ]; then
  echo \"Usage: $SCRIPT_NAME <command>\"
  echo
  bb \\
    --deps-root \"$SCRIPT_ROOT\" \\
    --config <(echo \"{:deps {$SCRIPT_LIB $SCRIPT_COORDS}}\") \\
    -e \"
      (require '$SCRIPT_NS_DEFAULT)
      (def fns (filter #(fn? (deref (val %))) (ns-publics '$SCRIPT_NS_DEFAULT)))
      (def max-width (->> (keys fns) (map (comp count str)) (apply max)))
      (defn pad-right [x] (format (str \\\"%-\\\" max-width \\\"s\\\") x))
      (doseq [[k v] fns]
        (println
          (str \\\"  $SCRIPT_NAME \\\" (pad-right k) \\\"  \\\"
               (when (:doc (meta v))
                 (first (str/split-lines (:doc (meta v))))))))\"
else
  exec bb \\
    --deps-root \"$SCRIPT_ROOT\" \\
    --config <(echo \"{:deps {$SCRIPT_LIB $SCRIPT_COORDS}}\") \\
    -x $SCRIPT_NS_DEFAULT/$1 \\
    -- \"${@:2}\"
fi"))

(def ^:private git-or-local-template-str
  (if util/windows?
    "#!/usr/bin/env bb
; :bbin/start
;
{{script/meta}}
; :bbin/end

(require '[babashka.process :as process]
         '[babashka.fs :as fs]
         '[clojure.string :as str])

(let [SCRIPT_ROOT    {{script/root|pr-str}}             
      SCRIPT_LIB     '{{script/lib}}
      SCRIPT_COORDS  {{script/coords|str}}               
      SCRIPT_MAIN_OPTS_FIRST  {{script/main-opts.0|pr-str}}
      SCRIPT_MAIN_OPTS_SECOND {{script/main-opts.1|pr-str}} 
      TMP_EDN (doto (fs/file (fs/temp-dir) (str (gensym \"bbin\")))
                      (spit (str \"{:deps {\" SCRIPT_LIB SCRIPT_COORDS\"}}\")))]
                        
     (babashka.process/exec
        (str/join \" \" (concat [\"bb --deps-root\" SCRIPT_ROOT \"--config\" (str TMP_EDN)
                         SCRIPT_MAIN_OPTS_FIRST SCRIPT_MAIN_OPTS_SECOND
                         \"--\"] *command-line-args*))))"
;
; non-windows script template
;
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
  -- \"$@\""))

(defn- http-url->script-name [http-url]
  (first
    (str/split (last (str/split http-url #"/"))
               #"\.")))

(defn- bb-shebang? [s]
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

(defn- install-script 
  "Spits `contents` to `path` (adding an extension on Windows), or 
  pprints them if `dry-run?` is truthy.
  Side-effecting."
  [path contents dry-run?]
  (let [path-str (str path)]
    (if dry-run?
      (pprint {:script-file     path-str
               :script-contents contents}
        dry-run?)
      (do
        (spit path-str contents)
        (when-not util/windows? (sh ["chmod" "+x" path-str]))
        (when util/windows? 
          (spit (str path-str windows-wrapper-extension) (str "@call bb -f %~dp0" (fs/file-name path-str) " -- %*")))
        nil))))

(defn- install-http [cli-opts]
  (let [http-url (:script/lib cli-opts)
        script-deps {:bbin/url http-url}
        header {:coords script-deps}
        _ (pprint header cli-opts)
        script-name (or (:as cli-opts) (http-url->script-name http-url))
        script-contents (-> (slurp (:bbin/url script-deps))
                            (insert-script-header header))
        script-file (fs/canonicalize (fs/file (util/bin-dir cli-opts) script-name)
                                     {:nofollow-links true})]
    (install-script script-file script-contents (:dry-run cli-opts))))

(defn- default-script-config [cli-opts]
  (let [[ns name] (str/split (:script/lib cli-opts) #"/")
        top (last (str/split ns #"\."))]
    {:main-opts ["-m" (str top "." name)]
     :ns-default (str top "." name)}))

(defn- install-deps-git-or-local [cli-opts]
  (let [script-deps (deps-info-infer/infer (assoc cli-opts :lib (:script/lib cli-opts)))
        header {:lib (key (first script-deps))
                :coords (val (first script-deps))}
        _ (pprint header cli-opts)
        _ (deps/add-deps {:deps script-deps})
        script-root (fs/canonicalize (or (:local/root cli-opts) (gitlib-path cli-opts script-deps)) {:nofollow-links true})
        bb-file (fs/file script-root "bb.edn")
        bb-edn (when (fs/exists? bb-file)
                 (some-> bb-file slurp edn/read-string))
        script-name (or (:as cli-opts)
                        (some-> (:bbin/bin bb-edn) first key str)
                        (second (str/split (:script/lib cli-opts) #"/")))
        script-config (merge (default-script-config cli-opts)
                             (some-> (:bbin/bin bb-edn) first val)
                             (when (:ns-default cli-opts)
                               {:ns-default (edn/read-string (:ns-default cli-opts))}))
        script-edn-out (with-out-str
                         (binding [*print-namespace-maps* false]
                           (clojure.pprint/pprint header)))
        tool-mode (or (:tool cli-opts)
                      (and (some-> (:bbin/bin bb-edn) first val :ns-default)
                           (not (some-> (:bbin/bin bb-edn) first val :main-opts))))
        main-opts (or (some-> (:main-opts cli-opts) edn/read-string)
                      (:main-opts script-config))
        template-opts {:script/meta (->> script-edn-out
                                         str/split-lines
                                         (map #(str comment-char %))
                                         (str/join "\n"))
                       :script/root script-root
                       :script/lib (pr-str (key (first script-deps)))
                       :script/coords (binding [*print-namespace-maps* false] (pr-str (val (first script-deps))))}
        template-opts' (if tool-mode
                         (assoc template-opts :script/ns-default (:ns-default script-config))
                         (assoc template-opts :script/main-opts
                                              [(first main-opts)
                                               (if (= "-f" (first main-opts))
                                                 (fs/canonicalize (fs/file script-root (second main-opts))
                                                                  {:nofollow-links true})
                                                 (second main-opts))]))
        template-str (if tool-mode
                       tool-template-str
                       git-or-local-template-str)
        template-out (selmer-util/without-escaping
                       (selmer/render template-str template-opts'))
        script-file (fs/canonicalize (fs/file (util/bin-dir cli-opts) script-name) {:nofollow-links true})]
    (install-script script-file template-out (:dry-run cli-opts))))

(def ^:private maven-template-str
  (if util/windows?
    "#!/usr/bin/env bb
; :bbin/start
;
{{script/meta}}
; :bbin/end
(require '[babashka.process :as process]
         '[babashka.fs :as fs]
         '[clojure.string :as str])

(let [SCRIPT_LIB     '{{script/lib}}
      SCRIPT_COORDS  {{script/coords|str}}
      SCRIPT_MAIN_OPTS_FIRST  {{script/main-opts.0|pr-str}}
      SCRIPT_MAIN_OPTS_SECOND {{script/main-opts.1|pr-str}} 
      TMP_EDN (doto (fs/file (fs/temp-dir) (str (gensym \"bbin\")))
                 (spit (str \"{:deps {\" SCRIPT_LIB SCRIPT_COORDS \"}}\")))]
                                         
(babashka.process/exec
  (str/join \" \" (concat [\"bb --config\" (str TMP_EDN)
                   SCRIPT_MAIN_OPTS_FIRST SCRIPT_MAIN_OPTS_SECOND
                   \"--\"] *command-line-args*))))\""
;
; non-windows script template
;  
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
  -- \"$@\""))

(defn- install-deps-maven [cli-opts]
  (let [script-deps {(edn/read-string (:script/lib cli-opts))
                     (select-keys cli-opts [:mvn/version])}
        header {:lib (key (first script-deps))
                :coords (val (first script-deps))}
        _ (pprint header cli-opts)
        _ (deps/add-deps {:deps script-deps})
        script-root (fs/canonicalize (or (:local/root cli-opts) (gitlib-path cli-opts script-deps)) {:nofollow-links true})
        script-name (or (:as cli-opts) (second (str/split (:script/lib cli-opts) #"/")))
        script-config (default-script-config cli-opts)
        script-edn-out (with-out-str
                         (binding [*print-namespace-maps* false]
                           (clojure.pprint/pprint header)))
        main-opts (or (some-> (:main-opts cli-opts) edn/read-string)
                      (:main-opts script-config))
        template-opts {:script/meta (->> script-edn-out
                                         str/split-lines
                                         (map #(str comment-char %))
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
        script-file (fs/canonicalize (fs/file (util/bin-dir cli-opts) script-name) {:nofollow-links true})]
    (install-script script-file template-out (:dry-run cli-opts))))

(defn- parse-script [s]
  (let [lines (str/split-lines s)
        prefix (if (or util/windows? (str/ends-with? (first lines) "bb")) ";" "#")]
    (->> lines
         (drop-while #(not (re-seq (re-pattern (str "^" prefix " *:bbin/start")) %)))
         next
         (take-while #(not (re-seq (re-pattern (str "^" prefix " *:bbin/end")) %)))
         (map #(str/replace % (re-pattern (str "^" prefix " *")) ""))
         (str/join "\n")
         edn/read-string)))

(defn load-scripts [cli-opts]
  (->> (file-seq (util/bin-dir cli-opts))
       (filter #(.isFile %))
       (map (fn [x] [(symbol (str (fs/relativize (util/bin-dir cli-opts) x)))
                     (parse-script (slurp x))]))
       (filter second)
       (into {})))

(defn ls [cli-opts]
  (-> (load-scripts cli-opts)
      (util/pprint cli-opts)))

(defn bin [cli-opts]
  (println (str (util/bin-dir cli-opts))))

(defn install [cli-opts]
  (if-not (:script/lib cli-opts)
    (util/print-help)
    (do
      (util/ensure-bbin-dirs cli-opts)
      (let [cli-opts' (util/canonicalized-cli-opts cli-opts)
            {:keys [procurer]} (deps-info-summary/summary cli-opts')]
        (case procurer
          :http (install-http cli-opts')
          :maven (install-deps-maven cli-opts')
          :git (install-deps-git-or-local cli-opts')
          :local (install-deps-git-or-local cli-opts'))))))

(defn uninstall [cli-opts]
  (if-not (:script/lib cli-opts)
    (util/print-help)
    (do
      (util/ensure-bbin-dirs cli-opts)
      (let [script-name (:script/lib cli-opts)
            script-file (fs/canonicalize (fs/file (util/bin-dir cli-opts) script-name) {:nofollow-links true})]
        (when (fs/delete-if-exists script-file)
          (when util/windows? (fs/delete-if-exists (fs/file (str script-file windows-wrapper-extension))))
          (println "Removing" (str script-file)))))))
