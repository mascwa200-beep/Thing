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
)

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
