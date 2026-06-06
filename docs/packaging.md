# Packaging for bbin

`bbin` can install scripts and projects from various sources. This guide explains how to package your repo for seamless installation with bbin.

## Installation Sources

bbin supports installing packages from:

- Git repositories
- HTTP URLs
- Maven artifacts
- Local filesystem

There is no central registry for `bbin` - it leverages existing infrastructure like GitHub and Maven repositories.

## Configuration

To properly package your repo for bbin, include a top-level `:bbin` map in `bb.edn` that specifies the binaries to install:

```clojure
{:paths ["src"]
 :bbin {:bin {cmd-name {:main-opts ["-m" "cmd.core"]}}}}
```

This configuration defines:
- The binary name (`neil` in this example)
- How to execute it (with the main namespace to run being `cmd.core`)
- That `bb.edn` is the source of truth when bbin generates the installed wrapper

The older `:bbin/bin` key is still accepted for compatibility, but it is deprecated. A project with both `deps.edn` and `bb.edn` only opts into `bb.edn` classpath behavior when the top-level `:bbin` map is present. If you are installing a repo you do not control, `bbin install ... --config bb.edn` forces that config file to be used by the generated wrapper.

## Installation Methods

- **For projects with `deps.edn`**: Install using `--git/url`, `--mvn/version`, or `--local/root` options
  - GitHub projects can use the shorthand syntax: `io.github.user/repo`

- **For standalone `.clj` scripts**: Host on GitHub and install from the raw HTTP URL
  - Scripts can be installed from any local path or HTTP URL ending in `.clj`
