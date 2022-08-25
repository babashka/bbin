(ns rads.bbin.trust
  (:require [clojure.string :as str]
            [babashka.fs :as fs]
            [rads.bbin.util :as util])
  (:import (java.util Date)))

(def base-allow-list
  {'babashka {}
   'borkdude {}
   'rads {}})

(defn user-allow-list [cli-opts]
  (->> (file-seq (util/trust-dir cli-opts))
       (filter #(.isFile %))
       (map (fn [file]
              [(symbol (str/replace (fs/file-name file) #"^github-user-(.+)\.edn$" "$1"))
               {}]))
       (into {})))

(defn combined-allow-list [cli-opts]
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

(defn github-trust-file [opts]
  {:path (str (fs/path (util/trust-dir opts) (str "github-user-" (:github/user opts) ".edn")))})

(defn trust-file [opts]
  (cond
    (:github/user opts) (github-trust-file opts)
    :else (throw (ex-info "Invalid CLI opts" {:cli-opts opts}))))

(defn trust [opts]
  (-> (trust-file opts)
      (assoc :contents {:trusted-at (Date.)})))

(defn revoke [opts]
  (trust-file opts))
