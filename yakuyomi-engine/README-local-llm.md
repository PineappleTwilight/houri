# On-device Local LLM provider — build & bundling guide

The "Local (On-device LLM)" translation provider runs a small LLM fully offline via **llama.cpp**.
Any **GGUF** model can be loaded directly — no per-model compilation — so users can also load
their own GGUF files from device storage. No API key, no upload.

## Runtime

The runtime is built from source by the `:llamatik-native` module, out of the
`external/llamatik` submodule (our fork of [ferranpons/Llamatik](https://github.com/ferranpons/Llamatik),
MIT). The upstream `com.llamatik:library` AAR is **not** used.

Two reasons for the fork:

1. The published AAR ships a **CPU-only** llama.cpp. Its `jni/<abi>/` directory contains only
   `libggml-cpu.so` and no GPU backend plugin, so its `gpuLayers` parameter offloads nothing.
   llamatik does support the flag — `-DGGML_VULKAN=ON` — their CI just never passes it.
2. The AAR is built with `GGML_BACKEND_DL=ON`, so ggml backends are separate `.so` plugins that
   it `dlopen`s and version-checks. We link ggml statically instead, which puts a compiled-in
   backend directly inside `libllama_jni.so`.

The Kotlin wrapper (`com.llamatik.library.platform`) is vendored into
`llamatik-native/src/main/kotlin`. It must stay in that package: JNI mangles the package into
the native symbol names, and `System.loadLibrary("llama_jni")` fixes the `.so` name.

## GPU offload (Vulkan)

Vulkan is the only ggml backend with real Android support — CUDA/Metal/SYCL are
desktop-only, OpenCL is not part of the platform, and Hexagon/QNN needs a licensed vendor
SDK. It is **on by default**, and needs two things a plain NDK install does not give you:

1. **A Vulkan SDK, or an equivalent header triple.** The NDK hides its bundled `glslc`
   (`shader-tools/`) from `find_package(Vulkan)`, so we locate it ourselves, and what it
   ships is a loader stub plus `vulkan_core.h` — not `vulkan/vulkan.hpp` (Vulkan-Hpp) and not
   SPIRV-Headers. Vulkan-Hpp must also be the *same version* as your Vulkan-Headers: the
   NDK's bundled `VK_HEADER_VERSION 275` is too old for a current Vulkan-Hpp (which wants
   363). Run `llamatik-native/wsl-setup.sh` to stage a matched triple and verify it.
2. **API level 29+.** ggml-vulkan needs the Vulkan 1.1 core entry point
   `vkGetPhysicalDeviceFeatures2`; the NDK's import stub only exports it from API 29
   (148 Vulkan symbols at API 26, 182 at 29). The Vulkan build therefore compiles against
   `android-29` even though the app itself supports API 26 — devices below that keep using
   the CPU path.

**Only 64-bit ABIs get the Vulkan backend.** ggml-vulkan does not compile for 32-bit at this
llama.cpp revision: it relies on implicit `vk::Buffer` conversions that Vulkan-Hpp only
provides when the native handle is a pointer. `armeabi-v7a` and `x86` therefore build
CPU-only. All four ABIs still ship a working on-device LLM, and 64-bit devices get the full
GPU runtime.

```bash
# One-time: stage the header triple (works unprivileged with MTL_VULKAN_HEADERS=~/vulkan-headers)
wsl -e bash /mnt/c/Users/<you>/komikku-pineapple/llamatik-native/wsl-setup.sh

# Linux / WSL / CI — AGP drives the per-ABI builds directly
./gradlew :llamatik-native:assembleRelease

# CPU-only, needs nothing beyond the NDK
./gradlew :llamatik-native:assembleRelease -Pmtl.gpuOffload=false
```

A **Windows** host does not configure CMake at all. Proxying tool calls across the
Windows/WSL boundary does not work here: ggml-vulkan invokes `glslc` ~40 times per ABI, so
each call would pay a VM round-trip, and the Vulkan headers cannot be proxied at all because
a Windows `clang++.exe` cannot `#include` anything inside the WSL root filesystem. The whole
configure and build instead runs inside WSL (`wslNativeBuild`), staging each
`libllama_jni.so` into `src/main/jniLibs`. `wsl.exe` is required and its absence is a hard
failure, with no MSYS2 fallback, matching `external/imagedecoder-houri`.

`MTL_GPU_OFFLOAD=false` in the environment works too, and `VULKAN_SDK` is honoured as a
fallback for `mtl.vulkanSdkDir`. The full recipe and every pitfall are documented in
[`llamatik-native/BUILD.md`](../../llamatik-native/BUILD.md).

Once enabled, `gpuLayers` genuinely offloads. `LocalLlmAccelerator` decides whether to trust
it: if the build has no GPU backend for the current ABI, or the device reports no Vulkan
compute support, the value is forced to `0` and the settings screen says so — llama.cpp
never fails an offload it cannot perform, it just runs on the CPU and the "GPU layers"
setting becomes a lie.

### Size

Measured with NDK r28c, stripped (`llvm-strip --strip-unneeded`), per ABI:

| Variant | ABIs | `libllama_jni.so` |
|---|---|---|
| CPU-only | all four | **6.6 MB** |
| Vulkan | 64-bit only | **64.2 MB** |

The Vulkan variant is ~58 MB larger per ABI because ggml embeds its SPIR-V compute shaders.
Across the two 64-bit ABIs (`arm64-v8a`, `x86_64`) that is ~115 MB added to the APK; the two
32-bit ABIs stay CPU-only, so a CPU-only build is ~26 MB. (For reference, the `com.llamatik:library` AAR it replaces ships 24 MB
of `libllama_jni.so` *plus* 4 MB of whisper, and that figure is unstripped.)

## NPU

**There is no NPU backend for GGUF.** `ggml-hexagon` (Qualcomm QNN) needs a licensed vendor SDK
and has narrow op coverage; there is no MediaTek or NNAPI NPU path in llama.cpp. The NPU path
that does work on Android is the one already shipped separately: **Gemini Nano via ML Kit GenAI**
(`GeminiNanoTranslator`), which runs on Android AICore. GGUF stays CPU-or-GPU.

## Backends

| Backend | Runtime | Hardware | Notes |
|---|---|---|---|
| **llama.cpp** (LLAMACPP) | built from `external/llamatik` | CPU (ARM NEON), GPU via Vulkan when `-Pmtl.gpuOffload=true` | the only backend; loads any GGUF |

The MLC-LLM and ExecuTorch backends were retired: MLC required compiling per-model TVM libraries
(fragile JIT, segfaulted in CI) and ExecuTorch needed per-SoC `.pte` artifacts. GGUF via llama.cpp
is the download-and-run path.

## Submodules

`external/llamatik` is a submodule that itself has nested submodules, so it must be initialised
recursively — otherwise CMake fails with `llama.cpp not found`:

```bash
git submodule update --init --recursive external/llamatik
```

Only `llama.cpp` is built. The fork's `whisper.cpp` and `stable-diffusion.cpp` submodules are
left unchecked out, so their sources are unused. The nested llama.cpp commit is pinned by the
fork; bump it by advancing the submodule there, never by editing a path here.

## Model catalog

Entries are GGUF files on HuggingFace (imatrix K-quants like Q5_K_M preferred). Verified repos:

| Model | Repo / file | Size | Quality |
|---|---|---|---|
| Gemma 4 E4B IT | `unsloth/gemma-4-E4B-it-GGUF` → `gemma-4-E4B-it-Q5_K_M.gguf` | ~3.1 GB | best |
| Gemma 4 E4B IT (QAT) | `google/gemma-4-E4B-it-qat-q4_0-gguf` → `gemma-4-E4B_q4_0-it.gguf` | ~2.8 GB | high |
| TranslateGemma 4B (TL finetune) | `Qwe1325/translategemma-4b-it-GGUF` → `translategemma-4b-it-q5_k_m.gguf` | ~3.0 GB | best |
| Qwen3 4B (text-only) | `unsloth/Qwen3-4B-GGUF` → `Qwen3-4B-Q5_K_M.gguf` | ~2.9 GB | high |
| Qwen3 1.7B (text-only) | `unsloth/Qwen3-1.7B-GGUF` → `Qwen3-1.7B-Q5_K_M.gguf` | ~1.3 GB | good |
| Gemma 4 E2B IT | `unsloth/gemma-4-E2B-it-GGUF` → `gemma-4-E2B-it-Q5_K_M.gguf` | ~1.8 GB | good |
| Gemma 4 E2B IT (QAT) | `google/gemma-4-E2B-it-qat-q4_0-gguf` → `gemma-4-E2B_q4_0-it.gguf` | ~1.6 GB | good |
| Llama 3.2 1B Instruct | `unsloth/Llama-3.2-1B-Instruct-GGUF` → `Llama-3.2-1B-Instruct-Q5_K_M.gguf` | ~0.9 GB | basic |

Only the RAM gate is enforced (a model is hidden when it exceeds the device's total RAM); the
best-fit model is presented as a default but never forced. Users can load their own GGUF via
Settings → Translation → Local → *Load custom GGUF…* — the file is copied into app storage and
used verbatim (no API key, no upload). The Qwen3 presets are text-only and do not download a
vision projector; closed reasoning blocks are removed from their generated output.

## Adding a new model

1. Find (or produce) a GGUF: official QAT repos (`google/*-gguf`) or imatrix K-quants from
   `unsloth/*-GGUF` work best; a translation finetune can be exported with `llama.cpp`'s
   `convert_hf_to_gguf.py` (imatrix + `Q5_K_M` recommended).
2. Add a `LocalLlmModel` entry in `LocalLlmCatalog` with `ggufRepo` + `ggufFile` (the downloader
   fetches `https://huggingface.co/<repo>/resolve/main/<file>`).
3. That's it — llama.cpp loads the file as-is.
