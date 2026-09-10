#!/bin/bash
# AVscode Build Script
# This script builds the complete AVscode Android application

set -e

echo "========================================"
echo "AVscode - VS Code for Android Build"
echo "========================================"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Check prerequisites
check_prerequisites() {
    echo -e "${YELLOW}Checking prerequisites...${NC}"
    
    # Check Android SDK
    if [ -z "$ANDROID_HOME" ] && [ -z "$ANDROID_SDK_ROOT" ]; then
        echo -e "${RED}Error: ANDROID_HOME or ANDROID_SDK_ROOT not set${NC}"
        exit 1
    fi
    
    # Check NDK
    if [ -z "$ANDROID_NDK_HOME" ] && [ -z "$NDK_HOME" ]; then
        echo -e "${RED}Error: ANDROID_NDK_HOME or NDK_HOME not set${NC}"
        exit 1
    fi
    
    # Check Java
    if ! command -v java &> /dev/null; then
        echo -e "${RED}Error: Java not found${NC}"
        exit 1
    fi
    
    echo -e "${GREEN}Prerequisites OK${NC}"
}

# Build native libraries
build_native() {
    echo -e "${YELLOW}Building native libraries...${NC}"
    
    cd android/runtime/src/main/cpp
    
    # Create build directory
    mkdir -p build
    cd build
    
    # Configure with CMake
    cmake .. \
        -DCMAKE_TOOLCHAIN_FILE=$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake \
        -DANDROID_ABI=arm64-v8a \
        -DANDROID_PLATFORM=android-26 \
        -DCMAKE_BUILD_TYPE=Release
    
    # Build
    cmake --build . --parallel $(nproc)
    
    cd ../../../../../..
    
    echo -e "${GREEN}Native libraries built successfully${NC}"
}

# Build Android application
build_android() {
    echo -e "${YELLOW}Building Android application...${NC}"
    
    cd android
    
    # Make gradlew executable
    chmod +x gradlew
    
    # Clean and build
    ./gradlew clean assembleDebug --no-daemon
    
    cd ..
    
    echo -e "${GREEN}Android application built successfully${NC}"
}

# Main build process
main() {
    echo ""
    check_prerequisites
    echo ""
    
    # Note: Native build requires proper NDK setup
    # For now, we'll skip native build and let Gradle handle it
    # build_native
    
    build_android
    
    echo ""
    echo "========================================"
    echo -e "${GREEN}Build completed successfully!${NC}"
    echo "========================================"
    echo ""
    echo "APK location: android/app/build/outputs/apk/debug/"
    echo ""
}

main "$@"
