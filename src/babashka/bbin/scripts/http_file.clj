(ns babashka.bbin.scripts.http-file
  (:require [babashka.bbin.protocols :as p]
            [babashka.bbin.dirs :as dirs]
            [babashka.bbin.util :as util]
            [babashka.bbin.scripts.common :as common]
            [babashka.fs :as fs]))

(defrecord HttpFile [cli-opts coords]
  p/Script
  (install [_]
    (let [http-url (:script/lib cli-opts)
          script-deps {:bbin/url http-url}
          header {:coords script-deps}
          _ (util/pprint header cli-opts)
          script-name (or (:as cli-opts) (common/http-url->script-name http-url))
          script-contents (-> (slurp (:bbin/url script-deps))
                              (common/insert-script-header header))
          script-file (fs/canonicalize (fs/file (dirs/bin-dir cli-opts) script-name)
                                       {:nofollow-links true})]
      (common/install-script script-file script-contents (:dry-run cli-opts))))

  (upgrade [_]
    (p/install (map->HttpFile {:cli-opts {:script/lib (:bbin/url coords)}
                               :coords coords})))

  (uninstall [_]
    (common/delete-files cli-opts)))
