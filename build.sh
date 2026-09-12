#!/usr/bin/env bash
# ==============================================================================
# AVSCode Build Script (VS Code for Android)
# Builds LinuxDroid PRoot runtime and Android APK (Debug / Release)
# ==============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ANDROID_DIR="${SCRIPT_DIR}/android"
PROOT_REPO="${SCRIPT_DIR}/proot-repo"

BUILD_TYPE="${1:-release}"
if [[ "$BUILD_TYPE" != "debug" && "$BUILD_TYPE" != "release" ]]; then
    echo "Usage: $0 [debug|release]"
    exit 1
fi

echo "=========================================="
echo " Building AVSCode ($BUILD_TYPE)"
echo "=========================================="

# 1. Check Submodules
if [ ! -f "${PROOT_REPO}/CMakeLists.txt" ]; then
    echo "Initializing PRoot submodule..."
    git submodule update --init --recursive
fi

# 2. Check Android SDK / NDK Environment
if [ -z "${ANDROID_HOME:-}" ] && [ -z "${ANDROID_SDK_ROOT:-}" ]; then
    if [ -f "${ANDROID_DIR}/local.properties" ]; then
        SDK_PROP=$(grep "^sdk.dir=" "${ANDROID_DIR}/local.properties" | cut -d'=' -f2 | sed 's/\\//g')
        if [ -n "$SDK_PROP" ] && [ -d "$SDK_PROP" ]; then
            export ANDROID_HOME="$SDK_PROP"
            export ANDROID_SDK_ROOT="$SDK_PROP"
        fi
    fi
fi

if [ -z "${ANDROID_HOME:-}" ]; then
    echo "ERROR: ANDROID_HOME or ANDROID_SDK_ROOT is not set."
    echo "Please set ANDROID_HOME or configure local.properties in android/"
    exit 1
fi

# Auto-detect NDK if not set
if [ -z "${ANDROID_NDK_HOME:-}" ]; then
    if [ -d "${ANDROID_HOME}/ndk" ]; then
        NDK_LATEST=$(find "${ANDROID_HOME}/ndk" -maxdepth 1 -mindepth 1 -type d | sort -V | tail -n 1)
        if [ -n "$NDK_LATEST" ]; then
            export ANDROID_NDK_HOME="$NDK_LATEST"
            echo "Auto-detected NDK: ${ANDROID_NDK_HOME}"
        fi
    fi
fi

chmod +x "${ANDROID_DIR}/gradlew"

cd "${ANDROID_DIR}"

if [ "$BUILD_TYPE" = "debug" ]; then
    ./gradlew assembleDebug --no-configuration-cache
    APK="${ANDROID_DIR}/app/build/outputs/apk/debug/app-debug.apk"
else
    # Check if release keystore exists or create a temporary fallback one
    if [ ! -f "${ANDROID_DIR}/release.keystore" ]; then
        echo "Generating release keystore for local build..."
        keytool -genkeypair -v -keystore "${ANDROID_DIR}/release.keystore" \
            -alias avscode -keyalg RSA -keysize 2048 -validity 10000 \
            -storepass avscode123 -keypass avscode123 \
            -dname "CN=AVSCode, OU=Mobile, O=AVSCode, L=City, S=State, C=US"
    fi
    ./gradlew assembleRelease --no-configuration-cache
    APK="${ANDROID_DIR}/app/build/outputs/apk/release/app-release.apk"
fi

if [ -f "$APK" ]; then
    echo "=========================================="
    echo " Build Succeeded!"
    echo " APK: $APK"
    echo " Size: $(du -h "$APK" | cut -f1)"
    echo "=========================================="
else
    echo "ERROR: Expected APK not found at $APK"
    exit 1
fi
