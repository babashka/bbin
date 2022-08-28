(ns babashka.bbin.gen-script
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [fipp.edn :as fipp]))

(def bbin-deps (some-> (slurp "deps.edn") edn/read-string :deps))

(def prelude-template
  (str/triml "
#!/usr/bin/env bb

; :bbin/start
;
; {:coords {:bbin/url \"https://raw.githubusercontent.com/babashka/bbin/main/bbin\"}}
;
; :bbin/end

(babashka.deps/add-deps
  '{:deps %s})
"))

(def prelude-str
  (let [lines (-> (with-out-str (fipp/pprint bbin-deps {:width 80})) str/split-lines)]
    (format prelude-template
            (str/join "\n" (cons (first lines) (map #(str "          " %) (rest lines)))))))

(defn gen-script []
  (let [trust (slurp "src/babashka/bbin/trust.clj")
        scripts (slurp "src/babashka/bbin/scripts.clj")
        cli (slurp "src/babashka/bbin/cli.clj")]
    (spit "bbin" (str/join "\n" [prelude-str trust scripts cli]))))
