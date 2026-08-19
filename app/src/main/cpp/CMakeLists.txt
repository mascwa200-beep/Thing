# The native side of the acoustic interrogator.
#
# ⚠️ N0 — TOOLCHAIN PROOF. Right now this builds ONE trivial translation unit and nothing else.
# That is deliberate. whisper.cpp and llama.cpp are both large CMake trees, and this repository has
# never compiled a line of native code: every .so it ships (Vosk, JNA, MediaPipe, MapLibre,
# ExoPlayer) arrives prebuilt inside an AAR. There is no NDK in the development container and
# GitHub is unreachable through its proxy, so the first compile of anything here happens on a CI
# runner, ~20-35 minutes per attempt, with no local gate whatsoever.
#
# Proving the toolchain by itself first means that when the two upstreams are added and something
# breaks, the failure is unambiguously in THEM rather than in the NDK version, the CMake version,
# the ABI filter, the AGP wiring or the packaging step. Cheap insurance against a very slow loop.
#
# ⚠️ Upstream sources are NOT vendored and are NOT git submodules. Submodules need a pinned commit
# SHA, and a SHA cannot be obtained from a container that cannot reach GitHub. Instead the CI
# workflow shallow-clones each project at a pinned TAG into this directory before Gradle runs, and
# .gitignore keeps those trees out of the repository. When N1/N2 land, the add_subdirectory lines
# below come with them.

cmake_minimum_required(VERSION 3.22.1)
project(lcarsnative CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Size and speed both matter on a phone: -O3 for the DSP-heavy code that is coming, and hidden
# visibility so only the JNI entry points are exported once the two upstreams are linked in.
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -O3 -fvisibility=hidden")

add_library(lcarsnative SHARED
        lcars_native.cpp
        whisper_jni.cpp
        llama_jni.cpp
)

# ---- whisper.cpp -----------------------------------------------------------------------------
#
# The tree is cloned by CI at a pinned tag (see .github/workflows/android-build.yml) and is NOT in
# the repository — see the header of this file for why a submodule is not an option here.
#
# ⚠️ The `if(EXISTS)` is for the LOCAL host gate, not for CI. Without it, `cmake --build` in a
# checkout that has never run the clone step fails outright, and the cheap local syntax check that
# caught a missing jni.h earlier stops working. On CI the guard must never be the thing that decides:
# the workflow asserts the tree is present after cloning, and asserts the whisper JNI symbols are in
# the shipped .so afterwards. A build that quietly lost speech recognition and still went green would
# be far worse than one that failed.
# ---- llama.cpp -------------------------------------------------------------------------------
#
# ⚠️ ADDED BEFORE whisper.cpp, DELIBERATELY. Both projects bundle ggml, and two add_subdirectory
# calls that each define targets named `ggml`, `ggml-base` and `ggml-cpu` collide outright. llama.cpp
# is the upstream home of ggml and carries the fuller build, so it defines the targets and whisper is
# told to reuse them. If that reuse does not take, the failure is a CMake duplicate-target error
# naming the exact target, which is unambiguous - and the workflow prints how each tree guards its
# own ggml before the build even starts, so one round explains it.
set(LLAMA_DIR ${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp)
set(LCARS_GGML_PROVIDED OFF)
if(EXISTS ${LLAMA_DIR}/CMakeLists.txt)
    set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
    set(LLAMA_CURL OFF CACHE BOOL "" FORCE)
    set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
    add_subdirectory(${LLAMA_DIR} ${CMAKE_CURRENT_BINARY_DIR}/llama EXCLUDE_FROM_ALL)
    target_link_libraries(lcarsnative llama)
    target_compile_definitions(lcarsnative PRIVATE HAVE_LLAMA=1)
    if(TARGET ggml)
        set(LCARS_GGML_PROVIDED ON)
        message(STATUS "lcarsnative: llama.cpp found and defines the ggml targets")
    else()
        message(STATUS "lcarsnative: llama.cpp found but no ggml target - whisper will build its own")
    endif()
else()
    message(STATUS "lcarsnative: no llama.cpp tree - the adjudicator will be absent")
endif()

set(WHISPER_DIR ${CMAKE_CURRENT_SOURCE_DIR}/whisper.cpp)
if(EXISTS ${WHISPER_DIR}/CMakeLists.txt)
    # Static, so everything lands in liblcarsnative.so and there are no extra .so files to package
    # or to keep in step. Tests/examples/server off because they are megabytes of tools nobody here
    # runs. OpenMP off because the NDK does not ship libomp on the default sysroot path and ggml
    # falls back to its own threading, which is what the phone wants anyway.
    set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
    # Reuse llama's ggml rather than building a second copy under a colliding name. Belt and braces:
    # the option is only meaningful if this whisper tag has it, and the `if(NOT TARGET ggml)` guard
    # that recent whisper releases carry covers the case where it does not.
    if(LCARS_GGML_PROVIDED)
        set(WHISPER_USE_SYSTEM_GGML ON CACHE BOOL "" FORCE)
    endif()
    set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
    set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
    set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
    set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
    add_subdirectory(${WHISPER_DIR} ${CMAKE_CURRENT_BINARY_DIR}/whisper EXCLUDE_FROM_ALL)
    target_link_libraries(lcarsnative whisper)
    # Gates the JNI entry points themselves, so their ABSENCE from the shipped library is what CI
    # checks. A flag the runtime reports would be a claim; a missing symbol is a fact.
    target_compile_definitions(lcarsnative PRIVATE HAVE_WHISPER=1)
    message(STATUS "lcarsnative: whisper.cpp found, speech recognition enabled")
else()
    message(STATUS "lcarsnative: no whisper.cpp tree - building the host gate only")
endif()

# ⚠️ Off Android, jni.h has to be found explicitly, and without this the local gate below only ever
# CONFIGURES — the compile fails immediately on `#include <jni.h>`. The NDK puts the header on its
# sysroot include path so nothing is needed there; a host toolchain has it in the JDK instead. This
# was caught by actually running the host build rather than assuming it worked, which is the whole
# reason to keep a local gate: a gate that cannot run is worth nothing.
# Belt and braces: the NDK toolchain file sets ANDROID, but if that ever changed, adding the host
# JDK's linux headers to an Android cross-compile would be a 25-minute CI round to discover.
if(NOT ANDROID AND NOT CMAKE_SYSTEM_NAME STREQUAL "Android")
    find_package(JNI QUIET)
    if(JNI_INCLUDE_DIRS)
        target_include_directories(lcarsnative PRIVATE ${JNI_INCLUDE_DIRS})
    elseif(DEFINED ENV{JAVA_HOME})
        target_include_directories(lcarsnative PRIVATE
                $ENV{JAVA_HOME}/include $ENV{JAVA_HOME}/include/linux)
    endif()
endif()

# log is the only system library needed so far; whisper/llama will add android and OpenMP.
#
# ⚠️ Guarded so this file also CONFIGURES AND BUILDS ON A HOST TOOLCHAIN, which is the point.
# liblog exists only on Android, and the usual unguarded `target_link_libraries(... ${log-lib})`
# makes CMake fail generation everywhere else. Under the NDK the library is always found and the
# behaviour is identical — but off it, this turns `cmake -S app/src/main/cpp -B build && cmake
# --build build` into a real local syntax-and-compile gate. With no NDK in the development
# container and CI rounds running 20-35 minutes once whisper.cpp and llama.cpp are in the build,
# catching a stray brace locally instead of on a runner is worth two lines.
find_library(log-lib log)
if(log-lib)
    target_link_libraries(lcarsnative ${log-lib})
endif()
