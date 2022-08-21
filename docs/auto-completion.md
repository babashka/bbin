# Auto-Completion

### ZSH

Add this to `~/.zshrc`:

```shell
function _bbin() { _arguments "1: :($(bbin commands))" }
compdef _bbin bbin
```
