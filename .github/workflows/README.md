# AVscode CI/CD Pipeline

## Overview

This directory contains GitHub Actions workflows for building, testing, and releasing AVscode.

## Workflows

### 1. CI Pipeline (`ci.yml`)
**Triggers:** Push to `main`/`develop`, Pull Requests

**Steps:**
1. Checkout code
2. Setup JDK 21
3. Install Android SDK & NDK 29
4. Run lint checks
5. Compile all modules
6. Run unit tests (if any)
7. Verify APK generation
8. Display build information
9. Upload APK artifact (retention: 1 day)
10. Upload build logs on failure

**Artifacts:**
- `avscode-debug-apk-{commit-sha}` - Debug APK valid for 1 day

### 2. Build Workflow (`build.yml`)
**Triggers:** Push to `main`/`develop`, Pull Requests

**Steps:**
1. Checkout code
2. Setup JDK 21
3. Install Android SDK & NDK 29
4. Build debug APK
5. Upload artifact

**Artifacts:**
- `avscode-debug-apk` - Debug APK valid for 1 day

### 3. Release Workflow (`release.yml`)
**Triggers:** Git tags (`v*`), Manual dispatch

**Steps:**
1. Checkout code
2. Setup JDK 21
3. Install Android SDK & NDK 29
4. Build debug and release APKs
5. Upload both artifacts
6. Create GitHub release (if tagged)

**Artifacts:**
- `avscode-debug-apk` - Debug APK valid for 1 day
- `avscode-release-apk` - Release APK valid for 1 day

## Usage

### Automatic Builds
Push to `main` or `develop` branch to trigger CI automatically.

### Manual Release
1. Go to Actions tab
2. Select "Build AVscode Release APK"
3. Click "Run workflow"
4. Enter version number
5. Download APK from artifacts

### Tagged Release
```bash
git tag v1.0.0
git push origin v1.0.0
```
This will automatically create a draft release with APKs attached.

## Artifact Retention

All APK artifacts are retained for **1 day** only to save storage space. Download immediately after build completion.

## Requirements

- GitHub Actions runners with Ubuntu
- Android SDK license acceptance (handled automatically)
- ~15GB disk space for Android SDK + NDK + build artifacts

## Troubleshooting

### Build Fails
1. Check "Display Build Info" step for details
2. Review build logs in artifacts
3. Run locally: `./build.sh debug`

### NDK Not Found
Ensure NDK 29 is installed in the runner (handled by workflow).

### APK Not Generated
Check compilation errors in the "Compile All Modules" step.

## Security Notes

- Release APKs are unsigned (requires manual signing for production)
- Debug APKs should not be distributed publicly
- Never commit signing keys to repository
