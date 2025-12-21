(ns babashka.bbin.scripts.common
  (:require [babashka.bbin.dirs :as dirs]
            [babashka.bbin.specs]
            [babashka.bbin.util :as util :refer [sh]]
            [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.main :as main]
            [clojure.spec.alpha :as s]
            [clojure.string :as str])
  (:import (java.util.jar JarFile)))

(def bb-shebang-str "#!/usr/bin/env bb")

(defn- bb-shebang? [s]
  (str/starts-with? s bb-shebang-str))

(defn insert-script-header [script-contents header]
  (let [prev-lines (str/split-lines script-contents)
        [prefix [shebang & code]] (split-with #(not (bb-shebang? %)) prev-lines)
        header (concat [""
                        "; :bbin/start"
                        ";"]
                       (map #(str "; " %)
                            (str/split-lines
                             (with-out-str
                               (util/pprint header))))
                       [";"
                        "; :bbin/end"
                        ""])
        next-lines (if shebang
                     (concat prefix [shebang] header code)
                     (concat [bb-shebang-str] header prefix))]
    (str/join "\n" next-lines)))

(defn file-path->script-name [file-path]
  (-> file-path
      fs/file-name
      fs/strip-ext
      util/snake-case))

(defn http-url->script-name [http-url]
  (util/snake-case
   (first
    (str/split (last (str/split http-url #"/"))
               #"\."))))

(def windows-wrapper-extension ".bat")

(defn install-script
  "Spits `contents` to `path` (adding an extension on Windows), or
  pprints them if `dry-run?` is truthy.
  Side-effecting."
  ([{:keys [script-name header script-file template-out cli-opts] :as _params}]
   (install-script script-name header script-file template-out cli-opts))
  ([_script-name _header path contents & {:keys [dry-run] :as _cli-opts}]
   (let [path-str (str path)]
     (if dry-run
       (util/pprint {:script-file path-str
                     :script-contents contents}
                    dry-run)
       (do
         (spit path-str contents)
         (when-not util/windows? (sh ["chmod" "+x" path-str]))
         (when util/windows?
           (spit (str path-str windows-wrapper-extension)
                 (str "@bb -f %~dp0" (fs/file-name path-str) " -- %*")))

         nil)))))

(defn generate-deps-lib-name [git-url]
  (let [s (str "script-"
               (.hashCode git-url)
               "-"
               (-> git-url
                   (str/replace #"[^a-zA-Z0-9-]" "-")
                   (str/replace #"--+" "-")))]
    (symbol "org.babashka.bbin" s)))

(defn local-lib-path
  ([script-deps]
   (local-lib-path script-deps (System/getenv "GITLIBS")))
  ([script-deps gitlibs-env]
   (let [lib (key (first script-deps))
         coords (val (first script-deps))
         gitlibs-root (or (not-empty gitlibs-env)
                          (str (fs/path (fs/home) ".gitlibs")))]
     (if (#{::no-lib} lib)
       (:local/root coords)
       (fs/expand-home (str/join fs/file-separator [gitlibs-root "libs" (namespace lib) (name lib) (:git/sha coords)]))))))

(defn load-bin-config [script-root]
  (let [bb-file (fs/file script-root "bb.edn")
        bb-edn (when (fs/exists? bb-file)
                 (some-> bb-file slurp edn/read-string))
        bin-config (:bbin/bin bb-edn)]
    (when bin-config
      (if (s/valid? :bbin/bin bin-config)
        bin-config
        (throw (ex-info (s/explain-str :bbin/bin bin-config)
                        {:bbin/bin bin-config}))))))

(defn default-script-config [lib]
  (let [lib-ns (namespace lib)
        lib-name (name lib)
        top (last (str/split lib-ns #"\."))]
    {:main-opts ["-m" (str top "." lib-name)]
     :ns-default (str top "." lib-name)}))

(defn process-main-opts
  "Process main-opts, canonicalizing file paths that follow -f flags."
  [main-opts script-root]
  (->> main-opts
       (map-indexed (fn [i arg]
                      (if (and (pos? i) (= "-f" (nth main-opts (dec i))))
                        (str (fs/canonicalize (fs/file script-root arg)
                                              {:nofollow-links true}))
                        arg)))
       vec))

(def comment-char ";")

(def local-dir-tool-template-str
  (str/trim "
#!/usr/bin/env bb

; :bbin/start
;
{{script/meta}}
;
; :bbin/end

(require '[babashka.process :as process]
         '[babashka.fs :as fs]
         '[clojure.string :as str])

(def script-root {{script/root|pr-str}})
(def script-ns-default '{{script/ns-default}})
(def script-name (fs/file-name *file*))

(def tmp-edn
  (doto (fs/file (fs/temp-dir) (str (gensym \"bbin\")))
    (spit (str \"{:deps {local/deps {:local/root \" (pr-str script-root) \"}}}\"))
    (fs/delete-on-exit)))

(def base-command
  [\"bb\" \"--deps-root\" script-root \"--config\" (str tmp-edn)])

(defn help-eval-str []
  (str \"(require '\" script-ns-default \")
        (def fns (filter #(fn? (deref (val %))) (ns-publics '\" script-ns-default \")))
        (def max-width (->> (keys fns) (map (comp count str)) (apply max)))
        (defn pad-right [x] (format (str \\\"%-\\\" max-width \\\"s\\\") x))
        (println (str \\\"Usage: \" script-name \" <command>\\\"))
        (newline)
        (doseq [[k v] fns]
          (println
            (str \\\"  \" script-name \" \\\" (pad-right k) \\\"  \\\"
               (when (:doc (meta v))
                 (first (str/split-lines (:doc (meta v))))))))\"))

(def first-arg (first *command-line-args*))
(def rest-args (rest *command-line-args*))

(if first-arg
  (process/exec
    (vec (concat base-command
                 [\"-x\" (str script-ns-default \"/\" first-arg)]
                 rest-args)))
  (process/exec (into base-command [\"-e\" (help-eval-str)])))
"))

(def deps-tool-template-str
  (str/trim "
#!/usr/bin/env bb

; :bbin/start
;
{{script/meta}}
;
; :bbin/end

(require '[babashka.process :as process]
         '[babashka.fs :as fs]
         '[clojure.string :as str])

(def script-root {{script/root|pr-str}})
(def script-lib '{{script/lib}})
(def script-coords {{script/coords|str}})
(def script-ns-default '{{script/ns-default}})
(def script-name (fs/file-name *file*))

(def tmp-edn
  (doto (fs/file (fs/temp-dir) (str (gensym \"bbin\")))
    (spit (str \"{:deps {\" script-lib script-coords \"}}\"))
    (fs/delete-on-exit)))

(def base-command
  [\"bb\" \"--deps-root\" script-root \"--config\" (str tmp-edn)])

(defn help-eval-str []
  (str \"(require '\" script-ns-default \")
        (def fns (filter #(fn? (deref (val %))) (ns-publics '\" script-ns-default \")))
        (def max-width (->> (keys fns) (map (comp count str)) (apply max)))
        (defn pad-right [x] (format (str \\\"%-\\\" max-width \\\"s\\\") x))
        (println (str \\\"Usage: \" script-name \" <command>\\\"))
        (newline)
        (doseq [[k v] fns]
          (println
            (str \\\"  \" script-name \" \\\" (pad-right k) \\\"  \\\"
               (when (:doc (meta v))
                 (first (str/split-lines (:doc (meta v))))))))\"))

(def first-arg (first *command-line-args*))
(def rest-args (rest *command-line-args*))

(if first-arg
  (process/exec
    (vec (concat base-command
                 [\"-x\" (str script-ns-default \"/\" first-arg)]
                 rest-args)))
  (process/exec (into base-command [\"-e\" (help-eval-str)])))
"))

(def git-or-local-template-str-with-bb-edn
  (str/trim "
#!/usr/bin/env bb

; :bbin/start
;
{{script/meta}}
;
; :bbin/end

(require '[babashka.process :as process])

(def script-root {{script/root|pr-str}})
(def script-main-opts {{script/main-opts}})

(def base-command
  (vec (concat [\"bb\" \"--config\" (str script-root \"/bb.edn\")]
               script-main-opts
               [\"--\"])))

(process/exec (into base-command *command-line-args*))
"))

(defn jar->main-ns [jar-path]
  (with-open [jar-file (JarFile. (fs/file jar-path))]
    (let [main-attributes (some-> jar-file .getManifest .getMainAttributes)
          ;; TODO After July 17th 2023: Remove workaround below and start using (.getValue "Main-Class") instead
          ;;      (see https://github.com/babashka/bbin/pull/47#discussion_r1071348344)
          main-class (some (fn [[k v]] (when (str/includes? k "Main-Class") v))
                           main-attributes)]
      (if main-class
        (main/demunge main-class)
        (throw (ex-info "jar has no Main-Class" {:jar-path jar-path}))))))

(defn delete-files [cli-opts]
  (let [script-name (:script/lib cli-opts)
        script-file (fs/canonicalize (fs/file (dirs/bin-dir cli-opts) script-name) {:nofollow-links true})]
    (when (fs/delete-if-exists script-file)
      (when util/windows? (fs/delete-if-exists (fs/file (str script-file windows-wrapper-extension))))
      (fs/delete-if-exists (fs/file (dirs/jars-dir cli-opts) (str script-name ".jar")))
      (println "Removing" (str script-file)))))

(def local-jar-template-str
  (str/trim "
#!/usr/bin/env bb

; :bbin/start
;
{{script/meta}}
;
; :bbin/end

(require '[babashka.classpath :refer [add-classpath]])

(def script-jar {{script/jar|pr-str}})

(add-classpath script-jar)

(require '[{{script/main-ns}}])
(apply {{script/main-ns}}/-main *command-line-args*)
"))

(def maven-template-str
  (str/trim "
#!/usr/bin/env bb

; :bbin/start
;
{{script/meta}}
;
; :bbin/end

(require '[babashka.process :as process]
         '[babashka.fs :as fs]
         '[clojure.string :as str])

(def script-lib '{{script/lib}})
(def script-coords {{script/coords|str}})
(def script-main-opts {{script/main-opts}})

(def tmp-edn
  (doto (fs/file (fs/temp-dir) (str (gensym \"bbin\")))
    (spit (str \"{:deps {\" script-lib script-coords \"}}\"))
    (fs/delete-on-exit)))

(def base-command
  (vec (concat [\"bb\" \"--config\" (str tmp-edn)]
               script-main-opts
               [\"--\"])))

(process/exec (into base-command *command-line-args*))
"))
