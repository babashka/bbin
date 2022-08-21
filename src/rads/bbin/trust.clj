(ns rads.bbin.trust
  (:require [clojure.string :as str]))

(def allow-list
  {'babashka {}
   'rads {}})

(defn allowed-url? [url]
  (some #(or (str/starts-with? url (str "https://github.com/" % "/"))
             (str/starts-with? url (str "https://gist.githubusercontent.com/" % "/")))
        (keys allow-list)))
