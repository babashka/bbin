(ns babashka.bbin.cli-test
  (:require [babashka.bbin.dirs :as dirs]
            [babashka.bbin.meta :as meta]
            [babashka.bbin.test-util :refer [bbin bbin-dirs-fixture reset-test-dir run-bin-script]]
            [babashka.bbin.util :as util]
            [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]))

(use-fixtures :each
  (fn [f] (reset-test-dir) (f))
  (bbin-dirs-fixture)
  (fn [f] (reset-test-dir) (f)))

(deftest bin-test
  (let [{:keys [exit err out]} (bbin ["bin"])]
    (is (= nil exit) "exit")
    (is (= "" err) "err")
    (is (= (str (dirs/bin-dir nil)) out))))

(deftest commands-help-test
  (doseq [args [["help"] ["-h"] ["--help"] []]]
    (testing (str "args: " args)
      (let [{:keys [exit err out]} (bbin args)
            [version _ usage] (str/split-lines out)]
        (is (= nil exit) "exit") ;; natural exit
        (is (= "" err) "err")
        (is (= version (str "Version: " meta/version)))
        (is (= usage "Usage: bbin [options] <command>"))))))

(deftest command-help-test
  (doseq [cmd ["install" "uninstall" "ls" "bin" "version" "migrate"]
          arg ["-h" "--help"]]
    (testing (str cmd " " arg)
      (let [{:keys [exit err out]} (bbin [cmd arg])
            [version _ usage] (str/split-lines out)]
        (is (= nil exit) "exit")
        (is (= "" err) "err")
        (is (= version (str "Version: " meta/version)))
        (is (str/starts-with? usage (format "Usage: bbin %s [options]" cmd)))))))

(deftest command-invalid-error-test
  (let [{:keys [exit err out]} (bbin ["foobar"])
        [error _ usage] (str/split-lines err)]
    (is (= 1 exit) "exit")
    (is (= "" out) "out")
    (is (= "Error: Unexpected argument: foobar" error))
    (is (= "Usage: bbin [options] <command>" usage))))

(deftest command-invalid-opt-test
  (doseq [cmd ["install" "uninstall" "ls" "bin" "version" "migrate"]
          arg ["--foobar"]]
    (testing (str cmd " " arg)
      (let [{:keys [exit err out]} (bbin [cmd arg])
            [error _ usage] (str/split-lines err)]
        (is (= 1 exit) "exit")
        (is (= "" out) "out")
        (is (= "Error: Unknown option: --foobar" error))
        (is (str/starts-with? usage (format "Usage: bbin %s [options]" cmd)))))))

(deftest command-unexpected-arg-test
  (doseq [[cmds args]
          [[["install"] ["some-source" "boop"]]
           [["uninstall"] ["some-script" "boop"]]
           [["ls"] ["boop"]]
           [["bin"] ["boop"]]
           [["version"] ["boop"]]
           [["migrate" "auto"] ["boop"]]]]
    (testing (str (into cmds args))
      (let [{:keys [exit err out]} (bbin (into cmds args))
            [error _ usage] (str/split-lines err)]
        (is (= 1 exit) "exit")
        (is (= "" out) "out")
        (is (= "Error: Unexpected argument: boop" error))
        (is (str/starts-with? usage (format "Usage: bbin %s [options]" (str/join " " cmds))))))))

(deftest install-command-error-test
  (doseq [[args                                              expected-error]
          [[[]                                     "Error: Required argument: <source>"]
           [["--mvn/version" "foo"]                "Error: Required argument: <source>"]
           [["some-source" ":color"]               "Error: Unexpected argument: :color"]
           [["some-source" "foo"]                  "Error: Unexpected argument: foo"]
           [["some-source" "--foo"]                "Error: Unknown option: --foo"]
           [["some-source"
             "--git/tag" "foo"
             "--git/sha" "foo"
             "--latest-sha"]                       "Error: Cannot specify together: --git/sha, --git/tag with --latest-sha"]
           [["some-source"
             "--latest-tag"
             "--latest-sha"]                       "Error: Cannot specify together: --latest-sha with --latest-tag"]
           [["some-source"
             "--git/tag" "foo"
             "--latest-sha"]                       "Error: Cannot specify together: --git/tag with --latest-sha"]
           [["some-source"
             "--git/url" "some-url"
             "--mvn/version" "some-version"]       "Error: Cannot specify together: --git/url with --mvn/version"]
           [["some-source"
             "--git/url" "some-url"
             "--git/sha" "some-sha"
             "--mvn/version" "some-version"]       "Error: Cannot specify together: --git/url, --git/sha with --mvn/version"]]]
    (testing (str "args: " args)
      (let [{:keys [exit err out]} (bbin (into ["install"] args))
            [error _ usage] (str/split-lines err)]
        (is (= 1 exit) "exit")
        (is (= "" out) "out")
        (is (= expected-error error))
        (is (= "Usage: bbin install [options] <source>" usage))))))

(deftest version-test
  (let [{:keys [exit err out]} (bbin ["--version"])]
    (is (= nil exit) "exit")
    (is (= "" err) "err")
    (is (str/starts-with? out (str "bbin " meta/version)))))

(def expected-commands
  (cond->
   #{"commands"
     "help"
     "install"
     "uninstall"
     "migrate"
     "version"
     "ls"
     "bin"}
    (util/upgrade-enabled?) (conj "upgrade")))

(deftest commands-test
  (let [{:keys [exit out err]} (bbin ["commands"])
        commands (set (str/split out #" "))]
    (is (= nil exit) "exit")
    (is (= "" err) "err")
    (is (= expected-commands commands))))

(deftest install-test
  (let [calls (atom [])
        args ["install" "io.github.rads/watch"
              "--as" "watch-script"
              "--main-opts" (pr-str ["--foo" "bar"])
              "--mvn/version" "1.2.3"
              "--no-color"]]
    (bbin args
          :install-fn #(swap! calls conj %))
    (is (= [{:script/lib "io.github.rads/watch"
             :as "watch-script"
             :main-opts ["--foo" "bar"]
             :mvn/version "1.2.3"
             :color false}]
           @calls))))

(deftest full-install-ns-default-override-test
  (let [rel-local-root (str (fs/file "test-resources" "local-tool"))
        args ["install" "bbin/foo"
              "--local/root" rel-local-root
              "--as" "footool"
              "--ns-default" "bbin.bar" ;; alternate to default
              "--tool"
              "--edn"]
        {:keys [exit out err]} (bbin args)
        out (edn/read-string out)
        script (str (fs/file (dirs/bin-dir nil) "footool"))]
    (is (= nil exit) "exit")
    (is (= {:coords {:local/root (str (fs/absolutize rel-local-root))}
            :lib 'bbin/foo} out) "out")
    (is (= "" err) "err")
    (is (fs/exists? script) "generated script exits")
    (let [footool-help (run-bin-script "footool")
          [usage _ cmd] (str/split-lines footool-help)]
      (is (= "Usage: footool <command>" usage) "footool usage")
      (is (re-matches #" *footool callme *prints opts" cmd) "footool command"))
    (let [footool-out (run-bin-script "footool" "callme" ":a" "1")]
      (is (= "opts: {:a 1}" footool-out) "footool run output"))))

(deftest full-install-main-opts-test
  (let [rel-local-root (str (fs/file "test-resources" "local-tool"))
        args ["install" "bbin/foo"
              "--as" "footool"
              "--main-opts" (pr-str ["-m" "bbin.foo" "--foo" "bar"])
              "--local/root" rel-local-root
              "--edn"]
        {:keys [exit out err]} (bbin args)
        out (edn/read-string out)
        script (str (fs/file (dirs/bin-dir nil) "footool"))]
    (is (= nil exit) "exit")
    (is (= {:coords {:local/root (str (fs/absolutize rel-local-root))}
            :lib 'bbin/foo} out) "out")
    (is (= "" err) "err")
    (is (fs/exists? script))
    (let [footool-out (run-bin-script "footool" "bippedy" "boop")]
      (is (= "main args: --foo bar -- bippedy boop" footool-out) "footool run output"))))

(deftest uninstall-test
  (let [calls (atom [])
        args ["uninstall" "watch" "--plain"]]
    (bbin args
          :uninstall-fn #(swap! calls conj %))
    (is (= [{:script/lib "watch"
             :plain true}]
           @calls))))

(deftest migrate-test
  (let [calls (atom [])
        {:keys [exit out err]} (bbin ["migrate"] :migrate-fn #(swap! calls conj %))]
    (is (= nil exit) "exit")
    (is (= "" err) "err")
    (is (= [] @calls) "does not call migrate fn")
    (is (str/includes? out "bbin migrate auto") "outputs help on how to migrate")))

(deftest migrate-auto-test
  (let [calls (atom [])
        args ["migrate" "auto" "--force"]]
    (bbin args
          :migrate-fn #(swap! calls conj %))
    (is (= [{:force true}] @calls))))


(defn- is-legacy-warning? [text]
  (let [lines (str/split-lines text)]
    (and (= 6 (count lines))
         (str/includes? (first lines) "bbin 0.2.0")
         (every? #(str/starts-with? % "WARNING:") lines))))

(defn- looks-like-help? [text]
  (let [[version _ usage] (str/split-lines text)]
    (and (str/starts-with? version "Version:")
         (str/starts-with? usage "Usage:"))))

(defn- looks-like-error? [text]
  (let [[error _ usage] (str/split-lines text)]
    (and (str/starts-with? error "Error:")
         (str/starts-with? usage "Usage:"))))

(deftest legacy-path-warning-test
  (fs/create-dirs (dirs/legacy-bin-dir))
  (testing "warning is suppressed for migrate commands"
    (testing "help"
      (doseq [args [["migrate"] ;; special case of help for migrate
                    ["migrate" "--help"]
                    ["migrate" "-h"]
                    ["migrate" "auto" "--help"]
                    ["migrate" "auto" "-h"]]]
        (testing (str "args: " args)
          (let [{:keys [exit out err]} (bbin args)]
            (is (= nil exit) "exit")
            (is (= "" err) "err")
            (is (looks-like-help? out))))))
    (testing "error"
      (doseq [args [["migrate" "--foobar"]
                    ["migrate" "auto" "--foobar"]
                    ["migrate" "foobar"]]]
        (testing (str "args: " args)
          (let [{:keys [exit out err]} (bbin args)]
            (is (= 1 exit))
            (is (= "" out) "out")
            (is (looks-like-error? err) "err")))))
    (testing "valid run"
      (doseq [args [["migrate" "auto"]
                    ["migrate" "auto" "--force"]]]
        (testing (str args)
          (let [{:keys [exit out err]} (bbin args :migrate-fn (fn [_opts] (println "executing: migrate")))]
            (is (= nil exit) "exit")
            (is (= "executing: migrate" out) "out")
            (is (= "" err) "err"))))))
  (testing "warning is output for non-migrate commands"
    (testing "help"
      (doseq [args [[]
                    ["help"]
                    ["-h"]
                    ["--help"]
                    ["install" "-h"]
                    ["uninstall" "--help"]
                    ["bin" "-h"]
                    ["ls" "--help"]]]
        (testing (str "args: " args)
          (let [{:keys [exit err out]} (bbin args)]
            (is (= nil exit) "exit") ;; natural exit
            (is (is-legacy-warning? err) "err")
            (is (looks-like-help? out))))))
    (testing "error"
      (doseq [args [["badcommand"]
                    ["--bad-opt"]
                    ["install" "some-source" "--bad-opt"]
                    ["install" "--mvn/version" "1.2.3" "--local/root" "some-dir"] ;; post validation error
                    ["uninstall" "some-script" "extra-arg"]
                    ["ls" "--bad-opt"]
                    ["bin" "extra-arg"]]]
        (testing (str "args: " args)
          (let [{:keys [exit err out]} (bbin args)
                lines (str/split-lines err)
                warning (->> lines (take 6) (str/join "\n"))
                error (->> lines (drop 7) (str/join "\n"))]
            (is (= 1 exit) "exit")
            (is (= "" out) "out")
            (is (is-legacy-warning? warning) "warning")
            (is (looks-like-error? error) "error")))))
    (testing "valid run"
      (doseq [args [["commands"]
                    ["install" "some-source"]
                    ["uninstall" "some-source"]
                    ["ls"]
                    ["bin"]
                    ["version"]]]
        (testing (str "args: " args)
          (let [{:keys [exit err out]} (bbin args
                                             :commands-fn (fn [_opts] (println "executing: commands"))
                                             :install-fn (fn [_opts] (println "executing: install"))
                                             :uninstall-fn (fn [_opts] (println "executing: uninstall"))
                                             :ls-fn (fn [_opts] (println "executing: ls"))
                                             :bin-fn (fn [_opts] (println "executing: bin"))
                                             :version-fn (fn [_opts] (println "executing: version")))]
            (is (= nil exit) "exit") ;; natural exit
            (is (is-legacy-warning? err) "err")
            (is (= (str "executing: " (first args)) out) "out")))))))
