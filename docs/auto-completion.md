# Auto-Completion

`bbin` uses `babashka/cli` and therefore inherits its [completions](https://github.com/babashka/cli/blob/main/README.md#completions) support.

### ZSH

Add this to your zsh init file after `compinit`:

```shell
source <(bbin org.babashka.cli/completions snippet --shell zsh)
```

### BASH

Add this to your bash init file:

```shell
source <(bbin org.babashka.cli/completions snippet --shell bash)
```

## Other Shells
For more details and other shells, see [babashka cli docs](https://github.com/babashka/cli/blob/main/README.md#completions).
