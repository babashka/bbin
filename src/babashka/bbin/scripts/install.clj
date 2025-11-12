(ns babashka.bbin.scripts.install
  (:require [babashka.bbin.deps :as bbin-deps]
            [babashka.bbin.dirs :as dirs]
            [babashka.bbin.scripts.common :as common]
            [babashka.bbin.util :as util]
            [babashka.fs :as fs]
            [babashka.http-client :as http]
            [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [clojure.string :as str]
            [clojure.tools.gitlibs :as gitlibs]
            [selmer.parser :as selmer]
            [selmer.util :as selmer-util]))

;; =============================================================================
;; Parse

(defn- parse-deps [{:keys [cli-opts] :as _params}]
  (let [source-deps (bbin-deps/infer cli-opts)]
    {:source-deps source-deps}))

(defn- parse-http [{:keys [cli-opts] :as _params}]
  (let [source-deps {:bbin/url (:script/lib cli-opts)}
        source-url (:script/lib cli-opts)]
    {:source-deps source-deps
     :source-url source-url}))

(defn- parse-local [{:keys [cli-opts] :as _params}]
  (let [source-path (-> (fs/path (:script/lib cli-opts))
                        (fs/canonicalize {:nofollow-links true})
                        str)
        source-deps {:bbin/url (str "file://" source-path)}]
    {:source-deps source-deps
     :source-path source-path}))

(defn parse
  "Parse source from the CLI options."
  [{:keys [cli-opts] :as params}]
  (let [{:keys [tool-mode]} cli-opts
        {:keys [procurer artifact]} (bbin-deps/summary cli-opts)
        parse-fn (case [procurer artifact]
                   ([:http :file] [:http :jar])
                   #'parse-http

                   ([:local :file] [:local :jar] [:local :dir])
                   #'parse-local

                   ([:git :dir] [:maven :jar])
                   #'parse-deps)
        parsed (parse-fn params)
        header {:coords (:source-deps parsed)}]
    (merge params
           parsed
           {:parse-fn parse-fn
            :procurer procurer
            :artifact artifact
            :header header
            :tool-mode tool-mode})))

;; =============================================================================
;; Cache

(defn- cache-repo [{:keys [cli-opts source-deps] :as _params}]
  {:cached-path (gitlibs/procure (:git/url source-deps)
                                 (:script/lib cli-opts)
                                 (:git/sha source-deps))})

(defn- cache-http-file [{:keys [source-path tmp-dir] :as _params}]
  (let [cached-path (str (fs/path tmp-dir (fs/file-name source-path)))]
    (io/copy (:body (http/get source-path {:as :bytes})) (fs/file cached-path))
    {:cached-path cached-path}))

(defn- cache-local-file [{:keys [source-path tmp-dir] :as _params}]
  (let [cached-path (str (fs/path tmp-dir (fs/file-name source-path)))]
    (io/copy (fs/file source-path) (fs/file cached-path))
    {:cached-path cached-path}))

(defn- cache-deps [{:keys [source-deps] :as _params}]
  {:deps-result (bbin-deps/add-deps {:deps source-deps})})

(defn cache
  "Cache dependencies for the script."
  [{:keys [procurer artifact] :as params}]
  (let [cache-fn (case [procurer artifact]
                   [:git :dir]
                   #'cache-repo

                   ([:http :file] [:http :jar])
                   #'cache-http-file

                   ([:local :file] [:local :jar])
                   #'cache-local-file

                   [:local :dir]
                   ::no-cache

                   [:maven :jar]
                   #'cache-deps)]

    (merge {:cache-fn cache-fn} (cache-fn params))))

;; =============================================================================
;; Analyze

(defn- analyze-dir [params]
  (let [project-path (or (:cached-path params) (:source-path params))
        bin-config (common/load-bin-config project-path)]
    {:project-path project-path
     :scripts bin-config}))

(defn- analyze-file [{:keys [source-path] :as _params}]
  (let [script-name (-> (fs/file-name source-path)
                        (str/replace #"\.(clj|cljc|bb|jar)$" "")
                        symbol)]
    {:scripts {script-name {}}}))

(defn- analyze-deps [{:keys [source-contents] :as params}]
  (let [script-name 'foo]
    {:scripts {script-name {}}}))

(defn- analyze
  "Analyze source contents to find scripts."
  [{:keys [procurer artifact] :as params}]
  (let [analyze-fn (cond
                     (= artifact :dir)
                     #'analyze-dir

                     (or (= artifact :file)
                         (and (not= procurer :maven) (= artifact :jar)))
                     #'analyze-file

                     (and (= procurer :maven) (= artifact :jar))
                     #'analyze-deps)]
    (merge {:analyze-fn analyze-fn} (analyze-fn params))))

;; =============================================================================
;; Select

(defn select
  "Select scripts to install."
  [{:keys [scripts] :as _params}]
  {:selected (set (keys scripts))})

;; =============================================================================
;; Generate

(defn- script-meta [{:keys [source-deps] :as _params}]
  (str/join "\n"
            (->> (binding [*print-namespace-maps* false]
                   (with-out-str (pprint/pprint source-deps)))
                 str/split-lines
                 (map #(str common/comment-char " " %)))))

(defn- generate-dir-script [{:keys [source-path source-deps main-opts] :as params}]
  (let [template-opts {:script/meta (script-meta params)
                       :script/root source-path
                       :script/lib (pr-str (key (first source-deps)))
                       :script/coords (binding [*print-namespace-maps* false]
                                        (pr-str (val (first source-deps))))}
        main-opts (common/process-main-opts main-opts source-path)
        template-opts' (assoc template-opts :script/main-opts main-opts)
        script-contents (selmer-util/without-escaping
                          (selmer/render common/git-or-local-template-str-with-bb-edn
                                         template-opts'))]
    {:script-contents script-contents}))

(defn- generate-source-code-script [{:keys [header cached-path]}]
  (let [source-contents (slurp cached-path)]
    {:script-contents (common/insert-script-header source-contents header)}))

(defn- generate-local-jar-script [{:keys [source-path source-deps] :as params}]
  (let [main-ns (common/jar->main-ns source-path)
        template-opts {:script/meta (script-meta params)
                       :script/jar source-path
                       :script/main-ns main-ns}
        script-contents (selmer-util/without-escaping
                          (selmer/render common/local-jar-template-str
                                         template-opts))]
    {:script-contents script-contents}))

(defn- generate-maven-jar-script [params]
  {:script-contents common/maven-template-str})

(defn- generate-single [{:keys [procurer artifact] :as params}]
  (let [generate-fn (cond
                      (= artifact :dir)
                      #'generate-dir-script

                      (= artifact :file)
                      #'generate-source-code-script

                      (and (= procurer :local) (= artifact :jar))
                      #'generate-local-jar-script

                      (and (= procurer :maven) (= artifact :jar))
                      #'generate-maven-jar-script)]
    (merge {:generate-fn generate-fn} (generate-fn params))))

(defn generate
  "Generate the script contents."
  [{:keys [selected scripts] :as params}]
  (let [generated (->> (select-keys scripts selected)
                       (map (fn [[script-name script-config]]
                              [script-name (generate-single
                                             (merge params
                                                    script-config
                                                    {:script-name script-name}))]))
                       (into {}))]
    {:generated generated}))

;; =============================================================================
;; Write

(defn- write-single [{:keys [cli-opts script-name script-config]}]
  (let [script-path (str (fs/path (dirs/bin-dir cli-opts) (str script-name)))]
    ;(fs/copy file-path cached-jar-path {:replace-existing true})
    (spit script-path (:script-contents script-config))
    (when-not util/windows?
      (util/sh ["chmod" "+x" script-path]))
    [script-name script-path]))

(defn write
  "Write the script files."
  [{:keys [generated cli-opts] :as _params}]
  (let [written (doall
                  (->> generated
                       (map (fn [[script-name script-config]]
                              (write-single {:cli-opts cli-opts
                                             :script-name script-name
                                             :script-config script-config})))
                       (into {})))]
    {:written written}))

;; =============================================================================
;; Install

(def install-steps
  [#'parse
   #'cache
   #'analyze
   #'select
   #'generate
   #'write])

(defn- install-start [{:keys [cli-opts] :as params}]
  (when-not (util/edn? cli-opts)
    (println)
    (println (util/bold "Starting install..." cli-opts))
    (println))
  params)

(defn- install-run [params]
  (let [params' (reduce (fn [params step-fn]
                          (let [ret (step-fn params)]
                            (tap> {:_step-fn step-fn, :params params, :ret ret})
                            (merge params ret)))
                        params
                        install-steps)
        {:keys [cli-opts header]} params']
    (when (util/edn? cli-opts)
      (util/pprint header))
    params'))

(defn- install-end [{:keys [cli-opts] :as params}]
  (when-not (util/edn? cli-opts)
    (println)
    (println (util/bold "Install complete." cli-opts))
    (println))
  params)

(defn install [cli-opts]
  (fs/with-temp-dir [tmp-dir {}]
    (-> {:cli-opts cli-opts, :tmp-dir tmp-dir}
        install-start
        install-run
        install-end)))

(comment
  (do
    (require '[clojure.string :as str]
             '[babashka.bbin.cli :as cli])

    (defn bbin [command]
      (apply cli/-main (str/split command #" "))))

  (bbin "install ./test-resources/install-sources/test-dir --as btest")
  (bbin "install ./test-resources/install-sources/bbin-test-script-3.clj")
  (bbin "install ./test-resources/install-sources/test-dir2"))
