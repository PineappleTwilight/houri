# Komikku – AI Agent Guide

**Generated:** 2026-09-15
**Commit:** 89a39453c
**Branch:** master

Houri (`applicationId app.houri`) — Android manga reader (min SDK 26, target 36, compile 37, JVM 17 / Kotlin) forked from **Mihon** + **TachiyomiSY**. Stack: Jetpack Compose + Material3, Voyager navigation, SQLDelight, Metro DI (+ legacy Injekt bridge).

**Scale:** ~2300 files, ~190k lines Kotlin/Java.

## Guides in this repo (read the child before touching its area)

| Guide | Covers |
|-------|--------|
| `app/AGENTS.md` | app module: package roots, Metro DI, screens, backup/restore, theme |
| `app/.../tachiyomi/ui/reader/AGENTS.md` | ReaderActivity, viewers (pager/webtoon), loaders, upscale, MTL hookup |
| `app/.../tachiyomi/data/track/AGENTS.md` | Tracker services + framework (add/remove: `track/core/README.md`) |
| `app/.../exh/AGENTS.md` | E-Hentai/MangaDex/delegated sources, metadata, recommendations |
| `app/.../settings/framework/AGENTS.md` | Modular settings framework (keys → hosts → screens) |
| `domain/AGENTS.md` | Interactors, models, repository interfaces |
| `data/AGENTS.md` | SQLDelight schema, migrations, repo impls, mappers |
| `source-api/AGENTS.md` | Extension Source API + ABI rules |
| `core/common/AGENTS.md` | Network, prefs framework, storage, utils, logging |
| `core/archive/AGENTS.md` | CBZ/archive reading + encryption |
| `presentation-core/AGENTS.md` | Shared Compose components (+ parallel SettingsItems system) |
| `presentation-widget/AGENTS.md` | Glance home-screen widget |
| `yakuyomi-engine/AGENTS.md` | MTL orchestration (wrapper; native engine is a submodule, commit it first) |
| `llamatik-native/AGENTS.md` | On-device LLM runtime: vendored llama.cpp from the `external/llamatik` fork (Vulkan on by default, 64-bit ABIs only; Windows hosts build in WSL) |

---

## Mandatory rules for AI agents

**Read this section before every change.** These rules override shortcuts (e.g. copying nearby `MR` imports or only running `compileDebugKotlin`).

### Git

| Rule | Required behavior |
|------|-------------------|
| Branch | Create a **feature branch** for the task (`git checkout -b <type>/<short-description>`). |
| Commit | **OK** on a feature branch when work is ready. **Never** commit directly to `master` / `main` unless the user explicitly asks. |
| Push | **OK** to push the **current feature branch** when work is ready. **Never** push to `master` / `main` unless the user explicitly asks. |
| Ported PRs | When porting code from an upstream PR (Mihon/Komikku/SY or any fork), **co-author the PR author** on the porting commit: add `Co-authored-by: <author-name> <<author-email>>` (fetch from the PR's commits API — `https://api.github.com/repos/<owner>/<repo>/pulls/<n>/commits`). |

Before `git push`, confirm the current branch is not `master` or `main` (`git branch --show-current`).

### Internationalization (strings)

| String kind | Module | Resource class | Base folder only |
|-------------|--------|----------------|------------------|
| Komikku-only (new features, KMK UI, library-update errors, WebDAV, Discord, etc.) | `i18n-kmk/` | **`KMR`** | `i18n-kmk/src/commonMain/moko-resources/base/` |
| Shared Mihon / upstream behavior | `i18n/` | **`MR`** | `i18n/src/commonMain/moko-resources/base/` |
| TachiyomiSY-only | `i18n-sy/` | **`SYMR`** | `i18n-sy/src/commonMain/moko-resources/base/` |

**Hard rules:**

- **Never** add Komikku-specific strings to `i18n/` or `i18n-sy/`.
- **Never** edit non-`base` locale `strings.xml` or `plurals.xml` files in `i18n-kmk/`, `i18n/`, or `i18n-sy/` (Weblate owns translations).
- Import: `import tachiyomi.i18n.kmk.KMR` for Komikku strings.
- If a change is inside `// KMK -->` … `// KMK <--` or adds Komikku-only behavior, default to **`KMR` + `i18n-kmk`**.

**Self-check before finishing:** `git diff` must not add new `<string name="…">` or `<plurals name="…">` entries under non-`base` locales in `i18n-kmk/src/`, `i18n/src/`, or `i18n-sy/src/`.

### Formatting & build verification

**“Build passes” is not enough.** After Kotlin/XML edits, run **in this order** before marking work complete:

```bash
./gradlew spotlessApply    # fix formatting
./gradlew spotlessCheck    # must pass (same as CI)
./gradlew assembleDebug    # or :app:compileDebugKotlin for a faster compile-only check
```

- **Do not** skip `spotlessCheck` when verifying changes.
- If `spotlessCheck` fails, run `spotlessApply` and re-run `spotlessCheck`.
- On Cloud VM, export `ANDROID_HOME` and `JAVA_HOME` first (see [Cursor Cloud](#cursor-cloud-specific-instructions)).

### Local properties

- **NEVER** touch `local.properties` or `external/*/local.properties`. They contain the local Android SDK path (`sdk.dir`) and are `.gitignore`d. Changing them breaks the build on other machines/CI. If a Gradle sync fails due to SDK location, ask the user to fix their SDK instead of editing these files.

---

## Module layout

| Module | Purpose |
|--------|---------|
| `app/` | UI (`eu.kanade.*`, `exh/`, `mihon/`), DI, workers, build variants |
| `domain/` | Use cases in `…/interactor/` (e.g. `GetManga`), models, repo interfaces |
| `data/` | SQLDelight DB, `*RepositoryImpl` (`tachiyomi.data.*`) |
| `core:common/` | Network (OkHttp), security, storage, shared utils |
| `core:metro/` | Metro DI bridge (`GraphProvider`, `metroGraph()` for `Context.appGraph`) |
| `core:concurrency/` | Coroutine dispatch helpers |
| `core:archive/` | CBZ/archive reading with optional encryption |
| `core-metadata/` | Comic-info metadata parsing |
| `source-api/` / `source-local/` | Extension `Source` API (KMP `commonMain`/`androidMain`) + local source |
| `presentation-core/` | Shared Compose components |
| `presentation-widget/` | Home-screen Glance widget |
| `yakuyomi-engine/` (`:yakuyomi`) | Komikku-side MTL wrapper (`exh.yakuyomi.*`: manager, translators, models, cache) |
| `yakuyomi-stub/` (`:yakuyomi-stub`) | No-op MTL stub compiled by the `nomtl` flavor |
| `llamatik-native/` (`:llamatik-native`) | Vendored llamatik/llama.cpp native runtime + Kotlin wrapper (replaces the `com.llamatik:library` AAR, which is CPU-only) |
| `external/yakuyomi-engine` | Git **submodule** (houri-engine): native pipeline `li.joye.yakuyomi.engine.*` — commit it BEFORE the main repo |
| `external/llamatik` | Git **submodule** (Llamatik fork) with **nested** submodules: needs `git submodule update --init --recursive`. Supplies llama.cpp for `:llamatik-native`; commit it BEFORE the main repo |
| `external/webgpuviewer-houri`, `external/imagedecoder-houri` | Git submodules (composite builds): WebGPU viewer, image decoder |
| `i18n/` | Mihon strings → `MR` (moko-resources) |
| `i18n-kmk/` | Komikku strings → `KMR` |
| `i18n-sy/` | TachiyomiSY strings → `SYMR` |
| `flagkit/` | Country-flag drawables |
| `telemetry/` | Firebase/Crashlytics (noop unless `-Pinclude-telemetry`) |
| `macrobenchmark/` | Macrobenchmark tests |

Dependency flow: `app` → `domain` → `source-api`; `data` implements `domain` repos.

Version catalogs: `gradle/libs.versions.toml`, `kotlinx.versions.toml`, `androidx.versions.toml`, `compose.versions.toml`, `sy.versions.toml`.

---

## Architecture

**DI** – **Metro** (`dev.zacsweers.metro`) via `AppGraph` (`mihon/app/di/AppGraph.kt`). Annotate constructors `@Inject`; resolve from Compose/services via `context.appGraph.<accessor>` (`mihon.app.di.appGraph`), from non-Context classes via `globalAppGraph.<accessor>` (set in `App.onCreate`). Activities get `graph.inject(this)` + `@Inject lateinit var` fields; ViewModels use `@AssistedInject` + nested `@AssistedFactory` (`@ViewModelAssistedFactoryKey`) and are resolved with `viewModels<T> { graph.viewModelFactory }`. **Do not add new `uy.kohesive.injekt` usage** — it survives only as an extension-ABI bridge (`MetroInteropModule`, source-api base classes) plus a few documented infra keeps (see `todo.md` chore notes). Legacy registration lives in `DomainModule.kt`/`KMKDomainModule.kt`/`SYDomainModule.kt` (`eu/kanade/domain/`) and `di/AppModule.kt`/`PreferenceModule.kt`/`SYPreferenceModule.kt`.

**UI & navigation** – [Voyager](https://voyager.adriel.cafe/): `Screen` in `eu.kanade.tachiyomi.ui.*`, composables in `eu.kanade.presentation.*`. Base type: `eu.kanade.presentation.util.Screen`. State via `rememberScreenModel { … }`; most models extend `StateScreenModel<State>` or bases like `SearchScreenModel`; some use plain `ScreenModel`. Prefer `screenModelScope` and `ioCoroutineScope`; use `launchIO` / `withIOContext` from `tachiyomi.core.common.util.lang`. `rememberCoroutineScope()` is fine in Compose; long-lived services may use their own `CoroutineScope`.

**Activities (not Voyager)** – `MainActivity` (sole Voyager host), `ReaderActivity`
(`ReaderActivity.newIntent(...)`; AndroidX `ReaderViewModel` — see `ui/reader/AGENTS.md`),
`WebViewActivity`, `UnlockActivity`, OAuth login activities, trampoline `DeepLinkActivity`
(real resolution: Voyager `DeepLinkScreen`/`DeepLinkScreenModel`).

**Domain / data / DB** – One class per operation under `domain/…/interactor/` (see `domain/AGENTS.md`);
SQLDelight in `data/src/main/sqldelight/tachiyomi/` — schema change = new `.sqm` + regenerate
(`:data:generateSqlDelightInterface`); full patterns: `data/AGENTS.md`.

**App preference migrations** – `app/src/main/java/mihon/core/migration/migrations/` (`mihon.core.migration.Migration`).

**Images** – Coil 3 (`coil3.*`, `context.imageLoader`). No Glide/Picasso.

---

## App module package roots

`app/src/main/java` has **3 top-level dirs** (`eu/`, `exh/`, `mihon/`), subdivided by fork origin —
NOT by architectural layer:

| Package root | Origin | Content |
|---|---|---|
| `eu.kanade.tachiyomi.*` | Original Tachiyomi | Activities, Voyager screens, widgets, legacy Injekt DI, data services (backup/download/sync/track) |
| `eu.kanade.domain.*` | Tachiyomi refactor | App-level domain interactors + Injekt modules (`DomainModule`, `KMKDomainModule`, `SYDomainModule`) |
| `eu.kanade.presentation.*` | Compose migration | Compose screens, components, theme |
| `exh.*` | TachiyomiSY/ExHentai | E-Hentai/MangaDex/delegated sources, metadata, recs (see `exh/AGENTS.md`) |
| `mihon.*` | Mihon upstream | `app/di/` (Metro `AppGraph`), migration, upcoming, Shizuku |

The clean `tachiyomi.*` root does **not** live in `app/` — it lives in the dedicated modules
(`domain/`, `data/`, `core:common`, `presentation-core`, `presentation-widget`, `core-metadata`).

---

## Komikku-specific work

- **Strings:** Komikku → **`KMR`** / `i18n-kmk/…/base/` only (see Mandatory rules). Examples: library
  update error UI, WebDAV/Discord settings, updater notifications, `mihon/feature/*` Komikku screens.
- Komikku code/DI: search `// KMK` (e.g. `KMKDomainModule`, library-update errors).
- New code uses the modular settings framework — keys in `*SettingKeys`, UI in `*SettingsHost`
  (guide: `app/.../settings/framework/AGENTS.md`; store base: `core/common/AGENTS.md`).
- New settings screen: register in `SettingsCatalog.searchableScreens` AND `SettingsMainScreen.items`.

---

## Domain module patterns

104 interactors (`Get*`/`Set*`/`Insert*`/`Delete*`/`Update*`; `await()` one-shot, `subscribe()` Flow,
`invoke()` single-use), 18 repo interfaces (implemented in `data/`), 28 models + update DTOs.
Full archetypes and DI wiring: `domain/AGENTS.md`.

---

## Data module patterns

SQLDelight DB: 23 tables/views across 4 `.sq` files (`mangas`, `chapters`, `categories`, `history`,
`manga_sync`, `merged`, `libraryUpdateError`), 46 migrations, 18 `*RepositoryImpl`
(`handler.await` / `handler.subscribeTo*`), 11 mappers, coalesce-UPSERT conventions.
Full schema/views/mappers: `data/AGENTS.md`.

---

## Extensions & sources

- Catalog sources: installable APK extensions (not in this repo).
- In-repo: delegated sources and metadata in `exh/` (E-Hentai, NHentai, MangaDex, `exh/recs/`).
- `source-api`: `eu.kanade.tachiyomi.source.*` — avoid breaking extension ABI (see `source-api/AGENTS.md`).

---

## exh module (E-Hentai/ExHentai)

`EHentai.kt` + delegated sources (NHentai, 8Muses, Pururin, LANraragi), MangaDex OAuth stack,
two-tier metadata (`FlatMetadata` → `RaisedSearchMetadata`), 6-source recommendation system.
Full map: `app/.../exh/AGENTS.md`.

---

## Build & CI

Build types: `debug` (`.dev`), `release`, `releaseTest` (`.rt`), `foss` (`.foss`), `preview` (`.beta`, CI default), `benchmark`.
Engine flavors: `mtl` (default, → `:yakuyomi`) / `nomtl` (`.nomtl`, → `:yakuyomi-stub`, no telemetry).
`preview` and `benchmark` use the **debug signing key**; `release` is unsigned at build time —
signing happens only in CI (`r0adkll/sign-android-release`). No `signingConfigs` block in `app/build.gradle.kts`.

Gradle `-P` flags (`buildSrc/.../BuildConfig.kt`): `include-telemetry`, `enable-updater`,
`disable-code-shrink`, `include-dependency-info`.

10 workflows (`.github/workflows/`): `build_push` (master CI), `build_pull_request` (path-filtered —
ignores Weblate-owned non-base locales), `build_preview` / `build_release` / `build_benchmark`
(manual/tag), `auto_release` (auto-tags `v<versionName>` on master), `sync_extensions`,
`todo_discord`, `delete_merged_branch`, `pr_label`. All actions SHA-pinned; all build workflows
gate on `spotlessCheck`. JDK 21 in CI vs JVM 17 target is intentional.

```bash
./gradlew spotlessApply              # format (run before spotlessCheck)
./gradlew spotlessCheck              # REQUIRED before considering work done (CI gate)
./gradlew assemblePreview            # main CI/dev APK
./gradlew assemblePreview -Pinclude-telemetry -Penable-updater  # full upstream CI build
./gradlew testReleaseUnitTest        # CI unit tests (or ./gradlew test for all modules)
./gradlew installDebug               # device install
./gradlew :data:generateSqlDelightInterface  # after .sq / .sqm changes
```

**Agent verification checklist (minimum):** `spotlessApply` → `spotlessCheck` → `assembleDebug` (or `compileDebugKotlin` only if the user asked for a quick compile check—but still run Spotless).

---

## Version catalogs

5 catalogs — `libs` (`gradle/libs.versions.toml`), `kotlinx`, `androidx`, `compose`, `sylibs`
(`gradle/<name>.versions.toml`) — wired in `settings.gradle.kts` (`versionCatalogs { ... }`,
`TYPESAFE_PROJECT_ACCESSORS`). Variant/shipping behavior: see [Build & CI](#build--ci).

---

## Fork-origin markers

Preserve inline blocks when editing:

```kotlin
// KMK -->  … // KMK <--   Komikku
// SY -->   … // SY <--    TachiyomiSY
// EXH -->  … // EXH <--   E-Hentai / exh (existing); prefer KMK for new Komikku-only code
```

Package roots: `eu.kanade.tachiyomi.*` (legacy UI), `tachiyomi.*` (domain/data), `mihon.*` (Mihon upstream), `exh.*` (enhanced sources).

---

## Tests

22 test files in 3 modules: `domain/src/test` (11), `app/src/test` (10, incl. `MigratorTest`), `yakuyomi-engine` (1).
`data/`, presentation, and core modules have zero tests. No `androidTest`, no Robolectric.

Each tested module opts in with: `testImplementation(libs.bundles.test)` (JUnit Jupiter 6 + Kotest
assertions + MockK) + `kotlinx.coroutines.test` + `testRuntimeOnly(junit-platform-launcher)`.

Conventions: `@Execution(CONCURRENT)` on every class, backtick test names, Kotest `shouldBe`,
MockK (`mockk`/`coEvery`/`coVerifySequence`) + `runTest` for suspend, `.create().copy()` model factories.

---

## Conventions

- **Logging** – Prefer `xLogE()` / `xLog()` helpers from `exh.log` for Komikku code, Mihon uses `logcat { }` from `tachiyomi.core.common.util.system`. Avoid raw `android.util.Log`.
- **Formatting** – Spotless + ktlint (`buildSrc/.../mihon.code.lint.gradle.kts`). Agents **must** run `spotlessApply` and `spotlessCheck` (see [Mandatory rules](#mandatory-rules-for-ai-agents)).
- **Fork edits** – New Komikku features inside `// KMK` islands; keep `// SY` / `// EXH` blocks intact when merging upstream.

---

## Key files

- `App.kt` – Injekt bootstrap, logging setup
- `MainActivity.kt` – Voyager host
- `app/src/main/java/eu/kanade/tachiyomi/di/AppModule.kt` – core DI
- `app/src/main/java/eu/kanade/domain/DomainModule.kt` – domain interactors
- `app/src/main/java/eu/kanade/domain/KMKDomainModule.kt` – Komikku-specific domain
- `app/src/main/java/eu/kanade/domain/SYDomainModule.kt` – TachiyomiSY domain
- `buildSrc/.../BuildConfig.kt`, `AndroidConfig.kt` – flags, SDK versions
- `app/build.gradle.kts`, `settings.gradle.kts`

---

## Cursor Cloud specific instructions

### Environment

The VM update script installs the Android SDK (platform 36, build-tools 35.0.1, platform-tools, cmdline-tools) into `/opt/android-sdk` and writes `local.properties` with `sdk.dir`. JDK 21 is pre-installed and works fine for compiling to JVM target 17. `ANDROID_HOME`, `JAVA_HOME`, and `PATH` are set in `~/.bashrc`.

### Running key commands

All Gradle commands require the environment variables set above. Export them before invoking `./gradlew` if running in a fresh shell:

```bash
export ANDROID_HOME=/opt/android-sdk
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
```

Commands: same as [Build & CI](#build--ci), except verification is `assembleDebug` only
(no emulator/device, so `installDebug` will fail).

### Gotchas

- First Gradle build downloads ~1 GB of dependencies; subsequent builds use the Gradle cache and are much faster.
- `local.properties` is `.gitignore`d — it must be recreated if missing (the update script handles this).
- No Android emulator or device is available on the Cloud VM, so `installDebug` will fail. Build verification is done via `assembleDebug`.
- `google-services.json` and `client_secrets.json` are not present (CI secrets); builds without `-Pinclude-telemetry` succeed without them.
- Gradle daemon may use significant memory (`-Xmx4g` in `gradle.properties`). If OOM occurs, kill and restart the daemon with `./gradlew --stop`.
