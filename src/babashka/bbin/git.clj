(ns babashka.bbin.git
  (:require [clojure.string :as str]
            [babashka.fs :as fs]
            [babashka.process :refer [sh]]))

(defn- ensure-git-dir [client git-url]
  (binding [*err* (java.io.StringWriter.)]
    (let [path ((:ensure-git-dir client) git-url)]
      ((:git-fetch client) (fs/file path))
      path)))

(defn default-branch [client git-url]
  (let [lib-dir (ensure-git-dir client git-url)
        remote-info (sh "git remote show origin" {:dir lib-dir
                                                  :extra-env {"LC_ALL" "C"}})
        [[_ branch]] (->> (:out remote-info)
                          str/split-lines
                          (some #(re-seq #"HEAD branch: (\w+)" %)))]
    branch))

(defn latest-git-sha [client git-url]
  (let [lib-dir (ensure-git-dir client git-url)
        branch (default-branch client git-url)
        log-result (sh ["git" "log" "-n" "1" branch "--pretty=format:%H"]
                       {:dir lib-dir})]
    (str/trim-newline (:out log-result))))

(defn find-git-tag [client git-url tag]
  (let [lib-dir (ensure-git-dir client git-url)
        log-result (sh ["git" "log" "-n" "1" tag "--pretty=format:%H"]
                       {:dir lib-dir})
        sha (str/trim-newline (:out log-result))]
    {:name (str tag)
     :commit {:sha sha}}))

(defn latest-git-tag [client git-url]
  (let [lib-dir (ensure-git-dir client git-url)
        describe-result (sh "git describe --tags --abbrev=0" {:dir lib-dir})
        tag (str/trim-newline (:out describe-result))]
    (when-not (str/blank? tag)
      (find-git-tag client git-url tag))))

(def providers
  {#"^(com|io)\.github\." :github
   #"^(com|io)\.gitlab\." :gitlab
   #"^(org|io)\.bitbucket\." :bitbucket
   #"^(com|io)\.beanstalkapp\." :beanstalk
   #"^ht\.sr\." :sourcehut})

(defn- clean-lib-str [lib]
  (->> (reduce #(str/replace %1 %2 "") lib (keys providers))
       symbol))

(defn git-http-url [lib]
  (let [provider (some #(when (re-seq (key %) (str lib)) %) providers)
        s (clean-lib-str (str lib))]
    (case (val provider)
      :github (str "https://github.com/" s ".git")
      :gitlab (str "https://gitlab.com/" s ".git")
      :bitbucket (let [[u] (str/split (str s) #"/")]
                   (str "https://" u "@bitbucket.org/" s ".git"))
      :beanstalk (let [[u] (str/split (str s) #"/")]
                   (str "https://" u ".git.beanstalkapp.com/" (name lib) ".git"))
      :sourcehut (str "https://git.sr.ht/~" s))))

(defn git-ssh-url [lib]
  (let [provider (some #(when (re-seq (key %) (str lib)) %) providers)
        s (clean-lib-str (str lib))]
    (case (val provider)
      :github (str "git@github.com:" s ".git")
      :gitlab (str "git@gitlab.com:" s ".git")
      :bitbucket (str "git@bitbucket.org:" s ".git")
      :beanstalk (let [[u] (str/split (str s) #"/")]
                   (str "git@" u ".git.beanstalkapp.com:/" s ".git"))
      :sourcehut (str "git@git.sr.ht:~" s))))

(defn git-repo-url [client lib]
  (try
    (let [url (git-http-url lib)]
      (ensure-git-dir client url)
      url)
    (catch Exception e
      (if (re-seq #"^Unable to clone " (ex-message e))
        (let [url (git-ssh-url lib)]
          (ensure-git-dir client url)
          url)
        (throw e)))))
