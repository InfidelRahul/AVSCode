cmake_minimum_required(VERSION 3.22.1)
project("avscode-runtime" LANGUAGES C CXX ASM)

# Ensure all shared libraries and executables output to the top binary dir for AGP packaging
if(DEFINED CMAKE_LIBRARY_OUTPUT_DIRECTORY)
    set(CMAKE_ARCHIVE_OUTPUT_DIRECTORY "${CMAKE_LIBRARY_OUTPUT_DIRECTORY}")
    set(CMAKE_RUNTIME_OUTPUT_DIRECTORY "${CMAKE_LIBRARY_OUTPUT_DIRECTORY}")
else()
    set(CMAKE_LIBRARY_OUTPUT_DIRECTORY "${CMAKE_CURRENT_BINARY_DIR}")
    set(CMAKE_ARCHIVE_OUTPUT_DIRECTORY "${CMAKE_CURRENT_BINARY_DIR}")
    set(CMAKE_RUNTIME_OUTPUT_DIRECTORY "${CMAKE_CURRENT_BINARY_DIR}")
endif()

# Locate LinuxDroid PRoot repository
set(PROOT_CANDIDATE_DIRS
    "${CMAKE_CURRENT_SOURCE_DIR}/../../../../../proot-repo"
    "${CMAKE_CURRENT_SOURCE_DIR}/../../../../proot-repo"
    "$ENV{PROOT_REPO}"
    "$ENV{GITHUB_WORKSPACE}/proot-repo"
    "/workspaces/AVSCode/proot-repo"
)

set(PROOT_REPO_DIR "")
foreach(dir ${PROOT_CANDIDATE_DIRS})
    if(dir AND EXISTS "${dir}/CMakeLists.txt")
        get_filename_component(PROOT_REPO_DIR "${dir}" ABSOLUTE)
        break()
    endif()
endforeach()

if(NOT PROOT_REPO_DIR)
    find_package(Git QUIET)
    if(GIT_FOUND)
        message(STATUS "Attempting to initialize proot-repo submodule via git...")
        execute_process(
            COMMAND ${GIT_EXECUTABLE} submodule update --init --recursive proot-repo
            WORKING_DIRECTORY "${CMAKE_CURRENT_SOURCE_DIR}/../../../../.."
            RESULT_VARIABLE GIT_SUBMOD_RESULT
        )
        if(EXISTS "${CMAKE_CURRENT_SOURCE_DIR}/../../../../../proot-repo/CMakeLists.txt")
            get_filename_component(PROOT_REPO_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../../../proot-repo" ABSOLUTE)
        endif()
    endif()
endif()

if(NOT PROOT_REPO_DIR)
    message(FATAL_ERROR "LinuxDroid PRoot repository not found. Checked: ${PROOT_CANDIDATE_DIRS}")
endif()

message(STATUS "Including LinuxDroid PRoot from: ${PROOT_REPO_DIR}")
add_subdirectory("${PROOT_REPO_DIR}" "${CMAKE_CURRENT_BINARY_DIR}/proot_build")

# AVscode Native Spawner
add_library(avscodespawn SHARED "${CMAKE_CURRENT_SOURCE_DIR}/avscode_spawn.c")
target_compile_options(avscodespawn PRIVATE
    -Wall -Wextra -O2
    -Wno-implicit-function-declaration
    -Wno-error=implicit-function-declaration
)
target_link_libraries(avscodespawn log android)

if(ANDROID)
    target_link_options(avscodespawn PRIVATE "-Wl,-z,max-page-size=16384")
endif()
