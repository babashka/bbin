(ns babashka.bbin.scripts.maven-jar
  (:require [babashka.bbin.protocols :as p]
            [babashka.bbin.scripts.common :as common]
            [babashka.bbin.util :as util]
            [babashka.deps :as deps]
            [babashka.json :as json]
            [babashka.http-client :as http]
            [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [selmer.parser :as selmer]
            [selmer.util :as selmer-util]))

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

(defn- first-stable-version [versions]
  (let [vparse (requiring-resolve 'version-clj.core/parse)]
    (some (fn [version]
            (let [{:keys [qualifiers]} (vparse version)]
              (when-not
                  ;; assume all qualifiers indicate non-stable version
                  (some #{"rc" "alpha" "beta" "snapshot" "milestone"} qualifiers)
                version)))
          versions)))

(defn- search-mvn [qlib limit]
  (let [url (format "https://search.maven.org/solrsearch/select?q=g:%s+AND+a:%s&rows=%s&core=gav&wt=json"
                    (namespace qlib)
                    (name qlib)
                    (str limit))]
    (json/read-str (:body (http/get url)))))

(defn- mvn-versions [qlib {:keys [limit] :or {limit 10}}]
  (let [payload (search-mvn qlib limit)]
    (->> payload
         :docs
         (map :v))))

(defn- latest-stable-mvn-version [qlib]
  (first-stable-version (mvn-versions qlib {:limit 100})))

(defn- get-clojars-artifact [qlib]
  (let [url (format "https://clojars.org/api/artifacts/%s"
                    qlib)]
    (json/read-str (:body (http/get url)))))

(defn- clojars-versions [qlib {:keys [limit] :or {limit 10}}]
  (let [body (get-clojars-artifact qlib)]
    (->> body
         :recent_versions
         (map :version)
         (take limit))))

(defn- latest-stable-clojars-version
  [qlib]
  (first-stable-version (clojars-versions qlib {:limit 100})))

(defrecord MavenJar [cli-opts lib]
  p/Script
  (install [_]
    (let [script-deps {(edn/read-string (:script/lib cli-opts))
                       (select-keys cli-opts [:mvn/version])}
          header {:lib (key (first script-deps))
                  :coords (val (first script-deps))}
          _ (util/pprint header cli-opts)
          _ (deps/add-deps {:deps script-deps})
          script-root (fs/canonicalize (or (:local/root cli-opts) (common/local-lib-path script-deps)) {:nofollow-links true})
          script-name (or (:as cli-opts) (second (str/split (:script/lib cli-opts) #"/")))
          script-config (common/default-script-config cli-opts)
          script-edn-out (with-out-str
                           (binding [*print-namespace-maps* false]
                             (util/pprint header)))
          main-opts (or (some-> (:main-opts cli-opts) edn/read-string)
                        (:main-opts script-config))
          template-opts {:script/meta (->> script-edn-out
                                           str/split-lines
                                           (map #(str common/comment-char " " %))
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
      (common/install-script script-file template-out (:dry-run cli-opts))))

  (upgrade [_]
    (let [latest-version (or (latest-stable-clojars-version lib)
                             (latest-stable-mvn-version lib))]
      (p/install (map->MavenJar {:cli-opts {:script/lib (str lib)
                                            :mvn/version latest-version}
                                 :lib lib}))))

  (uninstall [_]
    (common/delete-files cli-opts)))
