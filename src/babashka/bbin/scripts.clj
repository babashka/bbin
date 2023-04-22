(ns babashka.bbin.scripts
  (:require
    [babashka.bbin.scripts.common :as common]
    [babashka.bbin.util :as util]
    [babashka.bbin.dirs :as dirs]
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

(defn parse-script [s]
  (let [lines (str/split-lines s)
        prefix (if (str/ends-with? (first lines) "bb") ";" "#")]
    (->> lines
         (drop-while #(not (re-seq (re-pattern (str "^" prefix " *:bbin/start")) %)))
         next
         (take-while #(not (re-seq (re-pattern (str "^" prefix " *:bbin/end")) %)))
         (map #(str/replace % (re-pattern (str "^" prefix " *")) ""))
         (str/join "\n")
         edn/read-string)))

(defn load-scripts [dir]
  (->> (file-seq dir)
       (filter #(.isFile %))
       (map (fn [x] [(symbol (str (fs/relativize dir x)))
                     (parse-script (slurp x))]))
       (filter second)
       (into {})))

(defn printable-scripts [scripts]
  (map (fn [[bin {{lroot :local/root
                   gtag  :git/tag
                   gsha  :git/sha
                   gurl  :git/url
                   burl  :bbin/url} :coords}]]
         (cond-> (assoc {} :bin bin)
           gurl  (assoc :location gurl)
           burl  (assoc :location burl)
           lroot (assoc :location (str "file://" lroot))
           gsha  (assoc :version gsha)
           gtag  (assoc :version gtag)))
       scripts))

(defn print-scripts [printable-scripts {:as _cli-opts :keys [no-color plain]}]
  (let [tty?              (util/is-tty 1 :out)
        plain-mode?       (or plain (not tty?))
        skip-header?      plain-mode?
        no-color?         (or no-color plain-mode?
                              (System/getenv "NO_COLOR") (= "dumb" (System/getenv "TERM")))
        column-atts       '(:bin :version :location)
        column-coercions  {:version #(if (or plain-mode? (not= 40 (count %)))
                                       %
                                       (subs % 0 7))}
        max-width         (when-not plain-mode?
                            (:cols (util/terminal-dimensions)))
        location-truncate #(-> %1
                               (str/replace #"^(file|https?):\/\/" "")
                               (util/truncate {:truncate-to       %2
                                               :omission          "…"
                                               :omission-position :center}))]
    (util/print-table column-atts (sort-by :bin printable-scripts) {:skip-header         skip-header?
                                                                    :max-width           max-width
                                                                    :width-reduce-column :location
                                                                    :width-reduce-fn     location-truncate
                                                                    :column-coercions    column-coercions
                                                                    :no-color            no-color?})))

(defn ls [cli-opts]
  (let [scripts (load-scripts (dirs/bin-dir cli-opts))]
    (if (util/pretty-output-enabled?)
      (if (:edn cli-opts)
        (util/pprint scripts cli-opts)
        (print-scripts (printable-scripts scripts) cli-opts))
      (util/pprint scripts cli-opts))))

(defn bin [cli-opts]
  (println (str (dirs/bin-dir cli-opts))))

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
      (dirs/ensure-bbin-dirs cli-opts)
      (let [cli-opts' (util/canonicalized-cli-opts cli-opts)
            script (new-script cli-opts')]
        (p/install script)))))

(defn- default-script [cli-opts]
  (reify
    p/Script
    (install [_])
    (upgrade [_]
      (throw (ex-info "Not implemented" {})))
    (uninstall [_]
      (common/delete-files cli-opts))))

(defn- load-script [cli-opts]
  (let [script-name (:script/lib cli-opts)
        script-file (fs/file (fs/canonicalize (fs/file (dirs/bin-dir cli-opts) script-name) {:nofollow-links true}))
        parsed (parse-script (slurp script-file))]
    (cond
      (-> parsed :coords :bbin/url)
      (let [summary (deps-info-summary/summary {:script/lib (-> parsed :coords :bbin/url)})
            {:keys [procurer artifact]} summary]
        (case [procurer artifact]
          [:git :dir] (map->GitDir {:cli-opts cli-opts :summary summary :coords (:coords parsed)})
          [:http :file] (map->HttpFile {:cli-opts cli-opts :coords (:coords parsed)})
          [:http :jar] (map->HttpJar {:cli-opts cli-opts :coords (:coords parsed)})
          [:local :dir] (map->LocalDir {:cli-opts cli-opts :summary summary})
          [:local :file] (map->LocalFile {:cli-opts cli-opts})
          [:local :jar] (map->LocalJar {:cli-opts cli-opts})
          (throw-invalid-script summary cli-opts)))

      (-> parsed :coords :mvn/version)
      (map->MavenJar {:cli-opts cli-opts :lib (:lib parsed)})

      (-> parsed :coords :git/tag)
      (let [summary (deps-info-summary/summary {:script/lib (:lib parsed)
                                                :git/tag (-> parsed :coords :git/tag)})]
        (map->GitDir {:cli-opts cli-opts :summary summary :coords (:coords parsed)}))

      (-> parsed :coords :git/sha)
      (let [summary (deps-info-summary/summary {:script/lib (:lib parsed)
                                                :git/sha (-> parsed :coords :git/sha)})]
        (map->GitDir {:cli-opts cli-opts :summary summary :coords (:coords parsed)}))

      :else (default-script cli-opts))))

(defn upgrade [cli-opts]
  (if-not (:script/lib cli-opts)
    (util/print-help)
    (do
      (dirs/ensure-bbin-dirs cli-opts)
      (let [script (load-script cli-opts)]
        (p/upgrade script)))))

(defn uninstall [cli-opts]
  (if-not (:script/lib cli-opts)
    (util/print-help)
    (do
      (dirs/ensure-bbin-dirs cli-opts)
      (let [script (load-script cli-opts)]
        (p/uninstall script)))))
