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
SDK. It is **opt-in** because it needs two things a plain NDK install does not give you:

1. **A Vulkan SDK, or an equivalent header triple.** The NDK *does* ship `glslc` (under
   `shader-tools/`) and the `libvulkan.so` loader stub, so shader compilation is fine. What
   it does not ship is `vulkan/vulkan.hpp` (Vulkan-Hpp) or SPIRV-Headers. ggml-vulkan also
   calls `find_package(SPIRV-Headers)` without ever linking the target, so that package's
   include dir never reaches the compiler. And the Vulkan-Hpp you pair with it must be the
   *same version* as your Vulkan-Headers — the NDK's bundled `VK_HEADER_VERSION 275` is
   too old for a current Vulkan-Hpp (which wants 363).
2. **API level 29+.** ggml-vulkan needs the Vulkan 1.1 core entry point
   `vkGetPhysicalDeviceFeatures2`; the NDK's import stub only exports it from API 29
   (148 Vulkan symbols at API 26, 182 at 29). The Vulkan build therefore compiles against
   `android-29` even though the app itself supports API 26 — devices below that keep using
   the CPU path.

```bash
./gradlew :llamatik-native:assembleRelease -Pmtl.gpuOffload=true \
    -Pmtl.vulkanSdkDir=/opt/VulkanSDK/1.4.313

# or, without a full SDK — Vulkan-Headers and Vulkan-Hpp must match versions:
./gradlew :llamatik-native:assembleRelease -Pmtl.gpuOffload=true \
    -Pmtl.vulkanIncludeDirs="/src/Vulkan-Headers/include;/src/Vulkan-Hpp;/src/SPIRV-Headers/include"
```

On Debian/Ubuntu the whole SDK is three apt packages — `glslc libvulkan-dev spirv-headers` —
plus a staging dir holding only the headers. The full recipe, the Windows/WSL proxying, and
every pitfall are documented in
[`llamatik-native/BUILD.md`](../../llamatik-native/BUILD.md).

`MTL_GPU_OFFLOAD=true` in the environment works too, and `VULKAN_SDK` is honoured as a
fallback for `mtl.vulkanSdkDir`. Without the flag the build is CPU-only and needs nothing
beyond the NDK.

Once enabled, `gpuLayers` genuinely offloads. `LocalLlmAccelerator` decides whether to trust
it: if the build has no GPU backend or the device reports no Vulkan compute support, the
value is forced to `0` and the settings screen says so — llama.cpp never fails an offload it
cannot perform, it just runs on the CPU and the "GPU layers" setting becomes a lie.

### Size

Measured with NDK r28c, stripped (`llvm-strip --strip-unneeded`), per ABI:

| Variant | `libllama_jni.so` |
|---|---|
| CPU-only (default) | **6.6 MB** |
| Vulkan | **64.2 MB** |

The Vulkan variant is ~58 MB larger per ABI because ggml embeds its SPIR-V compute shaders.
With both `arm64-v8a` and `armeabi-v7a` that is ~128 MB added to the APK, versus ~13 MB for
the CPU-only build. (For reference, the `com.llamatik:library` AAR it replaces ships 24 MB
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
