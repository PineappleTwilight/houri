cmake_minimum_required(VERSION 3.22.1)
project(llamatik_llama_jni C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# =============================================================================
# Houri's on-device LLM runtime.
#
# The published `com.llamatik:library` AAR ships a CPU-only llama.cpp build, so its
# `gpuLayers` parameter silently offloads nothing. This module rebuilds the same JNI
# layer from source and can compile the ggml Vulkan backend in, which is the only GPU
# backend with real Android support. The Kotlin wrapper
# (`com.llamatik.library.platform`) is vendored alongside it, so the AAR is not
# consumed at all — see `llamatik-native/AGENTS.md`.
#
# GPU offload is opt-in (see `llamatik-native/build.gradle.kts`):
#   ./gradlew :llamatik-native:assembleDebug -Pmtl.gpuOffload=true \
#       -Pmtl.vulkanIncludeDirs="<Vulkan-Headers>/include;<Vulkan-Hpp>;<SPIRV-Headers>/include"
# It needs a Vulkan SDK (or an equivalent Vulkan-Headers + Vulkan-Hpp + SPIRV-Headers
# triple) and an API level of 29 or newer; see the HOURI_GPU_OFFLOAD block below.
# =============================================================================

# ---- Sources ----------------------------------------------------------------
# `external/llamatik` is the PineappleTwilight fork of ferranpons/Llamatik (MIT),
# which vendors llama.cpp as a nested submodule.
set(LLAMATIK_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../../external/llamatik")
set(LLAMA_CPP_DIR "${LLAMATIK_ROOT}/llama.cpp")
set(LLAMATIK_CPP_DIR "${LLAMATIK_ROOT}/library/src/commonMain/cpp")
set(LLAMATIK_INTEROP_DIR "${LLAMATIK_ROOT}/library/src/commonMain/c_interop/include")
set(MTMD_DIR "${LLAMA_CPP_DIR}/tools/mtmd")

if(NOT EXISTS "${LLAMA_CPP_DIR}/CMakeLists.txt")
    message(FATAL_ERROR
        "llama.cpp not found at ${LLAMA_CPP_DIR}.\n"
        "The llamatik fork's nested submodules are not checked out. Run:\n"
        "  git submodule update --init --recursive external/llamatik")
endif()

# ---- Windows / WSL interop ---------------------------------------------------
# This module is pure CMake driven by AGP's own NDK toolchain, so a CPU-only build needs
# no interop at all: cmake.exe + the Windows NDK work natively, and so do WSL cmake + the
# Linux NDK. Only the Vulkan variant can straddle the two, because its toolchain pieces
# (glslc, Vulkan-Hpp) are usually installed on whichever side the developer set up:
#
#   Gradle on Windows, SDK in WSL  -> cmake.exe must exec a Linux ELF glslc: impossible,
#                                     so we generate a wsl.exe wrapper that also rewrites
#                                     C:/... arguments to /mnt/c/...
#   Gradle in WSL, SDK on Windows   -> the mirror case; we rewrite /mnt/c/... to C:/... and
#                                     exec the Windows binary through WSL's /init
#
# See `mtl_find_glslc` below. The CPU-only path never reaches any of this.

# `/mnt/c/foo` or `/c/foo` -> `C:/foo`. No-op on paths that are already native.
function(mtl_to_windows_path in out)
    if(in MATCHES "^/mnt/([a-zA-Z])/(.*)$")
        set(${out} "${CMAKE_MATCH_1}:/${CMAKE_MATCH_2}" PARENT_SCOPE)
    elseif(in MATCHES "^/([a-zA-Z])/(.*)$")
        set(${out} "${CMAKE_MATCH_1}:/${CMAKE_MATCH_2}" PARENT_SCOPE)
    else()
        set(${out} "${in}" PARENT_SCOPE)
    endif()
endfunction()

# `C:/foo` or `C:\foo` -> `/mnt/c/foo`. No-op on paths that are already native.
function(mtl_to_wsl_path in out)
    string(REPLACE "\\" "/" in "${in}")
    if(in MATCHES "^([a-zA-Z]):/(.*)$")
        string(TOLOWER "${CMAKE_MATCH_1}" _drive)
        set(${out} "/mnt/${_drive}/${CMAKE_MATCH_2}" PARENT_SCOPE)
    else()
        set(${out} "${in}" PARENT_SCOPE)
    endif()
endfunction()

# Writes a text file with LF endings. cmake's file(WRITE) emits CRLF when the host is
# Windows, which bash then refuses to run.
function(mtl_write_unix_script path content)
    file(WRITE "${path}.tmp" "${content}")
    file(READ "${path}.tmp" _text)
    string(REPLACE "\r\n" "\n" _text "${_text}")
    file(WRITE "${path}" "${_text}")
    file(REMOVE "${path}.tmp")
    execute_process(COMMAND chmod +x "${path}" ERROR_QUIET)
endfunction()

# Locates a usable glslc and sets Vulkan_GLSLC_EXECUTABLE so FindVulkan picks it up
# (FindVulkan honours a preset value).
#
# The real tool is never handed to CMake directly; it is always invoked through a small
# wrapper that:
#   * rewrites C:/... <-> /mnt/c/... arguments when the tool lives on the other side of the
#     Windows/WSL boundary, and
#   * flattens stderr to a single line with no ';', quotes or backslashes.
# The second point is not cosmetic. ggml-vulkan's test_shader_extension_support() does
# `if(${glslc_error} MATCHES ".*extension not supported: <ext>.*")` with the variable
# pasted UNQUOTED, so any ';', '"' or newline in glslc's diagnostics turns the condition
# into extra arguments and CMake aborts with "Unknown arguments specified". stdout is left
# untouched because glslc emits the compiled SPIR-V there (`-o -`).
#
# A Windows-hosted build proxies into WSL, mirroring external/imagedecoder-houri: the Vulkan
# tools are usually installed in the Linux distro rather than on Windows, and a Windows
# cmake.exe cannot exec a Linux ELF. The cross-compiler, assembler and linker are
# untouched — AGP has already selected the Windows NDK by the time this file runs.
#
# Note the asymmetry: glslc can be proxied because it is *executed* here, but Vulkan headers
# cannot — they are *read* by AGP's Windows compiler, so they must live on a filesystem
# Windows can reach.
# Locates glslc and installs a wrapper that makes it safe for ggml-vulkan to call.
#
# There is deliberately NO wsl.exe proxy here. A Windows Gradle build no longer configures
# CMake at all: :llamatik-native runs the whole native build inside WSL and hands AGP the
# finished libllama_jni.so (see build.gradle.kts, "wslNativeBuild"). So this always runs on
# Linux/WSL/CI, where a native glslc is what we want, and where using wsl.exe would be
# actively wrong -- it is on PATH *inside* WSL, so proxying through it would send ggml-vulkan's
# ~40 shader compiles per ABI out to Windows and back, which looks exactly like a hang.
#
# A Windows host that reaches this point (a manual cmake invocation) can still work, because
# the NDK bundles a glslc for the host; it just cannot borrow WSL's toolchain.
function(mtl_find_glslc)
    # Probe absolute locations rather than trusting find_program alone: its result is cached,
    # so one configure that could not see glslc would keep reporting NOTFOUND forever after
    # the tool becomes available.
    set(_mtl_real "")
    set(_mtl_candidates "")
    if(DEFINED ENV{VULKAN_SDK})
        list(APPEND _mtl_candidates "$ENV{VULKAN_SDK}/bin/glslc" "$ENV{VULKAN_SDK}/bin/glslc.exe")
    endif()
    list(APPEND _mtl_candidates
        /usr/bin/glslc
        /usr/local/bin/glslc
        /opt/vulkan-sdk/bin/glslc
        "C:/VulkanSDK/bin/glslc.exe")
    foreach(_mtl_c IN LISTS _mtl_candidates)
        if(NOT _mtl_real AND EXISTS "${_mtl_c}")
            set(_mtl_real "${_mtl_c}")
        endif()
    endforeach()
    if(NOT _mtl_real)
        find_program(_mtl_found NAMES glslc NO_CACHE)
        if(_mtl_found)
            set(_mtl_real "${_mtl_found}")
        endif()
    endif()

    # The NDK bundles a shaderc/glslc at shader-tools/<host>/, but the NDK toolchain's find
    # root hides it, so find_package(Vulkan COMPONENTS glslc) never sees it. It is older
    # (shaderc 2022.3), so cooperative-matrix / bf16 shaders report "not supported" and those
    # optional features are disabled; the base shaders still compile. This is what keeps a
    # Windows-side manual configure working at all.
    if(NOT _mtl_real AND CMAKE_ANDROID_NDK)
        # AGP hands this over as C:\Users\... on Windows; a backslash path defeats the glob.
        file(TO_CMAKE_PATH "${CMAKE_ANDROID_NDK}" _mtl_ndk)
        file(GLOB _mtl_ndk_glslc
            "${_mtl_ndk}/shader-tools/*/glslc"
            "${_mtl_ndk}/shader-tools/*/glslc.exe")
        foreach(_mtl_c IN LISTS _mtl_ndk_glslc)
            if(NOT _mtl_real AND EXISTS "${_mtl_c}")
                set(_mtl_real "${_mtl_c}")
            endif()
        endforeach()
        if(_mtl_real)
            message(STATUS "Houri MTL: using the glslc bundled with the NDK")
        endif()
    endif()

    message(STATUS "Houri MTL: glslc resolution -- mode='${MTL_VULKAN_TOOLCHAIN}' "
                   "host='${CMAKE_HOST_SYSTEM}' real='${_mtl_real}' "
                   "(probed \$VULKAN_SDK/bin/glslc, /usr/bin/glslc, /usr/local/bin/glslc, "
                   "/opt/vulkan-sdk/bin/glslc, PATH, and ${CMAKE_ANDROID_NDK}/shader-tools/*)")

    if(NOT _mtl_real)
        if(CMAKE_HOST_WIN32)
            message(FATAL_ERROR
                "No native glslc found on this Windows host.\n"
                "  A normal `gradlew.bat` build does not get here: :llamatik-native runs the\n"
                "  whole native build inside WSL and packages the result, precisely because\n"
                "  the Vulkan headers live in the WSL filesystem where clang.exe cannot read\n"
                "  them. Build via Gradle, or run cmake inside WSL.\n"
                "  Building CPU-only for now:  -Pmtl.gpuOffload=false")
        endif()
        message(FATAL_ERROR
            "No glslc found.\n"
            "  Install it:  sudo apt-get install -y glslc\n"
            "  Or point at a SDK:  -Pmtl.vulkanSdkDir=/path/to/VulkanSDK/<ver>\n"
            "  Or build CPU-only for now:  -Pmtl.gpuOffload=false")
    endif()

    # Always invoke glslc through a wrapper, for one reason: ggml-vulkan's
    # test_shader_extension_support() does `if(${glslc_error} MATCHES ...)` with the variable
    # pasted UNQUOTED, so any ';', quote or newline in glslc's diagnostics splits the condition
    # into extra arguments and configure aborts with "Unknown arguments specified". The wrapper
    # flattens stderr to a single safe line.
    set(_mtl_wrap_dir "${CMAKE_BINARY_DIR}/mtl-glslc")
    file(MAKE_DIRECTORY "${_mtl_wrap_dir}")
    mtl_write_unix_script("${_mtl_wrap_dir}/mtl-glslc.sh"
"# Generated by :llamatik-native. Wraps glslc so ggml-vulkan can use it safely.
set -u
# stdout carries the compiled SPIR-V (glslc -o -) and must pass through untouched.
err=\$(mktemp)
trap 'rm -f \"\$err\"' EXIT
\"${_mtl_real}\" \"\$@\" 2>\"\$err\"
rc=\$?
# Flatten stderr: llama.cpp pastes it unquoted into if(... MATCHES ...), so a ';', a quote
# or a newline would split the condition into extra arguments and abort the configure.
# This goes to STDERR, never stdout, or the SPIR-V binary would be corrupted and the link
# would fail with undefined *_data / *_len symbols.
tr '\\r\\n' '  ' < \"\$err\" | sed -e 's/;/ /g' -e 's/\"/ /g' -e \"s/'/ /g\" -e 's/\\\\\\\\/ /g' >&2
rm -f \"\$err\"
exit \$rc
")
    set(Vulkan_GLSLC_EXECUTABLE "${_mtl_wrap_dir}/mtl-glslc.sh" CACHE FILEPATH "" FORCE)
    message(STATUS "Houri MTL: using glslc: ${_mtl_real}")
endfunction()

# ---- GPU offload (opt-in) ---------------------------------------------------
option(HOURI_GPU_OFFLOAD "Compile the ggml Vulkan backend for on-device LLM offload" OFF)

# ggml-vulkan does not compile for 32-bit ABIs at this llama.cpp revision: it relies on
# implicit vk::Buffer conversions that Vulkan-Hpp only provides when the native handle is a
# pointer (i.e. 64-bit). On armeabi-v7a/x86 the handle is a uint64_t and both
# `stream << handle` and `copyBuffer((VkBuffer) handle, ...)` fail to compile.
#
# Rather than fork-patch upstream per breakage, downgrade 32-bit ABIs to a CPU-only build.
# Every ABI still ships a working on-device LLM; 64-bit devices get the full GPU runtime, and
# 32-bit ones fall back to the CPU path the app already handles (LocalLlmAccelerator forces
# gpuLayers back to 0). Doing it here means AGP's per-ABI Android builds, the WSL build and
# CI all get the same behaviour from one place.
if(HOURI_GPU_OFFLOAD AND ANDROID_ABI AND
   NOT ANDROID_ABI MATCHES "^(arm64-v8a|x86_64|riscv64)$")
    message(STATUS
        "Houri MTL: ${ANDROID_ABI} is 32-bit and ggml-vulkan does not build for it at this "
        "llama.cpp revision; building CPU-only for this ABI. Use -Pmtl.gpuOffload=false to "
        "drop GPU offload entirely.")
    set(HOURI_GPU_OFFLOAD OFF)
endif()

# Link ggml statically so a compiled-in backend lands inside libllama_jni.so. The
# published AAR is built with GGML_BACKEND_DL=ON, which is why it ships a separate
# libggml-cpu.so and dlopens backend plugins at runtime instead.
set(GGML_BACKEND_DL OFF CACHE BOOL "Statically link ggml backends" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "Static libraries" FORCE)

if(HOURI_GPU_OFFLOAD)
    # On Android, CUDA/Metal/SYCL are desktop-only and OpenCL is not part of the
    # platform; Vulkan is the system graphics API. Hexagon/QNN needs a licensed
    # vendor SDK, so there is no NPU backend for GGUF.
    set(GGML_VULKAN ON CACHE BOOL "Vulkan GPU backend" FORCE)

    # ggml-vulkan needs a Vulkan *1.1* core entry point (vkGetPhysicalDeviceFeatures2).
    # The NDK's libvulkan.so import stub only declares it from API 29, so the Vulkan
    # variant must be compiled against a higher platform than the app's minSdk 26.
    # Devices below that simply keep using the CPU path at runtime.
    if(DEFINED ANDROID_PLATFORM AND ANDROID_PLATFORM MATCHES "^[0-9]+$")
        set(_mtl_api "${ANDROID_PLATFORM}")
    elseif(DEFINED ANDROID_PLATFORM AND ANDROID_PLATFORM MATCHES "^android-([0-9]+)$")
        set(_mtl_api "${CMAKE_MATCH_1}")
    else()
        set(_mtl_api "${ANDROID_NATIVE_API_LEVEL}")
    endif()
    if(_mtl_api AND _mtl_api LESS 29)
        message(FATAL_ERROR
            "GPU offload needs -DANDROID_PLATFORM=android-29 or newer (got '${ANDROID_PLATFORM}').\n"
            "The NDK's libvulkan.so stub below API 29 does not export the Vulkan 1.1 symbols\n"
            "ggml-vulkan requires, which shows up as 'undefined symbol: vkGetPhysicalDeviceFeatures2'.")
    endif()

    # The NDK does supply glslc (under shader-tools/) and the Vulkan loader stub, so the
    # LunarG SDK is not required for shaders. It does NOT supply Vulkan-Hpp's
    # <vulkan/vulkan.hpp>, and its bundled Vulkan-Headers is too old to pair with a
    # current Vulkan-Hpp (VK_HEADER_VERSION 275 vs 363). ggml-vulkan also calls
    # find_package(SPIRV-Headers) without linking the target, so its include dir never
    # reaches the compiler. Point MTL_VULKAN_SDK_DIR at a Vulkan SDK, or pass explicit
    # include dirs via MTL_VULKAN_INCLUDE_DIRS (Vulkan-Headers, Vulkan-Hpp, SPIRV-Headers).
    # The dirs themselves are applied to the ggml-vulkan target after add_subdirectory.
    # The NDK supplies the Vulkan loader stub and vulkan_core.h, but NOT Vulkan-Hpp's
    # <vulkan/vulkan.hpp>, NOT SPIRV-Headers, and NOT a discoverable glslc (the NDK's
    # shader-tools/glslc is not on PATH, and the NDK toolchain's find root hides it).
    # ggml-vulkan also calls find_package(SPIRV-Headers) without linking the target, so
    # that package's include dir never reaches the compiler — upstream only works because a
    # LunarG SDK puts everything under one include prefix.
    #
    # With nothing configured, probe the staging location the documented Debian/Ubuntu and
    # CI setup creates. A host /usr/include can never be used here: it would shadow the NDK
    # sysroot's libc headers (host features-time64.h wins, then bits/wordsize.h is missing),
    # and CMake drops /usr/include from include paths anyway.
    if(NOT DEFINED MTL_VULKAN_SDK_DIR AND NOT DEFINED MTL_VULKAN_INCLUDE_DIRS)
        set(_mtl_stages /opt/vulkan-headers /usr/local/share/vulkan-headers)
        if(CMAKE_HOST_WIN32)
            # A Windows-side build cannot read the WSL filesystem, so the Debian staging path
            # above never matches here; probe the equivalents on this side instead.
            list(APPEND _mtl_stages
                "C:/vulkan-headers"
                "C:/VulkanSDK/include"
                "C:/Program Files/VulkanSDK/include"
                "$ENV{USERPROFILE}/vulkan-headers")
            file(GLOB _mtl_sdk_incs "C:/VulkanSDK/*/include")
            list(APPEND _mtl_stages ${_mtl_sdk_incs})
        endif()
        foreach(_mtl_stage IN LISTS _mtl_stages)
            if(IS_DIRECTORY "${_mtl_stage}/vulkan" AND IS_DIRECTORY "${_mtl_stage}/vk_video")
                set(MTL_VULKAN_INCLUDE_DIRS "${_mtl_stage}" CACHE STRING "" FORCE)
                message(STATUS "Houri MTL: auto-detected staged Vulkan headers at ${_mtl_stage}")
                break()
            endif()
        endforeach()
    endif()

    if(DEFINED MTL_VULKAN_SDK_DIR)
        list(APPEND CMAKE_PREFIX_PATH "${MTL_VULKAN_SDK_DIR}")
        message(STATUS "Houri MTL: using Vulkan SDK at ${MTL_VULKAN_SDK_DIR}")
    endif()

    # Resolve and normalise the header dirs up front: they are needed both to build the
    # SPIRV-Headers shim below (which runs before ggml-vulkan is added) and, later, as -I
    # flags on the ggml-vulkan target. Rewriting any path that belongs to the other side of
    # the Windows/WSL boundary lets one -DMTL_VULKAN_INCLUDE_DIRS work from either host.
    set(_mtl_vk_includes "")
    if(DEFINED MTL_VULKAN_SDK_DIR)
        list(APPEND _mtl_vk_includes "${MTL_VULKAN_SDK_DIR}/include")
    endif()
    if(DEFINED MTL_VULKAN_INCLUDE_DIRS)
        list(APPEND _mtl_vk_includes ${MTL_VULKAN_INCLUDE_DIRS})
    endif()
    set(_mtl_vk_includes_host "")
    foreach(_mtl_dir IN LISTS _mtl_vk_includes)
        if(CMAKE_HOST_WIN32)
            mtl_to_windows_path("${_mtl_dir}" _mtl_norm)
        else()
            mtl_to_wsl_path("${_mtl_dir}" _mtl_norm)
        endif()
        if(CMAKE_HOST_WIN32 AND _mtl_norm MATCHES "^/[a-z]" AND NOT _mtl_norm MATCHES "^/mnt/")
            message(FATAL_ERROR
                "'${_mtl_dir}' is a WSL-only path that this Windows build cannot read.\n"
                "Copy the Vulkan headers somewhere Windows can reach (e.g. under C:) and pass that.")
        endif()
        if(NOT EXISTS "${_mtl_norm}")
            message(FATAL_ERROR "Vulkan include dir not found on the build host: '${_mtl_norm}'")
        endif()
        list(APPEND _mtl_vk_includes_host "${_mtl_norm}")
    endforeach()

    # Fail here rather than 380 steps into the build at "vulkan/vulkan.hpp file not found".
    # A Windows-side build with headers staged only inside WSL is the easy mistake to make:
    # every -I then points somewhere the Windows compiler cannot read.
    set(_mtl_hpp "")
    foreach(_mtl_dir IN LISTS _mtl_vk_includes_host)
        if(EXISTS "${_mtl_dir}/vulkan/vulkan.hpp")
            set(_mtl_hpp "${_mtl_dir}/vulkan/vulkan.hpp")
            break()
        endif()
    endforeach()
    if(NOT _mtl_hpp)
        set(_mtl_probed "")
        foreach(_mtl_dir IN LISTS _mtl_vk_includes_host)
            list(APPEND _mtl_probed "  ${_mtl_dir}\n")
        endforeach()
        if(_mtl_probed STREQUAL "")
            set(_mtl_probed "  (nothing - no Vulkan include dir was configured or found)\n")
        endif()
        if(CMAKE_HOST_WIN32)
            message(FATAL_ERROR
                "GPU offload needs <vulkan/vulkan.hpp>, which the NDK does not provide, and no\n"
                "Vulkan include dir supplying it was found.\n"
                "Probed include dirs:\n${_mtl_probed}"
                "This looks like a WSL/Windows split: headers staged in WSL (e.g.\n"
                "/opt/vulkan-headers) are invisible to a Windows-side compile, because /opt is\n"
                "inside the WSL root filesystem rather than on a drive Windows can read.\n"
                "  Fix A: stage the headers on this side, then pass them explicitly\n"
                "         -Pmtl.vulkanIncludeDirs=C:/vulkan-headers\n"
                "  Fix B: run the build from inside WSL, where /opt/vulkan-headers works\n"
                "  Fix C: build CPU-only for now\n"
                "         -Pmtl.gpuOffload=false\n"
                "See llamatik-native/BUILD.md section 3.")
        endif()
        message(FATAL_ERROR
            "GPU offload needs <vulkan/vulkan.hpp>, which no Android SDK ships, and no Vulkan\n"
            "include dir supplying it was found.\n"
            "Probed include dirs:\n${_mtl_probed}"
            "Run the one-time setup; it stages a matched Vulkan-Headers + Vulkan-Hpp +\n"
            "SPIRV-Headers set at /opt/vulkan-headers, which is the default probed above:\n"
            "  wsl -e bash <repo>/llamatik-native/wsl-setup.sh\n"
            "  (or, avoiding sudo:  MTL_VULKAN_HEADERS=~/vulkan-headers ./llamatik-native/wsl-setup.sh\n"
            "   then build with  -Pmtl.wslVulkanHeaders=~/vulkan-headers)\n"
            "  Build CPU-only for now:  -Pmtl.gpuOffload=false\n"
            "See llamatik-native/BUILD.md section 3.")
    endif()
    message(STATUS "Houri MTL: Vulkan-Hpp at ${_mtl_hpp}")

    # ggml-vulkan hard-requires find_package(SPIRV-Headers CONFIG) but never links the
    # target it would provide, and the NDK toolchain sets CMAKE_FIND_ROOT_PATH_MODE_PACKAGE
    # to ONLY, so a real distro install is invisible (CMAKE_PREFIX_PATH does not help
    # either). Hunting for the package therefore only ever worked on a LunarG SDK layout.
    #
    # ggml-vulkan genuinely only needs <spirv/unified1/spirv.hpp> on its include path,
    # which we already arrange below. So satisfy the gate with a generated config instead:
    # it cannot fail for being "not installed", and behaves the same on Windows, WSL and CI.
    # An explicit -DSPIRV-Headers_DIR= from the caller still wins.
    if(NOT DEFINED SPIRV-Headers_DIR)
        set(_mtl_spirv_root "")
        foreach(_mtl_dir IN LISTS _mtl_vk_includes_host)
            if(IS_DIRECTORY "${_mtl_dir}/spirv")
                set(_mtl_spirv_root "${_mtl_dir}")
                break()
            endif()
        endforeach()
        if(NOT _mtl_spirv_root)
            foreach(_mtl_p /usr/share/cmake/SPIRV-Headers /usr/local/share/cmake/SPIRV-Headers)
                if(IS_DIRECTORY "${_mtl_p}")
                    set(SPIRV-Headers_DIR "${_mtl_p}" CACHE PATH "SPIRV-Headers config dir" FORCE)
                    message(STATUS "Houri MTL: SPIRV-Headers config at ${_mtl_p}")
                    break()
                endif()
            endforeach()
        endif()
        if(NOT DEFINED SPIRV-Headers_DIR)
            set(_mtl_shim "${CMAKE_BINARY_DIR}/mtl-spirv")
            file(MAKE_DIRECTORY "${_mtl_shim}")
            file(WRITE "${_mtl_shim}/SPIRV-HeadersConfig.cmake"
"# Generated by :llamatik-native. Satisfies ggml-vulkan's find_package(SPIRV-Headers).
# It only needs the headers on the include path, which the ggml-vulkan target is given
# directly, so this config deliberately provides no targets.
set(SPIRV-Headers_FOUND TRUE)
set(SPIRV-Headers_INCLUDE_DIR \"${_mtl_spirv_root}\")
")
            file(WRITE "${_mtl_shim}/SPIRV-HeadersConfigVersion.cmake"
"set(PACKAGE_VERSION \"1.6.1\")
set(PACKAGE_VERSION_COMPATIBLE TRUE)
set(PACKAGE_VERSION_EXACT FALSE)
")
            set(SPIRV-Headers_DIR "${_mtl_shim}" CACHE PATH "SPIRV-Headers config dir" FORCE)
            message(STATUS "Houri MTL: generated SPIRV-Headers shim (include: ${_mtl_spirv_root})")
        endif()
    endif()

    # ggml-vulkan does this find_package too, but find_package IMPORTED targets are only
    # visible in the directory that called it, so Vulkan::Vulkan would be undefined here
    # and the link below would fail. Calling it in this scope fixes that.
    mtl_find_glslc()
    find_package(Vulkan COMPONENTS glslc REQUIRED)
    message(STATUS "Houri MTL: GPU offload enabled (ggml Vulkan, API level ${_mtl_api})")
else()
    set(GGML_VULKAN OFF CACHE BOOL "Vulkan GPU backend" FORCE)
endif()

set(GGML_OPENCL OFF CACHE BOOL "OpenCL backend" FORCE)
set(GGML_HEXAGON OFF CACHE BOOL "Qualcomm Hexagon NPU backend" FORCE)

# llama_jni.cpp calls into common/json-schema-to-grammar.h.
set(LLAMA_BUILD_COMMON ON CACHE BOOL "Build llama.cpp common helpers" FORCE)
set(LLAMA_CURL OFF CACHE BOOL "No libcurl in the NDK" FORCE)
set(LLAMA_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BENCHMARK OFF CACHE BOOL "" FORCE)

if(ANDROID AND ANDROID_ABI STREQUAL "armeabi-v7a")
    # llamafile is host-side x86 code and is not available on 32-bit ARM.
    set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)
    set(GGML_USE_LLAMAFILE OFF CACHE BOOL "" FORCE)
endif()

add_subdirectory("${LLAMA_CPP_DIR}" "${CMAKE_BINARY_DIR}/llama")

find_library(log-lib log)

# ---- mtmd (multimodal projector) ---------------------------------------------
# MultimodalBridge loads a vision GGUF plus an mmproj GGUF; mtmd implements the
# projector. Only llama.cpp is built here — the fork's whisper.cpp and
# stable-diffusion.cpp submodules are intentionally left unchecked out.
add_library(mtmd_sources OBJECT
        ${MTMD_DIR}/mtmd.cpp
        ${MTMD_DIR}/mtmd-helper.cpp
        ${MTMD_DIR}/mtmd-audio.cpp
        ${MTMD_DIR}/mtmd-image.cpp
        ${MTMD_DIR}/clip.cpp
        ${MTMD_DIR}/models/cogvlm.cpp
        ${MTMD_DIR}/models/conformer.cpp
        ${MTMD_DIR}/models/deepseekocr.cpp
        ${MTMD_DIR}/models/deepseekocr2.cpp
        ${MTMD_DIR}/models/dotsocr.cpp
        ${MTMD_DIR}/models/exaone4_5.cpp
        ${MTMD_DIR}/models/gemma4a.cpp
        ${MTMD_DIR}/models/gemma4ua.cpp
        ${MTMD_DIR}/models/gemma4uv.cpp
        ${MTMD_DIR}/models/gemma4v.cpp
        ${MTMD_DIR}/models/glm4v.cpp
        ${MTMD_DIR}/models/granite-speech.cpp
        ${MTMD_DIR}/models/granite4-vision.cpp
        ${MTMD_DIR}/models/hunyuanvl.cpp
        ${MTMD_DIR}/models/internvl.cpp
        ${MTMD_DIR}/models/kimik25.cpp
        ${MTMD_DIR}/models/kimivl.cpp
        ${MTMD_DIR}/models/llama4.cpp
        ${MTMD_DIR}/models/llava.cpp
        ${MTMD_DIR}/models/minicpmv.cpp
        ${MTMD_DIR}/models/mimovl.cpp
        ${MTMD_DIR}/models/mobilenetv5.cpp
        ${MTMD_DIR}/models/nemotron-v2-vl.cpp
        ${MTMD_DIR}/models/paddleocr.cpp
        ${MTMD_DIR}/models/pixtral.cpp
        ${MTMD_DIR}/models/qwen2vl.cpp
        ${MTMD_DIR}/models/qwen3a.cpp
        ${MTMD_DIR}/models/qwen3vl.cpp
        ${MTMD_DIR}/models/siglip.cpp
        ${MTMD_DIR}/models/step3vl.cpp
        ${MTMD_DIR}/models/whisper-enc.cpp
        ${MTMD_DIR}/models/yasa2.cpp
        ${MTMD_DIR}/models/youtuvl.cpp
        ${MTMD_DIR}/deprecation-warning.cpp
)

target_include_directories(mtmd_sources PRIVATE
        ${MTMD_DIR}
        ${LLAMA_CPP_DIR}/include
        ${LLAMA_CPP_DIR}/src
        ${LLAMA_CPP_DIR}/ggml/include
        ${LLAMA_CPP_DIR}/ggml/src
        ${LLAMA_CPP_DIR}/common
        ${LLAMA_CPP_DIR}/vendor
        ${LLAMA_CPP_DIR}/vendor/stb
        ${LLAMA_CPP_DIR}/vendor/nlohmann
)

target_compile_options(mtmd_sources PRIVATE -Wno-cast-qual)
target_compile_features(mtmd_sources PRIVATE cxx_std_17)

# ---- JNI library -------------------------------------------------------------
# The output name MUST stay `llama_jni`: the vendored Kotlin wrapper calls
# System.loadLibrary("llama_jni"), and JNI symbols are looked up by the
# `com_llamatik_library_platform_*` mangling of the Kotlin package.
add_library(llama_jni
        SHARED
        ${LLAMATIK_CPP_DIR}/llama_jni.cpp
        ${LLAMATIK_CPP_DIR}/llama_embed.cpp
        ${LLAMATIK_CPP_DIR}/multimodal_jni.cpp
        $<TARGET_OBJECTS:mtmd_sources>
)

target_include_directories(llama_jni PRIVATE
        ${LLAMATIK_INTEROP_DIR}
        ${LLAMA_CPP_DIR}
        ${LLAMA_CPP_DIR}/include
        ${LLAMA_CPP_DIR}/src
        ${LLAMA_CPP_DIR}/ggml/include
        ${LLAMA_CPP_DIR}/ggml/src
        ${LLAMA_CPP_DIR}/common
        ${LLAMA_CPP_DIR}/vendor
        ${LLAMA_CPP_DIR}/vendor/nlohmann
        ${LLAMA_CPP_DIR}/vendor/stb
        ${MTMD_DIR}
)

target_compile_features(llama_jni PRIVATE cxx_std_17)
# No -fvisibility=hidden on purpose: it would buy nothing while making every JNI entry
# point depend on the vendored sources using JNIEXPORT rather than plain extern "C".
target_compile_options(llama_jni PRIVATE -O3 -Wno-cast-qual)

target_link_libraries(llama_jni PRIVATE llama ${log-lib})

# Renamed from 'common' to 'llama-common' in llama.cpp b9102.
if(TARGET common)
    target_link_libraries(llama_jni PRIVATE common)
elseif(TARGET llama-common)
    target_link_libraries(llama_jni PRIVATE llama-common)
endif()

if(HOURI_GPU_OFFLOAD)
    # ggml-vulkan is only defined when GGML_VULKAN=ON. ggml-vulkan already did a REQUIRED
    # find_package(Vulkan ...), so Vulkan::Vulkan exists whenever this branch is reached.
    if(NOT TARGET ggml-vulkan)
        message(FATAL_ERROR "GGML_VULKAN was requested but the ggml-vulkan target was not defined")
    endif()

    # Vulkan-Hpp / SPIRV-Headers include dirs are injected as raw -I flags on this target
    # alone, via target_compile_options rather than target_include_directories or
    # include_directories. Two reasons, both learned the hard way:
    #   * CMake unconditionally drops "/usr/include" from a target's include path as an
    #     implicit system dir, so a distro `libvulkan-dev` would be silently ignored.
    #   * a project-wide -I/usr/include shadows the NDK sysroot's libc headers (the host
    #     features-time64.h wins and then cannot find the Android bits/wordsize.h).
    # _mtl_vk_includes_host was resolved and validated above, before add_subdirectory.
    foreach(_mtl_dir IN LISTS _mtl_vk_includes_host)
        target_compile_options(ggml-vulkan PRIVATE -I${_mtl_dir})
    endforeach()
    message(STATUS "Houri MTL: ggml-vulkan includes ${_mtl_vk_includes_host}")

    target_link_libraries(llama_jni PRIVATE ggml-vulkan Vulkan::Vulkan)
endif()
