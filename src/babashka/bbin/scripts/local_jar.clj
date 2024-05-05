(ns babashka.bbin.scripts.local-jar
  (:require [babashka.bbin.dirs :as dirs]
            [babashka.bbin.protocols :as p]
            [babashka.bbin.scripts.common :as common]
            [babashka.bbin.util :as util]
            [babashka.fs :as fs]
            [clojure.string :as str]
            [selmer.parser :as selmer]
            [selmer.util :as selmer-util]))

(def ^:private local-jar-template-str
  (str/trim "
#!/usr/bin/env bb

; :bbin/start
;
{{script/meta}}
;
; :bbin/end

(require '[babashka.classpath :refer [add-classpath]])

(def script-jar {{script/jar|pr-str}})

(add-classpath script-jar)

(require '[{{script/main-ns}}])
(apply {{script/main-ns}}/-main *command-line-args*)
"))

(defrecord LocalJar [cli-opts coords]
  p/Script
  (install [_]
    (fs/create-dirs (dirs/jars-dir cli-opts))
    (let [file-path (str (fs/canonicalize (:script/lib cli-opts) {:nofollow-links true}))
          main-ns (common/jar->main-ns file-path)
          script-deps {:bbin/url (str "file://" file-path)}
          header {:coords script-deps}
          script-name (or (:as cli-opts) (common/file-path->script-name file-path))
          cached-jar-path (fs/file (dirs/jars-dir cli-opts) (str script-name ".jar"))
          script-edn-out (with-out-str
                           (binding [*print-namespace-maps* false]
                             (util/pprint header)))
          template-opts {:script/meta (->> script-edn-out
                                           str/split-lines
                                           (map #(str common/comment-char " " %))
                                           (str/join "\n"))
                         :script/main-ns main-ns
                         :script/jar cached-jar-path}
          script-contents (selmer-util/without-escaping
                           (selmer/render local-jar-template-str template-opts))
          script-file (fs/canonicalize (fs/file (dirs/bin-dir cli-opts) script-name)
                                       {:nofollow-links true})]
      (fs/copy file-path cached-jar-path {:replace-existing true})
      (common/install-script script-name header script-file script-contents cli-opts)))

  (upgrade [_]
    (let [cli-opts' (merge (select-keys cli-opts [:edn])
                           {:script/lib (str/replace (:bbin/url coords) #"^file:(//|\\\\)" "")})]
      (p/install (map->LocalJar {:cli-opts cli-opts'
                                 :coords coords}))))

  (uninstall [_]
    (common/delete-files cli-opts)))
