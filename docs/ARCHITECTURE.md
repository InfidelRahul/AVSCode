# AVSCode Architecture & Technical Specification

AVSCode runs full Visual Studio Code (via Microsoft's official VS Code CLI `code serve-web`) locally on Android devices without requiring root access. The host Android application serves the Web UI through a hardware-accelerated, edge-to-edge Android WebView connected to `http://127.0.0.1:<dynamic-port>`, while all computation, filesystem operations, compiler toolchains, and language servers execute in a self-contained ARM64 Linux userspace managed via PRoot. A dedicated loopback `AuthBridgeServer` handles Android ↔ Linux authentication flows.

---

## High-Level Architecture Diagram

```
┌─────────────────────────────────────────────────────────────┐
│                    Android Host Layer                       │
│                                                             │
│   ┌───────────────────┐        ┌────────────────────────┐   │
│   │   MainActivity    │◄───────┤   RuntimeController    │   │
│   │  (WebView Host)   │ State  │  (Singleton Manager)   │   │
│   └─────────┬─────────┘        └───────────┬────────────┘   │
│             │                              │                │
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
│                                │    (avscode_spawn)     │   │
│                                └───────────┬────────────┘   │
└────────────────────────────────────────────┼────────────────┘
                                             │
                                             │ fork() / execve()
                                             │
┌────────────────────────────────────────────▼────────────────┐
│                    Linux Userspace Layer                    │
│                                                             │
│   ┌─────────────────────────────────────────────────────┐   │
│   │      LinuxDroid PRoot Engine (libproot.so)          │   │
│   │   - 16KB ELF page-size alignment (-z max-page-size) │   │
│   │   - Fake root credentials & syscall translation     │   │
│   │   - Isolated temp & binding mounts                  │   │
│   └──────────────────────────┬──────────────────────────┘   │
│                              │                              │
│   ┌──────────────────────────▼──────────────────────────┐   │
│   │              Ubuntu ARM64 Userspace                 │   │
│   │  - /bin/bash, /usr/bin/python3, /usr/bin/git        │   │
│   │  - /usr/local/bin/code serve-web (Local Server)     │   │
│   │  - /usr/local/bin/avscode-auth (Auth Bridge helper) │   │
│   │  - /home/user/projects (Persistent user workspaces) │   │
│   └─────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────┘
```

---

## Component Deep Dive

### 1. Host Application & Lifecycle Management

- **`RuntimeController` (`com.avscode.RuntimeController`)**:
  - Authoritative, thread-safe singleton state machine.
  - Coordinates the multi-step boot process:
    1. Rootfs verification & extraction.
    2. Linux userspace initialization & PRoot supervisor session.
    3. Linux userspace diagnostic probe (`/usr/bin/mkdir`).
    4. One-time guest development tool bootstrap (`bootstrap.sh`).
    5. Microsoft VS Code CLI verification & installation (`/usr/local/bin/code`).
    6. `AuthBridgeServer` startup on ephemeral local loopback port (`127.0.0.1:<bridgePort>`).
    7. Guest helper injection (`/usr/local/bin/avscode-auth`).
    8. Local VS Code Server (`code serve-web`) supervision on dynamic port with HTTP health probing.
    9. State transition to `AppState.Ready(url)` for WebView consumption.
- **`AuthBridgeServer` (`com.avscode.core.AuthBridgeServer`)**:
  - Lightweight, zero-dependency local loopback HTTP IPC service running on Android host.
  - Provides endpoints for guest processes: `/health`, `POST /auth/request`, `/auth/callback`, and single-use `GET /auth/token`.
  - Dispatches browser intents on Android host when guest processes request OAuth or web authorization.
- **`LinuxRuntimeService` (`com.avscode.runtime.LinuxRuntimeService`)**:
  - Android Foreground Service with type `FOREGROUND_SERVICE_TYPE_DATA_SYNC`.
  - Holds a wake lock and displays ongoing status notifications to prevent Android OOM kills while long-running compilation or local web servers run in the background.
- **`MainActivity` (`com.avscode.MainActivity`)**:
  - Pure view controller that observes `RuntimeController.appState`.
  - Implements modern edge-to-edge support with `WindowInsetsCompat` across status bars, display cutouts/notches, gesture navigation, and soft keyboard (IME).
  - Handles fullscreen display, back button dispatch via `OnBackPressedDispatcher`, and hardware keyboard shortcut pass-through to the WebView.
  - Switches automatically to the VS Code editor upon readiness; retains the Linux terminal as a secondary toggleable console.

---

### 2. PRoot Runtime & Native Spawner

- **LinuxDroid PRoot Integration**:
  - Built from official upstream source (`https://github.com/LinuxDroidapp/proot.git`) tracked as a submodule.
  - Compiles `libproot.so`, `libproot_loader.so`, and `liblinuxdroidspawn.so`.
  - **16KB ELF Alignment**: Configured with `-Wl,-z,max-page-size=16384` for compliance with Android 15/16 16KB memory page size standards.
- **Native Process Spawner (`avscode_spawn.c`)**:
  - Custom POSIX JNI spawner providing process group isolation (`setpgid(0, 0)`).
  - Configures child environment, file descriptor redirection, non-blocking pipes, and group killing (`kill(-pid, sig)`).
- **Filesystem Mounts**:
  - Mounts Android files directory as root `/`.
  - Mounts isolated `/tmp` inside application storage.
  - Automatically maps `/proc`, `/sys`, and `/dev`.

---

### 3. Rootfs & Guest Environment

- **Rootfs Provisioning (`RootfsInstaller.kt`)**:
  - Official Ubuntu ARM64 base filesystem archive.
  - Extracted using Apache Commons Compress with POSIX PAX header filtering and deferred hardlink resolution.
  - Structural and binary verification of critical paths (`/bin/bash`, `/bin/sh`, `/etc/passwd`, `/etc/os-release`).
  - Promotes staging atomically once verified.
- **Guest Configuration**:
  - Preconfigures DNS in `/etc/resolv.conf` using reliable public resolvers (`1.1.1.1`, `8.8.8.8`, `8.8.4.4`).
  - Sets up default user environment (`user` with UID 1000).
  - Deploys `/usr/sbin/policy-rc.d` (`exit 101`) to prevent service daemon errors during `apt` in PRoot.

---

### 4. Microsoft VS Code CLI & Local Web Server (`VsCodeCliManager.kt`)

- **Binary Distribution**:
  - Official Microsoft standalone ARM64 Linux CLI release (`cli-alpine-arm64`).
  - Installed into Ubuntu userspace at `/usr/local/bin/code`.
- **Launch Configuration**:
  - Executed inside PRoot as `user`:
    ```bash
    code serve-web \
      --host 127.0.0.1 \
      --port <dynamicPort> \
      --without-connection-token \
      --accept-server-license-terms \
      --cli-data-dir /home/user/.vscode-cli \
      --server-data-dir /home/user/.vscode-cli/data
    ```
- **Local Loopback Supervision**:
  - Ephemeral port allocated via `ServerSocket(0)` avoids port collisions.
  - HTTP health probe polls `http://127.0.0.1:<port>` until ready before loading into the WebView.
  - 100% local, offline-capable development environment without external cloud or tunnel dependencies.

---

### 5. Web Interface (`VsCodeWebView.kt`)

- Uses Chromium-based Android `WebView` with hardware acceleration enabled.
- Configured with `DOM_STORAGE_ENABLED`, `DATABASE_ENABLED`, and `JAVASCRIPT_ENABLED`.
