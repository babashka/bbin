(ns babashka.bbin.scripts.common-test
  (:require
   [clojure.test :refer [deftest is]]
   [babashka.bbin.scripts.common :as subject]
   [selmer.parser :as selmer]
   [selmer.filters]
   [clojure.string :as str]))

;; babashgka.bbin.scripts.common requires the :pr-str selmer filter
;; Add it if it doesn't exist yet.
(when-not (contains? @selmer.filters/filters :pr-str)
  (selmer.filters/add-filter! :pr-str (comp pr-str str)))

(defn trim= [s1 s2]
  (= (str/trim s1) (str/trim s2)))

(deftest local-dir-template-str-test
  (is (trim=
       (selmer/render subject/local-dir-template-str
                      {:script/meta "meta"
                       :script/root "root"
                       :script/lib "script dep"
                       :script/coords "script coords"
                       :script/ns-default "ns default"})
       "
#!/usr/bin/env bb

; :bbin/start
;
meta
;
; :bbin/end

(require '[babashka.process :as process]
         '[babashka.fs :as fs]
         '[clojure.string :as str])

(def script-root &quot;root&quot;)
(def script-main-opts-first &quot;&quot;)
(def script-main-opts-second &quot;&quot;)

(def tmp-edn
  (doto (fs/file (fs/temp-dir) (str (gensym \"bbin\")))
    (spit (str \"{:deps {local/deps {:local/root \" (pr-str script-root) \"}}}\"))
    (fs/delete-on-exit)))

(def base-command
  [\"bb\" \"--deps-root\" script-root \"--config\" (str tmp-edn)
        script-main-opts-first script-main-opts-second
        \"--\"])

(def script-deps-file
  (cond (fs/exists? (fs/file script-root \"bb.edn\"))
        (fs/file (fs/file script-root \"bb.edn\"))

        (fs/exists? (fs/file script-root \"deps.edn\"))
        (fs/file (fs/file script-root \"deps.edn\"))

        :else nil))

(def new-base-command
  (when script-deps-file
    [\"bb\" \"--config\" (str script-deps-file)
     script-main-opts-first script-main-opts-second
     \"--\"]))

(process/exec (into (or new-base-command base-command) *command-line-args*))
"
       )))

(comment
  ;; I want to write unit tests for selmer templates. And I want to avoid having
  ;; Clojure strings in this file that are super long.
  ;;
  ;; To solve this, I can use `println2` to print a string and preserve escaped
  ;; quotation marks in the string.
  ;;
  ;; The printed output can be copied back into Clojure strings.
  (defn prepare-for-print
    "Produce strings that span multiple lines that can be printed and copied back into code"
    [s]
    (str/escape (str/trim s)
                {\" "\\\""}))

  (def println2 (comp println prepare-for-print))

  (println2 (selmer/render subject/local-dir-template-str
                           {:script/meta "meta"
                            :script/root "root"
                            :script/lib "script dep"
                            :script/coords "script coords"}))

  :rcf)
