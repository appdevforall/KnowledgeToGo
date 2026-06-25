# Knowledge To Go (K2Go)

> **Status:** Early development. The original Proof of Concept has been **validated**; current work focuses on refactoring K2Go into a well-architected, first-class application that runs, manages, and routes a localized "Internet-in-a-Box" (IIAB) environment directly on an Android device.

## Overview

Knowledge To Go (K2Go) acts as the **frontend system manager** for the educational ecosystem. It runs a full Debian/IIAB Linux server *inside the phone*, using **Termux** as the execution substrate and shipping native binaries (`proot`, `aria2c`, `tar`, `xz`, `rsync`) as `.so` libraries. It bridges the native Android OS and the Linux subsystem where the actual IIAB services and modules reside.

Instead of requiring users to type commands in a terminal, the app provides a clean, user-friendly graphical interface to deploy, monitor, and control the entire localized server stack.

## Key Features

* **System Dashboard:** Real-time monitoring of device resources including Storage, RAM, Virtual Swap, Battery, and more.
* **Embedded Termux Engine:** A vendored Termux engine (multi-session terminal + shell environment) provides the Linux substrate and runs/controls the backend server (PRoot/Debian).
* **Embedded Content Browser:** A native, lightweight integrated WebView that lets users access and interact with local educational platforms (Kiwix, Kolibri, etc.) distraction-free.
* **Master Watchdog Service:** A background service that protects the backend from Android's aggressive battery optimizations (Doze mode) and keeps Wi-Fi/Hotspot connections active.
* **Peer-to-peer Sharing:** Share content access over local Wi-Fi/Hotspot, or clone the entire server to another device.
* **Logging:** Built-in connection log manager to monitor watchdog and server activity.

## How to build

This project vendors a Termux fork as a Git submodule, so a plain `git clone` is not enough.

To clone the repository and fetch the submodule, use:

```
git clone --recurse-submodules https://github.com/appdevforall/KnowledgeToGo
```

If you already cloned without the `--recurse-submodules` flag and your build is failing, fetch the missing submodule by running the following command inside the project root:

```
git submodule update --init --recursive
```

After cloning, open the project in your IDE and sync the Gradle files. The NDK will compile the native components used by the Termux engine.

## Acknowledgments

K2Go is built on top of the **[Termux](https://github.com/termux/termux-app)** project. The Termux engine is vendored as a Git submodule (`controller/termux-core/termux-source`, tracking [`appdevforall/termux-app`](https://github.com/appdevforall/termux-app) — a fork of upstream Termux) so we can run a Linux environment and native tooling on Android while still receiving upstream updates.

Our deepest thanks to the **Termux team and contributors** for their foundational work, without which K2Go would not be possible.

## Disclaimer

**This software is under active development and is provided "as is", without warranty of any kind.** It is intended for research, testing, and educational development. Because it interacts heavily with Android's background services and networking stack, it may behave differently across various device manufacturers (OEMs). It is not yet guaranteed for stable, unattended production deployment. Use at your own risk.
