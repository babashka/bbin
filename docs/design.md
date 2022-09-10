# Design Docs

## Goals

- Provide a CLI that's easy to remember and type
- Allow project authors to provide default script names
- When possible, follow existing patterns from `tools.deps` for managing dependencies


## Basic Flows
### `install`
- Determine the type of install (http, maven, etc)
- Build the script from the corresponding template
  - Selmer template rendering of a bash script (or a babashka script on Windows)
  - Script also includes commented metadata for use by `ls`
- Write script out to bbin's bin directory (~/.bbin/bin)
  - On Windows, a batch file is also written to provide a command-line executable

### `uninstall`
- Delete the script created by install
  - On Windows, also delete the batch file 

### `ls`
- Find all files in the bin directory that contain metadata
- pprint a map of script -> metadata
