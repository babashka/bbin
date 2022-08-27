# Design Docs

## Goals

- Secure by default
- Provide a CLI that's easy to remember and type
- Allow project authors to provide default script names
- When possible, follow existing patterns from `tools.deps` for managing dependencies

## Notes

### Security

#### Problem

- **`bbin` makes it easy to download remote code and execute it locally.**
    - This is a potential security hazard.
- **There is no equivalent to `deno`'s sandbox feature in Babashka.**
    - How do you avoid accidentally installing a malicious script?
- **There is no equivalent to `npm`'s audit feature in Babashka.**
    - How do you make sure your tools are not out-of-date?

#### Solution

- **We only install inferred dependencies from trusted sources.**
    - **Trusted Organization or User**
        - `babashka.bbin.trust/allow-list`
    - **Trusted Domain**
        - Maven
        - Clojars
        - GitHub
        - BitBucket
        - GitLab
        - Beanstalk
        - Sourcehut
- **We can install dependencies from untrusted sources, but only with an explicit SHA.**
    - For a Git repo, it's a commit SHA.
    - For a file, it's a SHA256 hash of the file contents.
- **All scripts installed via `bbin install` contain metadata that can be used to find updates.**
