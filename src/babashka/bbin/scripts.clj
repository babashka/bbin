(ns babashka.bbin.scripts
  (:require
    [babashka.bbin.scripts.common :as common]
    [babashka.bbin.util :as util]
    [babashka.bbin.protocols :as p]
    [babashka.bbin.scripts.git-dir :refer [map->GitDir]]
    [babashka.bbin.scripts.local-file :refer [map->LocalFile]]
    [babashka.bbin.scripts.local-dir :refer [map->LocalDir]]
    [babashka.bbin.scripts.http-file :refer [map->HttpFile]]
    [babashka.bbin.scripts.http-jar :refer [map->HttpJar]]
    [babashka.bbin.scripts.local-jar :refer [map->LocalJar]]
    [babashka.bbin.scripts.maven-jar :refer [map->MavenJar]]
    [babashka.fs :as fs]
    [clojure.edn :as edn]
    [clojure.string :as str]
    [rads.deps-info.summary :as deps-info-summary]
    [selmer.filters :as filters]))

;; selmer filter for clojure escaping for e.g. files
(filters/add-filter! :pr-str (comp pr-str str))

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

(defn- throw-invalid-script [summary cli-opts]
  (let [{:keys [procurer artifact]} summary]
    (throw (ex-info "Invalid script coordinates.\nIf you're trying to install from the filesystem, make sure the path actually exists."
                    {:script/lib (:script/lib cli-opts)
                     :procurer procurer
                     :artifact artifact}))))

(defn- new-script [cli-opts]
  (let [summary (deps-info-summary/summary cli-opts)
        {:keys [procurer artifact]} summary]
    (case [procurer artifact]
      [:git :dir] (map->GitDir {:cli-opts cli-opts :summary summary})
      [:http :file] (map->HttpFile {:cli-opts cli-opts})
      [:http :jar] (map->HttpJar {:cli-opts cli-opts})
      [:local :dir] (map->LocalDir {:cli-opts cli-opts :summary summary})
      [:local :file] (map->LocalFile {:cli-opts cli-opts})
      [:local :jar] (map->LocalJar {:cli-opts cli-opts})
      [:maven :jar] (map->MavenJar {:cli-opts cli-opts})
      (throw-invalid-script summary cli-opts))))

(defn install [cli-opts]
  (if-not (:script/lib cli-opts)
    (util/print-help)
    (do
      (util/ensure-bbin-dirs cli-opts)
      (let [cli-opts' (util/canonicalized-cli-opts cli-opts)
            script (new-script cli-opts')]
        (p/install script)))))

(defn- load-script [cli-opts]
  ; TODO: Use correct type based on script metadata
  (reify
    p/Script
    (install [_])
    (uninstall [_]
      (common/delete-files cli-opts))))

(defn uninstall [cli-opts]
  (if-not (:script/lib cli-opts)
    (util/print-help)
    (do
      (util/ensure-bbin-dirs cli-opts)
      (let [script (load-script cli-opts)]
        (p/uninstall script)))))
