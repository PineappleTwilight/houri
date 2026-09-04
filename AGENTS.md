# Komikku – AI Agent Guide

**Generated:** 2026-08-07
**Commit:** 936e25bf9
**Branch:** master

Komikku is an Android manga reader (min SDK 26, target SDK 36, JVM 17 / Kotlin) forked from **Mihon** + **TachiyomiSY**. Stack: Jetpack Compose + Material3, Voyager navigation, SQLDelight, Injekt DI. `applicationId`: `app.komikku`.

**Scale:** 2244 files, 186k lines of Kotlin/Java,47 files >500 lines.

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

---

## Module layout

| Module | Purpose |
|--------|---------|
| `app/` | UI (`eu.kanade.*`, `exh/`, `mihon/`), DI, workers, build variants |
| `domain/` | Use cases in `…/interactor/` (e.g. `GetManga`), models, repo interfaces |
| `data/` | SQLDelight DB, `*RepositoryImpl` (`tachiyomi.data.*`) |
| `core:common/` | Network (OkHttp), security, storage, shared utils |
| `core:archive/` | CBZ/archive reading with optional encryption |
| `core-metadata/` | Comic-info metadata parsing |
| `source-api/` / `source-local/` | Extension `Source` API + local source |
| `presentation-core/` | Shared Compose components |
| `presentation-widget/` | Home-screen Glance widget |
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

**Activities (not Voyager)** – `MainActivity` (shell), `ReaderActivity` + `ReaderViewModel`, `WebViewActivity`, `UnlockActivity`, OAuth login activities, `DeepLinkActivity`. Reader: `ReaderActivity.newIntent(context, mangaId, chapterId)`. Web: both `WebViewScreen` (Voyager) and `WebViewActivity.newIntent(...)`.

Example: `DeepLinkScreen` + `DeepLinkScreenModel` in `app/src/main/java/eu/kanade/tachiyomi/ui/deeplink/`.

**Domain / data** – One class per operation under `domain/…/interactor/` (verb names, not `*Interactor` suffix). Also `app/src/main/java/eu/kanade/domain/…/interactor/` for app-specific cases. Wire repos in `eu.kanade.domain.DomainModule.kt` (+ `KMKDomainModule`, `SYDomainModule`).

**Database** – SQLDelight in `data/src/main/sqldelight/tachiyomi/` (`.sq` queries, `migrations/*.sqm`). After schema changes add a new `.sqm` and often `// KMK` blocks in `.sq` / mappers. Regenerate: `./gradlew :data:generateSqlDelightInterface` (or any compile that touches `:data`).

**App preference migrations** – `app/src/main/java/mihon/core/migration/migrations/` (`mihon.core.migration.Migration`).

**Images** – Coil 3 (`coil3.*`, `context.imageLoader`). No Glide/Picasso.

---

## App module package roots

The `app/` module contains **4 competing package roots** reflecting fork heritage:

| Package root | Origin | Content |
|---|---|---|
| `eu.kanade.tachiyomi.*` | Original Tachiyomi | UI (Activities, screens, widgets, DI, data layer services) |
| `eu.kanade.domain.*` | Tachiyomi refactor | Domain interactors (in `app` module, not `domain`) |
| `eu.kanade.presentation.*` | Compose migration | Compose screens, components |
| `exh.*` | TachiyomiSY/ExHentai | SY features (search, metadata, recs, debug) |
| `mihon.*` | Mihon upstream | Newer features (upcoming, migration) |
| `tachiyomi.*` | Clean domain layer | Domain/data in dedicated modules |

**Key insight**: Code is organized by fork origin, NOT by architectural layer. Each root contains its own mix of domain/data/presentation code.

---

## Komikku-specific work

- **Strings:** see [Mandatory rules – Internationalization](#mandatory-rules-for-ai-agents). Summary: Komikku → **`KMR`** / `i18n-kmk/…/base/` only.
- Do not edit locale `strings.xml` in `i18n/` or `i18n-sy/` except when syncing upstream; translations via [Weblate](https://hosted.weblate.org/engage/komikku-app/).
- Komikku code/DI: search `// KMK` (e.g. `KMKDomainModule`, `HideCategory`, library-update errors).
- Prefs: `eu.kanade.domain.*.service.*Preferences` (e.g. `SourcePreferences.relatedMangas()`).

**Examples (Komikku → `i18n-kmk`, not `i18n`):** library update error UI, sync-before-update messages, WebDAV/Discord settings, updater notifications, `mihon/feature/*` Komikku screens.

---

## Domain module patterns

The `domain/` module contains104 interactors organized by feature:

**Interactor naming conventions:**
- `Get*` – Read operations (e.g., `GetManga`, `GetLibraryManga`)
- `Set*` – Flag/setting writes (e.g., `SetMangaChapterFlags`)
- `Insert*` – Create/upsert entities (e.g., `InsertTrack`)
- `Delete*` – Remove entities (e.g., `DeleteChapters`)
- `Update*` – Update entities (e.g., `UpdateMangaNotes`)

**Method conventions:**
- `await(...)` – One-shot suspend read/write
- `subscribe(...)` – Long-lived reactive stream (Flow)
- `invoke(...)` / `operator fun invoke` – Single-purpose use case

**Repository interfaces:** 18 repositories in `domain/.../repository/` (e.g., `MangaRepository`, `ChapterRepository`). Implementations in `data/` module.

**Models:** 28 models including `Manga`, `Chapter`, `Category`, `Track`, `History`, plus update DTOs (`MangaUpdate`, `ChapterUpdate`) and view/join models (`LibraryManga`, `HistoryWithRelations`).

---

## Data module patterns

The `data/` module uses SQLDelight with46 migrations:

**Schema:** 23 tables/views across4 `.sq` files. Key tables: `mangas`, `chapters`, `categories`, `history`, `manga_sync`, `merged`, `libraryUpdateError`.

**Repository implementations:** 18 `*RepositoryImpl` classes in `tachiyomi.data.*`. Pattern: `handler.await { queries.method(...) }` for single queries, `handler.subscribeTo*` for reactive flows.

**Mappers:** 11 mapper objects/lambdas (e.g., `MangaMapper`, `ChapterMapper`). Map SQLDelight result types to domain models.

**Column adapters:** `DateColumnAdapter`, `StringListColumnAdapter`, `UpdateStrategyColumnAdapter`, `MemoColumnAdapter` (for `JsonObject`).

**Common SQL patterns:**
- Coalesce updates: `SET col = coalesce(:param, col)` for partial updates
- UPSERT: `INSERT ... ON CONFLICT DO UPDATE`
- Sync-aware versioning: Triggers increment `version` only when `is_syncing = 0`

---

## Extensions & sources

- Catalog sources: installable APK extensions (not in this repo).
- In-repo: delegated sources and metadata in `exh/` (E-Hentai, NHentai, MangaDex, `exh/recs/`).
- `source-api`: `eu.kanade.tachiyomi.source.*` — avoid breaking extension ABI.

---

## exh module (E-Hentai/ExHentai)

The `exh/` module within `app/` provides multi-source integration:

**Source implementations:**
- `EHentai.kt` (1463 lines) – Core E-Hentai/ExHentai source with HTML parsing, favorites, auth
- Delegated sources: NHentai, 8Muses, Pururin, LANraragi
- MangaDex integration: Full API client stack with OAuth auth

**Metadata system:**
- Two-tier model: `FlatMetadata` (DB) → `RaisedSearchMetadata` (runtime)
- Per-source metadata classes: `EHentaiSearchMetadata`, `MangaDexSearchMetadata`, etc.
- UI: Per-source `*DescriptionAdapter` composables

**Recommendation system:**
- `RecommendationPagingSource` base class with6 implementations
- Sources: AniList (GraphQL), MAL (Jikan v4), MangaUpdates, MangaDex, Comick
- Batch search: `RecommendationSearchHelper` processes entire library

**Key patterns:**
- Fork markers: `// KMK -->` for Komikku additions
- DI: Injekt with `injectLazy()`, `ExhPreferences`
- URL import: `GalleryAdder.pickSource()` → `matchesUri()` → `mapUrlToMangaUrl()`

---

## Build & CI

Build types: `debug` (`.dev`), `release`, `releaseTest` (`.rt`), `foss` (`.foss`), `preview` (`.beta`, CI default), `benchmark`.

Gradle `-P` flags (`buildSrc/.../BuildConfig.kt`):

| Flag | Effect |
|------|--------|
| `include-telemetry` | Firebase Analytics + Crashlytics |
| `enable-updater` | In-app update checker |
| `disable-code-shrink` | Skip R8 minification |
| `include-dependency-info` | Dependency metadata in APK |

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

JDK **17**.

---

## Version catalogs

The project uses5 separate version catalogs:

| Catalog | File | Purpose |
|---------|------|---------|
| `libs` | `gradle/libs.versions.toml` | Main dependencies |
| `kotlinx` | `gradle/kotlinx.versions.toml` | KotlinX libraries |
| `androidx` | `gradle/androidx.versions.toml` | AndroidX libraries |
| `compose` | `gradle/compose.versions.toml` | Compose BOM & components |
| `sylibs` | `gradle/sy.versions.toml` | TachiyomiSY-specific deps |

Accessed via `settings.gradle.kts`'s `versionCatalogs { create("name") { from(files(...)) } }` and `TYPESAFE_PROJECT_ACCESSORS` feature preview.

---

## Build types

6 build types defined in `app/build.gradle.kts`:

| Build Type | Application ID Suffix | Minify | Signing | Special Behavior |
|---|---|---|---|---|
| `debug` | `.dev` | No | Debug key | Pseudo-locales enabled |
| `release` | (none) | Configurable | **Not signed at build time** | Build time from last Git commit |
| `releaseTest` | `.rt` | No (overrides release) | Falls back to release | Testing without minification |
| `foss` | `.foss` | Inherits release | Inherits release | FOSS variant |
| `preview` | `.beta` | Inherits release | **Uses debug key** | VersionName uses commit count |
| `benchmark` | `.benchmark` | Inherits release | **Uses debug key** | Profileable, not debuggable |

**Non-standard patterns:**
- `preview` and `benchmark` variants **use the debug signing config** even though they inherit from release
- `releaseTest` disables minification despite inheriting from release
- `foss` is a fully separate variant -- no telemetry
- All custom variants use `commonMatchingFallbacks` to fall back to `release`

**Signing:** There is NO `signingConfigs` block in `app/build.gradle.kts`. Signing is done entirely in CI via the `r0adkll/sign-android-release` action.

---

## CI/CD pipelines

10 GitHub Actions workflows:

| Workflow | Trigger | Build Variant | Signs? | Publishes? |
|---|---|---|---|---|
| `build_push.yml` | Push to `master` | `preview` | Yes | Artifact upload |
| `build_pull_request.yml` | PRs (path-filtered) | `preview` | Conditional (same-repo only) | Artifact upload |
| `build_preview.yml` | Manual dispatch | `preview` | Yes | Creates GitHub Release (prerelease, draft) |
| `build_release.yml` | Tag push `v*` | `release` | Yes | Creates GitHub Release (stable, draft) |
| `build_benchmark.yml` | Manual dispatch | `benchmark` | Yes | Artifact upload (30-day retention) |

**Non-standard CI patterns:**
- PR workflow path filtering is sophisticated: ignores translated `strings.xml`/`plurals.xml` in non-base locales (Weblate-owned)
- Cross-repo secret handling: PRs from forks skip `google-services.json`/`client_secrets.json` writes and signing
- All action versions are pinned by SHA (supply-chain security best practice)
- Java version mismatch: CI uses JDK 21 (`.github/.java-version`), but `AndroidConfig.kt` targets JVM 17

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

- Unit tests: `domain/src/test/`; app: `app/src/test/.../MigratorTest.kt`. No broad UI test suite.

**Test frameworks:** JUnit Jupiter6.0.3, Kotest assertions6.2.1, MockK1.14.11, kotlinx.coroutines.test.

**Test patterns:**
- Use `@Execution(ExecutionMode.CONCURRENT)` for parallel test execution
- Use Kotest `shouldBe` for assertions (preferred over JUnit assertions)
- Use MockK `mockk()` / `coEvery` / `coVerify` for mocking
- Test data: construct inline using domain model `.create().copy(...)` pattern

**Test coverage:** Domain layer has52 unit tests across7 files. App, data, presentation, and core modules have minimal or zero test coverage.

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

| Task | Command |
|------|---------|
| **Required format fix** | `./gradlew spotlessApply` (run first after code edits) |
| **Required format gate** | `./gradlew spotlessCheck` (must pass before task is done) |
| Debug APK build | `./gradlew assembleDebug` |
| Preview APK build (CI) | `./gradlew assemblePreview` |
| Unit tests (CI) | `./gradlew testReleaseUnitTest` |
| All module tests | `./gradlew test` |
| SQLDelight codegen | `./gradlew :data:generateSqlDelightInterface` |

### Gotchas

- First Gradle build downloads ~1 GB of dependencies; subsequent builds use the Gradle cache and are much faster.
- `local.properties` is `.gitignore`d — it must be recreated if missing (the update script handles this).
- No Android emulator or device is available on the Cloud VM, so `installDebug` will fail. Build verification is done via `assembleDebug`.
- `google-services.json` and `client_secrets.json` are not present (CI secrets); builds without `-Pinclude-telemetry` succeed without them.
- Gradle daemon may use significant memory (`-Xmx4g` in `gradle.properties`). If OOM occurs, kill and restart the daemon with `./gradlew --stop`.
