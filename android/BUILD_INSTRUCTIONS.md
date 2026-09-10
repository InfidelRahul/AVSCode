# AVscode Build Instructions

## Prerequisites

Before building AVscode, ensure you have the following installed:

### Required Software
- **JDK 17 or later** (OpenJDK recommended)
- **Android Studio** (Arctic Fox or later) OR standalone Android SDK
- **Android NDK 25 or later**
- **Git**

### Environment Setup

1. **Install Android SDK** (if not using Android Studio):
   ```bash
   # Download from https://developer.android.com/studio#command-tools
   mkdir -p ~/android-sdk
   cd ~/android-sdk
   # Extract command-line tools here
   ./cmdline-tools/bin/sdkmanager --sdk_root=$HOME/android-sdk \
     "platform-tools" \
     "platforms;android-36" \
     "build-tools;34.0.0" \
     "ndk;25.2.9519653"
   ```

2. **Set Environment Variables**:
   ```bash
   export ANDROID_HOME=$HOME/android-sdk
   export ANDROID_NDK_HOME=$ANDROID_HOME/ndk/25.2.9519653
   export PATH=$PATH:$ANDROID_HOME/tools:$ANDROID_HOME/platform-tools
   ```

3. **Clone PRoot Repository** (automatically handled by build script):
   ```bash
   cd /path/to/AVscode
   git clone https://github.com/LinuxDroidapp/proot.git proot-repo
   ```

## Building

### Option 1: Using Build Script (Recommended)

```bash
cd android
chmod +x build.sh
./build.sh debug
```

This will:
- Clone PRoot repository if not present
- Set up environment variables
- Build native libraries with CMake
- Build Android APK
- Output APK at `app/build/outputs/apk/debug/app-debug.apk`

### Option 2: Manual Build with Gradle

1. Create `local.properties` in the `android/` directory:
   ```properties
   sdk.dir=/path/to/android-sdk
   ndk.dir=/path/to/android-sdk/ndk/25.2.9519653
   ```

2. Build with Gradle:
   ```bash
   cd android
   ./gradlew assembleDebug
   ```

### Option 3: Using Android Studio

1. Open the `android/` directory in Android Studio
2. Wait for Gradle sync to complete
3. Click Build → Make Project
4. Find APK at `app/build/outputs/apk/debug/app-debug.apk`

## Build Outputs

| Build Type | Output Location |
|------------|----------------|
| Debug | `app/build/outputs/apk/debug/app-debug.apk` |
| Release | `app/build/outputs/apk/release/app-release-unsigned.apk` |

## Troubleshooting

### CMake Build Fails

**Error**: `PRoot repository not found`
- **Solution**: Ensure `proot-repo` directory exists in project root
  ```bash
  git clone https://github.com/LinuxDroidapp/proot.git proot-repo
  ```

**Error**: `talloc not found` or `libandroid-shmem not found`
- **Solution**: Verify PRoot repository has third_party submodules
  ```bash
  cd proot-repo
  ls third_party/
  # Should show: libandroid-shmem  talloc
  ```

### Gradle Build Fails

**Error**: `SDK location not found`
- **Solution**: Create `local.properties` with correct SDK path
  ```properties
  sdk.dir=/path/to/android-sdk
  ```

**Error**: `NDK not found`
- **Solution**: Install NDK via SDK Manager or set `ndk.dir` in `local.properties`

**Error**: `Could not determine java version`
- **Solution**: Ensure JDK 17+ is installed and JAVA_HOME is set correctly
  ```bash
  java -version  # Should show 17.x.x
  ```

### Native Build Issues

**Error**: `CMake error` during native compilation
- **Solution**: 
  1. Clean build: `./gradlew clean`
  2. Delete `.externalNativeBuild` directory
  3. Rebuild: `./gradlew assembleDebug`

**Error**: `ABI mismatch` or `Unsupported ABI`
- **Solution**: Ensure device/emulator supports arm64-v8a architecture

## Testing on Device

1. **Enable Developer Options** on Android device:
   - Go to Settings → About Phone
   - Tap "Build Number" 7 times
   
2. **Enable USB Debugging**:
   - Settings → Developer Options → USB Debugging

3. **Connect Device** via USB

4. **Install APK**:
   ```bash
   adb install app/build/outputs/apk/debug/app-debug.apk
   ```

5. **Run App** on device

6. **View Logs**:
   ```bash
   adb logcat | grep -E "AVscode|PRoot|VsCode|LinuxRuntime"
   ```

## First Launch

On first launch, the app will:
1. Download Ubuntu 26.04 ARM64 rootfs (~50MB)
2. Extract and install rootfs
3. Start Linux runtime
4. Install development tools
5. Download and install code-server
6. Start VS Code Web interface

**Note**: First launch may take 5-10 minutes depending on network speed.

## Release Build

For production release:

```bash
cd android
./gradlew assembleRelease
```

You must sign the APK before distribution:

```bash
# Generate keystore (one time)
keytool -genkey -v -keystore avscode.keystore -alias avscode -keyalg RSA -keysize 2048 -validity 10000

# Sign APK
apksigner sign --ks avscode.keystore --out app-release-signed.apk app/build/outputs/apk/release/app-release-unsigned.apk
```

## Architecture-Specific Builds

To build for specific ABIs:

```bash
# ARM64 only (recommended for most devices)
./gradlew assembleDebug -PabiFilters=arm64-v8a

# All ABIs (larger APK)
./gradlew assembleDebug
```

## Performance Tips

- Use SSD for faster builds
- Increase Gradle heap size in `gradle.properties`:
  ```properties
  org.gradle.jvmargs=-Xmx4g -XX:MaxMetaspaceSize=1g
  ```
- Enable Gradle daemon for faster subsequent builds
- Use `--parallel` flag for parallel project builds
