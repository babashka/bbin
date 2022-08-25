(ns rads.bbin.deps
  (:require [clojure.set :as set]
            [babashka.fs :as fs]))

(def symbol-regex #"(?i)^(?:((?:[a-z0-9-]+\.)*[a-z0-9-]+)/)?([a-z0-9-]+)$")

(defn lib-str? [x]
  (boolean (and (string? x) (re-seq symbol-regex x))))

(defn http-url? [x]
  (boolean (and (string? x) (re-seq #"^https?://" x))))

(def deps-types
  [{:lib lib-str?
    :coords #{:local/root}
    :procurer :local}

   {:lib lib-str?
    :coords #{:mvn/version}
    :procurer :maven}

   {:lib lib-str?
    :coords #{:git/sha :git/url :git/tag}
    :procurer :git}

   {:lib http-url?
    :coords #{:bbin/url}
    :procurer :http}

   {:lib lib-str?
    :coords #{}
    :procurer :git}

   {:lib http-url?
    :coords #{}
    :procurer :http}])

(defn deps-type-match? [cli-opts deps-type]
  (and ((:lib deps-type) (:script/lib cli-opts))
       (or (empty? (:coords deps-type))
           (seq (set/intersection (:coords deps-type) (set (keys cli-opts)))))
       deps-type))

(defn match-deps-type [cli-opts]
  (or (some #(deps-type-match? cli-opts %) deps-types)
      (throw (ex-info "Invalid match" {:cli-opts cli-opts}))))

(defn match-artifact [cli-opts procurer]
  (cond
    (or (and (#{:local} procurer) (re-seq #"\.clj$" (:script/lib cli-opts)))
        (and (#{:http} procurer) (re-seq #"\.clj$" (:script/lib cli-opts))))
    :file

    (or (#{:maven} procurer)
        (and (#{:local} procurer)
             (string? (:local/root cli-opts))
             (re-seq #"\.jar$" (:local/root cli-opts)))
        (and (#{:http} procurer) (re-seq #"\.jar$" (:script/lib cli-opts))))
    :jar

    (or (#{:git} procurer)
        (#{:local} procurer)
        (and (#{:http} procurer) (re-seq #"\.git$" (:script/lib cli-opts))))
    :dir))

(defn canonicalized-cli-opts [parsed-args]
  (merge (:opts parsed-args)
         (when-let [v (:local/root (:opts parsed-args))]
           {:local/root (str (fs/canonicalize v {:nofollow-links true}))})))

(defn summary [cli-opts]
  (let [{:keys [procurer]} (match-deps-type cli-opts)
        artifact (match-artifact cli-opts procurer)]
    {:procurer procurer
     :artifact artifact}))
