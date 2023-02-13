# Setup Engine - macOS

(Setup instructions for the editor [here](/editor/README.md)).

## Required Software

### Required Software - Java JDK 11

You need Java JDK 11 installed to build the tools. Download and install the latest JDK 11 release from either of these locations:

* [Adoptium/Temurin](https://github.com/adoptium/temurin11-binaries/releases) - The Adoptium Working Group promotes and supports high-quality runtimes and associated technology for use across the Java ecosystem
* [Microsoft OpenJDK builds](https://docs.microsoft.com/en-us/java/openjdk/download#openjdk-11) - The Microsoft Build of OpenJDK is a no-cost distribution of OpenJDK that's open source and available for free for anyone to deploy anywhere

When Java is installed you may also add need to add java to your PATH and export JAVA_HOME:

```sh
> nano ~/.bashrc

export JAVA_HOME=<JAVA_INSTALL_PATH>
export PATH=$JAVA_HOME/bin:$PATH
```

Verify that Java is installed and working:

```sh
> javac -version
```


### Required Software - Python 3

You need a 64 bit Python 3 version (x86_64) to build the engine and tools. The latest tested on all platforms is Python 3.10.5.

* Install via `https://www.python.org/downloads/release/python-3105/`

Once Python has been installed you also need install certificates (for https requests):

```sh
> /Applications/Python\ 3.10/Install\ Certificates.command
```


### Required Software - macOS

You need the `dos2unix` command line tool to convert line endings of certain source files when building files in `share/ext`. You can install `dos2unix` using [Brew](https://brew.sh/):

```sh
> brew install dos2unix
```

---

## Optional Software

It is recommended but not required that you install the following software:

* **wget** + **curl** - for downloading packages
* **7z** - for extracting packages (archives and binaries)
* **ccache** - for faster compilations of source code
* **cmake** for easier building of external projects
* **patch** for easier patching on windows (when building external projects)
* **ripgrep** for faster search

Quick and easy install:

```sh
> brew install wget curl p7zip ccache ripgrep
```

Configure `ccache` by running ([source](https://ccache.samba.org/manual.html))

```sh
> /usr/local/bin/ccache --max-size=5G
```

---

## Optional Setup

### Optional Setup - Command Prompt

It's useful to modify your command prompt to show the status of the repo you're in.
E.g. it makes it easier to keep the git branches apart.

You do this by editing the `PS1` variable. Put it in the recommended config for your system (e.g. `.profile` or `.bashrc`)
Here's a very small improvement on the default prompt, whic shows you the time of the last command, as well as the current git branch name and its status:

```sh
git_branch() {
    git branch 2> /dev/null | sed -e '/^[^*]/d' -e 's/* \(.*\)/(\1)/'
}
acolor() {
  [[ -n $(git status --porcelain=v2 2>/dev/null) ]] && echo 31 || echo 33
}
export PS1='\t \[\033[32m\]\w\[\033[$(acolor)m\] $(git_branch)\[\033[00m\] $ '
```
