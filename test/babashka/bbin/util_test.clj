(ns babashka.bbin.util-test
  (:require [babashka.bbin.util :as util]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing are]]))

(deftest truncate-test
  (are [input opts expected] (= expected (util/truncate input opts))
    "123456" {:truncate-to 6} "123456"
    "123456" {:truncate-to 5} "12..."

    "123456" {:truncate-to 5 :omission "longer than 5"} "longer than 5"

    "12345" {:truncate-to 3 :omission "…" :omission-position :center} "1…5"))

(deftest print-table-test
  (let [split-table           (fn [table]
                                (let [[header div & data :as lines] (str/split-lines table)]
                                  (if (re-find (re-pattern (str \u2500)) (str div))
                                    [header div data]
                                    [nil nil lines])))
        print-table           (fn
                                ([rows]
                                 (with-out-str (util/print-table rows)))
                                ([ks-or-rows rows-or-opts]
                                 (with-out-str (util/print-table ks-or-rows rows-or-opts)))
                                ([ks rows opts]
                                 (with-out-str (util/print-table ks rows opts))))
        header-matches        (fn [re table]
                                (let [[header & _r] (split-table table)]
                                  (is (re-find re (str header))
                                      (str "expected header to match " (pr-str re)))))
        contains-row-matching (fn [re table]
                                (let [[_header _div rows] (split-table table)]
                                  (is (some #(re-find re %) rows)
                                      (str "expected " (pr-str rows) " to contain a row matching " (pr-str re)))))]
    (testing ":no-color skips escape characters"
      (is (re-find #"^a\n─\n1\r?\n$"
                   (print-table [{:a 1}] {:no-color true}))))
    (testing "header from rows or keys"
      (header-matches #"a.+b" (print-table [{:a "12" :b "34"}]))
      (header-matches #"b.+a" (print-table '(:b :a) [{:a "12" :b "34"}]))
      (header-matches #"A" (print-table [{"A" 1}]))
      (header-matches #"A" (print-table '("A") [{"A" 1}]))
      (is (re-find #"^12  34\r?\n$" (print-table [{:a "12" :b "34"}] {:skip-header true}))
          "prints only rows when :skip-header"))
    (testing "naming columns"
      (header-matches #"A.+B" (print-table {:a "A" :b "B"}
                                           [{:a "12" :b "34"}])))
    (testing "skipping empty columns"
      (is (empty? (print-table [{:a nil :b ""}])))
      (header-matches #"(?<!a +)b" (print-table [{:a nil :b "b"}]))
      (header-matches #"a +b" (print-table [{:a nil :b "b"}] {:show-empty-columns true})))
    (testing "coercions"
      (let [coercions {:a (fnil boolean false)}]
        (is (seq (print-table [{:a nil}]
                              {:column-coercions coercions}))
            "applies coercions before skipping columns")
        (contains-row-matching #"false"
                               (print-table [{:a nil}]
                                            {:column-coercions coercions}))
        (contains-row-matching #"nil"
                               (print-table [{"a" nil}]
                                            {:column-coercions {"a" pr-str}}))))
    (testing "reducing width"
      (contains-row-matching #"12\.\.\." (print-table [{:a "123456"}] {:max-width           5
                                                                       :width-reduce-column :a}))
      (contains-row-matching #"^12345$" (print-table [{"a" "123456"}] {:max-width           5
                                                                       :width-reduce-fn     #(subs %1 0 %2)
                                                                       :width-reduce-column "a"}))
      ;; any coercion is applied first
      (contains-row-matching #"^https://example\.\.\.$"
                             (print-table [{"a" "example.org"}]
                                          {:column-coercions    {"a" #(str "https://" %)}
                                           :max-width           18
                                           :width-reduce-column "a"}))
      ;; column "a" is skipped
      (contains-row-matching #"^ABCDE$" (print-table [{"a" "123456"
                                                       "b" "ABCDE"}] {:max-width           5
                                                                      :width-reduce-fn     #(subs %1 0 %2)
                                                                      :width-reduce-column "a"})))))
