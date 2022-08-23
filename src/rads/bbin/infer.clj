(ns rads.bbin.infer
  (:require [rads.bbin.git :as git]
            [clojure.edn :as edn]
            [clojure.set :as set]))

(defn- github-repo-http-url [lib]
  (str "https://github.com/" (git/clean-github-lib lib)))

(def github-repo-ssh-regex #"^git@github.com:([^/]+)/([^\.]+)\.git$")
(def github-repo-http-regex #"^https://github.com/([^/]+)/([^\.]+)(\.git)?$")

(defn- parse-git-url [git-url]
  (let [[[_ gh-user repo-name]] (or (re-seq github-repo-ssh-regex git-url)
                                    (re-seq github-repo-http-regex git-url))]
    (if (and gh-user repo-name)
      {:gh-user gh-user :repo-name repo-name}
      (throw (ex-info "Failed to parse :git/url" {:git/url git-url})))))

(defn- git-url->lib-sym [git-url]
  (when-let [{:keys [gh-user repo-name]} (parse-git-url git-url)]
    (symbol (str "io.github." gh-user) repo-name)))

(def lib-opts->template-deps-fn
  "A map to define valid CLI options.

  - Each key is a sequence of valid combinations of CLI opts.
  - Each value is a function which returns a tools.deps lib map."
  {[#{:local/root}]
   (fn [lib-sym lib-opts]
     {lib-sym (select-keys lib-opts [:local/root])})

   [#{} #{:git/url}]
   (fn [lib-sym lib-opts]
     (let [url (or (:git/url lib-opts) (github-repo-http-url lib-sym))
           {:keys [name commit]} (git/latest-github-tag (git-url->lib-sym url))]
       {lib-sym {:git/url url :git/tag name :git/sha (:sha commit)}}))

   [#{:git/tag} #{:git/url :git/tag}]
   (fn [lib-sym lib-opts]
     (let [url (or (:git/url lib-opts) (github-repo-http-url lib-sym))
           tag (:git/tag lib-opts)
           {:keys [commit]} (git/find-github-tag (git-url->lib-sym url) tag)]
       {lib-sym {:git/url url :git/tag tag :git/sha (:sha commit)}}))

   [#{:git/sha} #{:git/url :git/sha}]
   (fn [lib-sym lib-opts]
     (let [url (or (:git/url lib-opts) (github-repo-http-url lib-sym))
           sha (:git/sha lib-opts)]
       {lib-sym {:git/url url :git/sha sha}}))

   [#{:latest-sha} #{:git/url :latest-sha}]
   (fn [lib-sym lib-opts]
     (let [url (or (:git/url lib-opts) (github-repo-http-url lib-sym))
           sha (git/latest-github-sha (git-url->lib-sym url))]
       {lib-sym {:git/url url :git/sha sha}}))

   [#{:git/url :git/tag :git/sha}]
   (fn [lib-sym lib-opts]
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
  (ex-info (str "Provided invalid combination of CLI options")
           {:provided-opts (set (keys provided-lib-opts))
            :valid-combinations valid-lib-opts}))

(defn resolve-deps
  "Returns a tools.deps lib map for the given CLI opts."
  [template cli-opts]
  (let [lib-opts (cli-opts->lib-opts cli-opts)
        lib-sym (edn/read-string template)
        template-deps-fn (find-template-deps-fn lib-opts)]
    (if-not template-deps-fn
      (throw (invalid-lib-opts-error lib-opts))
      (template-deps-fn lib-sym lib-opts))))
