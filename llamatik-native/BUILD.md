# Houri build requirements — on-device LLM runtime (`:llamatik-native`)

Requirements for building the GGUF on-device LLM runtime that Houri's MTL feature uses.
This is the `PineappleTwilight/Llamatik-extended` fork of
[ferranpons/Llamatik](https://github.com/ferranpons/Llamatik); the module that consumes it
lives in the Houri repo at `llamatik-native/`.

> Upstream llamatik's published `com.llamatik:library` AAR is **CPU-only** — its
> `jni/<abi>/` ships `libggml-cpu.so` and no GPU backend, so its `gpuLayers` parameter
> offloads nothing. Upstream supports `-DGGML_VULKAN=ON`; their CI just never passes it.
> Houri therefore builds the JNI layer from source instead.

---

## 1. Baseline requirements

These are needed regardless of GPU offload.

| Requirement | Version used / notes |
|---|---|
| Android NDK | `28.2.13676358` — pinned in `llamatik-native/build.gradle.kts` |
| CMake | 3.22.1 is what AGP requests; **3.31.6 verified working** |
| Ninja | from the Android SDK |
| JDK | 21 (CI), targeting JVM 17 |
| Submodules | `git submodule update --init --recursive external/llamatik` |

On top of that, GPU offload is on by default (§2), so a build host also needs
`glslc`, Vulkan-Hpp and SPIRV-Headers — see §3 for the three apt packages, or
`-Pmtl.gpuOffload=false` to build CPU-only without them.

**Verified:** `arm64-v8a` and `armeabi-v7a` at API 26 both configure, compile and link with
zero failures, producing `libllama_jni.so` with all 33 `Java_com_llamatik_library_platform_*`
JNI symbols exported.

**Platform note.** The default build is pure CMake driven by AGP's own NDK toolchain, so it
needs no Windows/WSL interop at all — `cmake.exe` + the Windows NDK work natively, and so do
WSL `cmake` + the Linux NDK. The image-decoder submodule's ~2,000-line WSL layer is for
cross-compiling autotools/meson/pkg-config dependencies and is not needed here.

---

## 2. GPU offload (Vulkan) — **on by default**

Houri ships full AI, not a CPU-only subset, so the Vulkan backend is compiled unless you
opt out. On a machine with no Vulkan toolchain either install the packages in §3 or build
with `-Pmtl.gpuOffload=false`.

### 2.1 What the Vulkan build needs that a plain NDK does not have

| Piece | In the NDK? | Notes |
|---|---|---|
| `glslc` (shaderc) | **no** | NDK r28c ships one at `shader-tools/<host>/glslc`, but the NDK toolchain's find root hides it — `find_package(Vulkan COMPONENTS glslc)` does not find it. Install glslc. |
| `libvulkan.so` loader stub | **yes** | but see the API-29 caveat below |
| `vulkan/vulkan.hpp` (Vulkan-Hpp) | **no** | hard requirement of `ggml-vulkan.cpp` |
| SPIRV-Headers | **no** | `find_package(SPIRV-Headers CONFIG REQUIRED)` |
| A LunarG SDK? | optional | a real SDK provides all of the above consistently |

**Vulkan-Hpp must match your Vulkan-Headers version.** The NDK ships
`vulkan_core.h` at `VK_HEADER_VERSION 275`; a current Vulkan-Hpp hard-asserts `== 363` and
then fails on `VkMemoryMapInfo`. Use a matched pair (e.g. both at `v1.4.313`).

**Include dir must contain only the Vulkan/SPIR-V headers.** The NDK toolchain compiles
against its own sysroot, so adding a *host* `/usr/include` to the include path shadows the
NDK's libc headers and the build dies with
`/usr/include/features-time64.h: 'bits/wordsize.h' file not found`. A real SDK's `include/`
is fine because it holds only `vulkan/`, `vk_video/` and `spirv/`.

> `vk_video/` is a **sibling** of `vulkan/`, not a subdirectory — `vulkan_core.h` includes
> `<vk_video/vulkan_video_codec_av1std.h>`. Both must sit under the same include root.

### 2.2 API level 29 is a hard floor

`ggml-vulkan` references the Vulkan **1.1** core function `vkGetPhysicalDeviceFeatures2`.
The NDK's `libvulkan.so` import stub only declares it from API 29:

| `ANDROID_PLATFORM` | Vulkan symbols in the NDK stub | has `vkGetPhysicalDeviceFeatures2` |
|---|---|---|
| android-26 | 148 | no |
| android-29 | 182 | yes |
| android-34 | 233 | yes |

The Vulkan variant therefore compiles against `android-29` even though the app supports
API 26. Devices below that keep using the CPU path at runtime — `exh.yakuyomi.LocalLlmAccelerator`
forces `gpuLayers` back to `0` and the settings screen says so. CMake aborts early with an
actionable message instead of letting you hit the link error.

### 2.3 SPIRV-Headers needs no configuration

The NDK toolchain sets `CMAKE_FIND_ROOT_PATH_MODE_PACKAGE=ONLY`, so a distro-installed
SPIRV-Headers is invisible to `find_package`, and `CMAKE_PREFIX_PATH` does not help either.
Because ggml-vulkan only needs `<spirv/unified1/spirv.hpp>` on its include path — which the
build already arranges — CMake writes a tiny `SPIRV-HeadersConfig.cmake` shim into
`mtl-spirv/` and points `SPIRV-Headers_DIR` at it. The gate can therefore never fail for
being "not installed", and behaves identically on Windows, WSL and CI. An explicit
`-DSPIRV-Headers_DIR=` still wins.

### 2.4 glslc is wrapped, and there is no WSL proxy

`glslc` is never handed to CMake directly. It is always invoked through a generated wrapper
that **flattens stderr to a single line free of `;`, quotes and backslashes**.

That is not cosmetic. `ggml-vulkan`'s `test_shader_extension_support()` does

```cmake
if (${glslc_error} MATCHES ".*extension not supported: <ext>.*")
```

with the variable pasted **unquoted**, so any `;`, `"` or newline in glslc's diagnostics
splits the condition into extra arguments and the configure aborts with
`if given arguments: … Unknown arguments specified`. The sanitized text is written to
**stderr, never stdout**, because stdout carries the compiled SPIR-V (`glslc -o -`); writing it
to stdout corrupts the generated shader header and the link then fails with undefined
`flash_attn_*_data` / `*_len` symbols.

Resolution order — all native, no boundary crossing:

| Order | Source |
|---|---|
| 1 | `$VULKAN_SDK/bin/glslc` |
| 2 | `/usr/bin/glslc`, `/usr/local/bin/glslc`, `/opt/vulkan-sdk/bin/glslc` |
| 3 | `PATH` (`find_program(..., NO_CACHE)`) |
| 4 | the NDK's own `shader-tools/<host>/glslc` (shaderc 2022.3) |

(4) exists because the NDK toolchain's find root hides it from
`find_package(Vulkan COMPONENTS glslc)`. Being older, it disables the cooperative-matrix and
bf16 shader features; a distro glslc avoids that, which is why `wsl-setup.sh`
requires one.

### 2.5 Windows hosts build the whole thing in WSL

There is **no `wsl.exe` proxy** for glslc or anything else. A Windows Gradle build never runs
CMake: `llamatik-native/build.gradle.kts` registers a `wslNativeBuild` task that configures and
compiles every ABI inside WSL, stages each `libllama_jni.so` into `build/wsl-jniLibs/<abi>/`,
and lets AGP package them from `jniLibs`. On Linux/WSL/CI, AGP's normal `externalNativeBuild`
is used instead and that task does not exist.

Two reasons, both learned the hard way:

- **Speed.** ggml-vulkan invokes glslc ~40 times per ABI. A `wsl.exe` proxy would pay a full
  VM round-trip per invocation, per ABI — which reads as a hang.
- **It cannot work at all for the headers.** The staged headers live in the WSL root
  filesystem; a Windows `clang++.exe` cannot `#include` anything under `/opt`. A partially
  proxied Windows build therefore dies 380 steps in with
  `fatal error: 'vulkan/vulkan.hpp' file not found`. CMake now validates this at configure
  time and names the split as the cause.

Vulkan header paths are normalised across the boundary, so one `-DMTL_VULKAN_INCLUDE_DIRS`
works from either host (`/mnt/c/x` ↔ `C:/x`), and a path still rooted at `/` on a **Windows**
host is rejected — that filesystem is only readable from WSL. Use `-Pmtl.wslVulkanHeaders` to
point the WSL build at a non-default staging root.

## 3. Debian / Ubuntu setup (verified on Debian 13 "trixie")

No LunarG SDK needed. Three apt packages cover it:

```bash
sudo apt install glslc libvulkan-dev spirv-headers
```

- `glslc` — shader compiler (`/usr/bin/glslc`)
- `libvulkan-dev` — Vulkan-Headers **and Vulkan-Hpp** 1.4.309, both under `/usr/include/vulkan`
- `spirv-headers` — SPIRV-Headers plus `/usr/share/cmake/SPIRV-Headers/SPIRV-HeadersConfig.cmake`

Then stage just the headers into a directory that does **not** also contain libc headers:

```bash
sudo mkdir -p /opt/vulkan-headers
sudo cp -r /usr/include/vulkan /usr/include/vk_video /usr/include/spirv /opt/vulkan-headers/
```

Build:

```bash
./gradlew :llamatik-native:assembleRelease -Pmtl.gpuOffload=true \
    -Pmtl.vulkanIncludeDirs=/opt/vulkan-headers
```

`SPIRV-Headers_DIR` is auto-detected from `/usr/share/cmake/SPIRV-Headers`, and `glslc` is
found on `PATH`.

**Verified end to end** on Debian 13 / WSL2 / NDK r28c: configure clean, all 593 build steps
succeed, `libllama_jni.so` links with 150 `ggml_vk`/`ggml_backend_vulkan` symbols.

Because GPU offload is on by default, this is the setup the normal build needs.
`wsl-setup.sh` automates exactly this: it checks the tools, clones the three
repos and stages them into `/opt/vulkan-headers` (no `sudo` needed when that path is
writable, and `MTL_VULKAN_HEADERS=~/vulkan-headers` avoids it entirely).

### Optional: a real LunarG SDK instead

If you have one, a single property is enough — it supplies Vulkan-Hpp, SPIRV-Headers and
glslc, and the rest is auto-detected:

```bash
./gradlew :llamatik-native:assembleRelease -Pmtl.gpuOffload=true \
    -Pmtl.vulkanSdkDir=/opt/VulkanSDK/1.4.313
```

`VULKAN_SDK` in the environment is used as a fallback.

---

## 4. Property reference

| Property | Env fallback | Default | Meaning |
|---|---|---|---|
| `mtl.gpuOffload` | `MTL_GPU_OFFLOAD` | `true` | Compile the ggml Vulkan backend |
| `mtl.vulkanToolchain` | — | `auto` | Which glslc to use *inside WSL*: `auto`, `wsl` or `native` |
| `mtl.wslNdkDir` | — | `/usr/lib/android-sdk/ndk/28.2.13676358` | NDK used by the WSL build (must be the **Linux** NDK) |
| `mtl.wslVulkanHeaders` | — | `/opt/vulkan-headers` | Staging root the WSL build passes as `MTL_VULKAN_INCLUDE_DIRS` when non-default |
| `mtl.vulkanSdkDir` | `VULKAN_SDK` | — | Vulkan SDK root (provides headers + SPIRV-Headers) |
| `mtl.vulkanIncludeDirs` | — | auto-detected | Semicolon-separated include dirs; overrides the SDK's `include/` |
| `mtl.spirvHeadersDir` | — | generated shim | SPIRV-Headers CMake config dir |

`GPU_OFFLOAD_COMPILED_IN` in the module's `BuildConfig` mirrors `mtl.gpuOffload`, and
`LlamatikBuildInfo.gpuOffloadCompiledIn` exposes it to the app.

When nothing is configured, CMake probes `/opt/vulkan-headers` (the location §3, CI and
`wsl-setup.sh` create) for a directory containing both `vulkan/` and `vk_video/`, and requires
one of them to actually provide `vulkan/vulkan.hpp`.

The `mtl.wsl*` properties only matter on a **Windows** host, where the build is delegated to
WSL; on Linux/WSL/CI they are ignored.

---

## 5. Pitfalls (each of these cost a real build)

1. **`Vulkan::Vulkan` undefined in the consuming scope.** `find_package` IMPORTED targets are
   visible only in the directory that called it, and `ggml-vulkan` calls it in its own
   subdirectory. The `find_package(Vulkan …)` has to be repeated at the top level or the
   link fails with an undefined imported target.
2. **`ggml-vulkan` finds SPIRV-Headers but never links the target**, so the package's include
   dir never reaches the compiler. It only works upstream when a monolithic SDK puts
   everything under one include prefix.
3. **Vulkan-Hpp/Vulkan-Headers version skew** → `static assertion failed … 275 == 363`.
4. **ggml-vulkan pastes `glslc`'s stderr unquoted** into
   `if(${glslc_error} MATCHES …)`; a `;`, quote or newline in that output aborts the
   configure with `Unknown arguments specified`. The generated wrapper flattens stderr.
5. **The wrapper must not write to stdout** — that stream carries the SPIR-V binary from
   `glslc -o -`. The sanitized text goes to stderr, or the generated shader header is
   corrupted and the link fails with undefined `*_data` / `*_len` symbols.
6. **Never treat `wsl.exe` as a proxy target on a non-Windows host.** It is on PATH inside
   WSL, so doing so routes ~40 shader compiles per ABI out to Windows and back — a
   plausible-looking hang.
7. **API 26 stub** → `undefined symbol: vkGetPhysicalDeviceFeatures2` at link time.
8. **A host `/usr/include` on the include path** shadows the NDK sysroot's libc headers.
9. **CMake silently drops `/usr/include`** from a target's include path as an implicit system
   dir, so `include_directories(/usr/include)` appears to do nothing. The module injects raw
   `-I` flags via `target_compile_options` on the `ggml-vulkan` target only, which sidesteps
   both this and pitfall 5.
10. **Nested submodules.** `external/llamatik` has its own submodules; a non-recursive checkout
   leaves `llama.cpp/` empty and CMake aborts with `llama.cpp not found`.
11. **The output name must stay `libllama_jni.so`** — `System.loadLibrary("llama_jni")` looks it
   up by that exact name, and a second artifact containing the same name is a duplicate
   `jniLibs` packaging error. That is why the `com.llamatik:library` AAR is not depended on.
12. **Every `external fun` needs a matching C++ symbol.** The vendored Kotlin wrapper is a
   trimmed subset; adding a function without the `Java_com_llamatik_library_platform_*`
   counterpart fails at runtime with `UnsatisfiedLinkError`.

---

## 6. Sizes

Measured with NDK r28c, `llvm-strip --strip-unneeded`, per ABI:

| Variant | `libllama_jni.so` stripped | unstripped |
|---|---|---|
| CPU-only (default) | **6.6 MB** | 75 MB |
| Vulkan | **64.2 MB** | 141 MB |

The ~58 MB Vulkan delta is ggml's embedded SPIR-V compute shaders. With `arm64-v8a` and
`armeabi-v7a` that is ~128 MB of APK versus ~13 MB for the CPU-only build. The unstripped
figures are dominated by debug info; AGP strips release builds.

---

## 7. Verifying a build

```bash
# CPU-only — both ABIs
./gradlew :llamatik-native:assembleRelease

# confirm the JNI surface
$NDK/toolchains/llvm/prebuilt/*/bin/llvm-nm -D --defined-only \
    path/to/libllama_jni.so | grep -c Java_com_llamatik
# expect 33

# Vulkan — expect 150 ggml_vk / ggml_backend_vulkan symbols
$NDK/…/llvm-nm -C path/to/libllama_jni.so | grep -c ggml_vk
```

CMake can also be driven directly, which is much faster than Gradle for iterating on the
native side:

```bash
cmake -S llamatik-native/src/main/cpp -B /tmp/llm -G Ninja \
  -DCMAKE_TOOLCHAIN_FILE=$NDK/build/cmake/android.toolchain.cmake \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-26 -DANDROID_STL=c++_static \
  -DHOURI_GPU_OFFLOAD=OFF
ninja -C /tmp/llm
```

---

## 8. Provenance

Verified with: Debian 13 (trixie) on WSL2, NDK `28.2.13676358` (linux-x86_64), CMake 3.31.6,
Ninja, llama.cpp pinned at `961e9a3e46ca4cf7e6e86cfceb5b5e32084bf5f0`. CPU-only and Vulkan
variants were both configured *and fully built* for `arm64-v8a`; CPU-only also for
`armeabi-v7a` and `x86_64`. The Windows-host branches in §2.4 are implemented and
time-bounded but were not exercised in that environment.
