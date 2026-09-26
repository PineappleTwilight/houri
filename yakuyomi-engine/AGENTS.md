# yakuyomi-engine/ Module

On-device + cloud manga translation ("AI Translation" / MTL). Package root: `exh.yakuyomi.*`.
Komikku-side orchestration; the native vision pipeline (`li.joye.yakuyomi.engine.*`:
Detector/Ocr/Inpainter/Grouping/Renderer/Pipeline) lives in `external/yakuyomi-engine`,
a git **submodule** (PineappleTwilight/houri-engine, branch `main`) — commit the submodule
pointer BEFORE the main repo. ABIs: arm64 + armeabi-v7a (x86 degrades to "not ready").

## Pipeline (per page)

detect (DBNet NCNN 1024) → OCR (ONNX 48px CTC) → group (union-find + MST) →
translate (LLM) → inpaint (AOT-GAN NCNN, overlaps translate) → typeset (Canvas).
Removal covers **all** detected regions; translation only the OCR-readable ones.
Never overwrite a good translation with a worse one (§11 rule).

## Key classes

| Class | Role |
|---|---|
| `TranslationManager` | Orchestrates pages, friendly errors, per-manga gate; falls back cloud ← on-device |
| `YakuyomiEngine` | Wraps native pipeline; resolves Typeface + text color per page |
| `YakuyomiTranslator` | Cloud LLM (openrouter/gemini/opencode_zen/nvidia_nim/custom_openai) |
| `GeminiNanoTranslator` | On-device ML Kit GenAI (`genai-prompt:1.0.0-beta4`); priority LLM when AVAILABLE |
| `LocalLlmManager` / `LocalLlmTranslator` / `LlamaCppLlmBackend` | Local GGUF via llama.cpp; per-model sampling overrides |
| `LocalLlmAccelerator` | Probes whether `gpuLayers` can actually offload (GPU backend built in? Vulkan compute?) |
| `LocalLlmDownloadManager` | Resumable GGUF+mmproj downloads (Range resume, throttled emits) |
| `ModelManager` | Downloads detector/OCR/inpainter models |
| `TranslationCache` / `TranslatedPageStore` | PageHash-keyed WEBP cache + translated page state |
| `BreadcrumbNotes` / `MangaInfoTranslation(Store)` | Cross-page consistency notes; per-manga toggle store |
| `TranslationPreferences` | ~54 prefs (engine tuning, per-provider keys); stub duplicate in `yakuyomi-stub/` |
| `TranslationPrompt.kt` | Shared prompt builders / line-alignment / JSON parsing |
| `TranslationStatus` / `TranslationErrorMapper` / `TranslationMetrics` | Status, error mapping, metrics |

## Gotchas

- ML Kit beta4 API differs from docs: `Generation.getClient()` → `GenerativeModel`;
  `checkStatus(): Int` (0–3); `download(): Flow<DownloadStatus>`; request via
  `generateContentRequest(ImagePart, TextPart) { temperature; maxOutputTokens }`.
- llama.cpp backend: `updateGenerateParams(...)` MUST be called or models output nothing;
  wrap prompts with `applyChatTemplate`.
- The llama.cpp runtime is **not** the `com.llamatik:library` AAR — it is built from the
  `external/llamatik` fork by `:llamatik-native` (see `README-local-llm.md`). The upstream AAR
  is CPU-only, so its `gpuLayers` offloads nothing.
- `external/llamatik` is a submodule with **nested** submodules: use
  `git submodule update --init --recursive external/llamatik` or CMake aborts.
- GPU offload (ggml Vulkan) is opt-in via `-Pmtl.gpuOffload=true`. It needs Vulkan-Hpp +
  SPIRV-Headers (the NDK has `glslc` but not those) and compiles against **API 29+**, because
  ggml-vulkan needs a Vulkan 1.1 core symbol the NDK's `libvulkan.so` stub only exports from
  there. Stripped cost: 6.6 MB/ABI CPU-only vs 64.2 MB/ABI with Vulkan.
- llama.cpp never fails an offload it cannot perform — it logs
  `compiled without support for GPU offload` and runs on the CPU. `LlamaCppLlmBackend` therefore
  forces `gpuLayers` to 0 unless `LocalLlmAccelerator` says offload is real.
- Renderer caps translated text at the original glyph size (`originalFontSize` = median
  line-quad thickness, measured on `TextLine.tightQuad` — the **pre-unclip** rect, since
  DBNet's 2.3x unclip inflates a 30x200 line to ~90x260); `RenderConfig.fixedTextColor` +
  `"fixed"` colorMode override it.
- Inpainting removes **glyph strokes**, not region boxes: `buildSegMask` intersects DBNet's
  stroke mask with the region boxes. Masking whole boxes hands AOT-GAN a hole several times
  the glyph area, which returns as a flat light patch over artwork.
- The **removal** set is every detected region (m-i-t parity — unreadable text is erased too,
  so no raw source script survives beside the English); the **translation** set is only regions
  with readable OCR. Removal's per-region box fallback is gated on readable text, so false
  detections are not blanketed out of the artwork.
- `colorMode="fixed"` resolves outline == fill on light backgrounds, so the renderer must skip
  the outline there — stroking black-on-black emboldens glyphs and closes their counters.
- Settings UI: `SettingsYakuyomiScreen` (+ LLM/engine advanced screens); Gemini Nano toggle
  + live status row; per-model sampling sliders hidden without a model.
- Prompt/parse helpers are shared — do not fork per-provider copies.
- Fork markers: `// KMK -->` for Komikku additions.
