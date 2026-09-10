# AVscode - VS Code for Android

A complete Android application that runs VS Code (via code-server) inside a Linux userspace using PRoot, presenting VS Code Web through an Android WebView.

## Architecture

```
Android Application
├── MainActivity (Lifecycle management)
├── WebView (VS Code Web interface)
└── Linux Runtime Controller
    └── PRoot (LinuxDroid with Android patches)
        └── Ubuntu 26.04 ARM64 Rootfs
            ├── code-server (VS Code Server)
            ├── Git, Node.js, Python
            └── User projects (/home/user/projects)
```

## Features

- **Full VS Code Experience**: Runs actual code-server inside Linux
- **Persistent Linux Environment**: Ubuntu 26.04 ARM64 base
- **Development Tools**: Git, Node.js, npm, Python3 pre-installed
- **Project Persistence**: All projects stored in Linux filesystem
- **Terminal Access**: Full Linux terminal inside VS Code
- **Extension Support**: Install and use VS Code extensions

## Prerequisites

- Android Studio Arctic Fox or later
- Android SDK 26+
- Android NDK 25+
- JDK 17+
- Android device with ARM64 architecture (or emulator)

## Building

### Option 1: Using Build Script

```bash
export ANDROID_HOME=/path/to/android/sdk
export ANDROID_NDK_HOME=/path/to/android/ndk
./build.sh
```

### Option 2: Manual Build

```bash
cd android
./gradlew assembleDebug
```

The APK will be generated at: `android/app/build/outputs/apk/debug/app-debug.apk`

## Installation

1. Transfer the APK to your Android device
2. Enable "Install from Unknown Sources" in Settings
3. Install the APK
4. Open AVscode

## First Launch

On first launch, the application will:

1. Download Ubuntu 26.04 ARM64 rootfs (~50MB)
2. Extract and install the rootfs
3. Start the Linux runtime
4. Install development tools (git, nodejs, python3, etc.)
5. Download and install code-server
6. Start code-server
7. Display VS Code Web in the WebView

**Note**: First launch may take 5-10 minutes depending on network speed.

## Usage

### Creating a Project

1. Open VS Code Terminal (Ctrl+` or View → Terminal)
2. Navigate to projects directory: `cd /home/user/projects`
3. Create a new project:
   ```bash
   mkdir my-project
   cd my-project
   git init
   ```

### Using Extensions

1. Click Extensions icon in Activity Bar
2. Search and install extensions as normal
3. Extensions are stored in `/home/user/.local/share/code-server/extensions`

## Technical Details

### Components

| Component | Description |
|-----------|-------------|
| RootfsInstaller | Downloads and installs Ubuntu 26.04 ARM64 |
| PRootRuntime | Manages Linux process lifecycle via PRoot |
| VsCodeServerManager | Downloads, installs, and manages code-server |
| VsCodeWebView | Renders VS Code Web interface |

### PRoot Integration

Uses LinuxDroid PRoot with:
- 16KB ELF page alignment for Android 15
- W^X bypass for modern Android
- Multi-ABI support
- Shared memory emulation

## Troubleshooting

### First Launch Fails
- Check network connectivity
- Ensure sufficient storage (2GB+ free)
- Check logcat: `adb logcat | grep -E "AVscode|PRoot|VsCode"`

### code-server Won't Start
```bash
# In VS Code Terminal:
/opt/code-server/bin/code-server --version
```

### WebView Shows Blank Screen
- Wait longer - code-server may still be starting
- Check server status in logs
- Try restarting the app

## Project Structure

```
android/
├── app/           # Main application
├── core/          # Core utilities
├── runtime/       # Linux runtime (PRoot + native)
├── rootfs/        # Rootfs management
├── vscode/        # VS Code Server manager
├── web/           # WebView management
└── diagnostics/   # Diagnostics and logging
```

## License

- PRoot: GPL v2
- talloc: LGPL v3
- libandroid-shmem: Apache 2.0
- code-server: MIT

AVscode provided as-is for educational purposes.

## Acknowledgments

- LinuxDroid team for PRoot
- Coder team for code-server
- Microsoft for VS Code
- Ubuntu team for Ubuntu Base
