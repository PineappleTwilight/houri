# MTL Settings Cleanup Design

Date: 2026-09-23

## Goal

Reduce the visual and navigational clutter of the MTL/Yakuyomi settings screen without changing preference keys, runtime behavior, or available actions. At the same time, make manga-details metadata translation a first-class, independently controllable feature instead of a secondary card tied to page-translation state.

## Non-goals

- Do not change the existing page-translation pipeline or its per-manga gate.
- Do not add a second global enable switch for metadata translation; the existing global MTL switch remains the master switch.
- Do not route metadata through the image-only MangaTranslator service in this change.
- Do not expand the no-MTL flavor beyond the current availability behavior.
- Do not rename or migrate existing preference keys.

## Current problems

### Settings

`SettingsYakuyomiScreen` currently combines the feature header, status, enablement, provider selection, Gemini Nano state, cloud credentials/models, local LLM management, MTL model downloads, remote model URLs, MangaTranslator account/cache settings, translation behavior, advanced engine settings, and active sessions in one long page. Provider-specific controls are mixed with model management and behavioral preferences, making the common setup path difficult to scan.

### Manga details

Metadata translation currently:

- is displayed in a separate card instead of in the normal details view;
- uses a per-manga preference, but `TranslationManager.translateMangaInfo()` still requires the separate page-translation per-manga gate;
- stores a result keyed only by manga ID, so source metadata, target language, provider, and model changes can leave stale text visible;
- has no explicit retry action after a failure;
- clears only in-memory state when disabled while leaving the persisted cache behind;
- assumes `JA` as the source language even when the source declares another language.

## Approved settings design

Use a hub-and-spoke layout.

### Main hub: `SettingsYakuyomiScreen`

Keep the MTL page focused on orientation and common setup:

- compact feature/status summary;
- MTL enable switch and target language;
- provider selection;
- one summary row for provider setup, showing the selected provider and its readiness/configuration state;
- one summary row for models and engine, showing local/remote model state and links to model controls;
- one summary row for translation behavior, showing cache/offline/save state;
- one row for advanced tools, linking to the existing engine/LLM advanced screens;
- one row for active translation sessions.

The large decorative header and verbose status block are reduced to a compact status presentation. No preference is removed from the hub; controls move to focused subpages.

### Provider setup subpage

Create a searchable `SettingsYakuyomiProviderScreen` for:

- Gemini Nano toggle and status;
- cloud provider API key, model, and model refresh;
- custom OpenAI-compatible base URL and headers;
- MangaTranslator-specific guidance and account navigation.

Only controls relevant to the selected provider are shown, preserving the current `mtlOnly`, `dependsOn`, and enabled-state behavior.

### Models and engine subpage

Create a searchable `SettingsYakuyomiModelsScreen` for:

- local LLM model selection, import, start/stop, auto-start, download progress, and cleanup;
- MTL detector/OCR/inpainter model download state and cleanup;
- remote model URL overrides and re-download action;
- navigation to the existing LLM advanced and engine advanced screens.

### Behavior subpage

Create a searchable `SettingsYakuyomiBehaviorScreen` for:

- offline fallback;
- translation cache enablement and clear-cache action;
- automatic translation on download;
- saving translated pages to chapter folders;
- auto-save while reading;
- breadcrumb/context window;
- the existing grammar/vocabulary note.

The metadata control remains on each manga's details screen; no new global metadata preference is added to the settings subpages.

### Navigation and search

- Register the three new searchable screens in `SettingsCatalog.searchableScreens`.
- Keep the existing main-screen entry for MTL unchanged.
- Reuse the existing `Preference.PreferenceItem` rows and existing advanced screens so visual and interaction behavior stays consistent.
- Do not rename or migrate any preference keys.

## Manga-details translation design

### State ownership

Add an app-layer `MangaInfoTranslationController` beside `MangaScreenModel`. It owns the metadata translation lifecycle and publishes a `StateFlow` with these states:

- `Hidden`: the feature is unavailable for this build or manga;
- `Disabled`: the user has not enabled metadata translation;
- `Translating`: a request is in progress;
- `Translated`: contains the translated title/description and whether the original or translated view is selected;
- `Error`: contains a user-facing failure category and supports retry.

`MangaScreenModel` collects the controller state and exposes it in `State.Success`. Both phone and tablet layouts consume the same state. The Compose content no longer reads or writes the metadata cache directly.

### Gating and provider routing

Metadata translation has its own eligibility check:

- the global MTL switch must be enabled;
- incognito and censorship gates still apply;
- the per-manga page-translation toggle is not consulted;
- the configured local/cloud text provider must be ready;
- MangaTranslator is reported as unavailable for metadata because it is an image service;
- the current `IS_NOMTL` availability rule remains unchanged, so the feature stays hidden in no-MTL builds.

The existing local-LLM and cloud text translation paths remain the supported metadata providers. Existing page-translation provider selection is not otherwise changed.

### User-visible behavior

The details screen keeps a compact per-manga **Translate details** control near the normal metadata area. When enabled:

1. A valid cached result is shown immediately.
2. Otherwise the original title/description remain visible while translation runs.
3. A successful result replaces the normal displayed title and description.
4. A **Show original / Show translated** control switches the display without deleting the cache.
5. **Refresh** bypasses the cache and retries the current source metadata with the current target language/provider/model.
6. **Reset/disable** cancels or ignores in-flight work, clears the persisted cache, and returns to the original metadata.

Failure keeps the original metadata visible and provides a retry action. A stale cache is never displayed as current.

### Source language and cache identity

Use the source's declared language when available, with `JA` retained as the fallback when the source does not declare one.

Extend the persisted `MangaInfoTranslation` record with these defaulted fields:

- `sourceFingerprint: String` — a stable digest of source ID, normalized source title, normalized source description, and source language;
- `targetLanguage: String`;
- `provider: String`;
- `model: String`.

The cache entry is valid only when all four fields match the current request. The manga ID remains the filename key. Legacy entries with missing/blank validation fields are treated as stale and replaced on the next request; no migration is required.

The JSON file remains one file per manga under `manga_info_translations/`.

### Rendering integration

Do not mutate the domain `Manga` model. Add explicit display values to the details rendering path:

- `MangaInfoBox` receives the selected display title;
- `ExpandableMangaDescription` receives the selected display description;
- the domain manga and original metadata remain available for editing, search, source actions, and fallback rendering.

All new user-facing strings for metadata translation status, actions, and errors use `KMR` and are added only to `i18n-kmk/src/commonMain/moko-resources/base/`.

## Error and cancellation behavior

- Cancelling or disabling metadata translation must not publish a late result.
- Provider/network errors remain retryable and never replace the original metadata.
- Empty or unusable model output is treated as an error, not a successful translation.
- Cache read/write failures fall back to the original metadata and can be retried.
- A refresh while a request is active supersedes the older request.

## Verification

- Add focused unit coverage for controller state transitions, independent gating, cache fingerprint validation, stale-cache replacement, and source-language fallback.
- Verify the existing page-translation toggle remains independent and unchanged.
- Inspect the final diff for accidental preference-key, page-pipeline, or no-MTL behavior changes.
- Run Kotlin LSP diagnostics when available.
- The user may run Spotless/Gradle verification; the implementation agent must not run Gradle tasks unless explicitly authorized.
