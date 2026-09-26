#!/usr/bin/env bash
# One-time WSL setup for building :llamatik-native with the Vulkan (GPU) backend.
#
# Run from Windows:  wsl -e bash <repo>/llamatik-native/wsl-setup.sh
# Or from inside WSL:  ./llamatik-native/wsl-setup.sh
#
# Why this exists: the Android NDK ships a Vulkan *loader stub* and `vulkan_core.h`, but NOT
# Vulkan-Hpp's `vulkan/vulkan.hpp` and NOT SPIRV-Headers — which `ggml-vulkan` hard-requires.
# This stages a matched, header-only set under one root so CMake can auto-detect it. It also
# checks the rest of the toolchain so a missing piece is reported here, once, instead of 380
# steps into a Gradle build.
#
#   ./wsl-setup.sh --headers-only   stage/verify just the headers (used by CI)
#
# Safe to re-run: it re-checks everything and only re-downloads what is missing.

set -euo pipefail

NDK_VERSION="${MTL_NDK_VERSION:-28.2.13676358}"
STAGE="${MTL_VULKAN_HEADERS:-/opt/vulkan-headers}"
# Vulkan-Headers and Vulkan-Hpp MUST be the same version: a current Vulkan-Hpp hard-asserts
# VK_HEADER_VERSION == 363, and the NDK's own vulkan_core.h is 275, which is why we install a
# matched pair rather than reusing the NDK's.
VULKAN_TAG="${MTL_VULKAN_TAG:-v1.4.309}"
SPIRV_TAG="${MTL_SPIRV_TAG:-vulkan-sdk-1.4.309}"
WORK="${MTL_SETUP_WORK:-/tmp/mtl-vulkan-setup}"

info() { printf '==> %s\n' "$*"; }
die()  { printf 'ERROR: %s\n' "$*" >&2; exit 1; }

need() { command -v "$1" >/dev/null 2>&1 || die "'$1' is missing. Install it and re-run."; }

# CI only needs the headers staged; it installs cmake/ninja in a later step and its NDK
# lives somewhere this script's probes may not match, so skip the build-tool and NDK checks.
headers_only=0
if [[ "${1:-}" == "--headers-only" ]]; then
  headers_only=1
  shift
fi

info "checking host tools"
need git
if [[ $headers_only -eq 0 ]]; then
  need cmake
  need ninja
fi
printf '    cmake %s\n' "$(cmake --version | head -1 | awk '{print $3}')"
printf '    ninja %s\n' "$(ninja --version)"

# ---------------------------------------------------------------------------
# glslc
# ---------------------------------------------------------------------------
# ggml-vulkan compiles ~40 shaders per ABI, so this is load-bearing. CMake also accepts the
# copy the NDK bundles under shader-tools/, but a distro one is newer (shaderc 2022.3 in the
# NDK predates cooperative-matrix/bf16, which then get disabled).
if command -v glslc >/dev/null 2>&1; then
  printf '    glslc: %s\n' "$(glslc --version 2>/dev/null | head -1)"
else
  die "glslc is missing. Install it:  sudo apt-get install -y glslc"
fi

# ---------------------------------------------------------------------------
# NDK (the Linux one — a Windows NDK's toolchain binaries cannot be used here)
# ---------------------------------------------------------------------------
ndk_root=""
for root in "/usr/lib/android-sdk/ndk/$NDK_VERSION" \
            "$HOME/Android/Sdk/ndk/$NDK_VERSION" \
            "/opt/android-sdk/ndk/$NDK_VERSION" \
            "${ANDROID_HOME:-/nonexistent}/ndk/$NDK_VERSION" \
            "${ANDROID_SDK_ROOT:-/nonexistent}/ndk/$NDK_VERSION" \
            "/usr/local/lib/android/sdk/ndk/$NDK_VERSION"; do
  [ -f "$root/build/cmake/android.toolchain.cmake" ] && { ndk_root="$root"; break; }
done
if [ $headers_only -eq 1 ]; then
  printf '    (headers-only: skipping the NDK check)\n'
  ndk_root=""
elif [ -z "$ndk_root" ]; then
  die "NDK $NDK_VERSION not found inside WSL. Install it:
    sudo sdkmanager --install \"ndk;$NDK_VERSION\"
  (the Windows NDK under C: cannot be used from WSL — its toolchain is windows-x86_64)
  or point elsewhere with -Pmtl.wslNdkDir=/path/to/ndk"
fi
printf '    NDK: %s\n' "$ndk_root"

# ---------------------------------------------------------------------------
# Vulkan headers
# ---------------------------------------------------------------------------
# One root must contain vulkan/, vk_video/ and spirv/. vk_video is a SIBLING of vulkan, not a
# subdirectory: vulkan_core.h does #include <vk_video/vulkan_video_codec_av1std.h>.
# A host /usr/include is never an option — it would shadow the NDK sysroot's libc headers
# (host features-time64.h wins, then bits/wordsize.h is missing) and CMake drops it anyway.
if [ -f "$STAGE/vulkan/vulkan.hpp" ] && [ -d "$STAGE/vk_video" ] && [ -d "$STAGE/spirv" ]; then
  printf '    Vulkan headers: %s (already present)\n' "$STAGE"
else
  info "staging Vulkan headers into $STAGE"
  mkdir -p "$WORK"
  cd "$WORK"
  for repo in Vulkan-Headers Vulkan-Hpp; do
    if [ ! -d "$repo" ]; then
      git clone --depth 1 --branch "$VULKAN_TAG" "https://github.com/KhronosGroup/$repo"
    fi
  done
  [ -d SPIRV-Headers ] || git clone --depth 1 --branch "$SPIRV_TAG" \
    "https://github.com/KhronosGroup/SPIRV-Headers"

  # Unprivileged when the destination is already writable (containers, CI, a user-owned
  # path); otherwise sudo. Probing first keeps the interactive password prompt out of the
  # common case and gives a clear message in the non-interactive one.
  # Vulkan-Hpp and SPIRV-Headers expose their headers under include/, but Vulkan-Hpp has no
  # include/ directory at v1.4.309: it moved vulkan.hpp to the repository root and its own
  # CMakeLists does target_include_directories(... "${CMAKE_CURRENT_SOURCE_DIR}"). So the copy
  # is per-repo, not a loop over include/.
  stage_into() { # $1=src-dir  $2=dest  $3=use-sudo(0|1)
    if [ "$3" = "1" ]; then
      sudo mkdir -p "$2" && sudo cp -r "$1/." "$2/"
    else
      mkdir -p "$2" && cp -r "$1/." "$2/"
    fi
  }

  sudo_mode=0
  if ! mkdir -p "$STAGE" 2>/dev/null; then
    sudo -n true 2>/dev/null || die "need sudo to write $STAGE, but sudo requires a password and
  this shell is non-interactive. Either re-run from an interactive terminal, or stage
  somewhere you already own:
      MTL_VULKAN_HEADERS=~/vulkan-headers ./wsl-setup.sh
  and build with:
      -Pmtl.wslVulkanHeaders=~/vulkan-headers"
    sudo_mode=1
  fi

  stage_into "$WORK/Vulkan-Headers/include" "$STAGE"      "$sudo_mode"  # vulkan/ + vk_video/
  stage_into "$WORK/Vulkan-Hpp/vulkan"     "$STAGE/vulkan" "$sudo_mode"  # root-level vulkan/
  stage_into "$WORK/SPIRV-Headers/include" "$STAGE"      "$sudo_mode"  # spirv/
fi

# Verify the staged layout, and that the two Vulkan halves are actually a matched pair.
[ -f "$STAGE/vulkan/vulkan.hpp" ] || die "staging failed: $STAGE/vulkan/vulkan.hpp missing"
[ -d "$STAGE/vk_video" ]          || die "staging failed: $STAGE/vk_video missing (sibling of vulkan/)"
[ -d "$STAGE/spirv" ]             || die "staging failed: $STAGE/spirv missing"
hdr_ver=$(sed -n 's/^#define VK_HEADER_VERSION \([0-9]*\).*/\1/p' "$STAGE/vulkan/vulkan_core.h" | tail -1)
printf '    vulkan_core.h VK_HEADER_VERSION: %s\n' "$hdr_ver"
if ! grep -q "VK_HEADER_VERSION == $hdr_ver" "$STAGE/vulkan/vulkan.hpp" 2>/dev/null; then
  die "Vulkan-Hpp expects a different VK_HEADER_VERSION than the staged Vulkan-Headers ($hdr_ver).
  They must be the same release — reinstall with a matching pair:
    MTL_VULKAN_TAG=v1.4.309 ./wsl-setup.sh"
fi

cat <<EOF

Setup OK. From Windows, build with:

    gradlew.bat assembleDebug

or, to do the native build in WSL (recommended, and what this module does anyway):

    ./gradlew assembleDebug

Notes:
  * Build without Vulkan entirely:          -Pmtl.gpuOffload=false
  * After editing CMakeLists.txt, NDK, or any -Pmtl.* property, run wipe-cache.sh — AGP
    caches CMake results and a stale one silently keeps old behaviour.
EOF
