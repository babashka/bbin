(ns babashka.bbin.matrix-test
  (:require [babashka.fs :as fs]
            [clojure.math.combinatorics :as combo]))

(def ^:private bbin-bin
  {'both-bb-edn-and-deps-edn {:main-opts ["-m" "main"]}})

(def ^:private paths
  ["."])

(def ^:private main-clj
  "(ns main)\n\n(defn -main [& _]\n  (println \"hello world\"))\n")

(defn- write-edn! [file m]
  (spit file (str (pr-str m) "\n")))

(defn- generated-bb-edn [opts]
  (cond-> {}
    (:bbin-bin opts) (assoc :bbin/bin bbin-bin)
    (:paths opts) (assoc :paths paths)))

(defn- generated-deps-edn [opts]
  (cond-> {}
    (:paths opts) (assoc :paths paths)))

(defn- boolean-option-maps [ks]
  (map #(zipmap ks %)
       (apply combo/cartesian-product (repeat (count ks) [false true]))))

(defn all-possible-inputs []
  (let [bb-edn-options (cons nil (boolean-option-maps [:bbin-bin :paths]))
        deps-edn-options (cons nil (boolean-option-maps [:paths]))]
    (mapv (fn [[bb-edn deps-edn]]
            (cond-> {}
              bb-edn (assoc :bb-edn bb-edn)
              deps-edn (assoc :deps-edn deps-edn)))
          (combo/cartesian-product bb-edn-options deps-edn-options))))

(defn generate-example! [example-dir input]
  (when (fs/exists? example-dir)
    (throw (ex-info (str "Example dir already exists: " example-dir)
                    {:example-dir example-dir})))
  (fs/create-dirs example-dir)
  (spit (fs/file example-dir "main.clj") main-clj)
  (when (contains? input :bb-edn)
    (write-edn! (fs/file example-dir "bb.edn")
                (generated-bb-edn (:bb-edn input))))
  (when (contains? input :deps-edn)
    (write-edn! (fs/file example-dir "deps.edn")
                (generated-deps-edn (:deps-edn input))))
  example-dir)

(comment
  (def repl-inputs (take 100 (all-possible-inputs)))
  (map-indexed vector repl-inputs)
  (let [prefix (str "test_" (System/nanoTime))]
    (doseq [[i input] (map-indexed vector repl-inputs)
            :let [example-dir (fs/file "target" prefix (str i))]]
      (generate-example! example-dir input))))
