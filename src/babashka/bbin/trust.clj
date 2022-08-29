(ns babashka.bbin.trust
  (:require [clojure.string :as str]
            [babashka.fs :as fs]
            [babashka.bbin.util :as util :refer [sh]]))

(def base-allow-list
  {'babashka {}
   'borkdude {}
   'rads {}})

(defn- owner-info [file]
  (let [parts (-> (:out (sh ["ls" "-l" (str file)]))
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
  (let [user (if *sudo* "root" (str/trim (:out (sh ["id" "-un"]))))
        group (str/trim (:out (sh ["id" "-gn" user])))]
    {:user user :group group}))

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
             (str/starts-with? url (str "https://gist.githubusercontent.com/" % "/"))
             (str/starts-with? url (str "https://raw.githubusercontent.com/" % "/")))
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
    (sh (sudo ["chown" "-R" (str user ":" group) (str trust-dir)]))))

(defn- valid-path? [path]
  (or (fs/starts-with? path (fs/expand-home "~/.bbin/trust"))
      (fs/starts-with? path (fs/temp-dir))))

(defn- assert-valid-write [path]
  (when-not (valid-path? path)
    (throw (ex-info "Invalid write path" {:invalid-path path}))))

(defn- write-trust-file [{:keys [path contents] :as _plan}]
  (assert-valid-write path)
  (sh (sudo ["tee" path]) {:in (prn-str contents)}))

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
    (sh (sudo ["rm" (str path)]))))

(defn revoke [cli-opts]
  (if-not (:github/user cli-opts)
    (util/print-help)
    (let [{:keys [path]} (trust-file cli-opts)]
      (println "Removing" (str path))
      (rm-trust-file path)
      nil)))

(defn- throw-lib-name-not-trusted [cli-opts]
  (let [msg (str "Lib name is not trusted.\nTo install this lib, provide "
                 "a --git/sha option or use `bbin trust` to allow inference "
                 "for this lib name.")]
    (throw (ex-info msg {:untrusted-lib (:script/lib cli-opts)}))))

(defn assert-trusted-lib [cli-opts]
  (when-not (allowed-lib? (:script/lib cli-opts) cli-opts)
    (throw-lib-name-not-trusted cli-opts)))

(def ^:private bbin-lib-str?
  #{"io.github.babashka/bbin" "com.github.babashka/bbin"})

(defn- bbin-git-url? [coords]
  (or (re-seq #"^https://github.com/babashka/bbin(\.git)?$" (:git/url coords))
      (= "git@github.com:babashka/bbin.git" (:git/url coords))))

(defn- bbin-http-url? [coords]
  (str/starts-with? (:bbin/url coords) "https://raw.githubusercontent.com/babashka/bbin/"))

(defn- valid-bbin-lib? [{:keys [lib coords] :as _header}]
  (or (and (bbin-lib-str? lib)
           (or (= #{:git/tag :git/sha} (set (keys coords)))
               (and (:git/url coords) (bbin-git-url? coords))))
      (and (= #{:bbin/url} (set (keys coords)))
           (bbin-http-url? coords))))

(defn- valid-script-name? [script-name header]
  (or (not= script-name "bbin") (valid-bbin-lib? header)))

(defn- throw-invalid-script-name [script-name header]
  (throw (ex-info (str "Invalid script name.\nThe `bbin` name is reserved for "
                       "installing `bbin` from the official repo.\nUse `--as` "
                       "to choose a different name.")
                  (merge {:script/name script-name} header))))

(defn assert-valid-script-name [script-name header]
  (when-not (valid-script-name? script-name header)
    (throw-invalid-script-name script-name header)))
