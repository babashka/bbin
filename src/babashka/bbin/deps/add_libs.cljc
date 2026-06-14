(ns babashka.bbin.deps.add-libs
  #?(:bb (:require [babashka.deps])
     :clj (:require [clojure.repl.deps])))

#_{:clj-kondo/ignore [:unused-binding]}
(defn add-libs
  [lib-coords]
  #?(:bb ((resolve 'babashka.deps/add-deps) {:deps lib-coords})
     :clj ((resolve 'clojure.repl.deps/add-libs) lib-coords)))
