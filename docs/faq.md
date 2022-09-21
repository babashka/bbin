# FAQ

## How do I get my software onto bbin?

`bbin` can install scripts and projects from the following sources:

- Git
- HTTP
- Maven
- Filesystem

There is no registry for `bbin` because it uses existing sources for sharing dependencies like GitHub and Maven.

A quick summary:

- **If your project has a `deps.edn` file, you can install it using the `--git/url`, `--mvn/version`, or `--local/root` options, which should be familiar if you've used the Clojure CLI tools**
    - If your project is on GitHub, you can omit `--git/url` using the `io.github.proj/repo` syntax
- **If you have a standalone `.clj` script, you can push it to GitHub and install it from the raw HTTP URL**
    - You can install scripts from any local filesystem path or HTTP URL ending in `.clj`
