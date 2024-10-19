# Changelog

[bbin](https://github.com/babashka/bbin): Install any Babashka script or project with one command

<!-- Notes for publishing: -->

<!-- - Update this changelog -->
<!-- - Change version in `deps.edn :aliases :neil :project :version` -->
<!-- - Run `bb gen-script` -->
<!-- - Create tag -->
<!-- - Go to https://github.com/babashka/homebrew-brew and run `bb update-bbin` and publish the new Formula -->

## 0.2.4

- [Fix #88: NPE when using `bbin ls` in dirs with zero-length files](https://github.com/babashka/bbin/issues/88)

## 0.2.3

- [Fix error in compiled script when installing from Homebrew (again)](https://github.com/babashka/bbin/commit/f0a3096a1e57408af77eed35f86a3d71cccccb07)

## 0.2.2

- [Fix #62: bbin ls is unnecessarily slow](https://github.com/babashka/bbin/issues/62)
- [Fix #72: bbin install [LOCAL-FILE] should not be restricted to files with the .clj extension](https://github.com/babashka/bbin/issues/72)

## 0.2.1

- [Fix error in compiled script when installing from Homebrew](https://github.com/babashka/bbin/commit/ba1749a3308744c9dcecc1f032214aeb109bb073)

## 0.2.0

**BREAKING CHANGES:**

- `bbin` now follows the [XDG Base Directory Specification](https://specifications.freedesktop.org/basedir-spec/basedir-spec-latest.html).
    - The `BABASHKA_BBIN_FLAG_XDG` flag is no longer used.
    - If you're still using `~/.babashka/bbin/bin`, `bbin` will print a warning.
        - To remove this warning, run `bbin migrate` for instructions on how to
            - run an automatic migration
            - migrate manually
            - revert to existing paths
- `bbin ls` and `bbin install` now print human-readable text by default.
    - The `BABASHKA_BBIN_FLAG_PRETTY_OUTPUT` flag is no longer used.
    - Pass in the `--edn` option to revert to the `0.1.x` behavior.

**Changed paths:**

- New:
    - Scripts: `~/.local/bin`
    - Cached JARs: `~/.cache/babashka/bbin/jars`
- Old:
    - Scripts: `~/.babashka/bbin/bin`
    - Cached JARs: `~/.babashka/bbin/jars`

**Fixed issues:**

- [Fix #35: Use Freedesktop specification for default paths](https://github.com/babashka/bbin/issues/35)
- [Fix #53: bbin should print human-readable text first and edn as an optional format](https://github.com/babashka/bbin/issues/53)
- [Fix #65: BUG: uninstall not working for some scripts](https://github.com/babashka/bbin/issues/65)

## 0.1.13

- [Fix #61: Disable `*print-namespace-maps*` when printing EDN](https://github.com/babashka/bbin/issues/61) ([@eval](http://github.com/eval))
- [Upcoming fixes for #53: bbin should print human-readable text first and edn as an optional format](https://github.com/babashka/bbin/issues/53)
  - [#54: `bbin ls` prints human readable text](https://github.com/babashka/bbin/pull/54) ([@eval](http://github.com/eval))
      - The new output format is currently disabled by default in `0.1.x` releases.
      - Set `BABASHKA_BBIN_FLAG_PRETTY_OUTPUT=true` to enable the new behavior. See the PR for [updated docs](https://github.com/eval/bbin/blob/afd33ed720f84dccae907f1b59d51c19536448e5/README.md#bbin-ls).
      - Since changing the default output format is a breaking change, the flag will be removed in an upcoming `0.2.0` release.
      - We're adding an `--edn` option to existing `bbin` commands to support raw data as output.

## 0.1.12

- [Fix #60: `XDG_DATA_HOME` does not work](https://github.com/babashka/bbin/issues/60)

## 0.1.11

- [Fix #55: Error when installing from a Git repo with no tags](https://github.com/babashka/bbin/issues/55)
    - [infer: Use latest SHA when no tag is found](https://github.com/rads/deps-info/commit/6d323ba978502635c4cf2f8f1da4ff04f48240ca)

## 0.1.10

- [Fix #57: escaping issue with local/root install on Windows](https://github.com/babashka/bbin/issues/57)
- [Fix #52: git url install does not handle a dot in the name](https://github.com/babashka/bbin/issues/52)

## 0.1.9

- [Bump `deps-info` version to `0.1.0`](https://github.com/babashka/bbin/commit/a1291ab9a61996bcafb135ebadc775a3a07f92b0)
    - Fixes an error when encountering Git tags named without a `v` prefix (thanks [@eval](https://github.com/eval))
- [Upgrade dependency versions](https://github.com/babashka/bbin/commit/178fadcc4cfd0e239b279651a4dcfe5e85ab9633)

## 0.1.8

- [Fix missing `babashka.bbin.specs` ns in generated script](https://github.com/babashka/bbin/commit/9a67b1ed2e7c90fc4eedf2710d9ee8df34ea896b)

## 0.1.7

- [Validate `:bbin/bin` config](https://github.com/babashka/bbin/commit/b6fe7dc6ce2bc4ee56205e181e71634d503cca02)
- [Add support for Git URLs without explicit lib name](https://github.com/babashka/bbin/commit/2ef56e19109fab3e8150819a0eaa8f63298da43b)
- [Run jars without process/exec](https://github.com/babashka/bbin/commit/af7d140d4dfb109ca1930e1f77115289ec067967) (thanks [@jeroenvandijk](https://github.com/jeroenvandijk)!)

## 0.1.6

- [Bump `io.github.rads/deps-info` to `v0.0.11`](https://github.com/babashka/bbin/commit/82dbee1e0f472cf4f6dda82858c848ed5b4a0709)
    - New features:
        - [Support inference for private Git repos](https://github.com/babashka/bbin/issues/48)
        - [Support all possible "lib to url" cases](https://github.com/babashka/bbin/issues/3)

## 0.1.5

- [Support installing script files without shebang](https://github.com/babashka/bbin/commit/d4103e26db3c5c94f9ed7414c1d5fcd988b40e34)

## 0.1.4

- [Replace `babashka.curl` with `org.httpkit.client`](https://github.com/babashka/bbin/commit/55f942bfccb8e3095ba715e242c99a1c030cf0e9)
- [Add opt-in flag for "Use Freedesktop specification for default paths"](https://github.com/babashka/bbin/commit/6fde1b1dbfaef3063eb1eba4899a730bf703c792)
    - We're currently working on making `bbin` follow the Freedesktop spec more closely, which means we need to change the default bin path ([\#35](https://github.com/babashka/bbin/issues/35))
    - In a future `0.2.0` release, `bbin` will change its default bin path from `~/.babashka/bbin/bin` to `~/.local/bin`
    - For versions `>=0.1.4`, the new default behavior can be enabled by setting an env variable:
        ```
        $ bbin bin
        /Users/rads/.babashka/bbin/bin
      
        $ BABASHKA_BBIN_FLAG_XDG=true bbin bin
        /Users/rads/.local/bin
        ```
    - The flag will not have any effect when used with `0.2.0`. It's only for previewing the upcoming changes
- [Fix "local installs without aliases throw exception on Windows"](https://github.com/babashka/bbin/commit/748722178824d7e2ff76544bfc7c23def8ce708c) (thanks [@bobisageek](https://github.com/bobisageek)!)

## 0.1.3

- [Fix script args being ignored when installing JARs](https://github.com/babashka/bbin/commit/ac85b8f984c8a30683c219d8d0faa32ef91e93e2)

## 0.1.2

- [Add support for HTTP and local JARs](https://github.com/babashka/bbin/commit/58d48df19969aaf5e7ff8ea0b87330e2d1e67568)
- [Add support for installing local directories](https://github.com/babashka/bbin/commit/268de01de73f26e8256498d33f508c61a3c5663d)

## 0.1.1

- [Add support for installing local files](https://github.com/babashka/bbin/commit/675c5826a633e10a1e997870dcf0cb28867c411f)
- [Coerce script names to snake-case](https://github.com/babashka/bbin/commit/7235b2c291400f7074232c2ef1230fe3e9652f23)

## 0.1.0

- [Remove alpha status warning](https://github.com/babashka/bbin/commit/ea7dc1999a0e928ce749520d64d6a833e8bba686)
- [Add docs for all supported options](https://github.com/babashka/bbin/commit/add4e2b2613a7503e49110ca711f7815734e9aed)
- [Add support for overriding bbin root via env variables](https://github.com/babashka/bbin/commit/a24775cfd8637541caee42d320be4a3882bf5219)

## 0.0.12

- [Change root dir from `~/.bbin` to `~/.babashka/bbin`](https://github.com/babashka/bbin/commit/99a5d2684f4e979ff8f183a2ce8088f3df26b405)
- [Improve script readability](https://github.com/babashka/bbin/commit/04c2e1851eae335a8a1b57118b7a4af78c3f4b1c)
- [Remove bash scripts](https://github.com/babashka/bbin/commit/f889f1a53620f87ac42af54015d85ecf0f70c7d0)
- [Do not stringify args (#24)](https://github.com/babashka/bbin/commit/e5b8daf6b71e5e51e8fb948ba677eaa748416218) (thanks [@borkdude](https://github.com/borkdude)!)

## 0.0.11

- [Windows Support](https://github.com/babashka/bbin/commit/378e7e7728d19b7800798afe73f2d1d2e4831273) (thanks [@bobisageek](https://github.com/bobisageek)!)
- [Bump `:min-bb-version` to `0.9.162`](https://github.com/babashka/bbin/commit/52bc0d053abef6c0a7744d0eb2045096ad0dc533)
- [Add `bbin version`](https://github.com/babashka/bbin/commit/7e5bef4d077afc1f20a5aa288f317ffe1bb1a8e1)
- [Add `bbin --version`](https://github.com/babashka/bbin/commit/6e066bd2005d930d5f5171e8a678beb16bce8546)

## 0.0.10

- [Remove `bbin trust` and `bbin revoke`](https://github.com/babashka/bbin/commit/6c1b44cd5d09415779557084d63ca4af325acae1)
- [Remove script name checks](https://github.com/babashka/bbin/commit/3c0730011b1c74514600beb476fa7713d2c30671)

## 0.0.9

- [Fix `bbin commands`](https://github.com/babashka/bbin/commit/c341c270ea2d5744c156fc719a6579f6c48549d2)
- [Check for reserved script names](https://github.com/babashka/bbin/commit/52887c3f9948a8b4e466766dfd06d3a35d443277)
- [Support Git and local installs when `bb.edn` file is missing](https://github.com/babashka/bbin/commit/a0bc556fc44c2d70e83bc0c387a3f3c716c25743)

## 0.0.8

- [Restrict `bbin` alias to installs from official repo](https://github.com/babashka/bbin/commit/d343c37d7d045f294cff928319ffe7f9fa39617a)
- [Add info message about password request](https://github.com/babashka/bbin/commit/28911b65a21ac96b66fa47e10b16ffdf5680e4ab)

## 0.0.7

- [Fix `sudo` being required in `bbin install` command](https://github.com/babashka/bbin/commit/8fb8a8d2b8186ab0e22cde978cfeae3ce7ce4d1d)

## 0.0.6

- [Use sudo user and group instead of hard-coded `root:wheel`](https://github.com/babashka/bbin/commit/e3d77ac6e26b9676bf898e60142499c9738c1877)
- [Fix missing `util` ns in `gen-script`](https://github.com/babashka/bbin/commit/96c54c3e7ad3ab3d4af9cff0d830fc7c5f0ca5a8)

## 0.0.5

- [Require privileged access for `~/.bbin/trust`](https://github.com/babashka/bbin/commit/ae6ca2fb2ac5a8c763ebb475151b5eddd4426809)

## 0.0.4

- [Rename to `babashka.bbin`](https://github.com/babashka/bbin/commit/6322b4d2bbb6e44589875057123c8e59cc5dfe6d)

## 0.0.3

- [Add `bbin trust` and `bbin revoke`](https://github.com/babashka/bbin/commit/3aa49be0a35bd8f77a72b26ffc4ac452bec75684)

## 0.0.2

- [Add `:min-bb-version` to `bb.edn`](https://github.com/babashka/bbin/commit/af09bebea56720118ca80aacb0fedcd96acc9624)
- [Add support for `:mvn/version` coordinates](https://github.com/babashka/bbin/commit/a30f1747b2147616f949b947d01b6023e56ce477)
- [Use `:bbin/url` instead of `:http/url`](https://github.com/babashka/bbin/commit/c09a955c473a8838de79509190d6bc088931afba)
- [Use `deps.edn` for deps instead of `bb.edn`](https://github.com/babashka/bbin/commit/6295ae344e455b0e85fbe7da96ddda9acf7fdf89)

## 0.0.1

- First release
