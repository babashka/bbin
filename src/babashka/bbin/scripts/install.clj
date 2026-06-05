(ns babashka.bbin.scripts.install
  (:require [babashka.bbin.deps :as deps]
            [babashka.bbin.dirs :as dirs]
            [babashka.bbin.scripts.common :as common]
            [babashka.bbin.scripts.install.analyze :as-alias analyze]
            [babashka.bbin.scripts.install.generate :as-alias generate]
            [babashka.bbin.scripts.install.input :as-alias input]
            [babashka.bbin.scripts.install.load :as-alias load]
            [babashka.bbin.scripts.install.parse :as-alias parse]
            [babashka.bbin.scripts.install.select :as-alias select]
            [babashka.bbin.scripts.install.write :as-alias write]
            [babashka.bbin.util :as util]
            [babashka.fs :as fs]
            [babashka.http-client :as http]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.tools.gitlibs :as gitlibs]
            [selmer.parser :as selmer]
            [selmer.util :as selmer-util]
            [taoensso.timbre :as log]))

(defn- event! [params event]
  (log/debug (assoc event ::params params)))

;; =============================================================================
;; Parse

(defn- parse-deps [{::input/keys [cli-opts] :as _params}]
  (cond
    (:mvn/version cli-opts)
    {::parse/coords (select-keys cli-opts [:mvn/version])
     ::parse/lib (edn/read-string (:script/lib cli-opts))}


    (deps/git-repo-url? (:script/lib cli-opts))
    (let [git-url (cond-> (:script/lib cli-opts)
                    (and (str/starts-with? (:script/lib cli-opts) "https://")
                         (not (str/ends-with? (:script/lib cli-opts) ".git")))
                    (str ".git"))
          lib (common/generate-deps-lib-name git-url)
          inferred (deps/infer
                     (cond-> (merge cli-opts {:lib (str lib), :git/url git-url})
                       (not (some cli-opts [:latest-tag :latest-sha :git/sha :git/tag]))
                       (assoc :latest-sha true)))
          coords (get inferred lib)]
      {::parse/coords coords
       ::parse/lib lib})

    :else
    (let [git-url (:script/lib cli-opts)
          lib (edn/read-string (:script/lib cli-opts))
          inferred (deps/infer (assoc cli-opts :lib git-url))
          coords (get inferred lib)]
      {::parse/coords coords
       ::parse/lib lib})))

(defn- parse-http [{::input/keys [cli-opts] :as _params}]
  {::parse/coords {:bbin/url (:script/lib cli-opts)}})

(defn- parse-local [{::input/keys [cli-opts] :as _params}]
  (let [source-path (-> (fs/path (or (:local/root cli-opts)
                                     (:script/lib cli-opts)))
                        (fs/canonicalize {:nofollow-links true})
                        str)]
    (cond-> {::parse/coords (if (:local/root cli-opts)
                              {:local/root source-path}
                              {:bbin/url (str "file://" source-path)})
             ::parse/source-path source-path}

      (:local/root cli-opts)
      (assoc ::parse/lib (edn/read-string (:script/lib cli-opts))))))

(defn parse
  "Parse coords from the CLI options."
  [{::input/keys [cli-opts] :as params}]
  (let [{:keys [tool as main-opts ns-default]} cli-opts
        {:keys [procurer artifact]} (deps/summary cli-opts)
        parsed (case [procurer artifact]
                 ([:git :dir] [:maven :jar])
                 (parse-deps params)

                 ([:http :file] [:http :jar])
                 (parse-http params)

                 ([:local :file] [:local :jar] [:local :dir])
                 (parse-local params)

                 (throw (ex-info "Invalid script coordinates.\nIf you're trying to install from the filesystem, make sure the path actually exists."
                                 {:script/lib (:script/lib cli-opts)
                                  :procurer procurer
                                  :artifact artifact})))
        header (-> (select-keys parsed [::parse/lib ::parse/coords])
                   (set/rename-keys {::parse/lib :lib
                                     ::parse/coords :coords}))
        extra (->> {::parse/header header
                    ::parse/tool tool
                    ::parse/as as
                    ::parse/main-opts main-opts
                    ::parse/ns-default (some-> ns-default edn/read-string)
                    ::parse/bin-dir (str (dirs/bin-dir cli-opts))
                    ::parse/jars-dir (str (dirs/jars-dir cli-opts))}
                   (filter #(some? (val %)))
                   (into {}))]
    (merge parsed extra)))

;; =============================================================================
;; Load

(defn- load-repo! [{::parse/keys [coords lib] :as params}]
  (let [loaded-path (gitlibs/procure (:git/url coords) lib (:git/sha coords))]
    (event! params {::event-type ::gitlibs-procured})
    {::load/loaded-path loaded-path}))

(defn- load-http-file!
  [{::input/keys [tmp-dir] ::parse/keys [coords] :as params}]
  (let [loaded-path (str (fs/path tmp-dir (fs/file-name (:bbin/url coords))))]
    (io/copy (:body (http/get (:bbin/url coords) {:as :bytes}))
             (fs/file loaded-path))
    (event! params {::event-type ::http-file-loaded})
    {::load/loaded-path loaded-path}))

(defn- load-deps! [{::parse/keys [lib coords] :as params}]
  (deps/add-libs {lib coords})
  (event! params {::event-type ::deps-loaded}))

(defn load!
  "Load files used to generate the script."
  [{::parse/keys [coords source-path] :as params}]
  (let [loaded (cond
                 (:git/url coords)
                 (load-repo! params)

                 (some->> (:bbin/url coords) (re-matches #"^https?://.+"))
                 (load-http-file! params)

                 (not (or (:bbin/url coords) (:local/root coords)))
                 (load-deps! params))
        artifact-path (or (::load/loaded-path loaded) source-path)]
    (merge loaded {::load/artifact-path artifact-path})))

;; =============================================================================
;; Analyze

(defn- analyze-dir
  [{::parse/keys [as lib ns-default] ::load/keys [artifact-path] :as _params}]
  ;; NOTE: A directory's bb.edn `:bbin/bin` may declare multiple scripts, but
  ;; only the first entry is installed. This matches the pre-refactor behavior
  ;; of `install-deps-git-or-local` (which also took `(first bin-config)`);
  ;; installing every entry would be a new feature, not a regression to fix.
  (let [bin-config (common/load-bin-config artifact-path)
        script-name (str (or as
                             (some-> bin-config first key)
                             (some-> lib name)
                             (-> (fs/file-name artifact-path)
                                 (str/replace #"\.(clj|cljc|bb|jar)$" "")
                                 util/snake-case)))
        script-config (merge (some-> lib common/default-script-config)
                             (some-> bin-config first val)
                             ;; `--ns-default` overrides both the derived
                             ;; default and any bb.edn `:bbin/bin` value.
                             (when ns-default {:ns-default ns-default}))
        bin-config' {script-name script-config}]
    {::analyze/scripts bin-config'}))

(defn- analyze-file
  [{::parse/keys [as jars-dir] ::load/keys [artifact-path] :as _params}]
  (let [script-name (or as (-> (fs/file-name artifact-path)
                               (str/replace #"\.(clj|cljc|bb|jar)$" "")
                               util/snake-case))]
   (cond-> {::analyze/scripts {script-name {}}}
     (str/ends-with? artifact-path ".jar")
     (assoc ::analyze/jar-path (str (fs/file jars-dir (str script-name ".jar")))))))

(defn- analyze-deps [{::parse/keys [as lib] :as _params}]
  (let [script-name (or as (name lib))
        script-config (common/default-script-config lib)]
    {::analyze/scripts {script-name script-config}}))

(defn- analyze
  "Analyze source contents to find scripts."
  [{::parse/keys [coords] ::load/keys [artifact-path] :as params}]
  (cond
    (some-> artifact-path fs/directory?)
    (analyze-dir params)

    (some-> artifact-path fs/regular-file?)
    (analyze-file params)

    coords
    (analyze-deps params)))

;; =============================================================================
;; Select

(defn select
  "Select scripts to install."
  [{::analyze/keys [scripts] :as _params}]
  {::select/selected (set (keys scripts))})

;; =============================================================================
;; Generate

(defn- generate-script-meta [{::parse/keys [header] :as _params}]
  (->> (binding [*print-namespace-maps* false]
         (with-out-str (pprint/pprint header)))
       str/split-lines
       (map #(str common/comment-char " " %))
       (str/join "\n")))

(defn- generate-dir-script
  [{::parse/keys [header tool main-opts]
    ::load/keys [artifact-path]
    ::generate/keys [script-config]
    :as params}]
  (let [template-opts {:script/meta (generate-script-meta params)
                       :script/root artifact-path
                       :script/lib (:lib header)
                       :script/coords (binding [*print-namespace-maps* false]
                                        (pr-str (:coords header)))
                       :script/ns-default (:ns-default script-config)}
        main-opts-source (or (some-> main-opts edn/read-string)
                             (:main-opts script-config))
        tool-mode (or tool (and (:ns-default script-config)
                                (not (:main-opts script-config))))
        _ (when (and (not tool-mode) (not (seq main-opts-source)))
            (throw (ex-info "Main opts not found. Use --main-opts or :bbin/bin to provide main opts."
                            {})))
        main-opts' (common/process-main-opts main-opts-source artifact-path)
        template-opts' (assoc template-opts :script/main-opts main-opts')
        script-contents (selmer-util/without-escaping
                          (selmer/render
                            (if tool-mode
                              (if (:lib header)
                                common/deps-tool-template-str
                                common/local-dir-tool-template-str)
                              (if (:git/url (:coords header))
                                common/git-dir-template-str
                                common/local-dir-template-str-with-bb-edn))
                            template-opts'))]
    {::generate/script-contents script-contents}))

(defn- generate-source-code-script
  [{::parse/keys [header] ::load/keys [artifact-path] :as _params}]
  (let [source-contents (slurp artifact-path)]
    {::generate/script-contents (common/insert-script-header source-contents header)}))

(defn- generate-local-jar-script
  [{::analyze/keys [jar-path] ::load/keys [artifact-path] :as params}]
  (let [main-ns (common/jar->main-ns artifact-path)
        template-opts {:script/meta (generate-script-meta params)
                       :script/jar jar-path
                       :script/main-ns main-ns}
        script-contents (selmer-util/without-escaping
                          (selmer/render common/local-jar-template-str
                                         template-opts))]
    {::generate/script-contents script-contents}))

(defn- generate-maven-jar-script
  [{::parse/keys [main-opts header]
    ::load/keys [artifact-path]
    ::generate/keys [script-config]
    :as params}]
  (let [main-opts' (-> (or (some-> main-opts edn/read-string)
                           (:main-opts script-config))
                       (common/process-main-opts artifact-path))
        template-opts {:script/meta (generate-script-meta params)
                       :script/coords (:coords header)
                       :script/lib (:lib header)
                       :script/main-opts main-opts'}
        script-contents (selmer-util/without-escaping
                          (selmer/render common/maven-template-str
                                         template-opts))]
    {::generate/script-contents script-contents}))

(defn- generate-single
  [{::parse/keys [coords] ::load/keys [artifact-path] :as params}]
  (cond
    (some-> artifact-path (str/ends-with? ".jar"))
    (generate-local-jar-script params)

    (:mvn/version coords)
    (generate-maven-jar-script params)

    (some-> artifact-path fs/directory?)
    (generate-dir-script params)

    (some-> artifact-path fs/regular-file?)
    (generate-source-code-script params)))

(defn generate
  "Generate the script contents."
  [{::analyze/keys [scripts] ::select/keys [selected] :as params}]
  (let [generated (-> (select-keys scripts selected)
                      (update-vals (fn [script-config]
                                     (-> params
                                         (assoc ::generate/script-config
                                                script-config)
                                         generate-single))))]
    {::generate/generated generated}))

;; =============================================================================
;; Write

(defn- write-single!
  [{::parse/keys [bin-dir jars-dir]
    ::load/keys [artifact-path]
    ::analyze/keys [jar-path]
    ::generate/keys [script-name script-config]
    :as params}]
  (let [script-path (str (fs/path bin-dir (str script-name)))]
    (when jar-path
      (fs/create-dirs jars-dir)
      (fs/copy artifact-path jar-path {:replace-existing true})
      (event! params {::event-type ::jar-copied}))
    (spit script-path (::generate/script-contents script-config))
    (if util/windows?
      (spit (str script-path ".bat")
            (str "@bb -f %~dp0" (fs/file-name script-path) " -- %*"))
      (util/sh ["chmod" "+x" script-path]))
    (event! params {::event-type ::script-written})
    [script-name {::write/script-path script-path}]))

(defn write!
  "Write the script files."
  [{::generate/keys [generated] :as params}]
  (let [written (doall
                  (->> generated
                       (map (fn [[script-name script-config]]
                              (write-single!
                                (merge params
                                       {::generate/script-name script-name
                                        ::generate/script-config script-config}))))
                       (into {})))]
    {::write/written written}))

;; =============================================================================
;; Install

(def install-steps
  [#'parse
   #'load!
   #'analyze
   #'select
   #'generate
   #'write!])

(defn- install-start [{::input/keys [cli-opts] :as params}]
  (when-not (util/edn? cli-opts)
    (println)
    (println (util/bold "Starting install..." cli-opts))
    (println))
  params)

(defn- install-run-single-step [params step-fn]
  (try
    (let [ret (step-fn params)]
      (event! params {::event-type ::step-completed
                      :step (symbol step-fn)
                      :ret ret})
      (merge params ret))
    (catch Exception e
      (event! params {::event-type ::step-failed
                      :step (symbol step-fn)
                      :error e})
      (throw e))))

(defn- install-run-all-steps [{::input/keys [cli-opts] :as params}]
  (let [params' (reduce install-run-single-step params install-steps)]
    (if (util/edn? cli-opts)
      (prn (-> params'
               (select-keys [::parse/coords ::parse/lib])
               (set/rename-keys {::parse/coords :coords, ::parse/lib :lib})))
      (let [out-scripts {(first (::select/selected params'))
                         (::parse/header params')}]
        (util/print-scripts (util/printable-scripts out-scripts) cli-opts)))
    params'))

(defn- install-end [{::input/keys [cli-opts] :as params}]
  (when-not (util/edn? cli-opts)
    (println)
    (println (util/bold "Install complete." cli-opts))
    (println))
  params)

(defn install [cli-opts]
  (fs/with-temp-dir [tmp-dir {}]
    (let [params {::input/cli-opts cli-opts
                  ::input/tmp-dir (str tmp-dir)}]
      (-> params
          install-start
          install-run-all-steps
          install-end))))
