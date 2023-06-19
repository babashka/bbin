(ns babashka.bbin.scripts.local-file
  (:require [babashka.bbin.protocols :as p]
            [babashka.bbin.scripts.common :as common]
            [babashka.bbin.dirs :as dirs]
            [babashka.fs :as fs]))

(defrecord LocalFile [cli-opts coords]
  p/Script
  (install [_]
    (let [file-path (str (fs/canonicalize (:script/lib cli-opts) {:nofollow-links true}))
          script-deps {:bbin/url (str "file://" file-path)}
          header {:coords script-deps}
          script-name (or (:as cli-opts) (common/file-path->script-name file-path))
          script-contents (-> (slurp file-path)
                              (common/insert-script-header header))
          script-file (fs/canonicalize (fs/file (dirs/bin-dir cli-opts) script-name)
                                       {:nofollow-links true})]
      (common/install-script script-name header script-file script-contents cli-opts)))

  (upgrade [_]
    (p/install (map->LocalFile {:cli-opts {:script/lib (:bbin/url coords)}
                                :coords coords})))

  (uninstall [_]
    (common/delete-files cli-opts)))
