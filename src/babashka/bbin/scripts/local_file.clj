(ns babashka.bbin.scripts.local-file
  (:require [babashka.bbin.dirs :as dirs]
            [babashka.bbin.protocols :as p]
            [babashka.bbin.scripts.common :as common]
            [babashka.fs :as fs]
            [clojure.string :as str]))

(defrecord LocalFile [cli-opts coords]
  p/Script
  (install [_]
    (let [file-path (str (fs/unixify (fs/canonicalize (fs/unixify (:script/lib cli-opts)) {:nofollow-links true})))
          script-deps {:bbin/url (str "file://" file-path)}
          header {:coords script-deps}
          script-name (or (:as cli-opts) (common/file-path->script-name file-path))
          script-contents (-> (slurp file-path)
                              (common/insert-script-header header))
          script-file (fs/canonicalize (fs/file (dirs/bin-dir cli-opts) script-name)
                                       {:nofollow-links true})]
      (common/install-script script-name header script-file script-contents cli-opts)))

  (upgrade [_]
    (let [cli-opts' (merge (select-keys cli-opts [:edn])
                           {:script/lib (str/replace (:bbin/url coords) #"^file://" "")})]
      (p/install (map->LocalFile {:cli-opts cli-opts'
                                  :coords coords}))))

  (uninstall [_]
    (common/delete-files cli-opts)))
