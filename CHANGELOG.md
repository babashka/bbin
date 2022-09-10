# Changelog

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
