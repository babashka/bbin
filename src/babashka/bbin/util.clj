(ns babashka.bbin.util
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.pprint :as pprint]
            [clojure.string :as str]
            [taoensso.timbre :as log])
  (:import (java.util Date)))

(defn sh [cmd & {:as opts}]
  (doto (p/sh cmd (merge {:err :inherit} opts))
    p/check))

(defn set-logging-config! [{:keys [debug]}]
  (log/merge-config! {:min-level (if debug :debug :warn)}))

(defn pprint [x _]
  (pprint/pprint x))

(defn print-help [& _]
  (println (str/trim "
Usage: bbin <command>

  bbin install    Install a script
  bbin uninstall  Remove a script
  bbin ls         List installed scripts
  bbin bin        Display bbin bin folder")))

(defn now []
  (Date.))

(def ^:dynamic *bbin-root* (fs/expand-home "~/.bbin"))

(defn bbin-root [_]
  *bbin-root*)

(defn bin-dir [cli-opts]
  (fs/file (bbin-root cli-opts) "bin"))

(defn canonicalized-cli-opts [cli-opts]
  (merge cli-opts
         (when-let [v (:local/root cli-opts)]
           {:local/root (str (fs/canonicalize v {:nofollow-links true}))})))

(defn ensure-bbin-dirs [cli-opts]
  (fs/create-dirs (bin-dir cli-opts)))

(def reserved-script-names
  #{"alias"
    "autoload"
    "bg"
    "bind"
    "bindkey"
    "break"
    "builtin"
    "bye"
    "case"
    "cd"
    "chdir"
    "command"
    "compadd"
    "comparguments"
    "compcall"
    "compctl"
    "compdescribe"
    "compfiles"
    "compgroups"
    "complete"
    "compquote"
    "compset"
    "comptags"
    "comptry"
    "compvalues"
    "continue"
    "declare"
    "dirs"
    "disable"
    "disown"
    "echo"
    "echotc"
    "echoti"
    "emulate"
    "enable"
    "eval"
    "exec"
    "exit"
    "export"
    "false"
    "fc"
    "fg"
    "float"
    "for"
    "function"
    "functions"
    "getln"
    "getopts"
    "hash"
    "help"
    "history"
    "integer"
    "jobs"
    "kill"
    "let"
    "limit"
    "local"
    "logout"
    "ls"
    "man"
    "noglob"
    "popd"
    "print"
    "printf"
    "private"
    "pushd"
    "pushln"
    "pwd"
    "r"
    "rm"
    "read"
    "readonly"
    "rehash"
    "return"
    "sched"
    "select"
    "set"
    "setopt"
    "shift"
    "source"
    "sudo"
    "su"
    "suspend"
    "test"
    "times"
    "trap"
    "true"
    "ttyctl"
    "type"
    "typeset"
    "ulimit"
    "umask"
    "unalias"
    "unfunction"
    "unhash"
    "unlimit"
    "unset"
    "unsetopt"
    "vared"
    "variables"
    "wait"
    "whence"
    "where"
    "which"
    "zcompile"
    "zformat"
    "zle"
    "zmodload"
    "zparseopts"
    "zregexparse"
    "zstyle"})
