# Tracker Framework

Built-in trackers are core — not user-deletable. This framework makes adding/removing a tracker a 3-step developer task.

## Add a new tracker

1. **Reserve ID** in `TrackerId.kt` (positive Long, unique across all trackers).

2. **Create tracker class** `app/src/main/java/eu/kanade/tachiyomi/data/track/<name>/<Name>.kt`
   - Extend `BaseTracker` (OAuth/Basic) or `AbstractCookieTrackerInterceptor` wiring (Cookie)
   - Copy `template/TrackerTemplate.kt` as starting point
   - Implement `getLogo()`, status list, `update`/`bind`/`search`/`refresh`

3. **Register** in `TrackerManager.kt`:
   - Add `val myNewTracker = MyNewTracker(TrackerId.MY_NEW)` field
   - Append to `trackers` list

No other files required. `TrackerManager` delegates to `TrackerId` so companion constants stay in sync. For OAuth, add token handling similar to `MyAnimeList`/`Anilist` (see `core/TrackerException` for error types).

## Remove a tracker

1. Remove field and list entry in `TrackerManager.kt`
2. Remove ID from `TrackerId.kt` (or keep reserved to avoid reuse)
3. Delete `app/src/main/java/eu/kanade/tachiyomi/data/track/<name>/` package

No migration needed unless DB contains `manga_sync` rows with that `syncId` — add a `Migration` to clean them if required.

## Auth types

- `TrackerAuthType.OAUTH` — token stored via `trackPreferences.trackToken()`, interceptor `*Interceptor` handles header
- `TrackerAuthType.COOKIE` — extend `interceptor.AbstractCookieTrackerInterceptor`
- `TrackerAuthType.BASIC` — username/password via `BaseTracker.saveCredentials`

## Capabilities

Declare via `TrackerCapabilities` in definition (not yet wired to UI):
`supportsReadingDates`, `supportsPrivateTracking`, `supportsRereadCount`, `supportsScore`, `isEnhanced`

## Validation

`TrackerRegistry.validate()` checks duplicate IDs at startup in debug builds. `TrackerId.all` documents the full ID space.
