# bbin

**Install any Babashka script or project with one command.**

```
$ bbin install io.github.babashka/neil
{:lib io.github.babashka/neil,
 :coords
 {:git/url "https://github.com/babashka/neil",
  :git/tag "v0.1.45",
  :git/sha "0474d4cb5cfb0207265a4508a0e82ae7a293ab61"}}

$ neil --version
neil 0.1.45

$ bbin install https://gist.githubusercontent.com/rads/da8ecbce63fe305f3520637810ff9506/raw/25e47ce2fb5f9a7f9d12a20423e801b64c20e787/portal.clj
{:coords {:bbin/url "https://gist.githubusercontent.com/rads/da8ecbce63fe305f3520637810ff9506/raw/25e47ce2fb5f9a7f9d12a20423e801b64c20e787/portal.clj"}}

# Open a Portal window with all installed scripts
$ portal <(bbin ls)
```

📦 See the [**Scripts and Projects**](https://github.com/babashka/bbin/wiki/Scripts-and-Projects) wiki page for a list of CLI tools from the community. This list is just a starting point — any existing Babashka script or project can be installed out-of-the-box!

📚 See the [**Usage**](#usage) and [**CLI**](#cli) docs for more examples of what `bbin` can do.

## Table of Contents

- [Installation](#installation)
- [Usage](#usage)
- [Docs](#docs)
- [CLI](#cli)
- [Contributing](#contributing)
- [License](#license)

## Installation

### Homebrew (Linux and macOS)

**1. Install via `brew`:**
```shell
brew install babashka/brew/bbin
```

**2. Add `~/.babashka/bbin/bin` to `PATH`:**
```shell
echo 'export PATH="$PATH:$HOME/.babashka/bbin/bin"' >> ~/.$(basename $SHELL)rc && exec $SHELL
```

### Scoop (Windows)

**1. Install `bbin` CLI:**
```shell
scoop bucket add scoop-clojure https://github.com/littleli/scoop-clojure
scoop install bbin
```

The Scoop package will automatically update your `Path` with `%HOMEDRIVE%%HOMEPATH%\.babashka\bbin\bin`, but you will have to restart your terminal for this to take effect.

### Manual (Linux, macOS, and Windows)

[Click here for manual installation instructions.](docs/installation.md#manual-linux-and-macos)

## Usage

```
# Install a script from a qualified lib name
$ bbin install io.github.babashka/neil
$ bbin install io.github.rads/watch --latest-sha
$ bbin install org.babashka/http-server --mvn/version 0.1.11

# Install an auto-generated CLI from a namespace of functions
$ bbin install io.github.borkdude/quickblog --tool --ns-default quickblog.api

# Install a script from a URL
$ bbin install https://gist.githubusercontent.com/rads/da8ecbce63fe305f3520637810ff9506/raw/25e47ce2fb5f9a7f9d12a20423e801b64c20e787/portal.clj
$ bbin install https://github.com/babashka/http-server/releases/download/v0.1.11/http-server.jar

# Install a script from a Git repo
$ bbin install https://gist.github.com/1d7670142f8117fa78d7db40a9d6ee80.git
$ bbin install git@gist.github.com:1d7670142f8117fa78d7db40a9d6ee80.git

# Install a script from a local file
$ bbin install foo.clj
$ bbin install http-server.jar

# Install a script from a local root (with no lib name)
$ git clone https://github.com/babashka/bbin.git ~/src/bbin
$ bbin install ~/src/bbin --as bbin-dev

# Install a script from a local root (with lib name)
$ bbin install io.github.babashka/bbin --local/root ~/src/bbin --as bbin-dev

# Remove a script
$ bbin uninstall watch

# Show installed scripts
$ bbin ls

# Show the bin path
$ bbin bin
```

## Docs

- [CLI Docs](#cli)
- [FAQ](docs/faq.md)
- [Design Docs](docs/design.md)
- [Community Scripts and Projects](https://github.com/babashka/bbin/wiki/Scripts-and-Projects)
- [Auto-Completion](docs/auto-completion.md)

## CLI

- [`bbin install [script]`](#bbin-install-script)
- [`bbin uninstall [script]`](#bbin-uninstall-script)
- [`bbin ls`](#bbin-ls)
- [`bbin bin`](#bbin-bin)
- [`bbin version`](#bbin-version)
- [`bbin help`](#bbin-help)

---

### `bbin install [script]`

**Install a script**

- By default, scripts will be installed to `~/.babashka/bbin/bin`
    - If `$BABASHKA_BBIN_DIR` is set, then use `$BABASHKA_BBIN_DIR` (explicit override)
    - If `$XDG_DATA_HOME` is set, then use `$XDG_DATA_HOME/.babashka/bbin/bin` (Freedesktop conventions)
- Each bin script is a self-contained shell script that fetches deps and invokes `bb` with the correct arguments.
- The bin scripts can be configured using the CLI options or the `:bbin/bin` key in `bb.edn`
- [See the FAQ for additional info on setting up your code to work with bbin](docs/faq.md#how-do-i-get-my-software-onto-bbin)

**Example `bb.edn` Config:**

```clojure
{:bbin/bin {neil {:main-opts ["-f" "neil"]}}}
```

**Supported Options:**

*Note:* `bbin` will throw an error if conflicting options are provided, such as using both `--git/sha` and `--mvn/version` at the same time.

If no `--git/tag` or `--git/sha` is provided, the latest tag from the Git repo will be used. If no tags exist, the latest SHA will be used.

- `--as`
    - The name of the script to be saved in the `bbin bin` path
- `--git/sha`
    - The SHA for a Git repo
- `--git/tag`
    - The tag for a Git repo
- `--git/url`
    - The URL for a Git repo
- `--latest-sha`
    - If provided, find the latest SHA from the Git repo
- `--local/root`
    - The path of a local directory containing a `deps.edn` file
- `--main-opts`
    - The provided options (EDN format) will be passed to the `bb` command-line when the installed script is run
    - By default, `--main-opts` will be set to `["-m" ...]`, inferring the main function from the lib name
    - For example, if you provide a lib name like `io.github.rads/watch`, `bbin` will infer `rads.watch/-main`
    - Project authors can provide a default in the `:bbin/bin` key in `bb.edn`
- `--mvn/version`
    - The version of a Maven dependency
- `--ns-default`
    - The namespace to use to find functions (tool mode only)
    - Project authors can provide a default in the `:bbin/bin` key in `bb.edn`
- `--tool`
    - If this option is provided, the script will be installed using **tool mode**
    - When enabled, the installed script acts as an entry point for functions in a namespace, similar to `clj -T`
    - If no function is provided, the installed script will infer a help message based on the function docstrings
---

### `bbin uninstall [script]`

**Remove a script**

---

### `bbin ls`

**List installed scripts**

---

### `bbin bin`

**Display bbin bin folder**

- The default folder is `~/.babashka/bbin/bin`

---

### `bbin version`

**Display bbin version**

---

### `bbin help`

**Display bbin help**

---

## Contributing

If you'd like to contribute to `bbin`, you're welcome to create [issues for ideas, feature requests, and bug reports](https://github.com/babashka/bbin/issues).

## License

`bbin` is released under the [MIT License](LICENSE).
