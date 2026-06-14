(ns babashka.bbin.deps.add-libs
  "Provides a function to add libs at runtime that works in both Clojure and
   Babashka. The Clojure functionality exists to allow the install command to
   be run from a Clojure REPL during development."
  #?(:bb (:require [babashka.deps])
     :clj (:require [clojure.repl.deps])))

#_{:clj-kondo/ignore [:unused-binding]}
(defn add-libs
  [lib-coords]
  #?(:bb ((resolve 'babashka.deps/add-deps) {:deps lib-coords})
     :clj ((resolve 'clojure.repl.deps/add-libs) lib-coords)))
