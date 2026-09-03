# On-device Local LLM provider — build & bundling guide

The "Local (On-device LLM)" translation provider runs a small LLM fully offline via **llama.cpp**
(Llamatik runtime, `com.llamatik:library` from Maven Central). Any **GGUF** model can be loaded
directly — no per-model compilation — so users can also load their own GGUF files from device
storage. No native bundling or CI build step is needed: the runtime ships as a regular AAR.

## Backends

| Backend | Runtime | Hardware | Notes |
|---|---|---|---|
| **llama.cpp** (LLAMACPP) | `com.llamatik:library` (Maven Central) | CPU (ARM NEON), GPU via Vulkan where supported | the only backend; loads any GGUF |

The MLC-LLM and ExecuTorch backends were retired: MLC required compiling per-model TVM libraries
(fragile JIT, segfaulted in CI) and ExecuTorch needed per-SoC `.pte` artifacts. GGUF via llama.cpp
is the download-and-run path.

## Model catalog

Entries are GGUF files on HuggingFace (imatrix K-quants like Q5_K_M preferred). Verified repos:

| Model | Repo / file | Size | Quality |
|---|---|---|---|
| Gemma 4 E4B IT | `unsloth/gemma-4-E4B-it-GGUF` → `gemma-4-E4B-it-Q5_K_M.gguf` | ~3.1 GB | best |
| Gemma 4 E4B IT (QAT) | `google/gemma-4-E4B-it-qat-q4_0-gguf` → `gemma-4-E4B_q4_0-it.gguf` | ~2.8 GB | high |
| TranslateGemma 4B (TL finetune) | `Qwe1325/translategemma-4b-it-GGUF` → `translategemma-4b-it-q5_k_m.gguf` | ~3.0 GB | best |
| Gemma 4 E2B IT | `unsloth/gemma-4-E2B-it-GGUF` → `gemma-4-E2B-it-Q5_K_M.gguf` | ~1.8 GB | good |
| Gemma 4 E2B IT (QAT) | `google/gemma-4-E2B-it-qat-q4_0-gguf` → `gemma-4-E2B_q4_0-it.gguf` | ~1.6 GB | good |
| Llama 3.2 1B Instruct | `unsloth/Llama-3.2-1B-Instruct-GGUF` → `Llama-3.2-1B-Instruct-Q5_K_M.gguf` | ~0.9 GB | basic |

Only the RAM gate is enforced (a model is hidden when it exceeds the device's total RAM); the
best-fit model is presented as a default but never forced. Users can load their own GGUF via
Settings → Translation → Local → *Load custom GGUF…* — the file is copied into app storage and
used verbatim (no API key, no upload).

## Adding a new model

1. Find (or produce) a GGUF: official QAT repos (`google/*-gguf`) or imatrix K-quants from
   `unsloth/*-GGUF` work best; a translation finetune can be exported with `llama.cpp`'s
   `convert_hf_to_gguf.py` (imatrix + `Q5_K_M` recommended).
2. Add a `LocalLlmModel` entry in `LocalLlmCatalog` with `ggufRepo` + `ggufFile` (the downloader
   fetches `https://huggingface.co/<repo>/resolve/main/<file>`).
3. That's it — llama.cpp loads the file as-is.
