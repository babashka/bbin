(ns babashka.bbin.scripts.local-dir
  (:require [babashka.bbin.protocols :as p]
            [babashka.bbin.scripts.common :as common]))

(defrecord LocalDir [cli-opts summary]
  p/Script
  (install [_]
    (common/install-deps-git-or-local cli-opts summary))

  (uninstall [_]
    (common/delete-files cli-opts)))
