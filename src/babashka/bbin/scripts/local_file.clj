(ns babashka.bbin.scripts.local-file
  (:require [babashka.bbin.protocols :as p]
            [babashka.bbin.scripts.common :as common]
            [babashka.bbin.util :as util]
            [babashka.fs :as fs]))

(defrecord LocalFile [cli-opts]
  p/Script
  (install [_]
    (let [file-path (str (fs/canonicalize (:script/lib cli-opts) {:nofollow-links true}))
          script-deps {:bbin/url (str "file://" file-path)}
          header {:coords script-deps}
          _ (util/pprint header cli-opts)
          script-name (or (:as cli-opts) (common/file-path->script-name file-path))
          script-contents (-> (slurp file-path)
                              (common/insert-script-header header))
          script-file (fs/canonicalize (fs/file (util/bin-dir cli-opts) script-name)
                                       {:nofollow-links true})]
      (common/install-script script-file script-contents (:dry-run cli-opts))))

  (uninstall [_]
    (common/delete-files cli-opts)))
