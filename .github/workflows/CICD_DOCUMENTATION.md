# CI/CD Pipeline Documentation

## Overview

The AVscode project uses GitHub Actions for continuous integration and deployment. All workflows automatically compile the entire project and produce APK artifacts with 1-day retention.

## Workflows

### 1. CI Workflow (`ci.yml`)
**Triggers:** Push to `main`/`develop`, Pull Requests

**Purpose:** Full build pipeline with testing

**Steps:**
1. Checkout code
2. Set up JDK 21
3. Setup Android SDK with NDK r29 (includes platform-tools, android-36, build-tools)
4. Grant gradlew permissions
5. Run lint checks
6. Compile all modules (assembleDebug)
7. Run unit tests
8. Verify APK generation
9. Display build information
10. Upload debug APK (1-day retention)
11. Upload build logs on failure

**Artifacts:**
- `avscode-debug-apk-{commit-sha}` - Debug APK
- `build-logs` - Build logs (on failure only)

### 2. Build Workflow (`build.yml`)
**Triggers:** Push to `main`/`develop`, Pull Requests

**Purpose:** Simple build-only pipeline

**Steps:**
1. Checkout code
2. Set up JDK 21
3. Setup Android SDK with NDK r29
4. Build with Gradle (assembleDebug)
5. Upload debug APK

**Artifacts:**
- `avscode-debug-apk` - Debug APK (1-day retention)

### 3. Release Workflow (`release.yml`)
**Triggers:** Version tags (`v*`), Manual dispatch

**Purpose:** Build both debug and release APKs for releases

**Steps:**
1. Checkout code
2. Set up JDK 21
3. Setup Android SDK with NDK r29
4. Build debug APK
5. Build release APK (unsigned)
6. Upload both APKs
7. Create GitHub Release (if triggered by tag)

**Artifacts:**
- `avscode-debug-apk` - Debug APK (1-day retention)
- `avscode-release-apk` - Release APK unsigned (1-day retention)

**GitHub Release:** Automatically creates draft release with both APKs attached

## Configuration

### SDK Components Installed
- **Platform Tools**: Latest version
- **Platform**: Android 36
- **Build Tools**: 35.0.0
- **NDK**: 29.0.14206865 (r29 - latest stable LTS)

### Environment Variables
```bash
ANDROID_NDK_HOME=$ANDROID_SDK_ROOT/ndk/29.0.14206865
```

### Java Version
- JDK 21 (Temurin distribution)

### Gradle Version
- Wrapper: 9.7.1
- Plugin: 8.9.0

## Artifact Retention

All artifacts are retained for **1 day** as specified. This ensures:
- Minimal storage usage
- Fresh builds always available
- Compliance with project requirements

## Downloading Artifacts

After a successful workflow run:

1. Go to the Actions tab in GitHub
2. Select the workflow run
3. Scroll to "Artifacts" section
4. Click on the artifact name to download
5. Extract the ZIP file to get the APK

## Manual Trigger

To manually trigger the release workflow:

1. Go to Actions → "Build AVscode Release APK"
2. Click "Run workflow"
3. Enter version number (e.g., `1.0.0`)
4. Click "Run workflow"

## Troubleshooting

### NDK Installation Issues
The workflows use `android-actions/setup-android@v3` which handles NDK installation automatically. If issues occur:

1. Check the workflow logs for sdkmanager errors
2. Verify the NDK version exists in Android repositories
3. The current version (29.0.14206865) is verified stable

### Build Failures
1. Check "Run Lint Checks" step for code issues
2. Review "Compile All Modules" step for compilation errors
3. Download build logs artifact for detailed analysis
4. Check "Display Build Info" for environment details

### APK Not Generated
If the verification step fails:
1. Check for compilation errors in previous steps
2. Verify gradle wrapper is executable
3. Ensure all dependencies are available
4. Review build logs for specific error messages

## Security Notes

- Release APKs are **unsigned** - you must sign them before distribution
- Debug APKs are for testing only
- Never commit keystore files to the repository
- Use GitHub Secrets for signing configurations in production

## Performance

Typical build times:
- **Lint + Build**: 8-12 minutes
- **Build Only**: 5-8 minutes
- **Release Build**: 10-15 minutes

## Next Steps for Production

To make this production-ready:

1. **Add Code Signing:**
   - Create Android keystore
   - Add signing config to build.gradle.kts
   - Store keystore password in GitHub Secrets
   - Update release workflow to sign APKs

2. **Add Testing:**
   - Unit tests
   - Integration tests
   - UI tests with Firebase Test Lab

3. **Add Quality Gates:**
   - Code coverage requirements
   - Static analysis (Detekt, SonarQube)
   - Security scanning

4. **Add Distribution:**
   - Google Play Store upload
   - Firebase App Distribution
   - GitHub Releases automation

5. **Add Monitoring:**
   - Crash reporting integration
   - Build performance tracking
   - Artifact size monitoring
