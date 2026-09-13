# source-api/ Module

Extension `Source` API (Kotlin Multiplatform). Package root: `eu.kanade.tachiyomi.source.*`.

## Purpose

Defines the contract that APK extensions (installed separately) must conform to. **Avoid breaking extension ABI.**

## Key interfaces

- `Source` – Base source interface
- `CatalogueSource` – Sources with browse/search (suspend APIs)
- `HttpSource` – HTTP-based sources with OkHttp client
- `MetadataSource<M, R>` – Sources providing structured metadata
- `UrlImportableSource` – Sources supporting URL import
- `NamespaceSource` – Sources with tag namespaces
- `PagePreviewSource` – Sources supporting page preview thumbnails

## KMP structure

- `commonMain` source set defines the extension API
- `androidTarget()` for Android platform
- Non-standard: only this module uses KMP; all others use `kotlin("android")`

## Delegated sources

In `exh/source/`: `DelegatedHttpSource`, `EnhancedHttpSource` for in-repo sources (NHentai, 8Muses, etc.)

## Per-source preferences (adjacent surface)

`ConfigurableSource` implementations keep prefs in per-source SharedPreferences files named
`source_$id` (extension ABI) — separate from the app-wide `PreferenceStore` and deliberately
**outside** the modular settings framework (`settings/framework/`). Do not migrate source
prefs into `SettingKey`s; the framework must never assume the default shared-prefs file.

## Conventions

- **NEVER** break extension ABI
- Package root: `eu.kanade.tachiyomi.source.*`
- Fork markers: `// SY -->` for TachiyomiSY additions
