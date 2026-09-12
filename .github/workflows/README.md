# AVSCode Unified CI/CD Pipeline

## Overview

This directory contains the single, authoritative GitHub Actions workflow for building, testing, signing, and releasing AVSCode.

## Workflow: `ci.yml` (AVSCode CI & Signed Release Build)

**Triggers:**
- Push to `main` and `develop`
- Tags (`v*`)
- Pull requests to `main` and `develop`
- Manual dispatch (`workflow_dispatch`)

### Pipeline Steps:
1. **Checkout Code & Submodules**: Recursive checkout of repository and `proot-repo` submodule.
2. **Setup JDK 21**: Microsoft Temurin JDK 21.
3. **Setup Gradle & Cache**: Gradle 9.x wrapper setup.
4. **Install Android SDK & NDK 29**: Platform 36, build-tools 35.0.0, NDK 29.0.14206865, and CMake 3.22.1.
5. **Setup Release Keystore**: Decodes `KEYSTORE_BASE64` secret if provided, or generates a deterministic release keystore automatically.
6. **Run Unit Tests**: Executes `./gradlew test` across all modules.
7. **Build Signed Release APK**: Executes `./gradlew assembleRelease` generating a fully signed release APK.
8. **Verify Signature**: Verifies APK signature scheme (v1/v2/v3) with `apksigner`.
9. **Upload Signed APK Artifact**: Artifact `avscode-signed-release-apk` retained for 7 days.
10. **Publish GitHub Release**: Automatically creates GitHub release when triggered by version tag `v*` or manual dispatch.
11. **Upload Failure Logs**: Diagnostic reports uploaded on build failure.

## Artifacts

- **`avscode-signed-release-apk`**: `app-release.apk` (Signed Release APK)

## Usage

### Automatic Builds
Every push and pull request runs unit tests and compiles the signed release APK.

### Release via Git Tag
```bash
git tag v1.0.0
git push origin v1.0.0
```
This automatically builds the signed release APK and creates a GitHub Release with the APK attached.

