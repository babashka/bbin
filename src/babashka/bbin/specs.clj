(ns babashka.bbin.specs
  (:require [clojure.spec.alpha :as s]))

(s/def ::ns-default symbol?)
(s/def ::main-opts (s/coll-of string?))
(s/def ::script-config (s/keys :opt-un [::main-opts ::ns-default]))
(s/def :bbin/bin (s/map-of symbol? ::script-config))
