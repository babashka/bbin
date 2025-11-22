(ns babashka.bbin.scripts.install
  (:require [babashka.bbin.deps :as deps]
            [babashka.bbin.dirs :as dirs]
            [babashka.bbin.scripts.common :as common]
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
            [selmer.util :as selmer-util]))

;; =============================================================================
;; Parse

(defn- parse-deps-maven? [{:keys [cli-opts] :as _params}]
  (:mvn/version cli-opts))

(defn- parse-deps-git-https-url? [{:keys [cli-opts] :as _params}]
  (str/starts-with? (:script/lib cli-opts) "https://"))

(defn- parse-deps [{:keys [cli-opts] :as params}]
  (cond
    (parse-deps-maven? params)
    {:source-deps (select-keys cli-opts [:mvn/version])
     :lib (edn/read-string (:script/lib cli-opts))}

    (parse-deps-git-https-url? params)
    (let [git-url (cond-> (:script/lib cli-opts)
                    (not (str/ends-with? (:script/lib cli-opts) ".git"))
                    (str ".git"))
          lib (symbol "bbin" (Integer/toHexString (hash git-url)))
          inferred (deps/infer (-> cli-opts
                                (assoc :lib (str lib))
                                (assoc :git/url git-url)))
          source-deps (get inferred lib)]
      {:source-deps source-deps
       :lib lib})

    :else
    (let [git-url (:script/lib cli-opts)
          lib (edn/read-string (:script/lib cli-opts))
          inferred (deps/infer (assoc cli-opts :lib git-url))
          source-deps (get inferred lib)]
      {:source-deps source-deps
       :lib lib})))

(defn- parse-http [{:keys [cli-opts] :as _params}]
  {:source-deps {:bbin/url (:script/lib cli-opts)}
   :source-url (:script/lib cli-opts)})

(defn- parse-local [{:keys [cli-opts] :as _params}]
  (let [source-path (-> (fs/path (:script/lib cli-opts))
                        (fs/canonicalize {:nofollow-links true})
                        str)
        source-deps {:bbin/url (str "file://" source-path)}]
    {:source-deps source-deps
     :source-path source-path}))

(defn parse
  "Parse source from the CLI options."
  [{:keys [cli-opts config] :as params}]
  (let [{:keys [tool-mode]} cli-opts
        {:keys [procurer artifact]} (deps/summary cli-opts)
        parse-fn (get-in config [[procurer artifact] :parse])
        parsed (parse-fn params)
        header (-> parsed
                   (set/rename-keys {:source-deps :coords})
                   (select-keys [:lib :coords]))]
    (merge parsed
           {:procurer procurer
            :artifact artifact
            :header header
            :tool-mode tool-mode})))

;; =============================================================================
;; Cache

(defn- load-repo [{:keys [source-deps lib] :as _params}]
  {:cached-path (gitlibs/procure (:git/url source-deps)
                                 lib
                                 (:git/sha source-deps))})

(defn- load-http-file [{:keys [source-url tmp-dir] :as _params}]
  (let [cached-path (str (fs/path tmp-dir (fs/file-name source-url)))]
    (io/copy (:body (http/get source-url {:as :bytes})) (fs/file cached-path))
    {:cached-path cached-path}))

(defn- load-local-file [{:keys [source-path tmp-dir] :as _params}]
  (let [cached-path (str (fs/path tmp-dir (fs/file-name source-path)))]
    (io/copy (fs/file source-path) (fs/file cached-path))
    {:cached-path cached-path}))

(defn- load-deps [{:keys [source-deps cli-opts] :as _params}]
  (let [lib (or (some-> (:script/lib cli-opts) edn/read-string)
                'local/root)]
    {:deps-result (deps/add-libs {lib source-deps})}))

(defn load
  "Cache dependencies for the script."
  [{:keys [procurer artifact config] :as params}]
  (let [load-fn (get-in config [[procurer artifact] :cache])]
    (when load-fn (load-fn params))))

;; =============================================================================
;; Analyze

(defn- analyze-dir [params]
  (let [bin-config (common/load-bin-config (or (:cached-path params)
                                               (:source-path params)))]
    {:scripts bin-config}))

(defn- analyze-file [{:keys [cached-path artifact cli-opts] :as _params}]
  (let [script-name (-> (fs/file-name cached-path)
                        (str/replace #"\.(clj|cljc|bb|jar)$" "")
                        symbol)]
   (cond-> {:scripts {script-name {}}}
     (= artifact :jar)
     (assoc :jar-path (fs/file (dirs/jars-dir cli-opts)
                               (str script-name ".jar"))))))

(defn- analyze-deps [{:keys [cli-opts] :as _params}]
  (let [script-name (or (:as cli-opts)
                        (second (str/split (:script/lib cli-opts) #"/")))]
    {:scripts {script-name (common/default-script-config cli-opts)}}))

(defn- analyze
  "Analyze source contents to find scripts."
  [{:keys [procurer artifact config] :as params}]
  (let [analyze-fn (get-in config [[procurer artifact] :analyze])]
    (analyze-fn params)))

;; =============================================================================
;; Select

(defn select
  "Select scripts to install."
  [{:keys [scripts] :as _params}]
  {:selected (set (keys scripts))})

;; =============================================================================
;; Generate

(defn- script-meta [{:keys [header] :as _params}]
  (str/join "\n"
            (->> (binding [*print-namespace-maps* false]
                   (with-out-str (pprint/pprint header)))
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

(defn- generate-local-jar-script [{:keys [cached-path jar-path] :as params}]
  (let [main-ns (common/jar->main-ns cached-path)
        template-opts {:script/meta (script-meta params)
                       :script/jar jar-path
                       :script/main-ns main-ns}
        script-contents (selmer-util/without-escaping
                          (selmer/render common/local-jar-template-str
                                         template-opts))]
    {:script-contents script-contents}))

(defn- generate-maven-jar-script
  [{:keys [cli-opts cached-path script-config header] :as params}]
  (let [main-opts (-> (or (some-> (:main-opts cli-opts) edn/read-string)
                          (:main-opts script-config))
                      (common/process-main-opts cached-path))
        template-opts {:script/meta (script-meta params)
                       :script/coords (:coords header)
                       :script/lib (:lib header)
                       :script/main-opts main-opts}
        script-contents (selmer-util/without-escaping
                          (selmer/render common/maven-template-str
                                         template-opts))]
    {:script-contents script-contents}))

(defn- generate-single [{:keys [procurer artifact config] :as params}]
  (let [generate-fn (get-in config [[procurer artifact] :generate])]
    (generate-fn params)))

(defn generate
  "Generate the script contents."
  [{:keys [selected scripts] :as params}]
  (let [generated (->> (select-keys scripts selected)
                       (map (fn [[script-name script-config]]
                              [script-name
                               (generate-single
                                 (merge params
                                        {:script-config script-config
                                         :script-name script-name}))]))
                       (into {}))]
    {:generated generated}))

;; =============================================================================
;; Write

(defn- write-single
  [{:keys [cached-path script-name script-config bin-dir jar-path jars-dir]}]
  (let [script-path (str (fs/path (dirs/bin-dir cli-opts) (str script-name)))]
    (when jar-path
      (fs/create-dirs (dirs/jars-dir cli-opts))
      (fs/copy cached-path jar-path {:replace-existing true}))
    (spit script-path (:script-contents script-config))
    (when-not util/windows?
      (util/sh ["chmod" "+x" script-path]))
    [script-name script-path]))

(defn write
  "Write the script files."
  [{:keys [generated] :as params}]
  (let [written (doall
                  (->> generated
                       (map (fn [[script-name script-config]]
                              (write-single
                                (merge params
                                       {:script-name script-name
                                        :script-config script-config}))))
                       (into {})))]
    {:written written}))

;; =============================================================================
;; Install

(def default-config
  {[:git :dir]
   {:parse #'parse-deps
    :cache #'load-repo
    :analyze #'analyze-dir
    :generate #'generate-dir-script}

   [:http :file]
   {:parse #'parse-http
    :cache #'load-http-file
    :analyze #'analyze-file
    :generate #'generate-source-code-script}

   [:http :jar]
   {:parse #'parse-http
    :cache #'load-http-file
    :analyze #'analyze-file
    :generate #'generate-local-jar-script}

   [:local :dir]
   {:parse #'parse-local
    :analyze #'analyze-file
    :generate #'generate-dir-script}

   [:local :file]
   {:parse #'parse-local
    :cache #'load-local-file
    :analyze #'analyze-file
    :generate #'generate-source-code-script}

   [:local :jar]
   {:parse #'parse-local
    :cache #'load-local-file
    :analyze #'analyze-file
    :generate #'generate-local-jar-script}

   [:maven :jar]
   {:parse #'parse-deps
    :cache #'load-deps
    :analyze #'analyze-deps
    :generate #'generate-maven-jar-script}})

(def install-steps
  [#'parse
   #'load
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
                          ;(clojure.pprint/pprint params)
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
    (let [params {:cli-opts cli-opts
                  :tmp-dir tmp-dir
                  :config default-config}]
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
