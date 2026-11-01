# Non-library content discovery + chapter-cache retention timer

**Date:** 2026-10-31
**Status:** Draft — pending user review
**Scope:** a discovery screen for downloads and cached chapters whose entry is not in the library; a sliding TTL ("retention") for the chapter cache with a visible countdown

---

## 1. Problem

Content the app stored on the user's behalf is invisible unless the entry is in the library:

- Downloads live under `downloads/<source>/<manga>/<chapter>`. When a manga is removed from the
  library (or was downloaded while never favorited), its folders keep occupying disk with no UI
  to see or reclaim them. "Clear database" deletes DB rows, not content, and lists only DB-known
  entries by source — no sizes, no per-entry control.
- Cached chapters (page lists + page images in `ChapterCache`) vanish with no explanation: the
  cache is a size-limited LRU, plus an optional "auto-clear on launch" switch. There is no
  per-chapter expiry and nothing tells the user when (or why) a cached chapter disappears.

## 2. Design

### 2.1 Chapter-cache retention setting

`LibraryPreferences` gains an int preference next to `autoClearChapterCache`:

```kotlin
fun chapterCacheRetention() = preferenceStore.getInt("chapter_cache_retention_hours", 0)
```

`0` = off (today's behaviour). Non-zero values offered in the UI: 12 h / 24 h / 3 d / 7 d / 30 d.

Semantics — **sliding expiry**: an entry's age is the `lastModified` of its cache file; every read
touches the file, so reading a chapter resets its timer. Entries older than the retention are
removed by a sweep, not by live checks:

- `ChapterCache.sweepExpired(retentionMs: Long): Int` iterates the cache directory, skips the
  journal (same guard as `clear()`), and removes every `*.0` entry whose
  `lastModified + retentionMs < now`.
- Sweep hooks: app launch (the `MainActivity` `isLaunch` block beside the existing auto-clear
  branch) and whenever the discovery screen is opened. A change of the setting takes effect at the
  next sweep; no listener, no background job.
- Touch-on-read lives in `getPageListFromCache` and `getImageFile` (after a successful read and
  only when the file exists). The cache dir is plain app-private storage, so this is one cheap
  `File.setLastModified` syscall per hit; when retention is off the touch is skipped entirely and
  `lastModified` simply stays "last written".

`lastModified` is deliberately chosen over a persisted last-access index: no second file to keep
in sync, no extra IO on the write path, and it survives process death. It does *not* interact with
DiskLruCache's own LRU order, which is in-memory.

Honest-caveat copy in the setting's subtitle: the size limit can still evict an entry early
(LRU), and auto-clear-on-launch still wipes everything regardless of retention.

### 2.2 ChapterCache query/removal API

New methods (all bookkeeping stays inside `ChapterCache`; the disk layout `md5(key).0` never
leaks):

- `getChapterCacheInfo(chapter): ChapterCacheInfo?` — null when the chapter's page list is not
  cached. Walks the stored page list's `imageUrl`s plus derived segment keys
  (`"$imageUrl#segment-$i"`, loop until a bounded run of misses, hard cap) summing existing image
  files, and reports `{ totalBytes, oldestLastModifiedMs, imageCount }`.
- `removePageListFromCache(chapter)` — resolves the stored image URLs (incl. segments) and removes
  each image entry, then the page-list entry.
- `sweepExpired` (2.1).

Images whose page list was evicted can no longer be attributed to a chapter; they are only
reflected in the cache total. The screen shows that gap as a footer line ("unattributed cached
data") rather than pretending completeness.

### 2.3 DownloadCache snapshot + Manga-less forget

`DownloadCache` already holds the full tree in memory (`rootDownloadsDir`). Two additions:

- `snapshotDownloadedMangaDirs(): List<DownloadedMangaDir>` — read-only copy under the existing
  mutex: `data class DownloadedMangaDir(sourceId: Long, mangaDirName: String, chapterDirNames: Set<String>)`.
- Manga-less forgetters used when the folder matches no DB row (the existing
  `removeManga`/`removeChapters` need a `Manga`): `removeMangaDirByIdName(sourceId, mangaDirName)`
  and `removeChapterDirNamesByIdName(sourceId, mangaDirName, names)`. Both mutate the tree and go
  through the usual `notifyChanges()` persistence.

### 2.4 DB access

`manga.sq` gains (no schema change — a query only; regenerate interfaces, no `.sqm`):

```sql
getNonLibraryManga:
SELECT * FROM manga WHERE user_favorite_at IS NULL;
```

Exposed as `MangaRepository.getNonLibraryManga()` + a thin `GetNonLibraryManga` interactor
(DomainModule factory, AppGraph accessor), matching the `DeleteNonLibraryManga` shape. The VM
pairs it with the existing `GetFavorites` to build, per source id, the sets
`libraryDirNames` / `nonLibraryMangaByDirName` using `provider.getMangaDirName(ogTitle)`.

**Merge children are excluded**, same guard as `deleteNonLibraryManga`: a merge child's own row
is `user_favorite_at IS NULL` while the content actually belongs to the library's merged entry —
its downloads must not show up here as reclaimable. Rows whose id appears in
`merged` (`WHERE manga_id != merge_id`) are filtered out of the screen and out of delete-all.

### 2.5 Discovery screen

Nav3 route `DownloadedContentRoute : NavKey` in `core/navigation` `AppRoutes.kt`, registered in
`AppNavGraph.kt`, pushed from the Data & storage group in `SettingsDataRoute.kt`
(`backStack.add(DownloadedContentRoute)`) via a new `TextPreference` row. Screen composable
co-locates its ViewModel in one file, following `ClearDatabaseScreen.kt`; location:
`eu/kanade/presentation/more/settings/screen/DownloadedContentScreen.kt`.

Data flow (all on IO, screen shows a loading state; SAF walks can be slow):

1. Snapshot download dirs (2.3) + `getFavorites()` + `getNonLibraryManga()` (2.4).
2. Classify each disk folder: **DB-known non-library** (cover/title/ogTitle available),
   **unknown to DB** (folder name only, stub-source name shown). Folders matching a *library*
   entry are excluded — that is normal content. The **local source (id 0) is excluded entirely**:
   its "downloads" are the user's own files.
3. Per DB-known entry, enumerate its chapters (existing chapter repo query) and probe
   `getChapterCacheInfo` → the **Cached chapters** drill-down list with size + countdown.
4. Sizes via a new recursive `UniFile` size walker (`tachiyomi.core.common.storage`), capped
   (max depth and file count) so a runaway folder cannot hang the screen.

Entry rows: cover, title (or folder name), `N chapters · size`, badge
"not in library" / "unknown to database"; expandable into downloaded chapter names (per-chapter
delete: via DB-matched chapters where the entry is DB-known, by folder name through
`findMangaDirByDirName` otherwise) and cached chapters (per-chapter delete + countdown).
Sources grouped by `sourceManager.getOrStub(id)` name, entries sorted by size, all deletions
behind confirmation dialogs, plus a screen-level "delete all shown".

Deletion:

- DB-known entry → `DownloadManager.deleteManga(manga, source)` (removes queued work, updates
  cache); per-chapter → `deleteChapters(..., ignoreCategoryExclusion = true)` — category rules
  must not shield non-library content.
- Unknown entry → resolve the folder with a new `DownloadProvider.findMangaDirByDirName(source,
  mangaDirName)`, delete it, forget via 2.3, delete the source dir if it became empty (mirrors
  `deleteManga`).
- Cached-chapter delete → `removePageListFromCache`.
- After any deletion the screen state is rebuilt.

Countdown rendering: `remaining = oldestLastModified + retention − now`, recomputed on a 30 s
ticker. Retention off → show age ("cached 2 d ago · kept until space is needed"); remaining ≤ 0 →
"removed at next sweep". Countdown pieces (hours/days formatting) is a pure function so the
offline harness can assert it.

### 2.6 Strings, release notes

All new strings in `KMR` (`i18n-kmk/.../base/` only): screen title + entry row, retention setting
title/entries/caveat subtitle, badges, section headers, per-item delete + confirmations, delete
all, cached-age / expires-in / removed-at-next-sweep lines, unattributed-data footer.
`RELEASE_NOTES.md` gains a **New** entry for the screen + retention timer.

## 3. Error handling

- Snapshot/size failures degrade to "size unavailable", never to a crash; deletion failures toast
  (existing error strings where they fit).
- Uninstalled extension → stub source: shown by stub name, deletable, no opening.
- A manga renamed after download: old folder classifies as unknown-to-DB; deleting it only touches
  that folder, so the failure mode is benign.
- Direct folder deletes (unknown entries) ignore the download queue; a queue entry pointing at a
  folder that vanishes fails its chapter download and is dropped — accepted.

## 4. Testing & verification

- Pure, harness-testable pieces: expiry decision `isExpired(mtime, now, retention)`, countdown
  math, dir-name classification (given `getMangaDirName`), sweep file-name filter. New scenarios in
  `scripts/kotlinc-check/run-harness.sh`; agents verify with `check.sh` / `run-harness.sh` only.
- No Gradle by agents. User verification: `spotlessApply` → `spotlessCheck` → `assembleDebug`,
  plus `:data:generateSqlDelightInterface` after the `manga.sq` addition (user-run).

## 5. Out of scope

- MTL translated-page cache and the SY page-preview cache (separate caches, untouched).
- Re-adding a discovered manga to the library from the screen (user chose "find & free space").
- A settings-level live countdown outside the discovery screen.
