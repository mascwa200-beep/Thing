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
        js_jni.cpp
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

    # ⚠️ THE KV-CACHE RESET IS A MOVING NAME, SO IT IS READ OUT OF THE HEADER RATHER THAN RECALLED.
    # llama.cpp has spelled "forget everything from the last prompt" three ways across build
    # generations — llama_kv_cache_clear(ctx), then llama_kv_self_clear(ctx), then
    # llama_memory_clear(llama_get_memory(ctx), true) — and which one a pinned build number carries
    # is a fact about that tag. Betting on it cost a 25-minute CI round once already: the pin has the
    # NEW model/vocab accessors and the OLD cache API, a combination no amount of reasoning about
    # "how recent is this tag" would have predicted, because the two renames landed months apart.
    #
    # Detecting it here rather than in the workflow means a tag bump in either direction keeps
    # building with no edit, and the choice is printed at configure time so the log always says which
    # generation was linked.
    set(LCARS_LLAMA_KV_API "")
    if(EXISTS ${LLAMA_DIR}/include/llama.h)
        file(READ ${LLAMA_DIR}/include/llama.h LCARS_LLAMA_HEADER)
        # Both names are required for the newest form: the clear takes a memory handle that only
        # exists once the accessor does, and matching on the clear alone would pick a generation
        # whose other half is missing.
        if(LCARS_LLAMA_HEADER MATCHES "llama_memory_clear" AND LCARS_LLAMA_HEADER MATCHES "llama_get_memory")
            set(LCARS_LLAMA_KV_API LCARS_LLAMA_KV_MEMORY)
        elseif(LCARS_LLAMA_HEADER MATCHES "llama_kv_self_clear")
            set(LCARS_LLAMA_KV_API LCARS_LLAMA_KV_SELF)
        elseif(LCARS_LLAMA_HEADER MATCHES "llama_kv_cache_clear")
            set(LCARS_LLAMA_KV_API LCARS_LLAMA_KV_CACHE)
        endif()
    endif()

    # ⚠️ FATAL, NOT A WARNING WITH THE RESET SKIPPED. Every judgement is single-turn and supplies its
    # whole prompt, so without a reset the second one decodes on top of the first one's context: a
    # fluent answer about an argument nobody made. That is precisely the failure the adjudicator
    # exists to prevent, and it would ship green. Failing here turns an unknown fourth spelling into
    # a configure error that names all three known ones instead of a mystery on the device.
    if(LCARS_LLAMA_KV_API STREQUAL "")
        message(FATAL_ERROR
                "lcarsnative: this llama.cpp carries none of llama_memory_clear, llama_kv_self_clear "
                "or llama_kv_cache_clear. The KV reset has been renamed again - read include/llama.h, "
                "add the spelling here and to reset_context() in llama_jni.cpp.")
    endif()
    target_compile_definitions(lcarsnative PRIVATE ${LCARS_LLAMA_KV_API}=1)
    message(STATUS "lcarsnative: llama.cpp KV reset API is ${LCARS_LLAMA_KV_API}")
    if(TARGET ggml)
        set(LCARS_GGML_PROVIDED ON)
        message(STATUS "lcarsnative: llama.cpp found and defines the ggml targets")
    else()
        message(STATUS "lcarsnative: llama.cpp found but no ggml target - whisper will build its own")
    endif()
else()
    message(STATUS "lcarsnative: no llama.cpp tree - the adjudicator will be absent")
endif()

# ---- QuickJS ---------------------------------------------------------------------------------
#
# The JavaScript engine yt-dlp needs for YouTube. quickjs-ng rather than Bellard's original because
# it ships a real CMakeLists (the original is a Makefile), and because yt-dlp names it as supported.
#
# ⚠️ The static library target is `qjs` — read out of the pinned tag's own CMakeLists, not recalled;
# `qjs_exe` and `qjsc` are the command-line tools and are deliberately not built. The whole library
# is a handful of C files, which is also why it can be built and RUN on the host before CI sees it —
# and doing that is what caught the pin being wrong. See the workflow: v0.11.0 could not solve a real
# challenge in three minutes where v0.16.0 does it in eight seconds.
set(QUICKJS_DIR ${CMAKE_CURRENT_SOURCE_DIR}/quickjs)
if(EXISTS ${QUICKJS_DIR}/CMakeLists.txt)
    set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
    # ⚠️ BOTH NAMES READ OUT OF THE PINNED TAG'S OWN CMakeLists, NOT RECALLED. A first draft also set
    # `QJS_ENABLE_INSTALL`, which does not exist in this project at all — the install rules are
    # guarded by `if(NOT IOS)` — so that line would have sat here implying it disabled something
    # while doing nothing whatsoever. Setting a name that is not an option is silent in CMake, which
    # is exactly why it has to be checked rather than assumed.
    #
    # `QJS_BUILD_LIBC` already defaults OFF upstream; it is stated anyway so a future flip of that
    # default cannot quietly pull the file/OS bindings into a JavaScript engine that runs untrusted
    # player code. That is a security property, not tidiness.
    set(QJS_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
    set(QJS_BUILD_LIBC OFF CACHE BOOL "" FORCE)
    # ⚠️ Cheap insurance against a link error 20 minutes into a CI round. A static library is being
    # linked INTO a shared one, which needs position-independent code, and quickjs-ng's CMakeLists
    # says nothing about PIC either way. The NDK's clang defaults to -fPIC for Android targets, so
    # this is expected to be redundant — but "expected to be redundant" and "verified" are different
    # things, and this line costs nothing where the round costs half an hour.
    set(CMAKE_POSITION_INDEPENDENT_CODE ON)
    add_subdirectory(${QUICKJS_DIR} ${CMAKE_CURRENT_BINARY_DIR}/quickjs EXCLUDE_FROM_ALL)
    target_link_libraries(lcarsnative qjs)
    target_include_directories(lcarsnative PRIVATE ${QUICKJS_DIR})
    # Gates the entry points themselves, so the ABSENCE of the symbol from the shipped library is
    # what CI checks — the same reasoning as HAVE_WHISPER. A runtime flag would be a claim.
    target_compile_definitions(lcarsnative PRIVATE HAVE_QUICKJS=1)
    message(STATUS "lcarsnative: quickjs-ng found, JavaScript enabled")
else()
    message(STATUS "lcarsnative: no quickjs tree - YouTube extraction will have no JS runtime")
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
