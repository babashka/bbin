(ns babashka.bbin.scripts
  (:require [babashka.fs :as fs]
            [babashka.deps :as deps]
            [babashka.process :refer [sh]]
            [rads.deps-infer :as deps-infer]
            [clojure.string :as str]
            [clojure.edn :as edn]
            [clojure.pprint :as pprint]
            [selmer.parser :as selmer]
            [selmer.util :as selmer-util]
            [babashka.bbin.trust :as trust]
            [babashka.bbin.util :as util]))

(defn pprint [x _]
  (pprint/pprint x))

(defn gitlib-path [cli-opts script-deps]
  (let [coords (val (first script-deps))]
    (fs/expand-home (str "~/.gitlibs/libs/" (:script/lib cli-opts) "/" (:git/sha coords)))))

(def tool-template-str
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
fi")

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

(defn install-http [cli-opts]
  (if-not (trust/allowed-url? (:script/lib cli-opts) cli-opts)
    (throw (ex-info (str "Script URL is not trusted") {:untrusted-url (:script/lib cli-opts)}))
    (let [http-url (:script/lib cli-opts)
          script-deps {:bbin/url http-url}
          header {:coords script-deps}
          _ (pprint header cli-opts)
          script-name (or (:as cli-opts) (http-url->script-name http-url))
          script-contents (-> (slurp (:bbin/url script-deps))
                              (insert-script-header header))
          script-file (fs/canonicalize (fs/file (util/bin-dir cli-opts) script-name)
                                       {:nofollow-links true})]
      (if (:dry-run cli-opts)
        (pprint {:script-file (str script-file)
                 :script-contents script-contents}
                cli-opts)
        (do
          (spit (str script-file) script-contents)
          (sh ["chmod" "+x" (str script-file)] {:err :inherit})
          nil)))))

(defn default-script-config [cli-opts]
  (let [[ns name] (str/split (:script/lib cli-opts) #"/")
        top (last (str/split ns #"\."))]
    {:main-opts ["-m" (str top "." name)]
     :ns-default (str top "." name)}))

(defn throw-lib-name-not-trusted [cli-opts]
  (let [msg (str "Lib name is not trusted.\nTo install this lib, provide "
                 "a --git/sha option or use `bbin trust` to allow inference "
                 "for this lib name.")]
    (throw (ex-info msg {:untrusted-lib (:script/lib cli-opts)}))))

(defn install-deps-git-or-local [cli-opts]
  (if-not (trust/allowed-lib? (:script/lib cli-opts) cli-opts)
    (throw-lib-name-not-trusted cli-opts)
    (let [script-deps (deps-infer/infer (assoc cli-opts :lib (:script/lib cli-opts)))
          header {:lib (key (first script-deps))
                  :coords (val (first script-deps))}
          _ (pprint header cli-opts)
          _ (deps/add-deps {:deps script-deps})
          script-root (fs/canonicalize (or (:local/root cli-opts) (gitlib-path cli-opts script-deps)) {:nofollow-links true})
          bb-edn (some-> (fs/file script-root "bb.edn") slurp edn/read-string)
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
                                           (map #(str "# " %))
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
      (if (:dry-run cli-opts)
        (pprint {:script-file (str script-file)
                 :template-out template-out}
                cli-opts)
        (do
          (spit (str script-file) template-out)
          (sh ["chmod" "+x" (str script-file)] {:err :inherit})
          nil)))))

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

(defn install-deps-maven [cli-opts]
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
        script-file (fs/canonicalize (fs/file (util/bin-dir cli-opts) script-name) {:nofollow-links true})]
    (if (:dry-run cli-opts)
      (pprint {:script-file (str script-file)
               :template-out template-out}
              cli-opts)
      (do
        (spit (str script-file) template-out)
        (sh ["chmod" "+x" (str script-file)] {:err :inherit})
        nil))))

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
  (->> (file-seq (util/bin-dir cli-opts))
       (filter #(.isFile %))
       (map (fn [x] [(symbol (str (fs/relativize (util/bin-dir cli-opts) x)))
                     (parse-script (slurp x))]))
       (into {})))
