# Builds llama.cpp (pinned in tools/build-llama.sh) and Locus's JNI bridge for one Android ABI.
# Not run by Gradle: tools/build-llama.sh runs it and copies the libraries into
# core/ondevice/src/main/jniLibs/<abi>/, the way tools/build-proot.sh does for proot. See
# docs/design/llamacpp.md and docs/FDROID.md.

cmake_minimum_required(VERSION 3.22)
project(locusllama LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

if(NOT DEFINED LLAMA_SRC)
    message(FATAL_ERROR "Set LLAMA_SRC to the llama.cpp source tree.")
endif()

# ggml and llama are shared libraries: GGML_BACKEND_DL loads the CPU backend variants at run time,
# and they link against libggml-base.so. llama.cpp's common library (chat templates, tool-call
# grammars and parsing, sampling) is linked into the bridge statically instead, so the linker can
# drop the parts Locus doesn't use (argument parsing, downloads, the HTTP client): -3.6 MB on arm64.
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
add_subdirectory(${LLAMA_SRC} llama.cpp)

set(BUILD_SHARED_LIBS OFF)
# common's build-info.cpp reads these; tools/build-llama.sh passes the pinned tag and commit. The
# compiler and host are left out on purpose: they differ between machines (reproducible builds).
if(NOT DEFINED LLAMA_BUILD_NUMBER)
    set(LLAMA_BUILD_NUMBER 0)
endif()
if(NOT DEFINED LLAMA_BUILD_COMMIT)
    set(LLAMA_BUILD_COMMIT "unknown")
endif()
set(BUILD_COMPILER "")
set(BUILD_TARGET "")
add_compile_options(-ffunction-sections -fdata-sections)
# llama.cpp only adds its vendored HTTP client when it builds common itself; common links it.
add_subdirectory(${LLAMA_SRC}/vendor/cpp-httplib vendor/cpp-httplib)
add_subdirectory(${LLAMA_SRC}/common common)

add_library(locusllama SHARED locus_llama.cpp)
target_link_libraries(locusllama PRIVATE llama llama-common)
if(ANDROID)
    target_link_libraries(locusllama PRIVATE android log)
    target_link_options(locusllama PRIVATE -Wl,--gc-sections -Wl,--exclude-libs,ALL)
else()
    # A desktop build of the bridge, only for exercising it against a real model on a computer.
    # JNI_INCLUDE: a folder with jni.h (a JDK's include/ or the NDK's sysroot/usr/include).
    if(NOT DEFINED JNI_INCLUDE)
        find_package(JNI REQUIRED)
        set(JNI_INCLUDE ${JNI_INCLUDE_DIRS})
    endif()
    target_include_directories(locusllama PRIVATE ${JNI_INCLUDE})
endif()
