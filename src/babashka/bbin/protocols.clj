(ns babashka.bbin.protocols)

(defprotocol Script
  (install [script])
  (uninstall [script]))
