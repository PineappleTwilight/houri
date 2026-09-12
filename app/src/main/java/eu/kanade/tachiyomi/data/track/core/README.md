# Tracker Framework

Built-in trackers are core — not user-deletable. This framework makes adding/removing a tracker a 3-step developer task with zero UI edits.

## Add a new tracker

1. **Reserve ID** in `TrackerId.kt` (positive Long, unique across all trackers, added to `all` set).

2. **Create tracker class** `app/src/main/java/eu/kanade/tachiyomi/data/track/<name>/<Name>.kt`
   - Extend `BaseTracker` (see `template/TrackerTemplate.kt`)
   - Implement `getLogo()`, `getStatusList()`/`getStatus()`, `getReadingStatus()`/`getCompletionStatus()`, `update()`/`bind()`/`search()`/`refresh()`, `login()`/`logout()`
   - Override `getLoginMode()` to declare UI wiring:
     - `OAUTH` → also override `getAuthUrl(): String?` returning `XxxApi.authUrl().toString()` (same-package `*Api` already exists)
     - `CREDENTIALS` → optionally override `getUsernameLabel()` (default `MR.strings.username`, `Kitsu` uses `email`)
     - `WEBVIEW_COOKIE` → override `createCookieLoginIntent(context): Intent?` and wire `*Interceptor` via `AbstractCookieTrackerInterceptor`
     - `ENHANCED_NOOP` → implement `EnhancedTracker` (`loginNoop()` only, no credentials UI)
   - For OAuth, add token handling similar to `MyAnimeList`/`Anilist` (see `core/TrackerException` for error types)

3. **Register** in `TrackerManager.kt`:
   - Add `val myNewTracker = MyNewTracker(TrackerId.MY_NEW)` field
   - Append to `trackers: List<Tracker>` (typed)
   - Companion constants delegate to `TrackerId` (`const val MY_NEW = TrackerId.MY_NEW`) for legacy call sites

No other files required. `TrackerManager` delegates to `TrackerId` so companion constants stay in sync.

## Remove a tracker

1. Remove field and list entry in `TrackerManager.kt`
2. Remove ID from `TrackerId.kt` (or keep reserved to avoid reuse)
3. Delete `app/src/main/java/eu/kanade/tachiyomi/data/track/<name>/` package

No migration needed unless DB contains `manga_sync` rows with that `syncId` — add a `Migration` to clean them if required.

## UI auto-wiring

`SettingsTrackingScreen` no longer hard-codes trackers. It iterates `trackerManager.trackers`:

- Standard group: `filter { it.getLoginMode() != ENHANCED_NOOP && it.id != TrackerId.MDLIST }` → `map { TrackerPreference }` with `when (getLoginMode()) { OAUTH -> openInBrowser(getAuthUrl()); CREDENTIALS -> LoginDialog(getUsernameLabel()); WEBVIEW_COOKIE -> startActivity(createCookieLoginIntent()); }`
- Enhanced group: `filterIsInstance<EnhancedTracker>()` partitioned by installed sources, already auto-discovers `Kavita`/`Komga`/`Suwayomi`. Adding an `EnhancedTracker` requires no settings change.

`Tracker.kt` default `getLoginMode()=CREDENTIALS` keeps existing trackers compatible; `SettingsTrackingScreen` uses `androidx.core.net.toUri` for OAuth URLs.

## Auth types

- `TrackerAuthType.OAUTH` — token stored via `trackPreferences.trackToken()`, interceptor `*Interceptor` handles header; framework maps to `TrackerLoginMode.OAUTH`
- `TrackerAuthType.COOKIE` — extend `interceptor.AbstractCookieTrackerInterceptor` (thread-safe `ReentrantLock`, expiry filtering, subdomain-aware `loadForRequest`, 8KB header cap) — maps to `WEBVIEW_COOKIE`
- `TrackerAuthType.BASIC` — username/password via `BaseTracker.saveCredentials` — maps to `CREDENTIALS`

## Capabilities

`TrackerCapabilities`: `supportsReadingDates`, `supportsPrivateTracking`, `supportsRereadCount`, `supportsScore`, `isEnhanced` (registry-level, not user-togglable).

## Validation

`TrackerRegistry.validate()` checks duplicate IDs at startup in debug builds. `TrackerId.all` documents the full ID space. `AbstractCookieTrackerInterceptor` guards mixed `&&`/`||` with explicit parentheses per ktlint `mixed-condition-operators`.

## File map

- `core/TrackerId.kt` — ID constants
- `core/TrackerLoginMode.kt` — UI login routing
- `core/AbstractCookieTrackerInterceptor.kt` — shared cookie jar
- `interceptor/` — `AnimePlanetInterceptor`/`ComicKInterceptor` now 12-line subclasses
- `template/TrackerTemplate.kt` — copy-paste starter with status/score stubs
