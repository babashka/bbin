# Installation

- [Homebrew (Linux and macOS)](#homebrew-linux-and-macos)
- [Scoop (Windows)](#scoop-windows)
- [Manual (Linux and macOS)](#manual-linux-and-macos)
- [Manual (Windows)](#manual-windows)

## Homebrew (Linux and macOS)

**1. Install via `brew`:**
```shell
brew install babashka/brew/bbin
```

**2. Add `~/.babashka/bbin/bin` to `PATH`:**
```shell
echo 'export PATH="$PATH:$HOME/.babashka/bbin/bin"' >> ~/.$(basename $SHELL)rc && exec $SHELL
```

## Scoop (Windows)

**1. Install `bbin` CLI:**
```shell
scoop bucket add scoop-clojure https://github.com/littleli/scoop-clojure
scoop install bbin
```

**2. Add `%HOMEDRIVE%%HOMEPATH%\.babashka\bbin\bin` to `Path` environment variable**

1. Search for `View advanced system settings` in the Start Menu
2. Click on the `Environment Variables...` button
3. Double-click on the `Path` variable to edit
4. When the edit dialog opens, click on `New`
5. Paste `%HOMEDRIVE%%HOMEPATH%\.babashka\bbin\bin` into the text field
6. Click `OK` on all remaining dialogs to save the changes


## Manual (Linux and macOS)

**1. Install `bbin` CLI:**
```shell
mkdir -p ~/.babashka/bbin/bin && curl -o- -L https://raw.githubusercontent.com/babashka/bbin/v{{version}}/bbin > ~/.babashka/bbin/bin/bbin && chmod +x ~/.babashka/bbin/bin/bbin
```

**2. Add `~/.babashka/bbin/bin` to `PATH`:**
```shell
# Use this for ZSH
echo 'export PATH="$PATH:$HOME/.babashka/bbin/bin"' >> ~/.zshrc && exec /bin/zsh

# Use this for Bash
echo 'export PATH="$PATH:$HOME/.babashka/bbin/bin"' >> ~/.bashrc && exec /bin/bash
```

## Manual (Windows)

**1. Open Windows Powershell and run the following command to install the `bbin` CLI (including `.bat` wrapper):**
```powershell
New-Item -ItemType Directory -Force -Path $Env:HOMEDRIVE$Env:HOMEPATH\.bbin\bin; Invoke-WebRequest -Uri https://raw.githubusercontent.com/babashka/bbin/v{{version}}/bbin -OutFile $Env:HOMEDRIVE$Env:HOMEPATH\.bbin\bin\bbin; Invoke-WebRequest -Uri https://raw.githubusercontent.com/babashka/bbin/v{{version}}/bbin.bat -OutFile $Env:HOMEDRIVE$Env:HOMEPATH\.bbin\bin\bbin.bat
```

**2. Add `%HOMEDRIVE%%HOMEPATH%\.bbin\bin` to `Path` environment variable**

1. Search for `View advanced system settings` in the Start Menu
2. Click on the `Environment Variables...` button
3. Double-click on the `Path` variable to edit
4. When the edit dialog opens, click on `New`
5. Paste `%HOMEDRIVE%%HOMEPATH%\.bbin\bin` into the text field
6. Click `OK` on all remaining dialogs to save the changes
