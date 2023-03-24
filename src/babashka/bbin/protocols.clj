(ns babashka.bbin.protocols)

(defprotocol Script
  (install [script])
  (upgrade [script])
  (uninstall [script]))
