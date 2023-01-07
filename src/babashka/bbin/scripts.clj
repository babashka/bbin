(ns babashka.bbin.scripts
  (:require [babashka.bbin.util :as util :refer [sh]]
            [babashka.deps :as deps]
            [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [clojure.string :as str]
            [rads.deps-info.infer :as deps-info-infer]
            [rads.deps-info.summary :as deps-info-summary]
            [org.httpkit.client :as http]
            [selmer.filters :as filters]
            [selmer.parser :as selmer]
            [selmer.util :as selmer-util]))

(defn- pprint [x _]
  (pprint/pprint x))

(defn- local-lib-path [cli-opts script-deps]
  (let [lib (key (first script-deps))
        coords (val (first script-deps))]
    (if (#{::no-lib} lib)
      (:local/root coords)
      (fs/expand-home (str/join fs/file-separator ["~" ".gitlibs" "libs" (:script/lib cli-opts) (:git/sha coords)])))))

(def ^:private comment-char ";")
(def windows-wrapper-extension ".bat")

;; selmer filter for clojure escaping for e.g. files
(filters/add-filter! :pr-str (comp pr-str str))

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
    (spit (str \"{:deps {local/deps {:local/root \\\"\" script-root \"\\\"}}}\"))
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

(def ^:private local-jar-template-str
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
    (spit (str \"{:deps {local/deps {:local/root \\\"\" script-root \"\\\"}}}\"))
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

(defn- http-url->script-name [http-url]
  (util/snake-case
    (first
     (str/split (last (str/split http-url #"/"))
                #"\."))))

(defn- file-path->script-name [file-path]
  (-> file-path
    fs/file-name
    fs/strip-ext
    util/snake-case))

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
                                (pprint/pprint header))))
                       [";"
                        "; :bbin/end"
                        ""])
        next-lines (if shebang
                     (concat prefix [shebang] header code)
                     (concat [bb-shebang-str] header prefix))]
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
          (spit (str path-str windows-wrapper-extension) (str "@bb -f %~dp0" (fs/file-name path-str) " -- %*")))
        nil))))

(defn- install-http-file [cli-opts]
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

(defn- install-local-script [cli-opts]
  (let [file-path (str (fs/canonicalize (:script/lib cli-opts) {:nofollow-links true}))
        script-deps {:bbin/url (str "file://" file-path)}
        header {:coords script-deps}
        _ (pprint header cli-opts)
        script-name (or (:as cli-opts) (file-path->script-name file-path))
        script-contents (-> (slurp file-path)
                            (insert-script-header header))
        script-file (fs/canonicalize (fs/file (util/bin-dir cli-opts) script-name)
                                     {:nofollow-links true})]
    (install-script script-file script-contents (:dry-run cli-opts))))


(defn main-ns [manifest-resource]
  (with-open [is (io/input-stream manifest-resource)]
    (or (some-> (java.util.jar.Manifest. is)
                (.getMainAttributes)
                ;; FIXME .getValue is missing in Babashka? Create issue. See https://docs.oracle.com/javase/7/docs/api/java/util/jar/Attributes.html
                #_(.getValue "Main-Class")
                (->> (into {}) (some (fn [[k v]]
                                       (when (clojure.string/includes? k "Main-Class") v))))
                (clojure.main/demunge)))))


(defn jar->main-ns [jar-path]
  (let [jar-file (io/file jar-path)
        manifest-path "META-INF/MANIFEST.MF"]
    (or (with-open [jar (java.util.jar.JarFile. jar-file)]
          (when-let [entry (.getEntry jar manifest-path)]
            (main-ns (java.net.URL. "jar" nil (str "file:" (.getAbsolutePath jar-file) "!/" manifest-path)))))

        (throw (ex-info "jar has no Main-Class" {:babashka/exit 1})))))


(defn- install-http-jar [cli-opts]
  (fs/create-dirs (util/jars-dir cli-opts))
  (let [http-url (:script/lib cli-opts)
        script-deps {:bbin/url http-url}
        header {:coords script-deps}
        script-name (or (:as cli-opts) (http-url->script-name http-url))
        cached-jar-path (fs/file (util/jars-dir cli-opts) (str script-name ".jar"))
        ;; TODO copy jar to temp dir and validate it first before installing
        _ (io/copy (:body @(http/get http-url {:as :byte-array})) cached-jar-path)
        main-ns (jar->main-ns cached-jar-path)
        _ (pprint header cli-opts)
        script-edn-out (with-out-str
                         (binding [*print-namespace-maps* false]
                           (clojure.pprint/pprint header)))
        template-opts {:script/meta (->> script-edn-out
                                         str/split-lines
                                         (map #(str comment-char " " %))
                                         (str/join "\n"))
                       :script/main-ns main-ns
                       :script/jar cached-jar-path}
        script-contents (selmer-util/without-escaping
                          (selmer/render local-jar-template-str template-opts))
        script-file (fs/canonicalize (fs/file (util/bin-dir cli-opts) script-name)
                                     {:nofollow-links true})]
    (install-script script-file script-contents (:dry-run cli-opts))))

(defn- install-local-jar [cli-opts]
  (fs/create-dirs (util/jars-dir cli-opts))
  (let [file-path (str (fs/canonicalize (:script/lib cli-opts) {:nofollow-links true}))
        main-ns (jar->main-ns file-path)
        script-deps {:bbin/url (str "file://" file-path)}
        header {:coords script-deps}
        _ (pprint header cli-opts)
        script-name (or (:as cli-opts) (file-path->script-name file-path))
        cached-jar-path (fs/file (util/jars-dir cli-opts) (str script-name ".jar"))
        script-edn-out (with-out-str
                         (binding [*print-namespace-maps* false]
                           (clojure.pprint/pprint header)))
        template-opts {:script/meta (->> script-edn-out
                                         str/split-lines
                                         (map #(str comment-char " " %))
                                         (str/join "\n"))
                       :script/main-ns main-ns
                       :script/jar cached-jar-path}
        script-contents (selmer-util/without-escaping
                          (selmer/render local-jar-template-str template-opts))
        script-file (fs/canonicalize (fs/file (util/bin-dir cli-opts) script-name)
                                     {:nofollow-links true})]
    (fs/copy file-path cached-jar-path {:replace-existing true})
    (install-script script-file script-contents (:dry-run cli-opts))))

(defn- default-script-config [cli-opts]
  (let [[ns name] (str/split (:script/lib cli-opts) #"/")
        top (last (str/split ns #"\."))]
    {:main-opts ["-m" (str top "." name)]
     :ns-default (str top "." name)}))

(def ^:private deps-info-client
  {:http-get-json #(json/parse-string (:body @(apply http/get %&)) true)})

(defn- deps-info-infer [& {:as opts}]
  (deps-info-infer/infer deps-info-client opts))

(defn- install-deps-git-or-local [cli-opts {:keys [procurer] :as _summary}]
  (let [script-deps (if (and (#{:local} procurer) (not (:local/root cli-opts)))
                      {::no-lib {:local/root (str (fs/canonicalize (:script/lib cli-opts) {:nofollow-links true}))}}
                      (deps-info-infer (assoc cli-opts :lib (:script/lib cli-opts))))
        lib (key (first script-deps))
        coords (val (first script-deps))
        header (merge {:coords coords} (when-not (#{::no-lib} lib) {:lib lib}))
        header' (if (#{::no-lib} lib)
                  {:coords {:bbin/url (str "file://" (get-in header [:coords :local/root]))}}
                  header)
        _ (pprint header' cli-opts)
        _ (when-not (#{::no-lib} lib)
            (deps/add-deps {:deps script-deps}))
        script-root (fs/canonicalize (or (get-in header [:coords :local/root])
                                         (local-lib-path cli-opts script-deps))
                                     {:nofollow-links true})
        bb-file (fs/file script-root "bb.edn")
        bb-edn (when (fs/exists? bb-file)
                 (some-> bb-file slurp edn/read-string))
        script-name (or (:as cli-opts)
                        (some-> (:bbin/bin bb-edn) first key str)
                        (and (not (#{::no-lib} lib))
                             (second (str/split (:script/lib cli-opts) #"/"))))
        _ (when (str/blank? script-name)
            (throw (ex-info "Script name not found. Use --as or :bbin/bin to provide a script name."
                            header)))
        script-config (merge (when-not (#{::no-lib} lib)
                               (default-script-config cli-opts))
                             (some-> (:bbin/bin bb-edn) first val)
                             (when (:ns-default cli-opts)
                               {:ns-default (edn/read-string (:ns-default cli-opts))}))
        script-edn-out (with-out-str
                         (binding [*print-namespace-maps* false]
                           (clojure.pprint/pprint header')))
        tool-mode (or (:tool cli-opts)
                      (and (some-> (:bbin/bin bb-edn) first val :ns-default)
                           (not (some-> (:bbin/bin bb-edn) first val :main-opts))))
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
        script-file (fs/canonicalize (fs/file (util/bin-dir cli-opts) script-name) {:nofollow-links true})]
    (install-script script-file template-out (:dry-run cli-opts))))

(def ^:private maven-template-str
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
(def script-main-opts-first {{script/main-opts.0|pr-str}})
(def script-main-opts-second {{script/main-opts.1|pr-str}})

(def tmp-edn
  (doto (fs/file (fs/temp-dir) (str (gensym \"bbin\")))
    (spit (str \"{:deps {\" script-lib script-coords \"}}\"))
    (fs/delete-on-exit)))

(def base-command
  [\"bb\" \"--config\" (str tmp-edn)
        script-main-opts-first script-main-opts-second
        \"--\"])

(process/exec (into base-command *command-line-args*))
"))

(defn- install-deps-maven [cli-opts]
  (let [script-deps {(edn/read-string (:script/lib cli-opts))
                     (select-keys cli-opts [:mvn/version])}
        header {:lib (key (first script-deps))
                :coords (val (first script-deps))}
        _ (pprint header cli-opts)
        _ (deps/add-deps {:deps script-deps})
        script-root (fs/canonicalize (or (:local/root cli-opts) (local-lib-path cli-opts script-deps)) {:nofollow-links true})
        script-name (or (:as cli-opts) (second (str/split (:script/lib cli-opts) #"/")))
        script-config (default-script-config cli-opts)
        script-edn-out (with-out-str
                         (binding [*print-namespace-maps* false]
                           (clojure.pprint/pprint header)))
        main-opts (or (some-> (:main-opts cli-opts) edn/read-string)
                      (:main-opts script-config))
        template-opts {:script/meta (->> script-edn-out
                                         str/split-lines
                                         (map #(str comment-char " " %))
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
            summary (deps-info-summary/summary cli-opts')
            {:keys [procurer artifact]} summary]
        (case [procurer artifact]
          [:git :dir] (install-deps-git-or-local cli-opts' summary)
          [:http :file] (install-http-file cli-opts')
          [:http :jar] (install-http-jar cli-opts')
          [:local :dir] (install-deps-git-or-local cli-opts' summary)
          [:local :file] (install-local-script cli-opts')
          [:local :jar] (install-local-jar cli-opts')
          [:maven :jar] (install-deps-maven cli-opts')
          (throw (ex-info "Invalid script coordinates.\nIf you're trying to install from the filesystem, make sure the path actually exists."
                          {:script/lib (:script/lib cli-opts')
                           :procurer procurer
                           :artifact artifact})))))))

(defn uninstall [cli-opts]
  (if-not (:script/lib cli-opts)
    (util/print-help)
    (do
      (util/ensure-bbin-dirs cli-opts)
      (let [script-name (:script/lib cli-opts)
            script-file (fs/canonicalize (fs/file (util/bin-dir cli-opts) script-name) {:nofollow-links true})]
        (when (fs/delete-if-exists script-file)
          (when util/windows? (fs/delete-if-exists (fs/file (str script-file windows-wrapper-extension))))
          (fs/delete-if-exists (fs/file (util/jars-dir cli-opts) (str script-name ".jar")))
          (println "Removing" (str script-file)))))))
