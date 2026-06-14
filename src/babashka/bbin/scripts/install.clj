(ns babashka.bbin.scripts.install
  "Install pipeline for `bbin install`.

  Installation runs as a series of steps (see `install-steps`), each a function
  that takes the accumulating `state` map and returns a map merged back into it.
  Every step reads earlier steps' output under its own key and writes its own
  results under a single top-level key. The keys, in order:

  - `:input`    Seed data, set before any step runs.
    - `:cli-opts`  Parsed CLI options for the install command.
    - `:tmp-dir`   Path to a temp dir for downloaded artifacts (e.g. HTTP).

  - `:parse`    Coordinates and options resolved from `:cli-opts` (`parse`).
    - `:coords`         Dependency coordinates (git, maven, local, or url).
    - `:lib`            Fully-qualified lib symbol, when known.
    - `:synthetic-lib`  True when `:lib` was generated from a git URL rather
                        than supplied by the user.
    - `:source-path`    Canonical filesystem path, for local installs.
    - `:header`         `{:lib :coords}` map embedded in the script header.
    - `:tool`           True to install as a tool (`--tool`).
    - `:as`             Explicit script name override (`--as`).
    - `:bb-opts`        Validated `bb` options to bake into the script.
    - `:main-opts`      Raw `--main-opts` string, when provided.
    - `:ns-default`     Default namespace for tool-mode scripts.
    - `:bin-dir`        Directory the script will be written to.
    - `:jars-dir`       Directory jars are copied to.

  - `:load`     Files fetched/located for the install (`load!`).
    - `:loaded-path`    Path produced by procuring/downloading the artifact.
    - `:artifact-path`  Path to the artifact to analyze (`:loaded-path` or
                          `:source-path`).

  - `:analyze`  Scripts discovered in the artifact (`analyze`).
    - `:scripts`        Map of script-name -> script-config to install.
    - `:jar-path`       Destination jar path, for local jar installs.

  - `:select`   Which discovered scripts to install (`select`).
    - `:selected`       Set of script names chosen from `:scripts`.

  - `:generate` Rendered script contents (`generate`).
    - `:generated`      Map of script-name -> `{:script-contents ...}`.
    - `:script-config`  Single script's config, bound while generating it.
    - `:script-name`    Single script's name, bound while writing it.

  - `:write`    Files written to disk (`write!`).
    - `:written`        Map of script-name -> `{:script-path ...}`."
  (:require [babashka.bbin.deps :as deps]
            [babashka.bbin.deps.add-libs :as deps-add-libs]
            [babashka.bbin.dirs :as dirs]
            [babashka.bbin.scripts.install.templates :as templates]
            [babashka.bbin.specs]
            [babashka.bbin.util :as util]
            [babashka.fs :as fs]
            [babashka.http-client :as http]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.main :as main]
            [clojure.pprint :as pprint]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [clojure.tools.gitlibs :as gitlibs]
            [selmer.parser :as selmer]
            [selmer.util :as selmer-util]
            [taoensso.timbre :as log])
  (:import (java.util.jar JarFile)))

(defn- event!
  ([state event-type]
   (event! state event-type nil))
  ([state event-type payload]
   (let [event (merge payload {:event-type event-type, :state state})]
     (log/debug event))))

;; =============================================================================
;; Helpers

(def comment-char ";")

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

(defn- read-bb-edn [script-root]
  (let [bb-file (fs/file script-root "bb.edn")
        bb-edn (when (fs/exists? bb-file)
                 (some-> bb-file slurp edn/read-string))]
    bb-edn))

(def bb-opts-config-message
  "Use --bb-opts '[\"--config\" \"bb.edn\"]' to pass --config to bb.")

(defn- parse-bb-opts [bb-opts]
  (cond
    (string? bb-opts) (edn/read-string bb-opts)
    (nil? bb-opts) nil
    :else bb-opts))

(defn validate-bb-opts [bb-opts]
  (let [bb-opts' (parse-bb-opts bb-opts)]
    (cond
      (nil? bb-opts')
      nil

      (and (sequential? bb-opts') (empty? bb-opts'))
      nil

      (and (sequential? bb-opts')
           (= 2 (count bb-opts'))
           (= "--config" (first bb-opts'))
           (string? (second bb-opts')))
      (vec bb-opts')

      :else
      (throw (ex-info (str "Only --config is supported by --bb-opts. "
                           bb-opts-config-message)
                      {:bbin/bb-opts bb-opts'})))))

(defn bb-opts->config [bb-opts]
  (second (validate-bb-opts bb-opts)))

(defn- resolve-config [script-root config]
  (let [config-file (fs/file config)]
    (str (fs/canonicalize
          (if (.isAbsolute config-file)
            config-file
            (if script-root
              (fs/file script-root config)
              config-file))
          {:nofollow-links true}))))

(defn- resolve-bb-opts [script-root bb-opts]
  (when-let [[_ config] (validate-bb-opts bb-opts)]
    ["--config" (resolve-config script-root config)]))

(defn dir-config-strategy [script-root cli-bb-opts]
  (let [bb-edn (read-bb-edn script-root)
        cli-bb-opts' (resolve-bb-opts script-root cli-bb-opts)]
    (cond
      cli-bb-opts'
      {:strategy :explicit-config
       :config (second cli-bb-opts')
       :bb-opts cli-bb-opts'}

      :else
      (let [author-bb-opts (resolve-bb-opts script-root (:bbin/bb-opts bb-edn))
            has-deps (fs/exists? (fs/file script-root "deps.edn"))
            has-bb (fs/exists? (fs/file script-root "bb.edn"))]
        (cond
          author-bb-opts
          {:strategy :explicit-config
           :config (second author-bb-opts)
           :bb-opts author-bb-opts}

          has-deps
          {:strategy :generated}

          has-bb
          {:strategy :explicit-config
           :config (resolve-config script-root "bb.edn")}

          :else
          {:strategy :no-config})))))

(defn default-script-config [lib]
  (let [lib-ns (namespace lib)
        lib-name (name lib)
        ;; An unqualified lib (no namespace) has no group segment; fall back to
        ;; the lib name alone instead of NPEing on (str/split nil ...).
        top (some-> lib-ns (str/split #"\.") last)
        main-ns (if top (str top "." lib-name) lib-name)]
    {:main-opts ["-m" main-ns]
     :ns-default main-ns}))

(defn- generate-deps-lib-name [git-url]
  (let [s (str "script-"
               (.hashCode git-url)
               "-"
               (-> git-url
                   (str/replace #"[^a-zA-Z0-9-]" "-")
                   (str/replace #"--+" "-")))]
    (symbol "org.babashka.bbin" s)))

(defn- load-bin-config [script-root]
  (let [bin-config (:bbin/bin (read-bb-edn script-root))]
    (when bin-config
      (if (s/valid? :bbin/bin bin-config)
        bin-config
        (throw (ex-info (s/explain-str :bbin/bin bin-config)
                        {:bbin/bin bin-config}))))))

(defn- process-main-opts
  "Process main-opts, canonicalizing file paths that follow -f flags.
  When `script-root` is nil (e.g. Maven installs, which have no source
  root), paths are left unchanged."
  [main-opts script-root]
  (->> main-opts
       (map-indexed (fn [i arg]
                      (if (and script-root
                               (pos? i) (= "-f" (nth main-opts (dec i))))
                        (str (fs/canonicalize (fs/file script-root arg)
                                              {:nofollow-links true}))
                        arg)))
       vec))

(defn- dir-template [strategy {:keys [git tool-mode lib]}]
  (case (:strategy strategy)
    :explicit-config
    (if tool-mode templates/config-tool-template-str templates/config-dir-template-str)

    :no-config
    (if tool-mode
      (throw (ex-info "Tool mode requires a bb.edn or deps.edn in the directory." {}))
      templates/no-config-dir-template-str)

    :generated
    (cond
      (and tool-mode lib) templates/deps-tool-template-str
      tool-mode templates/local-dir-tool-template-str
      git templates/git-dir-template-str
      :else templates/local-dir-template-str)))

(defn- jar->main-ns [jar-path]
  (with-open [jar-file (JarFile. (fs/file jar-path))]
    (let [main-attributes (some-> jar-file .getManifest .getMainAttributes)
          ;; TODO After July 17th 2023: Remove workaround below and start using (.getValue "Main-Class") instead
          ;;      (see https://github.com/babashka/bbin/pull/47#discussion_r1071348344)
          main-class (some (fn [[k v]] (when (str/includes? k "Main-Class") v))
                           main-attributes)]
      (if main-class
        (main/demunge main-class)
        (throw (ex-info "jar has no Main-Class" {:jar-path jar-path}))))))

;; =============================================================================
;; Parse

(defn- invalid-script-coordinates-error [cli-opts procurer artifact]
  (ex-info (str "Invalid script coordinates.\n"
                "If you're trying to install from the filesystem, "
                "make sure the path actually exists.")
           {:script/lib (:script/lib cli-opts)
            :procurer procurer
            :artifact artifact}))

(defn- parse-deps
  [{{:keys [cli-opts]} :input
    :as _state}]
  (cond
    (:mvn/version cli-opts)
    {:coords (select-keys cli-opts [:mvn/version])
     :lib (edn/read-string (:script/lib cli-opts))}

    (deps/git-repo-url? (:script/lib cli-opts))
    (let [git-url (cond-> (:script/lib cli-opts)
                    (and (str/starts-with? (:script/lib cli-opts) "https://")
                         (not (str/ends-with? (:script/lib cli-opts) ".git")))
                    (str ".git"))
          lib (generate-deps-lib-name git-url)
          inferred (deps/infer
                    (cond-> (merge cli-opts {:lib (str lib), :git/url git-url})
                      (not (some cli-opts [:latest-tag :latest-sha :git/sha :git/tag]))
                      (assoc :latest-sha true)))
          coords (get inferred lib)]
      {:coords coords
       :lib lib
       :synthetic-lib true})

    :else
    (let [git-url (:script/lib cli-opts)
          lib (edn/read-string (:script/lib cli-opts))
          inferred (deps/infer (assoc cli-opts :lib git-url))
          coords (get inferred lib)]
      {:coords coords
       :lib lib})))

(defn- parse-http
  [{{:keys [cli-opts]} :input
    :as _state}]
  {:coords {:bbin/url (:script/lib cli-opts)}})

(defn- parse-local
  [{{:keys [cli-opts]} :input
    :as _state}]
  (let [source-path (-> (fs/path (or (:local/root cli-opts)
                                     (:script/lib cli-opts)))
                        (fs/canonicalize {:nofollow-links true})
                        str)]
    (cond-> {:coords (if (:local/root cli-opts)
                       {:local/root source-path}
                       {:bbin/url (str "file://" source-path)})
             :source-path source-path}

      (:local/root cli-opts)
      (assoc :lib (edn/read-string (:script/lib cli-opts))))))

(defn parse
  "Parse coords from the CLI options."
  [{{:keys [cli-opts]} :input
    :as state}]
  (let [{:keys [tool as main-opts ns-default bb-opts]} cli-opts
        bb-opts' (validate-bb-opts bb-opts)
        {:keys [procurer artifact]} (deps/summary cli-opts)
        parsed (case [procurer artifact]
                 ([:git :dir] [:maven :jar])
                 (parse-deps state)

                 ([:http :file] [:http :jar])
                 (parse-http state)

                 ([:local :file] [:local :jar] [:local :dir])
                 (parse-local state)

                 (throw (invalid-script-coordinates-error cli-opts procurer artifact)))
        header (select-keys parsed [:lib :coords])
        extra (->> {:header header
                    :tool tool
                    :as as
                    :bb-opts bb-opts'
                    :main-opts main-opts
                    :ns-default (some-> ns-default edn/read-string)
                    :bin-dir (str (dirs/bin-dir cli-opts))
                    :jars-dir (str (dirs/jars-dir cli-opts))}
                   (filter #(some? (val %)))
                   (into {}))]
    {:parse (merge parsed extra)}))

;; =============================================================================
;; Load

(defn- load-repo!
  [{{:keys [coords lib]} :parse
    :as state}]
  (let [loaded-path (gitlibs/procure (:git/url coords) lib (:git/sha coords))]
    (event! state ::gitlibs-procured)
    {:loaded-path loaded-path}))

(defn- load-http-file!
  [{{:keys [tmp-dir]} :input
    {:keys [coords]} :parse
    :as state}]
  (let [loaded-path (str (fs/path tmp-dir (fs/file-name (:bbin/url coords))))]
    (io/copy (:body (http/get (:bbin/url coords) {:as :bytes}))
             (fs/file loaded-path))
    (event! state ::http-file-loaded)
    {:loaded-path loaded-path}))

(defn- load-deps!
  [{{:keys [lib coords]} :parse
    :as state}]
  (deps-add-libs/add-libs {lib coords})
  (event! state ::deps-loaded))

(defn load!
  "Load files used to generate the script."
  [{{:keys [coords source-path]} :parse
    :as state}]
  (let [loaded (cond
                 (:git/url coords)
                 (load-repo! state)

                 (some->> (:bbin/url coords) (re-matches #"^https?://.+"))
                 (load-http-file! state)

                 (not (or (:bbin/url coords) (:local/root coords)))
                 (load-deps! state))
        artifact-path (or (:loaded-path loaded) source-path)]
    {:load (merge loaded {:artifact-path artifact-path})}))

;; =============================================================================
;; Analyze

(defn- script-name-not-found-error [header]
  (ex-info (str "Script name not found. "
                "Use --as or :bbin/bin to provide a script name.")
           (or header {})))

(defn- analyze-dir
  [{{:keys [as header lib ns-default synthetic-lib]} :parse
    {:keys [artifact-path]} :load
    :as _state}]
  (let [bin-config (load-bin-config artifact-path)
        script-name (or as
                        (some-> bin-config first key str)
                        (when-not synthetic-lib
                          (some-> lib name))
                        (when-not synthetic-lib
                          (-> (fs/file-name artifact-path)
                              (str/replace #"\.(clj|cljc|bb|jar)$" "")
                              util/snake-case)))
        _ (when (str/blank? script-name)
            (throw (script-name-not-found-error header)))
        script-config (merge (some-> lib default-script-config)
                             (some-> bin-config first val)
                             (when ns-default {:ns-default ns-default}))
        bin-config' {script-name script-config}]
    {:scripts bin-config'}))

(defn- analyze-file
  [{{:keys [as jars-dir]} :parse
    {:keys [artifact-path]} :load
    :as _state}]
  (let [script-name (or as (-> (fs/file-name artifact-path)
                               (str/replace #"\.(clj|cljc|bb|jar)$" "")
                               util/snake-case))]
    (cond-> {:scripts {script-name {}}}
      (str/ends-with? artifact-path ".jar")
      (assoc :jar-path (str (fs/file jars-dir (str script-name ".jar")))))))

(defn- analyze-deps
  [{{:keys [as lib]} :parse
    :as _state}]
  (let [script-name (or as (name lib))
        script-config (default-script-config lib)]
    {:scripts {script-name script-config}}))

(defn- analyze
  "Analyze source contents to find scripts."
  [{{:keys [coords]} :parse
    {:keys [artifact-path]} :load
    :as state}]
  {:analyze (cond
              (some-> artifact-path fs/directory?)
              (analyze-dir state)

              (some-> artifact-path fs/regular-file?)
              (analyze-file state)

              coords
              (analyze-deps state))})

;; =============================================================================
;; Select

(defn select
  "Select scripts to install."
  [{{:keys [scripts]} :analyze
    :as _state}]
  ; Select only first script for now to preserve existing behavior
  {:select {:selected #{(first (keys scripts))}}})

;; =============================================================================
;; Generate

(defn- generate-script-meta
  [{{:keys [header]} :parse
    :as _state}]
  (->> (binding [*print-namespace-maps* false]
         (with-out-str (pprint/pprint header)))
       str/split-lines
       (map #(str comment-char " " %))
       (str/join "\n")))

(defn- generate-dir-script
  [{{:keys [header tool main-opts bb-opts]} :parse
    {:keys [artifact-path]} :load
    {:keys [script-config]} :generate
    :as state}]
  (let [strategy (dir-config-strategy artifact-path bb-opts)
        script-bb-opts (:bb-opts strategy)
        header' (cond-> header
                  script-bb-opts
                  (assoc :bbin/bb-opts script-bb-opts))
        state' (assoc-in state [:parse :header] header')
        template-opts {:script/meta (generate-script-meta state')
                       :script/root artifact-path
                       :script/config (:config strategy)
                       :script/bb-opts (pr-str (or script-bb-opts []))
                       :script/lib (:lib header')
                       :script/coords (binding [*print-namespace-maps* false]
                                        (pr-str (:coords header')))
                       :script/ns-default (:ns-default script-config)}
        main-opts-source (or (some-> main-opts edn/read-string)
                             (:main-opts script-config))
        tool-mode (or tool (and (:ns-default script-config)
                                (not (:main-opts script-config))))
        _ (when (and (not tool-mode) (not (seq main-opts-source)))
            (throw (ex-info "Main opts not found. Use --main-opts or :bbin/bin to provide main opts."
                            {})))
        main-opts' (process-main-opts main-opts-source artifact-path)
        template-opts' (assoc template-opts :script/main-opts main-opts')
        template (dir-template strategy
                               {:git (boolean (:git/url (:coords header')))
                                :tool-mode (boolean tool-mode)
                                :lib (boolean (:lib header'))})
        script-contents (selmer-util/without-escaping
                         (selmer/render
                          template
                          template-opts'))]
    {:script-contents script-contents}))

(defn- generate-source-code-script
  [{{:keys [header]} :parse
    {:keys [artifact-path]} :load
    :as _state}]
  (let [source-contents (slurp artifact-path)]
    {:script-contents (insert-script-header source-contents header)}))

(defn- generate-local-jar-script
  [{{:keys [jar-path]} :analyze
    {:keys [artifact-path]} :load
    :as state}]
  (let [main-ns (jar->main-ns artifact-path)
        template-opts {:script/meta (generate-script-meta state)
                       :script/jar jar-path
                       :script/main-ns main-ns}
        script-contents (selmer-util/without-escaping
                         (selmer/render templates/local-jar-template-str
                                        template-opts))]
    {:script-contents script-contents}))

(defn- generate-maven-jar-script
  [{{:keys [main-opts header]} :parse
    {:keys [artifact-path]} :load
    {:keys [script-config]} :generate
    :as state}]
  (let [main-opts' (-> (or (some-> main-opts edn/read-string)
                           (:main-opts script-config))
                       (process-main-opts artifact-path))
        template-opts {:script/meta (generate-script-meta state)
                       :script/coords (:coords header)
                       :script/lib (:lib header)
                       :script/main-opts main-opts'}
        script-contents (selmer-util/without-escaping
                         (selmer/render templates/maven-template-str
                                        template-opts))]
    {:script-contents script-contents}))

(defn- generate-single
  [{{:keys [coords]} :parse
    {:keys [artifact-path]} :load
    :as state}]
  (cond
    (some-> artifact-path (str/ends-with? ".jar"))
    (generate-local-jar-script state)

    (:mvn/version coords)
    (generate-maven-jar-script state)

    (some-> artifact-path fs/directory?)
    (generate-dir-script state)

    (some-> artifact-path fs/regular-file?)
    (generate-source-code-script state)))

(defn generate
  "Generate the script contents."
  [{{:keys [scripts]} :analyze
    {:keys [selected]} :select
    :as state}]
  (let [generated (-> (select-keys scripts selected)
                      (update-vals (fn [script-config]
                                     (-> state
                                         (assoc-in [:generate :script-config]
                                                   script-config)
                                         generate-single))))]
    {:generate {:generated generated}}))

;; =============================================================================
;; Write

(defn- write-single!
  [{{:keys [cli-opts]} :input
    {:keys [bin-dir jars-dir]} :parse
    {:keys [artifact-path]} :load
    {:keys [jar-path]} :analyze
    {:keys [script-name script-config]} :generate
    :as state}]
  (let [script-path (str (fs/path bin-dir (str script-name)))]
    (when jar-path
      (fs/create-dirs jars-dir)
      (fs/copy artifact-path jar-path {:replace-existing true})
      (event! state ::jar-copied))
    (if (:dry-run cli-opts)
      (util/pprint {:script-file script-path
                    :script-contents (:script-contents script-config)}
                   (:dry-run cli-opts))
      (do
        (spit script-path (:script-contents script-config))
        (if (fs/windows?)
          (spit (str script-path ".bat")
                (str "@bb -f %~dp0" (fs/file-name script-path) " -- %*"))
          (util/sh ["chmod" "+x" script-path]))))
    (event! state ::script-written)
    [script-name {:script-path script-path}]))

(defn write!
  "Write the script files."
  [{{:keys [generated]} :generate
    :as state}]
  (let [written (doall
                 (->> generated
                      (map (fn [[script-name script-config]]
                             (write-single!
                              (update state :generate merge
                                      {:script-name script-name
                                       :script-config script-config}))))
                      (into {})))]
    {:write {:written written}}))

;; =============================================================================
;; Install

(def install-steps
  [#'parse
   #'load!
   #'analyze
   #'select
   #'generate
   #'write!])

(defn- install-start
  [{{:keys [cli-opts]} :input
    :as state}]
  (when-not (util/edn? cli-opts)
    (println)
    (println (util/bold "Starting install..." cli-opts))
    (println))
  state)

(defn- install-run-single-step [state step-fn]
  (try
    (let [ret (step-fn state)]
      (event! state ::step-completed {:step (symbol step-fn), :ret ret})
      (merge state ret))
    (catch Exception e
      (event! state ::step-failed {:step (symbol step-fn), :error e})
      (throw e))))

(defn- install-run-all-steps
  [{{:keys [cli-opts]} :input
    :as state}]
  (let [state' (reduce install-run-single-step state install-steps)]
    (if (util/edn? cli-opts)
      (prn (-> (:parse state')
               (select-keys [:coords :lib])))
      (let [out-scripts {(first (get-in state' [:select :selected]))
                         (get-in state' [:parse :header])}]
        (util/print-scripts (util/printable-scripts out-scripts) cli-opts)))
    state'))

(defn- install-end
  [{{:keys [cli-opts]} :input
    :as state}]
  (when-not (util/edn? cli-opts)
    (println)
    (println (util/bold "Install complete." cli-opts))
    (println))
  state)

(defn install [cli-opts]
  (util/set-logging-config! cli-opts)
  (fs/with-temp-dir [tmp-dir {}]
    (let [state {:input {:cli-opts cli-opts
                         :tmp-dir (str tmp-dir)}}]
      (-> state
          install-start
          install-run-all-steps
          install-end))))
