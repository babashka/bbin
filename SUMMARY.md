# `refactor` branch — Release Summary (targeting `0.3.0`)

## TL;DR
The branch is a **ground-up rewrite of the install engine**: the old
protocol/`defrecord`-per-source-type design is replaced by a single, data-driven
**pipeline** (`parse → load → analyze → select → generate → write`). Functionally
it closes several long-standing install-ergonomics issues, but it is **not
release-ready as-is** — `upgrade` is stubbed out, the docs were accidentally
reverted, and the branch predates the current `main` tip (missing the #104 fix
shipped in 0.2.5).

## What changed (architecturally)

**Old design (deleted):** one `defrecord` implementing the `Script` protocol per
source type — `git_dir.clj`, `http_file.clj`, `http_jar.clj`, `local_dir.clj`,
`local_file.clj`, `local_jar.clj`, `maven_jar.clj` are **all removed**, along with
the dispatch `case` in `scripts.clj`.

**New design:** `src/babashka/bbin/scripts/install.clj` (+443 lines) — a linear,
namespaced-keyword pipeline:

```
install-steps = [parse  load!  analyze  select  generate  write!]
```

Each step is a pure-ish function threading a `params` map keyed by `::input/*`,
`::parse/*`, `::load/*`, `::analyze/*`, `::select/*`, `::generate/*`,
`::write/*`. Notable side effects:

- `install` now runs inside `fs/with-temp-dir`, so temp dirs are cleaned up on exit.
- A `tap>`/`add-tap` debug log (`debug.log`) is wired in, and CI uploads it on
  failure (Linux/macOS/Windows artifacts).

**Supporting changes:**

- `deps.clj` → **`deps.cljc`** (now `:bb`/`:clj` reader-conditional; `add-libs`
  resolves `babashka.deps/add-deps` vs `clojure.repl.deps/add-libs`). New
  `git-repo-url?`/`git-http-url?` predicates, including a regex that matches bare
  `https://github.com/foo/bar`.
- `git.clj`: provider lookup inlined.
- `common.clj`: many fns de-privatized for reuse by the new pipeline; new
  generated-script template `git-or-local-template-str-with-bb-edn` that runs
  `bb --config <root>/bb.edn` **instead of embedding `local/deps`**.
- Build: `deps.edn` bumped to `0.3.0-SNAPSHOT`, added `:dev`/`:test`/`:nrepl`
  aliases; `bb.edn` lint task rewritten (clj-kondo dependency-classpath caching);
  fswatcher pod `0.0.3 → 0.0.7`; `gen_script.clj` now resolves `.cljc` as well as
  `.clj`.

## Issues addressed (cross-referenced with `issues.json`)

CHANGELOG explicitly claims these for `0.3.0`:

| Issue | State in dump | Addressed by | Notes |
|------|------|------|------|
| **#64** Remove `local/deps` in generated scripts | OPEN | new `git-or-local` template uses `bb --config .../bb.edn` | ✅ for normal installs. ⚠️ the `--tool` template (`local-dir-tool-template-str`) **still spits `{:deps {local/deps …}}`** — partial fix. |
| **#78** Don't require `deps.edn` for local install | OPEN | `analyze-dir` reads `bb.edn`'s `:bbin/bin`; no `deps.edn` required | ✅ |
| **#86** Temp files not cleaned up | OPEN | `fs/with-temp-dir` wrapping `install` | ✅ |
| **#100** Support `bbin install https://github.com/foo/bar` | OPEN | `parse-deps` appends `.git`; `git-http-url?` matches bare GitHub URLs | ✅ |

Also effectively resolved by the rewrite (not listed in CHANGELOG, worth adding):

- **#72 / #79** install local files not ending in `.clj` (`.bb` etc.) —
  analyze/generate now key off `fs/regular-file?` + a `\.(clj|cljc|bb|jar)$` strip
  rather than a `.clj` gate. ✅
- **#95** don't require a manifest file — install proceeds from an empty/absent
  `bb.edn`. ✅
- **#98** `main-opts` limited to two values — `process-main-opts` now threads the
  full vector into the template (`script-main-opts`). ✅

## ⚠️ Blockers / regressions before this can ship as `0.3.0`

1. **`upgrade` is disabled.** In `scripts.clj`, `upgrade` is reduced to
   `ensure-bbin-dirs` with the real body `#_`-commented out. This is a feature
   regression — must be reimplemented on the new pipeline before release.
2. **Branch is behind `main`.** Merge-base is `e0b7719` (v0.2.5). Three commits on
   `main` are absent, most importantly **#104** ("better error on provider lookup
   failure", PR #105). The refactor's `git.clj` actually *removes* the guarded
   provider lookup, so the #104 NPE could regress. #104 is CLOSED in the dump but
   its fix is **not** in this branch.
3. **Docs accidentally reverted.** `docs/installation.md` was changed
   `v0.2.5 → v0.2.4`, and `README.md` gained a stray `-To install…` typo. These
   look like unintended WIP artifacts.
4. **Debug scaffolding left in.** `add-tap` + unconditional `debug.log` append in
   `install.clj` (and the matching CI upload steps) should be gated or removed for
   a release.

## Recommended release-notes draft (after fixing blockers)

> **0.3.0**
> - Rewrote the install engine as a composable pipeline.
> - Install local scripts with any extension, incl. `.bb` (#72, #79).
> - No `deps.edn`/manifest required for local & git installs (#78, #95).
> - Support `bbin install https://github.com/foo/bar` (#100).
> - Generated scripts no longer use `local/deps` (#64).
> - Honor full `:main-opts` vectors (#98).
> - Temp files cleaned up on exit (#86).
