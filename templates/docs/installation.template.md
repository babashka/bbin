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

**2. Add `~/.local/bin` to `PATH`:**
```shell
echo 'export PATH="$PATH:$HOME/.local/bin"' >> ~/.$(basename $SHELL)rc && exec $SHELL
```

## Scoop (Windows)

**1. Install `bbin` CLI:**
```shell
scoop bucket add scoop-clojure https://github.com/littleli/scoop-clojure
scoop install bbin
```

The Scoop package will automatically update your `Path` with `%HOMEDRIVE%%HOMEPATH%\.local\bin`, but you will have to restart your terminal for this to take effect.

## Manual (Linux and macOS)

**1. Install `bbin` CLI:**
```shell
mkdir -p ~/.local/bin && curl -o- -L https://raw.githubusercontent.com/babashka/bbin/v{{version}}/bbin > ~/.local/bin/bbin && chmod +x ~/.local/bin/bbin
```

**2. Add `~/.local/bin` to `PATH`:**
```shell
echo 'export PATH="$PATH:$HOME/.local/bin"' >> ~/.$(basename $SHELL)rc && exec $SHELL
```

## Manual (Windows)

**1. Open Windows Powershell and run the following commands to install the `bbin` CLI (including `.bat` wrapper):**
```powershell
New-Item -ItemType Directory -Force -Path $Env:HOMEDRIVE$Env:HOMEPATH\.local\bin
Invoke-WebRequest -Uri https://raw.githubusercontent.com/babashka/bbin/v{{version}}/bbin -OutFile $Env:HOMEDRIVE$Env:HOMEPATH\.local\bin\bbin
Invoke-WebRequest -Uri https://raw.githubusercontent.com/babashka/bbin/v{{version}}/bbin.bat -OutFile $Env:HOMEDRIVE$Env:HOMEPATH\.local\bin\bbin.bat
```

**2. Add `%HOMEDRIVE%%HOMEPATH%\.local\bin` to `Path` environment variable**

1. Search for `View advanced system settings` in the Start Menu
2. Click on the `Environment Variables...` button
3. Double-click on the `Path` variable to edit
4. When the edit dialog opens, click on `New`
5. Paste `%HOMEDRIVE%%HOMEPATH%\.local\bin` into the text field
6. Click `OK` on all remaining dialogs to save the changes
