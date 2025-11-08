(ns babashka.bbin.scripts.install
  (:require [babashka.bbin.deps :as bbin-deps]
            [babashka.deps :as deps]
            [babashka.bbin.dirs :as dirs]
            [clojure.tools.gitlibs :as gitlibs]
            [babashka.bbin.scripts.common :as common]
            [babashka.fs :as fs]))

(defn parse
  "Parse info from the CLI options."
  [{:keys [cli-opts] :as _params}]
  (let [http-url (:script/lib cli-opts)
        summary (bbin-deps/summary cli-opts)
        {:keys [procurer artifact]} summary
        script-deps (case [procurer artifact]
                      [:http :file] {:bbin/url http-url}
                      [:git :dir] (bbin-deps/infer cli-opts))
        script-name (or (:as cli-opts) (common/http-url->script-name http-url))
        script-file (fs/canonicalize (fs/file (dirs/bin-dir cli-opts)
                                              script-name)
                                     {:nofollow-links true})
        header {:coords script-deps}]
    (merge summary
           {:script-deps script-deps
            :script-file script-file
            :script-name script-name
            :header header})))

(defn- clone-repo [{:keys [cli-opts script-deps] :as _params}]
  {:repo-path (gitlibs/procure (:git/url script-deps)
                               (:script/lib cli-opts)
                               (:git/sha script-deps))})

(defn- read-file [{:keys [cli-opts] :as _params}]
  {:source-file (slurp (:script/lib cli-opts))})

(defn- cache-deps [{:keys [script-deps] :as _params}]
  {:deps-result (deps/add-deps {:deps script-deps})})

(defn fetch
  "Fetch dependencies for the script."
  [{:keys [summary] :as params}]
  (let [{:keys [procurer artifact]} summary]
    (case [procurer artifact]
      [:git :dir]
      (clone-repo params)

      ([:http :file] [:http :jar]
       [:local :file] [:local :jar])
      (read-file params)

      [:maven :jar]
      (cache-deps params))))

(defn- tool-script [params])

(defn- dir-script [params])

(defn- source-code-script [{:keys [source-code header]}]
  (common/insert-script-header source-code header))

(defn- local-jar-script [params])

(defn- maven-jar-script [params])

(defn generate
  "Generate the script contents."
  [{:keys [tool-mode summary] :as params}]
  (let [{:keys [procurer artifact]} summary]
    (cond
      tool-mode
      {:script-contents (tool-script params)}

      (= artifact :dir)
      {:script-contents (dir-script params)}

      (= artifact :file)
      {:script-contents (source-code-script params)}

      (and (= procurer :local) (= artifact :jar))
      {:script-contents (local-jar-script params)}

      (and (= procurer :maven) (= artifact :jar))
      {:script-contents (maven-jar-script params)})))

(defn write
  "Write the script files."
  [params]
  (common/install-script params))

(def steps [#'parse #'fetch #'generate #'write])

(defn install [cli-opts]
  (let [init {:cli-opts cli-opts}]
    (reduce (fn [params step-fn]
              (merge params (step-fn params)))
            init
            steps)))
