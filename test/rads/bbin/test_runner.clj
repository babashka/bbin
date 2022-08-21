(ns rads.bbin.test-runner
  (:require [clojure.edn :as edn]
            [clojure.test :as t]))

(def test-namespaces
  '[rads.bbin-test])

(doseq [ns test-namespaces]
  (require ns))

(defn run-tests [& {:keys [nses]}]
  (let [selected-tests (if nses
                         (edn/read-string nses)
                         test-namespaces)
        test-results (apply t/run-tests selected-tests)
        {:keys [:fail :error]} test-results]
    (when (pos? (+ fail error))
      (throw (ex-info "Tests failed" {:babashka/exit 1})))))
