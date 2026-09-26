#!/usr/bin/env bash
# Builds libllama_jni.so for every ABI, then stages it for AGP to package.
#
# Invoked by the `wslNativeBuild` task in build.gradle.kts, which passes the arguments below.
# It lives in its own file (rather than inline in the Kotlin DSL) so it can be linted, read
# and run directly:  bash build-wsl.sh <module> <ndk> <ON|OFF> <toolchain> <vulkanInc|""> <out>
#
# Only used on a Windows host. Linux/WSL/CI go through AGP's own externalNativeBuild instead.

set -euo pipefail

module="${1:?module dir (WSL path)}"
ndk="${2:?NDK dir (WSL path)}"
gpu="${3:-ON}"
toolchain="${4:-auto}"
vulkan_inc="${5:-}"
out="${6:-$module/build/wsl-jniLibs}"

abis=(arm64-v8a armeabi-v7a x86_64 x86)
toolchain_file="$ndk/build/cmake/android.toolchain.cmake"

if [ ! -f "$toolchain_file" ]; then
  cat >&2 <<EOF
ERROR: NDK toolchain not found at $toolchain_file
This build runs inside WSL, so it needs the *Linux* NDK -- the one under C: on Windows has
windows-x86_64 toolchain binaries and cannot be used from Linux.
Install it inside WSL:
    sudo sdkmanager --install "ndk;28.2.13676358"
or point elsewhere:
    -Pmtl.wslNdkDir=/path/to/ndk
EOF
  exit 1
fi

for tool in cmake ninja; do
  command -v "$tool" >/dev/null 2>&1 || {
    echo "ERROR: '$tool' is missing inside WSL. Install it and re-run wsl-setup.sh." >&2
    exit 1
  }
done

gpu_args=()
if [ "$gpu" = "ON" ]; then
  # ggml-vulkan references a Vulkan 1.1 core symbol the NDK's libvulkan.so stub only
  # exports from API 29; the app itself still supports API 26 and falls back to CPU there.
  gpu_args=(-DHOURI_GPU_OFFLOAD=ON -DANDROID_PLATFORM=android-29 "-DMTL_VULKAN_TOOLCHAIN=$toolchain")
  if [ -n "$vulkan_inc" ]; then
    gpu_args+=("-DMTL_VULKAN_INCLUDE_DIRS=$vulkan_inc")
  fi
else
  gpu_args=(-DHOURI_GPU_OFFLOAD=OFF)
fi

# Two native builds running at once in WSL oversubscribe it badly enough to be a real
# failure mode, not just slowness: :llamatik-native and the external/imagedecoder-houri
# included build both drive a full parallel compiler through the same VM. The lock is the
# only serializer that works across Gradle's composite-build boundary, where mustRunAfter
# cannot reach (imagedecoder is includeBuild()'d, a separate task graph).
lock="${MTL_WSL_LOCK:-/tmp/mtl-wsl-native.lock}"
echo "=== waiting for WSL native lock ($lock)"
exec 9>"$lock"
flock 9

# nproc in WSL often reports the host's core count, which is far more than a WSL VM can
# schedule; combined with a second module's build it starves and OOMs.
jobs="${MTL_NINJA_JOBS:-$(nproc)}"
echo "=== building with -j$jobs"

for abi in "${abis[@]}"; do
  build="$module/.cxx/wsl/$abi"
  echo "=== [$abi] configure"
  cmake -S "$module/src/main/cpp" -B "$build" -G Ninja \
    -DCMAKE_TOOLCHAIN_FILE="$toolchain_file" \
    -DANDROID_ABI="$abi" \
    -DANDROID_STL=c++_static \
    "${gpu_args[@]}"

  echo "=== [$abi] build"
  ninja -C "$build" -j"$jobs"

  mkdir -p "$out/$abi"
  cp "$build/libllama_jni.so" "$out/$abi/libllama_jni.so"
  echo "=== [$abi] staged -> $out/$abi/libllama_jni.so"
done
