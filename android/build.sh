#!/bin/bash

# AVscode Build Script
# This script builds the AVscode Android application

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"
PROOT_REPO="$PROJECT_ROOT/proot-repo"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

echo -e "${GREEN}========================================${NC}"
echo -e "${GREEN}       AVscode Build Script${NC}"
echo -e "${GREEN}========================================${NC}"

# Check build type
BUILD_TYPE="${1:-debug}"
if [[ "$BUILD_TYPE" != "debug" && "$BUILD_TYPE" != "release" ]]; then
    echo -e "${RED}Error: Invalid build type. Use 'debug' or 'release'.${NC}"
    exit 1
fi

echo -e "\n${YELLOW}Build type: $BUILD_TYPE${NC}"

# Step 1: Check PRoot repository
echo -e "\n${YELLOW}Step 1: Checking PRoot repository...${NC}"
if [ ! -d "$PROOT_REPO" ]; then
    echo -e "${YELLOW}PRoot repository not found. Cloning...${NC}"
    git clone https://github.com/LinuxDroidapp/proot.git "$PROOT_REPO"
else
    echo -e "${GREEN}PRoot repository found.${NC}"
    cd "$PROOT_REPO"
    git pull --quiet 2>/dev/null || true
fi

# Verify third_party directories
if [ ! -d "$PROOT_REPO/third_party/talloc" ] || [ ! -d "$PROOT_REPO/third_party/libandroid-shmem" ]; then
    echo -e "${RED}Error: PRoot third_party libraries not found.${NC}"
    echo -e "${YELLOW}Please ensure the PRoot repository includes talloc and libandroid-shmem.${NC}"
    exit 1
fi
echo -e "${GREEN}PRoot third_party libraries verified.${NC}"

# Step 2: Check environment variables
echo -e "\n${YELLOW}Step 2: Checking environment...${NC}"

if [ -z "$ANDROID_HOME" ] && [ -z "$ANDROID_SDK_ROOT" ]; then
    if [ -f "$SCRIPT_DIR/local.properties" ]; then
        echo -e "${YELLOW}Reading SDK path from local.properties...${NC}"
        source <(grep sdk.dir "$SCRIPT_DIR/local.properties" | sed 's/\\//g')
        export ANDROID_HOME="$sdk_dir"
    else
        echo -e "${RED}Warning: ANDROID_HOME not set and local.properties not found.${NC}"
        echo -e "${YELLOW}Please create local.properties with: sdk.dir=/path/to/android-sdk${NC}"
        echo -e "${YELLOW}Or set ANDROID_HOME environment variable.${NC}"
        echo -e "${YELLOW}Continuing anyway - build may fail if SDK is not found by Gradle.${NC}"
    fi
fi

if [ -n "$ANDROID_HOME" ]; then
    echo -e "${GREEN}ANDROID_HOME: $ANDROID_HOME${NC}"
fi

if [ -n "$ANDROID_NDK_HOME" ]; then
    echo -e "${GREEN}ANDROID_NDK_HOME: $ANDROID_NDK_HOME${NC}"
elif [ -n "$ANDROID_HOME" ] && [ -d "$ANDROID_HOME/ndk" ]; then
    NDK_PATH=$(find "$ANDROID_HOME/ndk" -maxdepth 1 -type d -name "[0-9]*" | head -1)
    if [ -n "$NDK_PATH" ]; then
        export ANDROID_NDK_HOME="$NDK_PATH"
        echo -e "${GREEN}ANDROID_NDK_HOME (auto-detected): $ANDROID_NDK_HOME${NC}"
    fi
fi

# Step 3: Clean previous build (optional)
echo -e "\n${YELLOW}Step 3: Preparing build...${NC}"
read -p "Clean previous build? (y/N): " -n 1 -r
echo
if [[ $REPLY =~ ^[Yy]$ ]]; then
    echo -e "${YELLOW}Cleaning...${NC}"
    cd "$SCRIPT_DIR"
    ./gradlew clean
fi

# Step 4: Build native libraries and APK
echo -e "\n${YELLOW}Step 4: Building AVscode ($BUILD_TYPE)...${NC}"
cd "$SCRIPT_DIR"

if [ "$BUILD_TYPE" == "debug" ]; then
    ./gradlew assembleDebug
    APK_PATH="app/build/outputs/apk/debug/app-debug.apk"
else
    ./gradlew assembleRelease
    APK_PATH="app/build/outputs/apk/release/app-release-unsigned.apk"
fi

# Step 5: Verify build output
echo -e "\n${YELLOW}Step 5: Verifying build output...${NC}"
if [ -f "$SCRIPT_DIR/$APK_PATH" ]; then
    APK_SIZE=$(du -h "$SCRIPT_DIR/$APK_PATH" | cut -f1)
    echo -e "${GREEN}========================================${NC}"
    echo -e "${GREEN}       BUILD SUCCESSFUL${NC}"
    echo -e "${GREEN}========================================${NC}"
    echo -e "\n${GREEN}APK Location: $SCRIPT_DIR/$APK_PATH${NC}"
    echo -e "${GREEN}APK Size: $APK_SIZE${NC}"
    echo -e "\n${YELLOW}To install on connected device:${NC}"
    echo -e "  adb install $APK_PATH"
    echo -e "\n${YELLOW}To view logs after installation:${NC}"
    echo -e "  adb logcat | grep -E 'AVscode|PRoot|VsCode|LinuxRuntime'"
else
    echo -e "${RED}========================================${NC}"
    echo -e "${RED}       BUILD FAILED${NC}"
    echo -e "${RED}========================================${NC}"
    echo -e "${RED}APK not found at: $SCRIPT_DIR/$APK_PATH${NC}"
    exit 1
fi

echo -e "\n${GREEN}Build completed successfully!${NC}"
