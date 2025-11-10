(ns babashka.bbin.test-util
  (:require [babashka.bbin.cli :as bbin]
            [babashka.bbin.dirs :as dirs]
            [babashka.bbin.scripts :as scripts]
            [babashka.bbin.util :as util]
            [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :as test]
            [org.httpkit.server :as http]
            [ring.util.mime-type :as mime-type]
            [taoensso.timbre :as log])
  (:import (java.net ServerSocket)))

(defmethod test/report :begin-test-var [m]
  (println "===" (-> m :var meta :name))
  (println))

(defn reset-test-dir []
  (let [path (str (fs/file (fs/temp-dir) "bbin-test"))]
    (fs/delete-tree path)
    (fs/create-dirs path)
    (fs/create-dirs (fs/file path "public"))
    (doto path (fs/delete-on-exit))))

(defn- random-available-port []
  (with-open [socket (ServerSocket. 0)]
    (.getLocalPort socket)))

(def test-dir (reset-test-dir))
(def http-public-dir (fs/file test-dir "public"))
(def http-port (random-available-port))

(defn- relativize [original]
  (fs/file test-dir (fs/relativize (dirs/user-home) original)))

(defn bbin-dirs-fixture []
  (fn [f]
    (binding [dirs/*legacy-bin-dir* (relativize (dirs/legacy-bin-dir))
              dirs/*legacy-jars-dir* (relativize (dirs/legacy-jars-dir))
              dirs/*xdg-bin-dir* (relativize (dirs/xdg-bin-dir nil))
              dirs/*xdg-jars-dir* (relativize (dirs/xdg-jars-dir nil))]
      (f))))

(def git-wrapper-path
  (-> (if (fs/windows?)
        "test-resources/git-wrapper.bat"
        "test-resources/git-wrapper")
      fs/file
      fs/canonicalize
      str))

(defn- set-gitlibs-command []
  (System/setProperty "clojure.gitlibs.command" git-wrapper-path))

(defn bbin-private-keys-fixture []
  (fn [f]
    (set-gitlibs-command)
    (f)))

(defn bbin [main-args & {:as opts}]
  (let [out (str/trim (with-out-str (bbin/bbin main-args opts)))]
    (if (#{:edn} (:out opts))
      (edn/read-string out)
      out)))

(defn run-install [cli-opts]
  (let [s (with-out-str (scripts/install (assoc cli-opts :edn true)))]
    (log/debug s)
    (edn/read-string s)))

(defn run-upgrade [cli-opts]
  (some-> (with-out-str (scripts/upgrade (assoc cli-opts :edn true)))
          edn/read-string))

(defn run-ls []
  (some-> (with-out-str (scripts/ls {:edn true}))
          edn/read-string))

(defn exec-cmd-line [script-name]
  (concat (when util/windows? ["cmd" "/c"])
          [(str (fs/canonicalize (fs/file (dirs/bin-dir nil) (name script-name)) {:nofollow-links true}))]))

(defn run-bin-script [script-name & script-args]
  (let [args (concat (exec-cmd-line script-name) script-args)
        {:keys [out]} (p/sh args {:err :inherit})]
    (str/trim out)))

(defn- static-file-handler [{:keys [uri] :as _req} public-dir]
  (let [file (io/file public-dir (subs uri 1))]
    (if (fs/exists? file)
      {:status 200
       :headers {"Content-Type" (mime-type/ext-mime-type (str file))}
       :body file}
      {:status 404
       :body "Not Found"})))

(defn- start-http-server []
  (http/run-server #(static-file-handler % http-public-dir)
                   {:port http-port}))

(defn http-server-fixture []
  (fn [f]
    (let [server (start-http-server)]
      (try
        (f)
        (finally
          (server))))))
