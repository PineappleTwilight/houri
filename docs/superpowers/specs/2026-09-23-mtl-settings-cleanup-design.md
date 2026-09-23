# MTL Settings Cleanup Design

Date: 2026-09-23

## Goal

Reduce the visual and navigational clutter of the MTL/Yakuyomi settings screen without changing preference keys, runtime behavior, or available actions.

## Current problem

`SettingsYakuyomiScreen` currently combines the feature header, status, enablement, provider selection, Gemini Nano state, cloud credentials/models, local LLM management, MTL model downloads, remote model URLs, MangaTranslator account/cache settings, translation behavior, advanced engine settings, and active sessions in one long page. Provider-specific controls are mixed with model management and behavioral preferences, making the common setup path difficult to scan.

## Approved design

Use a hub-and-spoke layout:

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

### Navigation and search

- Register the three new searchable screens in `SettingsCatalog.searchableScreens`.
- Keep the existing main-screen entry for MTL unchanged.
- Reuse the existing `Preference.PreferenceItem` rows and existing advanced screens so visual and interaction behavior stays consistent.
- Do not rename or migrate any preference keys.

## Data and behavior

The subpages receive the same `TranslationPreferences`, model manager, local LLM manager, and cache objects through the existing global graph. They only reorganize composition and navigation. Provider migration, model fetching, downloads, cache clearing, and all existing async state remain in their current owner components.

## Verification

- Inspect the final diff for accidental preference or behavior changes.
- Run the repository’s Kotlin LSP diagnostics when available.
- Run Spotless/Gradle verification only when explicitly requested by the user.
- Review the new screen registration in both the catalog and existing navigation flow.
