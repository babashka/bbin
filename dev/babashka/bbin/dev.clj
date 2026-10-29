(ns babashka.bbin.dev
  (:require [babashka.fs :as fs]
            [babashka.nrepl.server :as nrepl]
            [babashka.process :refer [sh]]
            [clojure.core.async :refer [<!] :as async]
            [clojure.string :as str]
            [pod.babashka.fswatcher :as fw]
            [taoensso.timbre :as log]))

(def watch-paths ["deps.edn" "bb.edn" "src" "dev"])

(defn- build-event? [{:keys [type path] :as _watch-event}]
  (and (not (#{:chmod} type))
       (not (str/ends-with? path "~"))))

(def build-number (atom 0))

(defn build-once [event]
  (let [i (swap! build-number inc)]
    (log/info [:start-build i event])
    (sh "bb gen-script" {:err :inherit})
    (log/info [:end-build i event])))

(defn- start-builder [build-events]
  (async/go-loop []
    (let [event (<! build-events)]
      (build-once event)
      (recur))))

(defn- start-watchers [watch-paths build-events]
  (doseq [p watch-paths]
    (fw/watch p #(async/put! build-events %) {:recursive true})))

(defn- start-nrepl []
  (let [{:keys [port]} (nrepl/start-server! {:port 0})]
    (spit ".nrepl-port" port)
    (fs/delete-on-exit ".nrepl-port")))

(defn dev []
  (let [build-xf (filter build-event?)
        build-events (async/chan (async/sliding-buffer 1) build-xf)]
    (log/info [:start-dev])
    (start-watchers watch-paths build-events)
    (build-once {:type ::startup-build})
    (start-builder build-events)
    (start-nrepl)
    (deref (promise))))
