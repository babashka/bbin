(ns babashka.bbin.specs
  (:require [clojure.spec.alpha :as s]))

(s/def ::ns-default symbol?)
(s/def ::main-opts (s/coll-of string?))
(s/def ::script-config (s/keys :opt-un [::main-opts ::ns-default]))
(s/def ::bin (s/map-of symbol? ::script-config))
(s/def :bbin/bin ::bin)
(s/def ::bbin (s/keys :opt-un [::bin]))
