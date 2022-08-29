(ns babashka.bbin.trust
  (:require [clojure.string :as str]
            [babashka.fs :as fs]
            [babashka.process :as process]
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

(defn- sudo-timed-out? []
  (when *sudo*
    (not (zero? (:exit (doto (process/sh ["sudo" "-n" "true"])))))))

(defn check-sudo-timeout []
  (when (sudo-timed-out?)
    (println (format "sudo is required to modify files in ~/.bbin/trust, asking for password"))))

(defn trust
  [cli-opts
   & {:keys [trusted-at]
      :or {trusted-at (util/now)}}]
  (if-not (:github/user cli-opts)
    (util/print-help)
    (let [owner (trust-owner)
          _ (check-sudo-timeout)
          _ (ensure-trust-dir cli-opts owner)
          plan (-> (trust-file cli-opts)
                   (assoc :contents {:trusted-at trusted-at}))]
      (util/pprint plan cli-opts)
      (write-trust-file plan)
      nil)))

(defn- rm-trust-file [path]
  (assert-valid-write path)
  (sh (sudo ["rm" (str path)])))

(defn revoke [cli-opts]
  (if-not (:github/user cli-opts)
    (util/print-help)
    (let [{:keys [path]} (trust-file cli-opts)]
      (if-not (fs/exists? path)
        (println (str "Trust file does not exist:\n  " path))
        (do
          (check-sudo-timeout)
          (println (str "Removing trust file:\n  " path))
          (rm-trust-file path)))
      nil)))

(defn- throw-lib-name-not-trusted [cli-opts]
  (let [msg (str "Lib name is not trusted.\nTo install this lib, provide "
                 "a --git/sha option or use `bbin trust` to allow inference "
                 "for this lib name.")]
    (throw (ex-info msg {:untrusted-lib (:script/lib cli-opts)}))))

(defn assert-trusted-lib [cli-opts]
  (when-not (allowed-lib? (:script/lib cli-opts) cli-opts)
    (throw-lib-name-not-trusted cli-opts)))

(defn- bbin-lib-str? [x]
  #{"io.github.babashka/bbin" "com.github.babashka/bbin"} (str x))

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

(defn- throw-invalid-bbin-script-name [script-name header]
  (throw (ex-info (str "Invalid script name.\nThe `bbin` name is reserved for "
                       "installing `bbin` from the official repo.\nUse `--as` "
                       "to choose a different name.")
                  (merge {:script/name script-name} header))))

(declare reserved-script-names)

(defn- throw-reserved-script-name [script-name]
  (let [msg (format
              (str "Invalid script name.\nThe name `%s` cannot be used because "
                   "it may conflict with a system command.\nUse `--as` "
                   "to choose a different name.")
              script-name)]
    (throw (ex-info msg {:script/name script-name}))))

(defn assert-valid-script-name [script-name header]
  (when (contains? reserved-script-names script-name)
    (throw-reserved-script-name script-name))
  (when (and (#{"bbin"} script-name) (not (valid-bbin-lib? header)))
    (throw-invalid-bbin-script-name script-name header)))

(def reserved-script-names
  #{"alias"
    "autoload"
    "bg"
    "bind"
    "bindkey"
    "break"
    "builtin"
    "bye"
    "case"
    "cd"
    "chdir"
    "command"
    "compadd"
    "comparguments"
    "compcall"
    "compctl"
    "compdescribe"
    "compfiles"
    "compgroups"
    "complete"
    "compquote"
    "compset"
    "comptags"
    "comptry"
    "compvalues"
    "continue"
    "declare"
    "dirs"
    "disable"
    "disown"
    "echo"
    "echotc"
    "echoti"
    "emulate"
    "enable"
    "eval"
    "exec"
    "exit"
    "export"
    "false"
    "fc"
    "fg"
    "float"
    "for"
    "function"
    "functions"
    "getln"
    "getopts"
    "hash"
    "help"
    "history"
    "integer"
    "jobs"
    "kill"
    "let"
    "limit"
    "local"
    "logout"
    "ls"
    "man"
    "noglob"
    "popd"
    "print"
    "printf"
    "private"
    "pushd"
    "pushln"
    "pwd"
    "r"
    "rm"
    "read"
    "readonly"
    "rehash"
    "return"
    "sched"
    "select"
    "set"
    "setopt"
    "shift"
    "source"
    "sudo"
    "su"
    "suspend"
    "test"
    "times"
    "trap"
    "true"
    "ttyctl"
    "type"
    "typeset"
    "ulimit"
    "umask"
    "unalias"
    "unfunction"
    "unhash"
    "unlimit"
    "unset"
    "unsetopt"
    "vared"
    "variables"
    "wait"
    "whence"
    "where"
    "which"
    "zcompile"
    "zformat"
    "zle"
    "zmodload"
    "zparseopts"
    "zregexparse"
    "zstyle"})
