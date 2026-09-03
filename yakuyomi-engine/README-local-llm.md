# On-device Local LLM provider — build & bundling guide

The "Local (On-device LLM)" translation provider runs a small LLM fully offline. It has two
native backends:

| Backend | Hardware | Runtime | Status |
|---|---|---|---|
| **MLC-LLM** | GPU (OpenCL on Adreno/Mali) | `mlc4j` (built from the MLC-LLM source tree) | primary; needs bundling |
| **ExecuTorch** | NPU (Qualcomm QNN / MediaTek NeuroPilot) or CPU (XNNPACK) | `org.pytorch:executorch-android` (Maven Central) | works out of the box; needs `.pte` model artifacts |

The ExecuTorch AAR is a normal Maven dependency (already wired in `yakuyomi-engine/build.gradle.kts`).
The MLC-LLM runtime is **not** on Maven Central, so it must be built from source and bundled — the
code drives it through reflection, so the app compiles and runs fine without it (the local provider
simply reports "runtime not bundled").

## Bundling the MLC-LLM runtime (`mlc4j`)

1. Clone MLC-LLM and prepare its Android package:

   ```bash
   git clone https://github.com/mlc-ai/mlc-llm.git && cd mlc-llm
   git submodule update --init --recursive
   pip install mlc-llm     # the python package used by `mlc_llm package`
   ```

2. Define the model list for the app in `android/MLCChat/mlc-package-config.json`. The compiled
   model libraries for the catalog's archs are produced here; weights stay on HuggingFace and are
   downloaded at runtime by the app.

3. Build the runtime + model libraries:

   ```bash
   cd android/MLCChat
   export MLC_LLM_SOURCE_DIR=/path/to/mlc-llm
   export ANDROID_NDK=/path/to/ndk
   mlc_llm package
   ```

   This produces `dist/lib/mlc4j` — a Gradle subproject with `libtvm4j_runtime_packed.so`
   (per ABI) and `tvm4j_core.jar`.

4. Bundle it: add the produced `.so` files to `yakuyomi-engine/src/main/jniLibs/<abi>/` and the
   `tvm4j_core.jar` as a packaged jar (or include `:mlc4j` as a composite build). After bundling,
   `LocalLlmCatalog.isMlcRuntimeBundled()` flips to `true` and the MLC models activate.

## Model conversion pipeline (Gemma 4, TranslateGemma, TL finetunes)

Catalog entries with `requiresArtifacts = true` point at weight repos that must be produced once
by a build pipeline (the MLC team's `mlc-ai/*-MLC` repos work as-is; everything else is ours):

1. Convert the source checkpoint (e.g. `google/gemma-4-E4B-it`) to MLC format:

   ```bash
   mlc_llm convert_weight \
     --source-format hf \
     https://huggingface.co/google/gemma-4-E4B-it \
     --output hf://houri-app/gemma-4-E4B-it-q4f16_1-MLC \
     --quantization q4f16_1
   ```

2. Push the converted weights to the repo id named in `LocalLlmCatalog` (e.g.
   `houri-app/gemma-4-E4B-it-q4f16_1-MLC`). The app downloads them from
   `https://huggingface.co/<repo>/resolve/main/...` automatically.

3. Compiled model libraries: either bundle them (step 3 above) or publish them to the repo named
   in each entry's `mlcLibRepo` (file convention `<modelLib>-android-<abi>.so`); the app falls back
   to `system://` bundled libs when the repo isn't reachable.

### Catalog model families

| Model | Size | Quality | Vision | Notes |
|---|---|---|---|---|
| Gemma 4 E4B IT | ~3 GB | best | yes | community-recommended for manga translation |
| TranslateGemma 4B | ~2.9 GB | best | yes | SOTA open translation model, 55 languages |
| Qwen3.5 4B VNTL | ~3 GB | best | no | manga-dialogue TL finetune (JA/KO/ZH→EN) |
| Gemma 3 4B IT | ~2.8 GB | high | yes | pre-converted by `mlc-ai` (works today) |
| Gemma 4 E2B IT | ~1.9 GB | good | yes | lighter Gemma 4 |
| Qwen2.5 3B / Gemma 2 2B / Qwen2.5 1.5B / Llama 3.2 1B | 0.7–2 GB | good→basic | no | pre-converted by `mlc-ai` (works today) |
| Llama 3.2 3B/1B (ExecuTorch) | 0.8–2.3 GB | good→basic | no | XNNPACK (any device) / QNN (Snapdragon NPU) |

Only the RAM gate is enforced (a model is hidden when it exceeds the device's total RAM); the
best-fit model is presented as a default but never forced.

## ExecuTorch `.pte` artifacts

ExecuTorch needs a serialized `.pte` program + tokenizer per model per backend. The catalog's
`etHfRepo` entries are project-hosted HuggingFace repos (e.g. `houri-app/executorch-llama-3.2-1b`)
holding `llama-3.2-1b-xnnpack.pte` / `llama-3.2-1b-qnn.pte` + `tokenizer.model`. Produce them with
`executorch`'s export scripts (`llama/llama.py` with the XNNPACK or Qualcomm QNN backend — QNN
`.pte` files are per-SoC family and must be compiled for the target device).