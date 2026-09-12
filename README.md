# AVSCode — VS Code for Android

[![AVSCode CI & Signed Release Build](https://github.com/InfidelRahul/AVSCode/actions/workflows/ci.yml/badge.svg)](https://github.com/InfidelRahul/AVSCode/actions/workflows/ci.yml)
[![Platform](https://img.shields.io/badge/Platform-Android%209.0%2B%20(API%2028--36)-green.svg)](https://developer.android.com)
[![Architecture](https://img.shields.io/badge/Architecture-ARM64%20(16KB%20Aligned)-blue.svg)](https://developer.android.com/guide/practices/page-sizes)
[![License](https://img.shields.io/badge/License-Apache%202.0-orange.svg)](LICENSE)

**AVSCode** is a native Android application that runs a complete, persistent Visual Studio Code environment locally on Android devices without requiring root access.

The application embeds an Ubuntu ARM64 Linux userspace powered by **LinuxDroid PRoot**, manages the official **Microsoft Visual Studio Code CLI (`code serve-web`)** on local loopback, provides a dedicated **Android ↔ Linux Authentication Bridge**, and renders a fully responsive, hardware-accelerated **Android WebView** user interface with edge-to-edge safe area support.

---

## Highlights

- **No Root Required**: Executes fully within user application sandbox using PRoot syscall emulation.
- **100% Local & Offline**: Serves VS Code directly over `http://127.0.0.1:<dynamic-port>` without external cloud or tunnel dependencies.
- **Dedicated Android ↔ Linux Auth Bridge**: Lightweight loopback IPC endpoint enabling guest tools to trigger Android browser auth flows and receive single-use, session-scoped callbacks.
- **Edge-to-Edge Safe Area Insets**: Uses `WindowInsetsCompat` to adapt dynamically to status bars, display cutouts/notches, gesture navigation bars, and soft keyboards across all Android versions (including Android 15 & 16).
- **Streamlined UI Experience**: Post-setup landing view is the VS Code editor directly, with the Linux terminal retained as a toggleable diagnostic/CLI console.
- **Android 15/16 Ready**: All native binaries (`libproot.so`, `libavscodespawn.so`, etc.) compiled with **16KB page-size alignment** (`-Wl,-z,max-page-size=16384`).
- **Complete Development Toolchain**: Ubuntu ARM64 userspace with Python 3, Git, Node.js, npm, and apt package manager.
- **Robust Process Supervision**: Custom JNI process spawner with POSIX process group isolation (`setpgid`) and clean group termination.
- **Persistent Workspace**: User files saved permanently in `/home/user/projects` inside internal app storage.
- **Foreground Service Persistence**: Ongoing foreground service with wake lock ensures background compilation tasks are not terminated by Android OOM killer.
- **Automated CI & Signed Releases**: Unified GitHub Actions pipeline automatically tests and builds signed release APKs.

---

## Architecture Overview

```
┌─────────────────────────────────────────────────────────────┐
│                    Android Host Layer                       │
│                                                             │
│   ┌───────────────────┐        ┌────────────────────────┐   │
│   │   MainActivity    │◄───────┤   RuntimeController    │   │
│   │  (WebView Host)   │ State  │  (Singleton Manager)   │   │
│   └─────────┬─────────┘        └───────────┬────────────┘   │
│             │ http://127.0.0.1:<port>      │ Starts / Stops │
│             │ (Local Web Server)           ▼                │
│             │                  ┌────────────────────────┐   │
│             │                  │  LinuxRuntimeService   │   │
│             │                  │  (Foreground Service)  │   │
│             │                  └───────────┬────────────┘   │
│             ▼                              │                │
│   ┌───────────────────┐        ┌───────────┴────────────┐   │
│   │   VsCodeWebView   │        │   AuthBridgeServer     │   │
│   └───────────────────┘        │ (127.0.0.1:<bridgePort>)   │
│                                └───────────┬────────────┘   │
│                                            │ Spawns JNI     │
│                                            ▼                │
│                                ┌────────────────────────┐   │
│                                │   NativeSpawn (JNI)    │   │
│                                └───────────┬────────────┘   │
└────────────────────────────────────────────┼────────────────┘
                                             │ fork() / execve()
┌────────────────────────────────────────────▼────────────────┐
│                    Linux Userspace Layer                    │
│                                                             │
│   ┌─────────────────────────────────────────────────────┐   │
│   │      LinuxDroid PRoot Engine (libproot.so)          │   │
│   │   - 16KB ELF page alignment for modern kernels      │   │
│   │   - Fake root & syscall translation layer           │   │
│   └──────────────────────────┬──────────────────────────┘   │
│                              │                              │
│   ┌──────────────────────────▼──────────────────────────┐   │
│   │              Ubuntu ARM64 Userspace                 │   │
│   │  - /bin/bash, Python 3, Git                         │   │
│   │  - /usr/local/bin/code serve-web (Local Server)     │   │
│   │  - /usr/local/bin/avscode-auth (Auth helper)        │   │
│   │  - /home/user/projects (Workspaces)                 │   │
│   └─────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────┘
```

For in-depth architectural details, refer to [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

---

## Prerequisites

To build AVSCode locally from source, ensure you have:

- **Operating System**: Linux or macOS (x86_64 or Apple Silicon)
- **JDK**: Java Development Kit 21 (Temurin or OpenJDK recommended)
- **Android SDK**: API Level 36 (`platform-tools`, `platforms;android-36`, `build-tools;35.0.0`)
- **Android NDK**: NDK revision `29.0.14206865` (r29)
- **CMake**: Version 3.22.1 or newer
- **Git**: With submodule support enabled

---

## Building from Source

### 1. Clone Repository & Submodules

```bash
git clone --recursive https://github.com/InfidelRahul/AVSCode.git
cd AVSCode
```

If already cloned without submodules:
```bash
git submodule update --init --recursive
```

### 2. Configure Environment

Set the paths to your Android SDK and NDK:
```bash
export ANDROID_HOME=/path/to/android-sdk
export ANDROID_NDK_HOME=$ANDROID_HOME/ndk/29.0.14206865
```
*(Or create `android/local.properties` containing `sdk.dir=/path/to/android-sdk`)*

### 3. Build APK

Using the unified build script:

```bash
# Build Signed Release APK (default)
./build.sh release

# Build Debug APK
./build.sh debug
```

Or using Gradle directly:

```bash
cd android

# Run unit tests
./gradlew test

# Assemble Signed Release APK
./gradlew assembleRelease

# Output APK: android/app/build/outputs/apk/release/app-release.apk
```

---

## Installation & Running

1. Enable **Install from Unknown Sources** in Android Settings.
2. Install the generated APK onto your Android device:
   ```bash
   adb install android/app/build/outputs/apk/release/app-release.apk
   ```
3. Launch **AVSCode**. On first boot:
   - Downloads and verifies the Ubuntu ARM64 userspace.
   - Configures guest networking and DNS.
   - Unpacks and starts VS Code Server.
   - Loads the VS Code IDE in the WebView.

---

## Project Structure

```
AVSCode/
├── .github/workflows/
│   ├── ci.yml                 # Unified CI & Signed Release Build workflow
│   └── README.md              # CI/CD workflow documentation
├── android/
│   ├── app/                   # Android UI, MainActivity, Foreground Service
│   ├── core/                  # AppPaths, AvsLogger, RuntimeState models
│   ├── runtime/               # PRoot engine, JNI avscode_spawn, LinuxRuntime
│   ├── rootfs/                # RootfsInstaller (Ubuntu ARM64 base + DNS/APT)
│   ├── vscode/                # VsCodeCliManager (Microsoft VS Code CLI & Tunnel)
│   ├── web/                   # VsCodeWebView (Chromium WebView & keyboard bridge)
│   └── diagnostics/           # RuntimeDiagnostics & health checks
├── docs/
│   └── ARCHITECTURE.md        # In-depth architectural documentation
├── proot-repo/                # LinuxDroid PRoot Git submodule (arm64-v8a)
├── build.sh                   # Unified local build script (debug/release)
└── README.md                  # Project overview
```

---

## License & Acknowledgments

- **AVSCode**: Apache License 2.0
- **PRoot**: GPL v2 ([LinuxDroid](https://github.com/LinuxDroidapp/proot))
- **VS Code CLI**: Microsoft Corporation ([Visual Studio Code](https://code.visualstudio.com))
- **Ubuntu Base**: Canonical Ltd.
