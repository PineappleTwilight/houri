# llamatik-native/ Module

On-device LLM runtime: the vendored llamatik JNI layer + llama.cpp, built from source. Package
root `com.llamatik.library.platform` — **do not rename it**; JNI mangles the package into the
native symbol names. Consumed by `yakuyomi-engine` (`LlamaCppLlmBackend`,
`MultimodalBridge`).

**Full build requirements — including the verified Debian setup, the Vulkan header staging
and the Windows/WSL interop — live in [`BUILD.md`](./BUILD.md).** Read that before touching the
native build. It is kept here rather than in the `external/llamatik` submodule because almost
all of it concerns *this* module's Gradle properties and tasks, which the fork knows nothing
about — and because a submodule edit needs its own commit + push to the fork plus a superproject
pointer bump, so submodule churn should be reserved for real code changes.

## Why this exists instead of the `com.llamatik:library` AAR

The published AAR is **CPU-only**: its `jni/<abi>/` ships `libggml-cpu.so` and no GPU backend
plugin, so `gpuLayers` offloads nothing. Upstream llamatik supports `-DGGML_VULKAN=ON`; their CI
just never passes it. We build the same JNI ourselves so the flag is ours to set, and link ggml
statically (`GGML_BACKEND_DL=OFF`) so a compiled-in backend lands inside `libllama_jni.so`
instead of needing a plugin `.so`.

| File | Role |
|---|---|
| `src/main/cpp/CMakeLists.txt` | Builds `libllama_jni` from `external/llamatik` + its pinned llama.cpp |
| `src/main/kotlin/.../LlamaBridge.kt` | Text generation; vendored + trimmed from the fork's androidMain |
| `src/main/kotlin/.../MultimodalBridge.kt` | Vision (GGUF + mmproj) |
| `src/main/kotlin/.../GenStream.kt` | Streaming callback interface, called back from JNI by name |
| `src/main/kotlin/.../LlamatikBuildInfo.kt` | Exposes whether a GPU backend was compiled in |
| `consumer-rules.pro` | Keeps the wrapper's class + native method names survive R8 |

## Windows: WSL is the only path

Matches `external/imagedecoder-houri` — deliberately **no** MSYS2/Git Bash fallback. On a
Windows host `build.gradle.kts`:

1. hard-fails at configuration time if neither `C:/Windows/System32/wsl.exe` nor
   `C:/Windows/Sysnative/wsl.exe` exists (`Sysnative` matters: a 32-bit Gradle daemon has
   `System32` redirected to `SysWOW64`);
2. skips AGP's `externalNativeBuild` entirely and instead runs the `wslNativeBuild` task,
   which configures and builds all four ABIs inside WSL with the **Linux** NDK
   (`-Pmtl.wslNdkDir`, default `/usr/lib/android-sdk/ndk/<version>`) and stages each
   `libllama_jni.so` into `build/wsl-jniLibs/<abi>/`;
3. feeds that dir to `jniLibs`, and hard-fails if any ABI's `.so` is missing so a split APK
   can never ship without the runtime.

The WSL task is never up-to-date (Gradle cannot see ninja's in-place object updates), but
incrementality is delegated to ninja, so a no-change rebuild costs a ninja no-op rather than
a full four-ABI Vulkan rebuild.

One-time setup inside WSL: **`llamatik-native/wsl-setup.sh`** (`wsl -e bash <repo>/llamatik-native/wsl-setup.sh`).
It verifies glslc/cmake/ninja plus the Linux NDK, then stages a *matched* Vulkan-Headers +
Vulkan-Hpp + SPIRV-Headers set under one root (`/opt/vulkan-headers`, which CMake
auto-detects) and asserts the version pair actually matches. It works unprivileged when the
destination is writable; pass `MTL_VULKAN_HEADERS=~/vulkan-headers` to avoid `sudo`, and
build with `-Pmtl.wslVulkanHeaders=~/vulkan-headers`.

> Vulkan-Hpp at v1.4.309 has **no `include/` directory** — `vulkan.hpp` moved to the repo root
> and its own CMake uses the source dir as the include path. The setup script copies
> per-repo rather than looping over `include/`.

## Stale-cache recovery

AGP keeps `llamatik-native/.cxx/<Variant>/<hash>/<abi>/CMakeCache.txt` between runs. That
cache pins `find_program()` results and our own `set(... CACHE "" FORCE)` values, so a
configure that could not see `glslc` keeps reporting `NOTFOUND` after the tool appears, and a
probed WSL proxy stays pinned. A 32-bit Gradle daemon additionally cannot see
`C:\Windows\System32\wsl.exe` (redirected to SysWOW64) and can cache a bogus "no WSL" probe.
`./gradle/configuration-cache` can hold a stale configuration on top of that.

**Always wipe after editing `CMakeLists.txt`, switching NDK, or changing any `-Pmtl.*`
property** — same trap documented in `external/imagedecoder-houri/AGENTS.md`.

```bash
llamatik-native/wipe-cache.sh                        # WSL / Linux
llamatik-native\wipe-cache.bat --build              # Windows (add Gradle args after --build)
```

Both stop the Gradle daemon first, delete `.cxx` + `.gradle/configuration-cache`, and with
`--build` re-run using `--no-configuration-cache --rerun-tasks`. A warm daemon can hold the
cache in memory and write it back after deletion, which resurrects the stale state.

Every configure now prints what it probed, so a miss is a one-glance diagnosis:

```
-- Houri MTL: glslc resolution -- mode='auto' host='Windows-10.0.26100' real='...' ...
```

## Gotchas

- **Submodule is nested.** `git submodule update --init --recursive external/llamatik`, else
  CMake aborts with `llama.cpp not found`. Only `llama.cpp` is built; the fork's `whisper.cpp`
  and `stable-diffusion.cpp` submodules are unused.
- **Verified on WSL/Linux with NDK r28c** (cmake 3.31, ninja), not just configured but fully
  built and linked for `arm64-v8a` and `armeabi-v7a`, CPU-only and Vulkan.
- **GPU offload is ON by default**; `-Pmtl.gpuOffload=false` opts out. It needs Vulkan-Hpp +
  SPIRV-Headers, which the NDK does not make discoverable, and **API level 29+** — ggml-vulkan
  references the Vulkan 1.1 core function `vkGetPhysicalDeviceFeatures2`, and the NDK's
  `libvulkan.so` import stub only exports it from API 29 (148 Vulkan symbols at API 26, 182 at
  29). CMake turns the resulting link error into a clear `FATAL_ERROR`. The app still supports
  API 26; those devices just use the CPU path.
- **All four ABIs are built** (`arm64-v8a`, `armeabi-v7a`, `x86_64`, `x86`) to match
  `app/build.gradle.kts` `splits.abi.include` and external/imagedecoder-houri. With per-ABI
  splits, a missing ABI yields a split APK with no LLM runtime at all.
- **glslc resolution order** is native-first: `$VULKAN_SDK/bin/glslc`, `/usr/bin/glslc`,
  `/usr/local/bin/glslc`, `/opt/vulkan-sdk/bin/glslc`, `PATH`, then the copy the **NDK bundles
  at `shader-tools/<host>/glslc`**. The NDK toolchain's find root hides that last one from
  `find_package(Vulkan COMPONENTS glslc)`, so we glob for it directly. That one is shaderc
  2022.3, so cooperative-matrix/bf16 shaders report "not supported" and those optional
  features are disabled (the base shaders still compile) — prefer a distro glslc, which is why
  `wsl-setup.sh` treats it as required.
- **`<vulkan/vulkan.hpp>` is validated at configure time.** Without it the build used to die
  380 steps in at `'vulkan/vulkan.hpp' file not found`; it now aborts during configure naming
  the WSL/Windows split as the likely cause. The staged root must hold `vulkan/`, `vk_video/`
  and `spirv/` — `vk_video` is a **sibling** of `vulkan`, not a subdirectory.
- The NDK's own `vulkan_core.h` is `VK_HEADER_VERSION 275`, too old to pair with a current
  Vulkan-Hpp (wants 363), so use a matched Vulkan-Headers + Vulkan-Hpp pair. Those plus
  SPIRV-Headers are all header-only, so plain git clones work.
- **ggml-vulkan pastes glslc's stderr unquoted** into `if(${glslc_error} MATCHES …)`, so any
  `;`, quote or newline in that output aborts configure with `Unknown arguments specified`.
  We always invoke glslc through a generated wrapper that flattens stderr to one safe line —
  written to **stderr, never stdout**, since stdout carries the SPIR-V binary from `glslc -o -`.
- **There is no `wsl.exe` proxy.** A Windows Gradle build never configures CMake: the
  `wslNativeBuild` task runs the whole configure+build inside WSL and AGP just packages the
  finished `libllama_jni.so` from `jniLibs`. So `mtl_find_glslc` always runs on Linux, where a
  native glslc is what it wants. Proxying was never viable here — ggml-vulkan calls glslc ~40x
  per ABI (a VM round-trip each), and the Vulkan *headers* cannot be proxied at all, since a
  Windows `clang++.exe` cannot `#include` anything inside the WSL root filesystem.
- **`find_package` IMPORTED targets are directory-scoped.** ggml-vulkan calls
  `find_package(Vulkan ...)` in its own subdirectory, so `Vulkan::Vulkan` does not exist in
  this one; the same `find_package` has to be repeated here or the link fails. Likewise
  SPIRV-Headers is instead satisfied by a generated `SPIRV-HeadersConfig.cmake` shim, because
  the NDK toolchain's `CMAKE_FIND_ROOT_PATH_MODE_PACKAGE=ONLY` hides any real install and
  ggml-vulkan never links the target anyway.
- **The output name must stay `llama_jni`** — `System.loadLibrary("llama_jni")` picks it, and
  a same-named lib in another artifact would be a duplicate-jniLibs packaging error.
- **Adding a Kotlin function means adding the C++.** Every `external` declaration needs a
  matching `Java_com_llamatik_library_platform_*` symbol in
  `external/llamatik/library/src/commonMain/cpp/llama_jni.cpp`, or it fails at runtime with
  `UnsatisfiedLinkError`. The shim is a deliberate subset of the fork's API — do not "restore"
  the rest without adding the C++ side.
- `PackageManager.FEATURE_VULKAN` was **removed in API 36**; use
  `FEATURE_VULKAN_HARDWARE_COMPUTE` (API 31+), see `exh.yakuyomi.LocalLlmAccelerator`.
- Stripped size: 6.6 MB/ABI CPU-only, 64.2 MB/ABI with Vulkan (ggml embeds its SPIR-V).
