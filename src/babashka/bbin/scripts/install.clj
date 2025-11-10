(ns babashka.bbin.scripts.install
  (:require [babashka.bbin.deps :as bbin-deps]
            [babashka.bbin.util :as util]
            [babashka.deps :as deps]
            [babashka.bbin.dirs :as dirs]
            [clojure.tools.gitlibs :as gitlibs]
            [babashka.bbin.scripts.common :as common]
            [babashka.fs :as fs]
            [taoensso.timbre :as log]))

;; =============================================================================
;; Parse

(defn- parse-http [{:keys [cli-opts] :as _params}]
  (let [http-url (:script/lib cli-opts)
        script-deps {:bbin/url (:script/lib cli-opts)}]
    {:source-deps script-deps
     :source-url http-url}))

(defn- parse-local-dir [{:keys [cli-opts] :as _params}]
  (let [local-dir (fs/canonicalize (fs/file (:script/lib cli-opts))
                                   {:nofollow-links true})
        script-deps {:bbin/url (str "file://" local-dir)}]
    {:source-deps script-deps
     :source-dir local-dir}))

(defn- parse-local-file [{:keys [cli-opts] :as _params}]
  (let [local-dir (fs/canonicalize (fs/file (:script/lib cli-opts))
                                   {:nofollow-links true})
        script-deps {:bbin/url (str "file://" local-dir)}]
    {:source-deps script-deps
     :source-dir local-dir}))

(defn- parse-maven-jar [{:keys [cli-opts] :as _params}]
  (let [local-dir (fs/canonicalize (fs/file (:script/lib cli-opts))
                                   {:nofollow-links true})
        script-deps {:bbin/url (str "file://" local-dir)}]
    {:source-deps script-deps
     :source-dir local-dir}))

(defn parse
  "Parse source from the CLI options."
  [{:keys [cli-opts] :as params}]
  (let [{:keys [procurer artifact] :as summary} (bbin-deps/summary cli-opts)
        parsed (case [procurer artifact]
                 ([:http :file] [:http :jar]
                  [:local :file] [:local :jar])
                 (parse-http params)

                 [:local :file]
                 (parse-local-file params)

                 [:local :dir]
                 (parse-local-dir params)

                 [:maven :jar]
                 (parse-maven-jar params))
        header {:coords (:script-deps parsed)}]
    (merge params summary parsed {:header header})))

;; =============================================================================
;; Fetch

(defn- fetch-repo [{:keys [cli-opts source-deps] :as _params}]
  {:repo-path (gitlibs/procure (:git/url source-deps)
                               (:script/lib cli-opts)
                               (:git/sha source-deps))})

(defn- fetch-file [{:keys [source-file] :as _params}]
  {:source-contents (slurp source-file)})

(defn- fetch-deps [{:keys [source-deps] :as _params}]
  {:deps-result (deps/add-deps {:deps source-deps})})

(defn fetch
  "Fetch dependencies for the source."
  [{:keys [procurer artifact] :as params}]
  (case [procurer artifact]
    [:git :dir]
    (fetch-repo params)

    ([:http :file] [:http :jar]
     [:local :file] [:local :jar])
    (fetch-file params)

    [:maven :jar]
    (fetch-deps params)

    [:local :dir]
    nil))

;; =============================================================================
;; Analyze

(defn analyze-dir [params]
  (let [bin-config (common/load-bin-config (:source-dir params))]
    {:scripts bin-config}))

(defn analyze-file-jar [{:keys [source-contents] :as params}]
  (let [script-name 'foo]
    {:scripts {script-name {}}}))

(defn analyze-file-source [{:keys [source-contents] :as params}]
  (let [script-name 'foo]
    {:scripts {script-name {}}}))

(defn analyze-deps [{:keys [source-contents] :as params}]
  (let [script-name 'foo]
    {:scripts {script-name source-contents}}))

(defn analyze
  "Analyze source contents to find scripts."
  [{:keys [procurer artifact] :as params}]
  (cond
    (= artifact :dir)
    (analyze-dir params)

    (= artifact :file)
    (analyze-file-source params)

    (and (not= procurer :maven) (= artifact :jar))
    (analyze-file-jar params)

    (and (= procurer :maven) (= artifact :jar))
    (analyze-deps params)))

;; =============================================================================
;; Generate

(defn- generate-tool-script [params])

(defn- generate-dir-script [params]
  common/git-or-local-template-str-with-bb-edn)

(defn- generate-source-code-script [{:keys [source-code header]}]
  (common/insert-script-header source-code header))

(defn- generate-local-jar-script [params])

(defn- generate-maven-jar-script [params])

(defn generate
  "Generate the script contents."
  [{:keys [tool-mode procurer artifact] :as params}]
  (cond
    tool-mode
    {:script-contents (generate-tool-script params)}

    (= artifact :dir)
    {:script-contents (generate-dir-script params)}

    (= artifact :file)
    {:script-contents (generate-source-code-script params)}

    (and (= procurer :local) (= artifact :jar))
    {:script-contents (generate-local-jar-script params)}

    (and (= procurer :maven) (= artifact :jar))
    {:script-contents (generate-maven-jar-script params)}))

;; =============================================================================
;; Write

(defn write
  "Write the script files."
  [params]
  (common/install-script params))

;; =============================================================================
;; Report

(defn report
  "Report the results."
  [params]
  {:report {}})

;; =============================================================================
;; Install

(def steps
  [#'parse
   #'fetch
   #'analyze
   #'generate
   #_#'write
   #'report])

(defn install [cli-opts]
  (when-not (util/edn? cli-opts)
    (println)
    (println (util/bold "Starting install..." cli-opts))
    (println))
  (let [init {:cli-opts cli-opts}
        result (reduce (fn [params step-fn]
                         #_(log/debug {:step-fn step-fn, :params params})
                         (merge params (step-fn params)))
                       init
                       steps)]
    (prn result)
    (when-not (util/edn? cli-opts)
      (println)
      (println (util/bold "Install complete." cli-opts))
      (println))))
