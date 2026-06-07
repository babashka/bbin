(ns babashka.bbin.matrix-test
  (:require [babashka.process :as p]
            [babashka.fs :as fs]
            [clojure.edn :as edn]
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
  (write-edn! (fs/file example-dir "input.edn") input)
  (when (contains? input :bb-edn)
    (write-edn! (fs/file example-dir "bb.edn")
                (generated-bb-edn (:bb-edn input))))
  (when (contains? input :deps-edn)
    (write-edn! (fs/file example-dir "deps.edn")
                (generated-deps-edn (:deps-edn input))))
  example-dir)

(defn- child-dirs [parent-dir]
  (->> (fs/list-dir parent-dir)
       (filter fs/directory?)
       (sort-by #(str (fs/file-name %)))))

(defn install-examples! [parent-dir prefix bbin-binary]
  (mapv (fn [child-dir]
          (let [basename (fs/file-name child-dir)
                script-name (str prefix "-" basename)]
            {:bbin-binary bbin-binary
             :child-dir child-dir
             :as script-name
             :input (edn/read-string (slurp (fs/file child-dir "input.edn")))
             :out (try
                    (p/shell {:out :string :err :string :continue true}
                             bbin-binary "install" (str child-dir) "--as" script-name)
                    (catch Exception e e))}))
        (child-dirs parent-dir)))

(defn run-examples! [install-results]
  (->> install-results
       (map (fn [{:keys [as bbin-binary child-dir input out]}]
              (let [error (instance? Exception out)
                    installed (and (not error) (zero? (:exit out)))
                    run (when installed
                          (-> (p/shell as {:out :string :err :string :continue true})
                              (select-keys [:out :err])))]
                {:as as
                 :child-dir (str child-dir)
                 :input input
                 :bbin-binary bbin-binary
                 :installed installed
                 :install-message (if error (ex-message out) (:out out))
                 :run run})))))

(comment
  (def repl-inputs (take 100 (all-possible-inputs)))
  (def repl-prefix (str "test_" (System/nanoTime)))
  (def repl-bbin-binaries ["bbin-0.2" "bbin-0.3"])
  (map-indexed vector repl-inputs)

  (doseq [[i input] (map-indexed vector repl-inputs)
          :let [example-dir (fs/file "target" repl-prefix (format "%02d" i))]]
    (generate-example! example-dir input))

  (def install-results
    (mapcat (fn [bbin-binary]
              (install-examples! (fs/file "target" repl-prefix)
                                 (str repl-prefix "-" bbin-binary)
                                 bbin-binary))
            repl-bbin-binaries))
  (def run-results (doall (run-examples! install-results)))
  (tap>
    (as-> run-results $
      (group-by :input $)
      (map (fn [[input results]]
             (let [by-binary (into {} (map (juxt :bbin-binary identity) results))]
               (merge
                 {:child-dir (str (:child-dir (first results)))
                  :0-input (with-meta input {:portal.viewer/default :portal.viewer/pprint})}
                 (into {}
                       (mapcat (fn [bbin-binary]
                                 (let [result (by-binary bbin-binary)
                                       ran (= (get-in result [:run :out]) "hello world\n")]
                                   [[(keyword (str "1-" bbin-binary) "installed")
                                     (if (:installed result) '✅ '❌)]
                                    [(keyword (str "2-" bbin-binary) "ran")
                                     (if ran '✅ '❌)]]))
                               repl-bbin-binaries)))))
           $)
      (sort-by :child-dir $)
      (map #(dissoc % :child-dir) $)
      (with-meta $ {:portal.viewer/default :portal.viewer/table}))))
