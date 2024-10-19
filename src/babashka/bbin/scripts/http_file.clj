(ns babashka.bbin.scripts.http-file
  (:require [babashka.bbin.dirs :as dirs]
            [babashka.bbin.protocols :as p]
            [babashka.bbin.scripts.common :as common]
            [babashka.fs :as fs]))

(defrecord HttpFile [cli-opts coords]
  p/Script
  (install [_]
    (let [http-url (:script/lib cli-opts)
          script-deps {:bbin/url http-url}
          header {:coords script-deps}
          script-name (or (:as cli-opts) (common/http-url->script-name http-url))
          script-contents (-> (slurp (:bbin/url script-deps))
                              (common/insert-script-header header))
          script-file (fs/canonicalize (fs/file (dirs/bin-dir cli-opts) script-name)
                                       {:nofollow-links true})]
      (common/install-script script-name header script-file script-contents cli-opts)))

  (upgrade [_]
    (let [cli-opts' (merge (select-keys cli-opts [:edn])
                           {:script/lib (:bbin/url coords)})]
      (p/install (map->HttpFile {:cli-opts cli-opts'
                                 :coords coords}))))

  (uninstall [_]
    (common/delete-files cli-opts)))
