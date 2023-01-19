(ns babashka.bbin.specs
  (:require [clojure.spec.alpha :as s]))

(s/def ::ns-default symbol?)
(s/def ::main-opts (s/coll-of string?))
(s/def ::script-config (s/keys :opt-un [::main-opts ::ns-default]))
(s/def :bbin/bin (s/map-of symbol? ::script-config))

(defn valid? [spec form]
  (s/valid? spec form))

(defn explain-str [spec form]
  ((requiring-resolve 'expound.alpha/expound-str) spec form))
