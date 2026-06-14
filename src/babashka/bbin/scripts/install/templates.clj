(ns babashka.bbin.scripts.install.templates
  "Selmer templates for the scripts `bbin install` generates."
  (:require [clojure.string :as str]))

(def local-dir-tool-template-str
  (str/trim "
#!/usr/bin/env bb

; :bbin/start
;
{{script/meta}}
;
; :bbin/end

(require '[babashka.process :as process]
         '[babashka.fs :as fs]
         '[clojure.string :as str])

(def script-root {{script/root|pr-str}})
(def script-ns-default '{{script/ns-default}})
(def script-name (fs/file-name *file*))

(def tmp-edn
  (doto (fs/file (fs/temp-dir) (str (gensym \"bbin\")))
    (spit (str \"{:deps {local/deps {:local/root \" (pr-str script-root) \"}}}\"))
    (fs/delete-on-exit)))

(def base-command
  [\"bb\" \"--config\" (str tmp-edn)])

(defn help-eval-str []
  (str \"(require '\" script-ns-default \")
        (def fns (filter #(fn? (deref (val %))) (ns-publics '\" script-ns-default \")))
        (def max-width (->> (keys fns) (map (comp count str)) (apply max)))
        (defn pad-right [x] (format (str \\\"%-\\\" max-width \\\"s\\\") x))
        (println (str \\\"Usage: \" script-name \" <command>\\\"))
        (newline)
        (doseq [[k v] fns]
          (println
            (str \\\"  \" script-name \" \\\" (pad-right k) \\\"  \\\"
               (when (:doc (meta v))
                 (first (str/split-lines (:doc (meta v))))))))\"))

(def first-arg (first *command-line-args*))
(def rest-args (rest *command-line-args*))

(if first-arg
  (process/exec
    (vec (concat base-command
                 [\"-x\" (str script-ns-default \"/\" first-arg)]
                 rest-args)))
  (process/exec (into base-command [\"-e\" (help-eval-str)])))
"))

(def deps-tool-template-str
  (str/trim "
#!/usr/bin/env bb

; :bbin/start
;
{{script/meta}}
;
; :bbin/end

(require '[babashka.process :as process]
         '[babashka.fs :as fs]
         '[clojure.string :as str])

(def script-lib '{{script/lib}})
(def script-coords {{script/coords|str}})
(def script-ns-default '{{script/ns-default}})
(def script-name (fs/file-name *file*))

(def tmp-edn
  (doto (fs/file (fs/temp-dir) (str (gensym \"bbin\")))
    (spit (str \"{:deps {\" script-lib script-coords \"}}\"))
    (fs/delete-on-exit)))

(def base-command
  [\"bb\" \"--config\" (str tmp-edn)])

(defn help-eval-str []
  (str \"(require '\" script-ns-default \")
        (def fns (filter #(fn? (deref (val %))) (ns-publics '\" script-ns-default \")))
        (def max-width (->> (keys fns) (map (comp count str)) (apply max)))
        (defn pad-right [x] (format (str \\\"%-\\\" max-width \\\"s\\\") x))
        (println (str \\\"Usage: \" script-name \" <command>\\\"))
        (newline)
        (doseq [[k v] fns]
          (println
            (str \\\"  \" script-name \" \\\" (pad-right k) \\\"  \\\"
               (when (:doc (meta v))
                 (first (str/split-lines (:doc (meta v))))))))\"))

(def first-arg (first *command-line-args*))
(def rest-args (rest *command-line-args*))

(if first-arg
  (process/exec
    (vec (concat base-command
                 [\"-x\" (str script-ns-default \"/\" first-arg)]
                 rest-args)))
  (process/exec (into base-command [\"-e\" (help-eval-str)])))
"))

(def local-dir-template-str
  (str/trim "
#!/usr/bin/env bb

; :bbin/start
;
{{script/meta}}
;
; :bbin/end

(require '[babashka.fs :as fs]
         '[babashka.process :as process])

(def script-root {{script/root|pr-str}})
(def script-main-opts {{script/main-opts}})

(def tmp-edn
  (doto (fs/file (fs/temp-dir) (str (gensym \"bbin\")))
    (spit (str \"{:deps {local/deps {:local/root \" (pr-str script-root) \"}}}\"))
    (fs/delete-on-exit)))

(def base-command
  (vec (concat [\"bb\" \"--config\" (str tmp-edn)]
               script-main-opts
               [\"--\"])))

(process/exec (into base-command *command-line-args*))
"))

(def config-dir-template-str
  (str/trim "
#!/usr/bin/env bb

; :bbin/start
;
{{script/meta}}
;
; :bbin/end

(require '[babashka.process :as process])

(def script-config {{script/config|pr-str}})
(def script-bb-opts {{script/bb-opts}})
(def script-main-opts {{script/main-opts}})

(def base-command
  (vec (concat (if (seq script-bb-opts)
                 (into [\"bb\"] script-bb-opts)
                 [\"bb\" \"--config\" script-config])
               script-main-opts
               [\"--\"])))

(process/exec (into base-command *command-line-args*))
"))

(def config-tool-template-str
  (str/trim "
#!/usr/bin/env bb

; :bbin/start
;
{{script/meta}}
;
; :bbin/end

(require '[babashka.process :as process]
         '[babashka.fs :as fs]
         '[clojure.string :as str])

(def script-config {{script/config|pr-str}})
(def script-bb-opts {{script/bb-opts}})
(def script-ns-default '{{script/ns-default}})
(def script-name (fs/file-name *file*))

(def base-command
  (if (seq script-bb-opts)
    (into [\"bb\"] script-bb-opts)
    [\"bb\" \"--config\" script-config]))

(defn help-eval-str []
  (str \"(require '\" script-ns-default \")
        (def fns (filter #(fn? (deref (val %))) (ns-publics '\" script-ns-default \")))
        (def max-width (->> (keys fns) (map (comp count str)) (apply max)))
        (defn pad-right [x] (format (str \\\"%-\\\" max-width \\\"s\\\") x))
        (println (str \\\"Usage: \" script-name \" <command>\\\"))
        (newline)
        (doseq [[k v] fns]
          (println
            (str \\\"  \" script-name \" \\\" (pad-right k) \\\"  \\\"
               (when (:doc (meta v))
                 (first (str/split-lines (:doc (meta v))))))))\"))

(def first-arg (first *command-line-args*))
(def rest-args (rest *command-line-args*))

(if first-arg
  (process/exec
    (vec (concat base-command
                 [\"-x\" (str script-ns-default \"/\" first-arg)]
                 rest-args)))
  (process/exec (into base-command [\"-e\" (help-eval-str)])))
"))

(def no-config-dir-template-str
  (str/trim "
#!/usr/bin/env bb

; :bbin/start
;
{{script/meta}}
;
; :bbin/end

(require '[babashka.process :as process])

(def script-main-opts {{script/main-opts}})
(def base-command (vec (concat [\"bb\"] script-main-opts [\"--\"])))

(process/exec (into base-command *command-line-args*))
"))

(def git-dir-template-str
  (str/trim "
#!/usr/bin/env bb

; :bbin/start
;
{{script/meta}}
;
; :bbin/end

(require '[babashka.process :as process]
         '[babashka.fs :as fs])

(def script-lib '{{script/lib}})
(def script-coords {{script/coords|str}})
(def script-main-opts {{script/main-opts}})

(def tmp-edn
  (doto (fs/file (fs/temp-dir) (str (gensym \"bbin\")))
    (spit (str \"{:deps {\" script-lib script-coords \"}}\"))
    (fs/delete-on-exit)))

(def base-command
  (vec (concat [\"bb\" \"--config\" (str tmp-edn)]
               script-main-opts
               [\"--\"])))

(process/exec (into base-command *command-line-args*))
"))

(def local-jar-template-str
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

(def maven-template-str
  (str/trim "
#!/usr/bin/env bb

; :bbin/start
;
{{script/meta}}
;
; :bbin/end

(require '[babashka.process :as process]
         '[babashka.fs :as fs]
         '[clojure.string :as str])

(def script-lib '{{script/lib}})
(def script-coords {{script/coords|str}})
(def script-main-opts {{script/main-opts}})

(def tmp-edn
  (doto (fs/file (fs/temp-dir) (str (gensym \"bbin\")))
    (spit (str \"{:deps {\" script-lib script-coords \"}}\"))
    (fs/delete-on-exit)))

(def base-command
  (vec (concat [\"bb\" \"--config\" (str tmp-edn)]
               script-main-opts
               [\"--\"])))

(process/exec (into base-command *command-line-args*))
"))
