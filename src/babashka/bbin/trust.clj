(ns babashka.bbin.trust
  (:require [clojure.string :as str]
            [babashka.fs :as fs]
            [babashka.process :refer [sh]]
            [babashka.bbin.util :as util]))

(def base-allow-list
  {'babashka {}
   'borkdude {}
   'rads {}})

(defn- owner-info [file]
  (let [parts (-> (:out (sh ["ls" "-l" (str file)]
                            {:err :inherit}))
                  str/split-lines
                  first
                  (str/split #" +"))
        [_ _ user group] parts]
    {:user user
     :group group}))

(def ^:dynamic *sudo* true)

(defn- sudo [cmd]
  (if *sudo* (into ["sudo"] cmd) cmd))

(defn- trust-owner []
  (let [group (str/trim (:out (sh ["id" "-gn" "root"]) {:err :inherit}))]
    {:user "root" :group group}))

(defn- valid-owner? [file trust-owner]
  (= trust-owner (owner-info file)))

(defn- user-allow-list [cli-opts]
  (let [owner (trust-owner)]
    (->> (file-seq (util/trust-dir cli-opts))
         (filter #(and (.isFile %) (valid-owner? % owner)))
         (map (fn [file]
                [(symbol (str/replace (fs/file-name file) #"^github-user-(.+)\.edn$" "$1"))
                 {}]))
         (into {}))))

(defn- combined-allow-list [cli-opts]
  (merge base-allow-list (user-allow-list cli-opts)))

(defn allowed-url? [url cli-opts]
  (some #(or (str/starts-with? url (str "https://github.com/" % "/"))
             (str/starts-with? url (str "https://gist.githubusercontent.com/" % "/")))
        (keys (combined-allow-list cli-opts))))

(defn allowed-lib? [lib cli-opts]
  (or (:git/sha cli-opts)
      (:local/root cli-opts)
      (some #(or (str/starts-with? lib (str "com.github." %))
                 (str/starts-with? lib (str "io.github." %)))
            (keys (combined-allow-list cli-opts)))))

(defn- github-trust-file [opts]
  {:path (str (fs/path (util/trust-dir opts) (str "github-user-" (:github/user opts) ".edn")))})

(defn- trust-file [opts]
  (cond
    (:github/user opts) (github-trust-file opts)
    :else (throw (ex-info "Invalid CLI opts" {:cli-opts opts}))))

(defn- ensure-trust-dir [cli-opts owner]
  (let [trust-dir (util/trust-dir cli-opts)
        {:keys [user group]} owner]
    (fs/create-dirs trust-dir)
    (sh (sudo ["chown" "-R" (str user ":" group) (str trust-dir)])
        {:err :inherit})))

(defn- valid-path? [path]
  (or (fs/starts-with? path (fs/expand-home "~/.bbin/trust"))
      (fs/starts-with? path (fs/temp-dir))))

(defn- assert-valid-write [path]
  (when-not (valid-path? path)
    (throw (ex-info "Invalid write path" {:invalid-path path}))))

(defn- write-trust-file [{:keys [path contents] :as _plan}]
  (assert-valid-write path)
  (sh (sudo ["tee" path]) {:in (prn-str contents) :err :inherit}))

(defn trust
  [cli-opts
   & {:keys [trusted-at]
      :or {trusted-at (util/now)}}]
  (if-not (:github/user cli-opts)
    (util/print-help)
    (let [owner (trust-owner)
          _ (ensure-trust-dir cli-opts owner)
          plan (-> (trust-file cli-opts)
                   (assoc :contents {:trusted-at trusted-at}))]
      (util/pprint plan cli-opts)
      (write-trust-file plan)
      nil)))

(defn- rm-trust-file [path]
  (assert-valid-write path)
  (when (fs/exists? path)
    (sh (sudo ["rm" (str path)]) {:err :inherit})))

(defn revoke [cli-opts]
  (if-not (:github/user cli-opts)
    (util/print-help)
    (let [{:keys [path]} (trust-file cli-opts)]
      (println "Removing" (str path))
      (rm-trust-file path)
      nil)))
