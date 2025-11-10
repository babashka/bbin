(ns babashka.bbin.scripts
  (:require
   [babashka.bbin.dirs :as dirs]
   [babashka.bbin.protocols :as p]
   [babashka.bbin.scripts.common :as common]
   [babashka.bbin.scripts.install :as install]
   [babashka.bbin.util :as util]
   [babashka.fs :as fs]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str]
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

(defn- read-header [filename]
  (or (with-open [input-stream (io/input-stream filename)]
        (let [buffer (byte-array (* 1024 5))
              n (.read input-stream buffer)]
          (when (nat-int? n)
            (String. buffer 0 n))))
      ""))

(defn load-scripts [dir]
  (->> (file-seq dir)
       (filter #(.isFile %))
       (map (fn [x] [(symbol (str (fs/relativize dir x)))
                     (-> (read-header x) (parse-script))]))
       (filter second)
       (into {})))

(defn ls [cli-opts]
  (let [scripts (load-scripts (dirs/bin-dir cli-opts))]
    (if (:edn cli-opts)
      (util/pprint scripts cli-opts)
      (do
        (println)
        (util/print-scripts (util/printable-scripts scripts) cli-opts)
        (println)))))

(defn bin [cli-opts]
  (println (str (dirs/bin-dir cli-opts))))

(defn install [cli-opts]
  (if-not (:script/lib cli-opts)
    (util/print-help)
    (do
      (dirs/ensure-bbin-dirs cli-opts)
      (let [cli-opts' (util/canonicalized-cli-opts cli-opts)]
        (install/install cli-opts')))))

(defn upgrade [cli-opts]
  (if-not (:script/lib cli-opts)
    (util/print-help)
    (do
      (dirs/ensure-bbin-dirs cli-opts)
      #_(let [script (load-script cli-opts)]
          (p/upgrade script)))))

(defn uninstall [cli-opts]
  (if-not (:script/lib cli-opts)
    (util/print-help)
    (do
      (dirs/ensure-bbin-dirs cli-opts)
      (let [script-name (:script/lib cli-opts)
            script-file (fs/canonicalize (fs/file (dirs/bin-dir cli-opts) script-name) {:nofollow-links true})]
        (when (fs/delete-if-exists script-file)
          (when util/windows? (fs/delete-if-exists (fs/file (str script-file common/windows-wrapper-extension))))
          (fs/delete-if-exists (fs/file (dirs/jars-dir cli-opts) (str script-name ".jar")))
          (println "Removing" (str script-file)))))))
