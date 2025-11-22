(ns babashka.bbin.scripts.common
  (:require [babashka.bbin.deps :as bbin-deps]
            [babashka.bbin.dirs :as dirs]
            [babashka.bbin.meta :as meta]
            [babashka.bbin.specs]
            [babashka.bbin.util :as util :refer [sh]]
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
  ([{:keys [script-name header script-file template-out cli-opts] :as _params}]
   (install-script script-name header script-file template-out cli-opts))
  ([script-name header path contents & {:keys [dry-run] :as cli-opts}]
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

(defn- generate-deps-lib-name [git-url]
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

(def ^:private local-dir-template-str-without-bb-edn
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
(def script-main-opts {{script/main-opts}})

(def tmp-edn
  (doto (fs/file (fs/temp-dir) (str (gensym \"bbin\")))
    (spit (str \"{:deps {local/deps {:local/root \" (pr-str script-root) \"}}}\"))
    (fs/delete-on-exit)))

(def base-command
  (vec (concat [\"bb\" \"--deps-root\" script-root \"--config\" (str tmp-edn)]
               script-main-opts
               [\"--\"])))

(process/exec (into base-command *command-line-args*))
"))

(def ^:private git-or-local-template-str-without-bb-edn
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
(def script-main-opts {{script/main-opts}})

(def tmp-edn
  (doto (fs/file (fs/temp-dir) (str (gensym \"bbin\")))
    (spit (str \"{:deps {\" script-lib script-coords \"}}\"))
    (fs/delete-on-exit)))

(def base-command
  (vec (concat [\"bb\" \"--deps-root\" script-root \"--config\" (str tmp-edn)]
               script-main-opts
               [\"--\"])))

(process/exec (into base-command *command-line-args*))
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

(defn calc-script-deps [{:keys [cli-opts summary] :as params}]
  (let [{:keys [procurer]} summary
        script-deps (cond
                      (and (#{:local} procurer) (not (:local/root cli-opts)))
                      {::no-lib {:local/root (str (fs/canonicalize (:script/lib cli-opts) {:nofollow-links true}))}}

                      (bbin-deps/git-repo-url? (:script/lib cli-opts))
                      (bbin-deps/infer
                        (cond-> (assoc cli-opts :lib (str (generate-deps-lib-name (:script/lib cli-opts)))
                                                :git/url (:script/lib cli-opts))
                          (not (some cli-opts [:latest-tag :latest-sha :git/sha :git/tag]))
                          (assoc :latest-sha true)))

                      :else
                      (bbin-deps/infer (assoc cli-opts :lib (:script/lib cli-opts))))]
    (assoc params :script-deps script-deps)))

(defn calc-header [{:keys [script-deps] :as params}]
  (let [lib (key (first script-deps))
        coords (val (first script-deps))
        header (merge {:coords coords} (when-not (#{::no-lib} lib) {:lib lib}))
        header' (if (#{::no-lib} lib)
                  {:coords {:bbin/url (str "file://" (get-in header [:coords :local/root]))}}
                  header)
        header' (assoc header' :bbin/version meta/version)]
    (assoc params :header header')))

(defn add-deps [{:keys [script-deps] :as params}]
  (let [lib (key (first script-deps))]
    (when-not (#{::no-lib} lib)
      (bbin-deps/add-libs script-deps))
    params))

(defn calc-script-root [{:keys [header script-deps] :as params}]
  (let [script-root (fs/canonicalize (or (get-in header [:coords :local/root])
                                         (local-lib-path script-deps))
                                     {:nofollow-links true})]
    (assoc params :script-root script-root)))

(defn calc-bin-config [{:keys [script-root] :as params}]
  (assoc params :bin-config (load-bin-config script-root)))

(defn calc-script-name
  [{:keys [cli-opts script-deps bin-config header] :as params}]
  (let [lib (key (first script-deps))
        script-name (or (:as cli-opts)
                        (some-> bin-config first key str)
                        (and (not (#{::no-lib} lib))
                             (second (str/split (:script/lib cli-opts) #"/"))))]
    (when (str/blank? script-name)
      (throw (ex-info "Script name not found. Use --as or :bbin/bin to provide a script name."
                      header)))
    (assoc params :script-name script-name)))

(defn calc-script-config [{:keys [cli-opts bin-config script-deps] :as params}]
  (let [lib (key (first script-deps))
        script-config (merge (when-not (#{::no-lib} lib)
                               (default-script-config cli-opts))
                             (some-> bin-config first val)
                             (when (:ns-default cli-opts)
                               {:ns-default (edn/read-string (:ns-default cli-opts))}))]
    (assoc params :script-config script-config)))

(defn calc-script-edn-out [{:keys [header] :as params}]
  (let [script-edn-out (with-out-str
                         (binding [*print-namespace-maps* false]
                           (util/pprint header)))]
    (assoc params :script-edn-out script-edn-out)))

(defn calc-tool-mode [{:keys [cli-opts bin-config] :as params}]
  (let [tool-mode (or (:tool cli-opts)
                      (and (some-> bin-config first val :ns-default)
                           (not (some-> bin-config first val :main-opts))))]
    (assoc params :tool-mode tool-mode)))

(defn calc-main-opts [{:keys [cli-opts tool-mode script-config] :as params}]
  (let [main-opts (or (some-> (:main-opts cli-opts) edn/read-string)
                      (:main-opts script-config))]
    (when (and (not tool-mode) (not (seq main-opts)))
      (throw (ex-info "Main opts not found. Use --main-opts or :bbin/bin to provide main opts."
                      {})))
    (assoc params :main-opts main-opts)))

(defn calc-template-opts
  [{:keys [script-edn-out script-root script-deps script-config main-opts
           tool-mode]
    :as params}]
  (let [template-opts {:script/meta (->> script-edn-out
                                         str/split-lines
                                         (map #(str comment-char " " %))
                                         (str/join "\n"))
                       :script/root script-root
                       :script/lib (pr-str (key (first script-deps)))
                       :script/coords (binding [*print-namespace-maps* false] (pr-str (val (first script-deps))))}
        template-opts' (if tool-mode
                         (assoc template-opts :script/ns-default (:ns-default script-config))
                         (assoc template-opts :script/main-opts
                                              (process-main-opts main-opts script-root)))]
    (assoc params :template-opts template-opts')))

(defn calc-template-str [{:keys [script-deps script-root tool-mode] :as params}]
  (let [lib (key (first script-deps))
        bb-file (fs/file script-root "bb.edn")
        bb-edn-exists (fs/exists? bb-file)
        template-str (cond
                       (and tool-mode (#{::no-lib} lib))
                       local-dir-tool-template-str

                       (and tool-mode (not (#{::no-lib} lib)))
                       deps-tool-template-str

                       (and (not tool-mode) bb-edn-exists)
                       git-or-local-template-str-with-bb-edn

                       (and (not tool-mode)
                            (not bb-edn-exists)
                            (#{::no-lib} lib))
                       local-dir-template-str-without-bb-edn

                       (and (not tool-mode)
                            (not bb-edn-exists)
                            (not (#{::no-lib} lib)))
                       git-or-local-template-str-without-bb-edn)]
    (assoc params :template-str template-str)))

(defn calc-template-out [{:keys [template-str template-opts] :as params}]
  (let [template-out (selmer-util/without-escaping
                       (selmer/render template-str template-opts))]
    (assoc params :template-out template-out)))

(defn calc-script-file [{:keys [cli-opts script-name] :as params}]
  (let [script-file (fs/canonicalize (fs/file (dirs/bin-dir cli-opts) script-name) {:nofollow-links true})]
    (assoc params :script-file script-file)))

(defn install-deps-git-or-local [cli-opts summary]
  (-> {:cli-opts cli-opts, :summary summary}
      calc-script-deps
      calc-header
      calc-script-root
      calc-bin-config
      calc-script-name
      calc-script-config
      calc-script-edn-out
      calc-tool-mode
      calc-main-opts
      calc-template-opts
      calc-template-str
      calc-template-out
      calc-script-file
      add-deps
      install-script))

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
