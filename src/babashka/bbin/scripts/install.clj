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
            [clojure.walk :as walk]
            [selmer.parser :as selmer]
            [selmer.util :as selmer-util]))

;; =============================================================================
;; Common

(defn ->artifact-path [params]
  (or (::load/loaded-path params) (::parse/source-path params)))

;; =============================================================================
;; Parse

(defn- parse-deps [{::input/keys [cli-opts] :as _params}]
  (cond
    (:mvn/version cli-opts)
    {::parse/coords (select-keys cli-opts [:mvn/version])
     ::parse/lib (edn/read-string (:script/lib cli-opts))}

    (str/starts-with? (:script/lib cli-opts) "https://")
    (let [git-url (cond-> (:script/lib cli-opts)
                    (not (str/ends-with? (:script/lib cli-opts) ".git"))
                    (str ".git"))
          lib (symbol "bbin" (Integer/toHexString (hash git-url)))
          inferred (deps/infer (-> cli-opts
                                (assoc :lib (str lib))
                                (assoc :git/url git-url)))
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
  (let [source-path (-> (fs/path (:script/lib cli-opts))
                        (fs/canonicalize {:nofollow-links true})
                        str)]
    {::parse/coords {:bbin/url (str "file://" source-path)}
     ::parse/source-path source-path}))

(defn parse
  "Parse coords from the CLI options."
  [{::input/keys [cli-opts] :as params}]
  (let [{:keys [tool-mode as main-opts]} cli-opts
        {:keys [procurer artifact]} (deps/summary cli-opts)
        parsed (case [procurer artifact]
                 ([:git :dir] [:maven :jar])
                 (parse-deps params)

                 ([:http :file] [:http :jar])
                 (parse-http params)

                 ([:local :file] [:local :jar] [:local :dir])
                 (parse-local params))
        header (-> (select-keys parsed [::parse/lib ::parse/coords])
                   (set/rename-keys {::parse/lib :lib
                                     ::parse/coords :coords}))
        extra (->> {::parse/header header
                    ::parse/tool-mode tool-mode
                    ::parse/as as
                    ::parse/main-opts main-opts
                    ::parse/bin-dir (str (dirs/bin-dir cli-opts))
                    ::parse/jars-dir (str (dirs/jars-dir cli-opts))}
                   (filter #(some? (val %)))
                   (into {}))]
    (merge parsed extra)))

;; =============================================================================
;; Load

(defn- load-repo [{::parse/keys [coords lib] :as _params}]
  {::load/loaded-path (gitlibs/procure (:git/url coords)
                                       lib
                                       (:git/sha coords))})

(defn- load-http-file
  [{::input/keys [tmp-dir] ::parse/keys [coords] :as _params}]
  (let [loaded-path (str (fs/path tmp-dir (fs/file-name (:bbin/url coords))))]
    (io/copy (:body (http/get (:bbin/url coords) {:as :bytes}))
             (fs/file loaded-path))
    {::load/loaded-path loaded-path}))

(defn- load-deps [{::parse/keys [lib coords] :as _params}]
  {::load/loaded-deps (deps/add-libs {lib coords})})

(defn load!
  "Load files used to generate the script."
  [{::parse/keys [coords] :as params}]
  (cond
    (:git/url coords)
    (load-repo params)

    (some->> (:bbin/url coords) (re-matches #"^https?://.+"))
    (load-http-file params)

    (not (:bbin/url coords))
    (load-deps params)))

;; =============================================================================
;; Analyze

(defn- analyze-dir [params]
  (let [artifact-path (->artifact-path params)
        bin-config (common/load-bin-config artifact-path)]
    {::analyze/scripts (update-keys bin-config str)}))

(defn- analyze-file [{::parse/keys [jars-dir] :as params}]
  (let [artifact-path (->artifact-path params)
        script-name (-> (fs/file-name artifact-path)
                        (str/replace #"\.(clj|cljc|bb|jar)$" ""))]
   (cond-> {::analyze/scripts {script-name {}}}
     (str/ends-with? artifact-path ".jar")
     (assoc ::analyze/jar-path (str (fs/file jars-dir (str script-name ".jar")))))))

(defn- analyze-deps [{::parse/keys [as lib] :as _params}]
  (let [script-name (or as (name lib))
        script-config (common/default-script-config lib)]
    {::analyze/scripts {script-name script-config}}))

(defn- analyze
  "Analyze source contents to find scripts."
  [{::parse/keys [coords source-path] ::load/keys [loaded-path] :as params}]
  (cond
    (or (some-> loaded-path fs/directory?)
        (some-> source-path fs/directory?))
    (analyze-dir params)

    (or (some-> loaded-path fs/regular-file?)
        (some-> source-path fs/regular-file?))
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
  [{::parse/keys [source-path coords main-opts] :as params}]
  (let [template-opts {:script/meta (generate-script-meta params)
                       :script/root source-path
                       :script/lib (pr-str (key (first coords)))
                       :script/coords (binding [*print-namespace-maps* false]
                                        (pr-str (val (first coords))))}
        main-opts' (common/process-main-opts main-opts source-path)
        template-opts' (assoc template-opts :script/main-opts main-opts')
        script-contents (selmer-util/without-escaping
                          (selmer/render
                            common/git-or-local-template-str-with-bb-edn
                            template-opts'))]
    {::generate/script-contents script-contents}))

(defn- generate-source-code-script [{::parse/keys [header] :as params}]
  (let [artifact-path (->artifact-path params)
        source-contents (slurp artifact-path)]
    {::generate/script-contents (common/insert-script-header source-contents header)}))

(defn- generate-local-jar-script [{::analyze/keys [jar-path] :as params}]
  (let [artifact-path (->artifact-path params)
        main-ns (common/jar->main-ns artifact-path)
        template-opts {:script/meta (generate-script-meta params)
                       :script/jar jar-path
                       :script/main-ns main-ns}
        script-contents (selmer-util/without-escaping
                          (selmer/render common/local-jar-template-str
                                         template-opts))]
    {::generate/script-contents script-contents}))

(defn- generate-maven-jar-script
  [{::parse/keys [main-opts header]
    ::load/keys [loaded-path]
    ::generate/keys [script-config]
    :as params}]
  (let [main-opts' (-> (or (some-> main-opts edn/read-string)
                           (:main-opts script-config))
                       (common/process-main-opts loaded-path))
        template-opts {:script/meta (generate-script-meta params)
                       :script/coords (:coords header)
                       :script/lib (:lib header)
                       :script/main-opts main-opts'}
        script-contents (selmer-util/without-escaping
                          (selmer/render common/maven-template-str
                                         template-opts))]
    {::generate/script-contents script-contents}))

(defn- generate-single [{::parse/keys [coords] :as params}]
  (let [artifact-path (->artifact-path params)]
    (cond
      (some-> artifact-path (str/ends-with? ".jar"))
      (generate-local-jar-script params)

      (:mvn/version coords)
      (generate-maven-jar-script params)

      (some-> artifact-path fs/directory?)
      (generate-dir-script params)

      (some-> artifact-path fs/regular-file?)
      (generate-source-code-script params))))

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

(defn- write-single
  [{::parse/keys [bin-dir jars-dir]
    ::analyze/keys [jar-path]
    ::generate/keys [script-name script-config]
    :as params}]
  (let [artifact-path (->artifact-path params)
        script-path (str (fs/path bin-dir (str script-name)))]
    (when jar-path
      (fs/create-dirs jars-dir)
      (fs/copy artifact-path jar-path {:replace-existing true}))
    (spit script-path (::generate/script-contents script-config))
    (when-not util/windows?
      (util/sh ["chmod" "+x" script-path]))
    [script-name {::write/script-path script-path}]))

(defn write!
  "Write the script files."
  [{::generate/keys [generated] :as params}]
  (let [written (doall
                  (->> generated
                       (map (fn [[script-name script-config]]
                              (write-single
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

(defn format-params [params]
  (let [step-ns (map (fn [step-var]
                       (let [var-name (-> step-var meta :name)]
                         (str (namespace ::this)
                              "."
                              (str/replace var-name "!" ""))))
                     install-steps)]
    (->> params
         (sort-by (fn [[k]] [(.indexOf step-ns (namespace k)) k]))
         (walk/postwalk (fn [x]
                          (if (and (keyword? x)
                                   (namespace x)
                                   (str/starts-with? (namespace x)
                                                     (namespace ::this)))
                            (keyword (str/replace (namespace x)
                                                  (str (namespace ::this) ".")
                                                  "")
                                     (name x))
                            x))))))

(defn- install-run [{::input/keys [cli-opts] :as params}]
  (let [params' (reduce (fn [params step-fn]
                          (try
                            (let [ret (step-fn params)]
                              (tap> {:_step-fn step-fn, :params params, :ret ret})
                              (merge params ret))
                            (catch Exception e
                              (tap> {:_step-fn step-fn, :params params, :error e})
                              (throw e))))
                        params
                        install-steps)]
    (when (util/edn? cli-opts)
      (prn (-> params'
               (select-keys [::parse/coords ::parse/lib])
               (set/rename-keys {::parse/coords :coords, ::parse/lib :lib}))))
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
            install-run
            install-end))))

(comment
  (do
    (require '[clojure.string :as str]
             '[babashka.bbin.cli :as cli])

    (defn bbin [command]
      (apply cli/-main (str/split command #" "))))

  (bbin "install ./test-resources/install-sources/test-dir --as btest")
  (bbin "install ./test-resources/install-sources/bbin-test-script-3.clj")
  (bbin "install ./test-resources/hello.jar")
  (bbin "install org.babashka/http-server --mvn/version 0.1.11"))

;(comment
;  ;; local jar
;  {; parse
;   :cli-opts {:script/lib "./test-resources/hello.jar"}
;
;   ; analyze
;   :parse/source-path "./test-resources/hello.jar"
;
;   ; select
;   :analyze/scripts {"http-server" {}}
;   :select/user-input "http-server"
;
;   ; generate
;   :select/scripts {"http-server" {}}
;
;   ; write
;   :generate/generated [{:script-name "http-server"
;                         :script-config {:script-contents "maven-jar-script"}}]
;   :analyze/jar-path "~/.cache/babashka/bbin/jars/hello.jar"
;   :parse/bin-dir "~/.local/bin"}
;
;  ;; maven jar
;  {; parse
;   :cli-opts {:script/lib "org.babashka/http-server"
;              :mvn/version "0.1.11"}
;
;   ; load
;   :lib 'org.babashka/http-server
;   :coords {:mvn/version "0.1.11"}
;
;   ; analyze
;   :lib 'org.babashka/http-server
;   :coords {:mvn/version "0.1.11"}
;
;   ; select
;   :scripts {"http-server" {}}
;   :user-input "http-server"
;
;   ; generate
;   :scripts {"http-server" {}}
;   :selected #{"http-server"}
;
;   ; write
;   :generated [{:script-name "http-server"
;                :script-config {:script-contents "maven-jar-script"}}]
;   :bin-dir "~/.local/bin"})
