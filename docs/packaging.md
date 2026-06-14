# Packaging

## Files

A **file** should be a Babashka-compatible file ending with `.clj` or `.bb`. When installing a file, bbin copies the script to the bin directory and prepends metadata to the beginning of the file as a comment (after the shebang).

```text
$ bbin install foo.clj
$ bbin install https://gist.githubusercontent.com/rads/da8ecbce63fe305f3520637810ff9506/raw/25e47ce2fb5f9a7f9d12a20423e801b64c20e787/portal.clj
```

## Directories

A **directory** should contain a Babashka-compatible project that uses either `deps.edn` or `bb.edn` for classpath resolution. The directory can provide scripts to install using the `:bbin/bin` key in `bb.edn`.

If `bb.edn` exists and there is no `deps.edn`, bbin will use `bb.edn` for classpath resolution. Otherwise, bbin will use `deps.edn` for classpath resolution, and classpath settings in `bb.edn` will be ignored. To force `bb.edn` for classpath resolution:

  - pass in `--bb-opts '["--config" "bb.edn"]'`
  - or set `:bbin/bb-opts ["--config" "bb.edn"]` in the project `bb.edn` file.

```text
$ bbin install ~/src/bbin --as bbin-dev
$ bbin install ~/src/my-cli --bb-opts '["--config" "bb.edn"]'
```

### Tool Mode

The `--tool` option can be used to install a project as a **tool**.

```text
$ bbin install io.github.borkdude/quickblog --tool --ns-default quickblog.api
```

## Git Repos

```text
$ bbin install io.github.babashka/neil
$ bbin install io.github.rads/watch --latest-sha
$ bbin install https://gist.github.com/1d7670142f8117fa78d7db40a9d6ee80.git
$ bbin install git@gist.github.com:1d7670142f8117fa78d7db40a9d6ee80.git
```

## JARs

```text
$ bbin install http-server.jar
$ bbin install https://github.com/babashka/http-server/releases/download/v0.1.11/http-server.jar
$ bbin install org.babashka/http-server --mvn/version 0.1.11
```

