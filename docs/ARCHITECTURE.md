# AVSCode Architecture & Technical Specification

AVSCode runs full Visual Studio Code (via `code-server`) locally on Android devices without requiring root access. The host Android application serves the Web UI through a hardware-accelerated Android WebView, while all computation, filesystem operations, compiler toolchains, and language servers execute in a self-contained ARM64 Linux userspace managed via PRoot.

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
│             │ HTTP                         │ Starts / Stops │
│             │ 127.0.0.1:8080               ▼                │
│             │                  ┌────────────────────────┐   │
│             │                  │  LinuxRuntimeService   │   │
│             │                  │  (Foreground Service)  │   │
│             ▼                  └───────────┬────────────┘   │
│   ┌───────────────────┐                    │                │
│   │   VsCodeWebView   │                    │ Spawns JNI     │
│   └───────────────────┘                    ▼                │
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
│   │  - /usr/bin/node & npm (symlinked from code-server) │   │
│   │  - /opt/code-server (v4.96.4 web server)            │   │
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
    1. Rootfs verification & atomic staging extraction.
    2. Linux userspace initialization & PRoot carrier discovery.
    3. One-time development tool bootstrap (git, python3, certificates).
    4. Code-server startup and HTTP readiness probing.
    5. State transition to `AppState.Ready(url)` for WebView consumption.
- **`LinuxRuntimeService` (`com.avscode.runtime.LinuxRuntimeService`)**:
  - Android Foreground Service with type `FOREGROUND_SERVICE_TYPE_DATA_SYNC`.
  - Holds a wake lock and displays ongoing status notifications to prevent Android OOM kills while long-running build or language server tasks run in the background.
- **`MainActivity` (`com.avscode.MainActivity`)**:
  - Pure view controller that observes `RuntimeController.appState`.
  - Handles fullscreen display, back button dispatch via `OnBackPressedDispatcher`, and hardware keyboard shortcut pass-through to the WebView.

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
  - Extracted to an isolated staging directory (`ubuntu-rootfs-staging`) with automated verification of critical binaries (`/bin/bash`, `/bin/sh`, `/etc/passwd`).
  - Provides a pure Kotlin fallback streaming tar/gzip extractor for environments where host utilities are unavailable.
  - Promotes staging atomically (`renameTo`) once verified.
- **Guest Configuration**:
  - Preconfigures DNS in `/etc/resolv.conf` using reliable public resolvers (`1.1.1.1`, `8.8.8.8`).
  - Sets up default user environment (`user` with UID 1000).
  - Automatically links bundled Node.js and npm binaries to `/usr/bin/node` and `/usr/bin/npm`.

---

### 4. VS Code Server (`VsCodeServerManager.kt`)

- **Binary Distribution**:
  - Upstream official release: `code-server-4.96.4-linux-arm64.tar.gz`.
  - Installed in guest directory `/opt/code-server`.
- **Launch Configuration**:
  - Executed inside PRoot:
    ```bash
    /opt/code-server/bin/code-server \
      --bind-addr 127.0.0.1:8080 \
      --auth none \
      --user-data-dir /home/user/.local/share/code-server \
      /home/user/projects
    ```
- **Readiness Polling**:
  - Uses asynchronous HTTP/socket polling against `http://127.0.0.1:8080/` before transitioning application state to `AppState.Ready`.

---

### 5. Web Interface (`VsCodeWebView.kt`)

- Uses Chromium-based Android `WebView` with hardware acceleration enabled.
- Configured with `DOM_STORAGE_ENABLED`, `DATABASE_ENABLED`, and `JAVASCRIPT_ENABLED`.
- Supports desktop-mode rendering and keyboard event interception to ensure standard IDE shortcuts (e.g., Ctrl+S, Ctrl+P, Ctrl+Shift+F) are routed directly into the web editor.
