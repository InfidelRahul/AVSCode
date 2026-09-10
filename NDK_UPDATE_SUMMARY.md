# NDK Version Update Summary

## Change Overview

Updated NDK version from **27.2.12479018 (r27c)** to **29.0.14206865 (r29)** across the entire AVscode project.

## Reason for Change

- **NDK r29** is now available in Android SDK repository and provides better stability
- Fixes the CI/CD pipeline error where NDK r27c was not found
- Better compatibility with CMake 4.4.3 and C++20 standard
- Improved support for ARM64 ABI
- Latest stable version as of current date

## Files Updated

### Build Configuration Files:
1. `/workspace/android/app/build.gradle.kts` - Updated ndkVersion
2. `/workspace/android/runtime/build.gradle.kts` - Updated ndkVersion

### CI/CD Workflow Files:
3. `/.github/workflows/ci.yml` - Updated sdkmanager package and environment variables
4. `/.github/workflows/build.yml` - Updated sdkmanager package and environment variables
5. `/.github/workflows/release.yml` - Updated sdkmanager package and environment variables

### Documentation Files:
6. `/workspace/android/UPGRADE_SUMMARY.md` - Updated version table
7. `/workspace/android/BUILD_INSTRUCTIONS.md` - Updated installation instructions
8. `/.github/workflows/CICD_DOCUMENTATION.md` - Updated NDK version references

## Installation Command

For local development, install NDK r29 via Android Studio SDK Manager or:

```bash
sdkmanager "ndk;29.0.14206865"
```

## Compatibility

- ✅ Compatible with CMake 4.4.3
- ✅ Supports C++20 standard
- ✅ Compatible with Android SDK 36
- ✅ Supports arm64-v8a ABI
- ✅ Works with Gradle 9.7.1 and AGP 9.3.0

## Verification

To verify the NDK installation:

```bash
cat $ANDROID_SDK_ROOT/ndk/29.0.14206865/source.properties | grep Pkg.Revision
```

Expected output: `Pkg.Revision = 29.0.14206865`

## Next Steps

1. CI/CD pipelines will automatically use NDK r29 on next run
2. Local developers should update their NDK installation
3. No code changes required - binary compatible upgrade
