(ns babashka.bbin.scripts.git-dir
  (:require [babashka.bbin.protocols :as p]
            [babashka.bbin.scripts.common :as common]))

(defrecord GitDir [cli-opts summary coords]
  p/Script
  (install [_]
    (common/install-deps-git-or-local cli-opts summary))

  (upgrade [_]
    (throw (ex-info "Not implemented" {:cli-opts cli-opts
                                       :summary summary
                                       :coords coords})))

  (uninstall [_]
    (common/delete-files cli-opts)))
