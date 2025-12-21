(ns babashka.bbin.deps
  (:require [babashka.bbin.git :as git]
            [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.tools.gitlibs.impl :as gitlibs-impl]
            #?@(:bb [babashka.deps]
                :clj [clojure.repl.deps])))

#_:clj-kondo/ignore
(defn add-libs
  [lib-coords]
  #?(:bb ((resolve 'babashka.deps/add-deps) {:deps lib-coords})
     :clj ((resolve 'clojure.repl.deps/add-libs) lib-coords)))

(def lib-opts->template-deps-fn
  "A map to define valid CLI options.

  - Each key is a sequence of valid combinations of CLI opts.
  - Each value is a function which returns a tools.deps lib map."
  {[#{:local/root}]
   (fn [_ lib-sym lib-opts]
     {lib-sym (select-keys lib-opts [:local/root])})

   [#{} #{:git/url}]
   (fn [client lib-sym lib-opts]
     (let [url (or (:git/url lib-opts) (git/git-repo-url client lib-sym))
           tag (git/latest-git-tag client url)]
       (if tag
         {lib-sym {:git/url url
                   :git/tag (:name tag)
                   :git/sha (-> tag :commit :sha)}}
         (let [sha (git/latest-git-sha client url)]
           {lib-sym {:git/url url
                     :git/sha sha}}))))

   [#{:git/tag} #{:git/url :git/tag}]
   (fn [client lib-sym lib-opts]
     (let [url (or (:git/url lib-opts) (git/git-repo-url client lib-sym))
           tag (:git/tag lib-opts)
           {:keys [commit]} (git/find-git-tag client url tag)]
       {lib-sym {:git/url url
                 :git/tag tag
                 :git/sha (:sha commit)}}))

   [#{:git/sha} #{:git/url :git/sha}]
   (fn [client lib-sym lib-opts]
     (let [url (or (:git/url lib-opts) (git/git-repo-url client lib-sym))
           sha (:git/sha lib-opts)]
       {lib-sym {:git/url url
                 :git/sha sha}}))

   [#{:latest-sha} #{:git/url :latest-sha}]
   (fn [client lib-sym lib-opts]
     (let [url (or (:git/url lib-opts) (git/git-repo-url client lib-sym))
           sha (git/latest-git-sha client url)]
       {lib-sym {:git/url url
                 :git/sha sha}}))

   [#{:git/url :git/tag :git/sha}]
   (fn [_ lib-sym lib-opts]
     {lib-sym (select-keys lib-opts [:git/url :git/tag :git/sha])})})

(def valid-lib-opts
  "The set of all valid combinations of CLI opts."
  (into #{} cat (keys lib-opts->template-deps-fn)))

(defn- cli-opts->lib-opts
  "Returns parsed lib opts from raw CLI opts."
  [cli-opts]
  (-> cli-opts
      (set/rename-keys {:sha :git/sha})
      (select-keys (into #{} cat valid-lib-opts))))

(defn- find-template-deps-fn
  "Returns a template-deps-fn given lib-opts parsed from raw CLI opts."
  [lib-opts]
  (some (fn [[k v]] (and (contains? (set k) (set (keys lib-opts))) v))
        lib-opts->template-deps-fn))

(defn- invalid-lib-opts-error [provided-lib-opts]
  (ex-info "Provided invalid combination of CLI options"
           {:provided-opts (set (keys provided-lib-opts))
            :valid-combinations valid-lib-opts}))

(def ^:private default-deps-info-client
  {:ensure-git-dir gitlibs-impl/ensure-git-dir
   :git-fetch gitlibs-impl/git-fetch})

(defn infer
  "Returns a tools.deps lib map for the given CLI opts."
  ([cli-opts] (infer nil cli-opts))
  ([client cli-opts]
   (let [client (merge default-deps-info-client client)
         lib-opts (cli-opts->lib-opts cli-opts)
         lib-sym (edn/read-string (:lib cli-opts))
         template-deps-fn (find-template-deps-fn lib-opts)]
     (if-not template-deps-fn
       (throw (invalid-lib-opts-error lib-opts))
       (template-deps-fn client lib-sym lib-opts)))))

(def ^:private symbol-regex
  (re-pattern
   (str "(?i)^"
        "(?:((?:[a-z0-9-]+\\.)*[a-z0-9-]+)/)?"
        "((?:[a-z0-9-]+\\.)*[a-z0-9-]+)"
        "$")))

(defn- lib-str? [x]
  (boolean (and (string? x) (re-seq symbol-regex x))))

(defn- local-script-path? [x]
  (boolean (and (string? x) (or (fs/exists? x)
                                (fs/exists? (str/replace x #"^file://" ""))))))

(defn- http-url? [x]
  (boolean (and (string? x) (re-seq #"^https?://" x))))

(defn- git-ssh-url? [x]
  (boolean (and (string? x) (re-seq #"^.+@.+:.+\.git$" x))))

(defn- git-http-url? [x]
  (boolean (and (string? x)
                (or (re-seq #"^https?://.+\.git$" x)
                    (re-seq #"^https?://github.com/[^/]+/[^/]+$" x)))))

(defn git-repo-url? [s]
  (or (git-http-url? s) (git-ssh-url? s)))

(def ^:private deps-types
  [{:lib lib-str?
    :coords #{:local/root}
    :procurer :local}

   {:lib lib-str?
    :coords #{:mvn/version}
    :procurer :maven}

   {:lib local-script-path?
    :coords #{:bbin/url}
    :procurer :local}

   {:lib #(or (git-http-url? %) (git-ssh-url? %))
    :coords #{:bbin/url}
    :procurer :git}

   {:lib http-url?
    :coords #{:bbin/url}
    :procurer :http}

   {:lib lib-str?
    :coords #{:git/sha :git/url :git/tag}
    :procurer :git}

   {:lib local-script-path?
    :coords #{}
    :procurer :local}

   {:lib #(or (git-http-url? %) (git-ssh-url? %) (lib-str? %))
    :coords #{}
    :procurer :git}

   {:lib http-url?
    :coords #{}
    :procurer :http}])

(defn- deps-type-match? [cli-opts deps-type]
  (and ((:lib deps-type) (:script/lib cli-opts))
       (or (empty? (:coords deps-type))
           (seq (set/intersection (:coords deps-type) (set (keys cli-opts)))))
       deps-type))

(defn- match-deps-type [cli-opts]
  (or (some #(deps-type-match? cli-opts %) deps-types)
      {:procurer :unknown-procurer}))

(defn directory? [x]
  (fs/directory? (str/replace x #"^file://" "")))

(defn regular-file? [x]
  (fs/regular-file? (str/replace x #"^file://" "")))

(defn- match-artifact [cli-opts procurer]
  (cond
    (or (#{:maven} procurer)
        (and (#{:local} procurer)
             (or (and (:script/lib cli-opts) (re-seq #"\.jar$" (:script/lib cli-opts)))
                 (and (:local/root cli-opts) (re-seq #"\.jar$" (:local/root cli-opts)))))
        (and (#{:http} procurer) (re-seq #"\.jar$" (:script/lib cli-opts))))
    :jar

    (or (#{:git} procurer)
        (and (#{:local} procurer)
             (or (and (:script/lib cli-opts)
                      (directory? (:script/lib cli-opts)))
                 (and (:local/root cli-opts) (directory? (:local/root cli-opts)))))
        (and (#{:http} procurer) (re-seq #"\.git$" (:script/lib cli-opts))))
    :dir

    (or (and (#{:local} procurer)
             (:script/lib cli-opts)
             (regular-file? (:script/lib cli-opts)))
        (and (#{:http} procurer) (re-seq #"\.(cljc?|bb)$" (:script/lib cli-opts))))
    :file

    :else :unknown-artifact))

(defn summary [cli-opts]
  (let [{:keys [procurer]} (match-deps-type cli-opts)
        artifact (match-artifact cli-opts procurer)]
    {:procurer procurer
     :artifact artifact}))
