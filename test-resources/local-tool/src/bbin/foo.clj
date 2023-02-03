(ns bbin.foo)

(defn k
  "just `keys`"
  [m]
  (prn (keys m)))

(defn v
  "just `vals`"
  [m]
  (prn (vals m)))
