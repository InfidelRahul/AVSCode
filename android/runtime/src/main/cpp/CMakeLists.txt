cmake_minimum_required(VERSION 4.4.3)

project("avscode-runtime" VERSION 1.0.0 LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_C_STANDARD 11)

# Enable position independent code for all targets
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# Find required packages
find_package(Android REQUIRED)

# Set Android ABI for loader address
set(TARGET_ABI "${ANDROID_ABI}")
message(STATUS "Building for ABI: ${TARGET_ABI}")

# Set loader address based on ABI (matching proot-repo src/arch.h)
if(TARGET_ABI STREQUAL "arm64-v8a")
    set(LOADER_ADDRESS "0x2000000000")
elseif(TARGET_ABI STREQUAL "armeabi-v7a")
    set(LOADER_ADDRESS "0xa0000000")
elseif(TARGET_ABI STREQUAL "x86")
    set(LOADER_ADDRESS "0x20000000")
elseif(TARGET_ABI STREQUAL "x86_64")
    set(LOADER_ADDRESS "0x600000000000")
else()
    set(LOADER_ADDRESS "0x2000000000")
endif()

# PRoot source paths
set(PROOT_ROOT "/workspace/proot-repo/src")
set(SHM_ROOT "/workspace/proot-repo/third_party/libandroid-shmem")
set(TALLOC_ROOT "/workspace/proot-repo/third_party/talloc")

# Generate build.h
file(WRITE "${CMAKE_CURRENT_BINARY_DIR}/build.h" [=[
#ifndef BUILD_H
#define BUILD_H
#define VERSION "5.1.107.92"
#define HAVE_PROCESS_VM 1
#define HAVE_SECCOMP_FILTER 1
#endif
]=])

# Samba talloc library (static)
add_library(talloc STATIC "${TALLOC_ROOT}/talloc.c")
target_include_directories(talloc PUBLIC "${TALLOC_ROOT}")
target_compile_definitions(talloc PRIVATE
    _GNU_SOURCE
    __STDC_WANT_LIB_EXT1__=1
    TALLOC_BUILD_VERSION_MAJOR=2
    TALLOC_BUILD_VERSION_MINOR=5
    TALLOC_BUILD_VERSION_RELEASE=0
)
target_compile_options(talloc PRIVATE
    -fPIC
    -Wno-implicit-function-declaration
    -Wno-error=implicit-function-declaration
    -Wno-incompatible-function-pointer-types
)

# Android Shared Memory emulation (static)
add_library(android-shmem STATIC "${SHM_ROOT}/shmem.c")
target_include_directories(android-shmem PUBLIC "${SHM_ROOT}")
target_compile_definitions(android-shmem PRIVATE _PATH_TMP="/data/data/com.avscode/cache/")
target_compile_options(android-shmem PRIVATE
    -fPIC
    -Wno-implicit-function-declaration
    -Wno-error=implicit-function-declaration
    -Wno-incompatible-function-pointer-types
)
target_link_libraries(android-shmem PUBLIC log android)

# PRoot Core sources - ALL source files
set(PROOT_SOURCES
    # CLI
    ${PROOT_ROOT}/cli/cli.c
    ${PROOT_ROOT}/cli/proot.c
    ${PROOT_ROOT}/cli/note.c
    
    # Execve
    ${PROOT_ROOT}/execve/enter.c
    ${PROOT_ROOT}/execve/exit.c
    ${PROOT_ROOT}/execve/shebang.c
    ${PROOT_ROOT}/execve/elf.c
    ${PROOT_ROOT}/execve/ldso.c
    ${PROOT_ROOT}/execve/auxv.c
    ${PROOT_ROOT}/execve/aoxp.c
    
    # Path
    ${PROOT_ROOT}/path/binding.c
    ${PROOT_ROOT}/path/glue.c
    ${PROOT_ROOT}/path/canon.c
    ${PROOT_ROOT}/path/f2fs-bug.c
    ${PROOT_ROOT}/path/path.c
    ${PROOT_ROOT}/path/proc.c
    ${PROOT_ROOT}/path/temp.c
    
    # Syscall
    ${PROOT_ROOT}/syscall/seccomp.c
    ${PROOT_ROOT}/syscall/syscall.c
    ${PROOT_ROOT}/syscall/chain.c
    ${PROOT_ROOT}/syscall/enter.c
    ${PROOT_ROOT}/syscall/exit.c
    ${PROOT_ROOT}/syscall/sysnum.c
    ${PROOT_ROOT}/syscall/socket.c
    ${PROOT_ROOT}/syscall/heap.c
    ${PROOT_ROOT}/syscall/rlimit.c
    ${PROOT_ROOT}/syscall/pipe_shadow.c
    
    # Tracee
    ${PROOT_ROOT}/tracee/tracee.c
    ${PROOT_ROOT}/tracee/mem.c
    ${PROOT_ROOT}/tracee/reg.c
    ${PROOT_ROOT}/tracee/event.c
    ${PROOT_ROOT}/tracee/seccomp.c
    ${PROOT_ROOT}/tracee/statx.c
    
    # Ptrace
    ${PROOT_ROOT}/ptrace/ptrace.c
    ${PROOT_ROOT}/ptrace/user.c
    ${PROOT_ROOT}/ptrace/wait.c
    
    # Extension core
    ${PROOT_ROOT}/extension/extension.c
    
    # Extension: ashmem_memfd
    ${PROOT_ROOT}/extension/ashmem_memfd/ashmem_memfd.c
    
    # Extension: kompat
    ${PROOT_ROOT}/extension/kompat/kompat.c
    
    # Extension: fake_id0 (all files)
    ${PROOT_ROOT}/extension/fake_id0/fake_id0.c
    ${PROOT_ROOT}/extension/fake_id0/chown.c
    ${PROOT_ROOT}/extension/fake_id0/chroot.c
    ${PROOT_ROOT}/extension/fake_id0/getsockopt.c
    ${PROOT_ROOT}/extension/fake_id0/sendmsg.c
    ${PROOT_ROOT}/extension/fake_id0/socket.c
    ${PROOT_ROOT}/extension/fake_id0/open.c
    ${PROOT_ROOT}/extension/fake_id0/unlink.c
    ${PROOT_ROOT}/extension/fake_id0/rename.c
    ${PROOT_ROOT}/extension/fake_id0/chmod.c
    ${PROOT_ROOT}/extension/fake_id0/utimensat.c
    ${PROOT_ROOT}/extension/fake_id0/access.c
    ${PROOT_ROOT}/extension/fake_id0/exec.c
    ${PROOT_ROOT}/extension/fake_id0/link.c
    ${PROOT_ROOT}/extension/fake_id0/symlink.c
    ${PROOT_ROOT}/extension/fake_id0/mk.c
    ${PROOT_ROOT}/extension/fake_id0/stat.c
    ${PROOT_ROOT}/extension/fake_id0/helper_functions.c
    
    # Extension: hidden_files
    ${PROOT_ROOT}/extension/hidden_files/hidden_files.c
    
    # Extension: mountinfo
    ${PROOT_ROOT}/extension/mountinfo/mountinfo.c
    
    # Extension: port_switch
    ${PROOT_ROOT}/extension/port_switch/port_switch.c
    
    # Extension: sysvipc (all files)
    ${PROOT_ROOT}/extension/sysvipc/sysvipc.c
    ${PROOT_ROOT}/extension/sysvipc/sysvipc_msg.c
    ${PROOT_ROOT}/extension/sysvipc/sysvipc_sem.c
    ${PROOT_ROOT}/extension/sysvipc/sysvipc_shm.c
    
    # Extension: link2symlink
    ${PROOT_ROOT}/extension/link2symlink/link2symlink.c
    
    # Extension: fix_symlink_size
    ${PROOT_ROOT}/extension/fix_symlink_size/fix_symlink_size.c
)

# Build PRoot as a static library for linking into our runtime
add_library(proot_core STATIC ${PROOT_SOURCES})
target_include_directories(proot_core PRIVATE
    "${PROOT_ROOT}"
    "${CMAKE_CURRENT_BINARY_DIR}"
    "${TALLOC_ROOT}"
    "${SHM_ROOT}"
)
target_compile_definitions(proot_core PRIVATE
    _FILE_OFFSET_BITS=64
    _GNU_SOURCE
    ARG_MAX=131072
    WITH_LIBANDROID_SHMEM
    HAVE_POKEDATA_WORKAROUND=1
)
target_compile_options(proot_core PRIVATE
    -Wall -Wextra -O2
    -Wno-implicit-function-declaration
    -Wno-error=implicit-function-declaration
    -Wno-incompatible-function-pointer-types
    -fPIC
)
target_link_libraries(proot_core PRIVATE talloc android-shmem)

# Native spawn helper from proot-repo
add_library(native_spawn SHARED "${PROOT_ROOT}/native/native_spawn.c")
target_compile_options(native_spawn PRIVATE
    -Wall -Wextra -O2
    -Wno-implicit-function-declaration
    -Wno-error=implicit-function-declaration
)
target_link_libraries(native_spawn android log)

# Main AVscode runtime library
add_library(
    avscode-runtime
    SHARED
    native_runtime.cpp
)

target_include_directories(
    avscode-runtime
    PRIVATE
    ${CMAKE_SOURCE_DIR}
    ${PROOT_ROOT}
    ${CMAKE_CURRENT_BINARY_DIR}
)

target_compile_definitions(avscode-runtime PRIVATE
    PROOT_BINARY_PATH="${CMAKE_LIBRARY_OUTPUT_DIRECTORY}/libproot.so"
)

target_link_libraries(
    avscode-runtime
    android
    log
    proot_core
    talloc
    android-shmem
)

# 16KB ELF page alignment for Android 15
set_target_properties(proot_core PROPERTIES LINK_FLAGS "-Wl,-z,max-page-size=16384")
set_target_properties(native_spawn PROPERTIES LINK_FLAGS "-Wl,-z,max-page-size=16384")
set_target_properties(avscode-runtime PROPERTIES LINK_FLAGS "-Wl,-z,max-page-size=16384")
