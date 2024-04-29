(ns babashka.bbin.scripts.common
  (:require [babashka.bbin.deps :as bbin-deps]
            [babashka.bbin.dirs :as dirs]
            [babashka.bbin.specs]
            [babashka.bbin.util :as util :refer [sh]]
            [babashka.deps :as deps]
            [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.main :as main]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [selmer.parser :as selmer]
            [selmer.util :as selmer-util])
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
  [script-name header path contents & {:keys [dry-run] :as cli-opts}]
  (let [path-str (str path)]
    (if dry-run
      (util/pprint {:script-file     path-str
                    :script-contents contents}
                   dry-run)
      (do
        (spit path-str contents)
        (when-not util/windows? (sh ["chmod" "+x" path-str]))
        (when util/windows?
          (spit (str path-str windows-wrapper-extension)
                (str "@bb -f %~dp0" (fs/file-name path-str) " -- %*")))
        (if (util/edn? cli-opts)
          (util/pprint header)
          (do
            (println)
            (util/print-scripts (util/printable-scripts {script-name header})
                                cli-opts)
            (println)
            (println (util/bold "Install complete." cli-opts))
            (println)))
        nil))))

(defn- generate-deps-lib-name [git-url]
  (let [s (str "script-"
               (.hashCode git-url)
               "-"
               (-> git-url
                   (str/replace #"[^a-zA-Z0-9-]" "-")
                   (str/replace #"--+" "-")))]
    (symbol "org.babashka.bbin" s)))

(defn local-lib-path [script-deps]
  (let [lib (key (first script-deps))
        coords (val (first script-deps))]
    (if (#{::no-lib} lib)
      (:local/root coords)
      (fs/expand-home (str/join fs/file-separator ["~" ".gitlibs" "libs" (namespace lib) (name lib) (:git/sha coords)])))))

(defn- load-bin-config [script-root]
  (let [bb-file (fs/file script-root "bb.edn")
        bb-edn (when (fs/exists? bb-file)
                 (some-> bb-file slurp edn/read-string))
        bin-config (:bbin/bin bb-edn)]
    (when bin-config
      (if (s/valid? :bbin/bin bin-config)
        bin-config
        (throw (ex-info (s/explain-str :bbin/bin bin-config)
                        {:bbin/bin bin-config}))))))

(defn default-script-config [cli-opts]
  (let [[ns name] (str/split (:script/lib cli-opts) #"/")
        top (last (str/split ns #"\."))]
    {:main-opts ["-m" (str top "." name)]
     :ns-default (str top "." name)}))

(def comment-char ";")

(def ^:private local-dir-tool-template-str
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

(def ^:private deps-tool-template-str
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

(def ^:private local-dir-template-str
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
(def script-main-opts-first {{script/main-opts.0|pr-str}})
(def script-main-opts-second {{script/main-opts.1|pr-str}})

(def tmp-edn
  (doto (fs/file (fs/temp-dir) (str (gensym \"bbin\")))
    (spit (str \"{:deps {local/deps {:local/root \" (pr-str script-root) \"}}}\"))
    (fs/delete-on-exit)))

(def base-command
  [\"bb\" \"--deps-root\" script-root \"--config\" (str tmp-edn)
        script-main-opts-first script-main-opts-second
        \"--\"])

(process/exec (into base-command *command-line-args*))
"))

(def ^:private git-or-local-template-str
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
(def script-main-opts-first {{script/main-opts.0|pr-str}})
(def script-main-opts-second {{script/main-opts.1|pr-str}})

(def tmp-edn
  (doto (fs/file (fs/temp-dir) (str (gensym \"bbin\")))
    (spit (str \"{:deps {\" script-lib script-coords \"}}\"))
    (fs/delete-on-exit)))

(def base-command
  [\"bb\" \"--deps-root\" script-root \"--config\" (str tmp-edn)
        script-main-opts-first script-main-opts-second
        \"--\"])

(process/exec (into base-command *command-line-args*))
"))

(defn install-deps-git-or-local [cli-opts {:keys [procurer] :as _summary}]
  (let [script-deps (cond
                      (and (#{:local} procurer) (not (:local/root cli-opts)))
                      {::no-lib {:local/root (str (fs/canonicalize (:script/lib cli-opts) {:nofollow-links true}))}}

                      (bbin-deps/git-repo-url? (:script/lib cli-opts))
                      (bbin-deps/infer
                       (cond-> (assoc cli-opts :lib (str (generate-deps-lib-name (:script/lib cli-opts)))
                                      :git/url (:script/lib cli-opts))
                         (not (some cli-opts [:latest-tag :latest-sha :git/sha :git/tag]))
                         (assoc :latest-sha true)))

                      :else
                      (bbin-deps/infer (assoc cli-opts :lib (:script/lib cli-opts))))
        lib (key (first script-deps))
        coords (val (first script-deps))
        header (merge {:coords coords} (when-not (#{::no-lib} lib) {:lib lib}))
        header' (if (#{::no-lib} lib)
                  {:coords {:bbin/url (str "file://" (get-in header [:coords :local/root]))}}
                  header)
        _ (when-not (#{::no-lib} lib)
            (deps/add-deps {:deps script-deps}))
        script-root (fs/canonicalize (or (get-in header [:coords :local/root])
                                         (local-lib-path script-deps))
                                     {:nofollow-links true})
        bin-config (load-bin-config script-root)
        script-name (or (:as cli-opts)
                        (some-> bin-config first key str)
                        (and (not (#{::no-lib} lib))
                             (second (str/split (:script/lib cli-opts) #"/"))))
        _ (when (str/blank? script-name)
            (throw (ex-info "Script name not found. Use --as or :bbin/bin to provide a script name."
                            header)))
        script-config (merge (when-not (#{::no-lib} lib)
                               (default-script-config cli-opts))
                             (some-> bin-config first val)
                             (when (:ns-default cli-opts)
                               {:ns-default (edn/read-string (:ns-default cli-opts))}))
        script-edn-out (with-out-str
                         (binding [*print-namespace-maps* false]
                           (util/pprint header')))
        tool-mode (or (:tool cli-opts)
                      (and (some-> bin-config first val :ns-default)
                           (not (some-> bin-config first val :main-opts))))
        main-opts (or (some-> (:main-opts cli-opts) edn/read-string)
                      (:main-opts script-config))
        _ (when (and (not tool-mode) (not (seq main-opts)))
            (throw (ex-info "Main opts not found. Use --main-opts or :bbin/bin to provide main opts."
                            {})))
        template-opts {:script/meta (->> script-edn-out
                                         str/split-lines
                                         (map #(str comment-char " " %))
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
                       (if (#{::no-lib} lib)
                         local-dir-tool-template-str
                         deps-tool-template-str)
                       (if (#{::no-lib} lib)
                         local-dir-template-str
                         git-or-local-template-str))
        template-out (selmer-util/without-escaping
                      (selmer/render template-str template-opts'))
        script-file (fs/canonicalize (fs/file (dirs/bin-dir cli-opts) script-name) {:nofollow-links true})]
    (install-script script-name header' script-file template-out cli-opts)))

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
