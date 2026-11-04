# Changelog

All notable changes to this project will be documented in this file.

The format is a modified version of [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).
- `Added` - for new features.
- `Changed ` - for changes in existing functionality.
- `Improved` - for enhancement or optimization in existing functionality.
- `Removed` - for now removed features.
- `Fixed` - for any bug fixes.
- `Other` - for technical stuff.

## [Unreleased]
### Added
- [Hikka](https://hikka.io/) tracker support ([@Lorg0n](https://github.com/Lorg0n)) ([#1386](https://github.com/mihonapp/mihon/pull/1386))
  - Fix Hikka not defaulting to "Plan to Read" for unread titles ([@MajorTanya](https://github.com/MajorTanya)) ([#3534](https://github.com/mihonapp/mihon/pull/3534))
  - Fix Hikka search throwing error due to unclosed response ([@MajorTanya](https://github.com/MajorTanya)) ([#3548](https://github.com/mihonapp/mihon/pull/3548))
- Add support for [MangaBaka](https://mangabaka.org) tracker ([@MajorTanya](https://github.com/MajorTanya)) ([#3047](https://github.com/mihonapp/mihon/pull/3047))
- Support resumable image downloads if supported by source ([@xMohnad](https://github.com/xMohnad)) ([#3167](https://github.com/mihonapp/mihon/pull/3167))
- Display authors and description in Shikimori search results ([@MajorTanya](https://github.com/MajorTanya)) ([#3499](https://github.com/mihonapp/mihon/pull/3499))
- Invalidate download cache after backup restore ([@leodyversemilla07](https://github.com/leodyversemilla07)) ([#3096](https://github.com/mihonapp/mihon/pull/3096))
- Add setting to control vertical chapter navigator height ([@AntsyLich](https://github.com/AntsyLich)) ([#3528](https://github.com/mihonapp/mihon/pull/3528))
- Add option to set which readers use vertical chapter navigator ([@AntsyLich](https://github.com/AntsyLich)) ([#3528](https://github.com/mihonapp/mihon/pull/3528))

### Changed
- Don't use GMS to detect if GMS is available ([@leodyversemilla07](https://github.com/leodyversemilla07)) ([#3525](https://github.com/mihonapp/mihon/pull/3525))

### Improved
- Show usernames in Tracking settings ([@MajorTanya](https://github.com/MajorTanya)) ([#3533](https://github.com/mihonapp/mihon/pull/3533))

### Fixed
- Fix Shikimori tracking not working ([@MajorTanya](https://github.com/MajorTanya)) ([#3497](https://github.com/mihonapp/mihon/pull/3497))
- Fix crash trying to select text in notes screen ([@AntsyLich](https://github.com/AntsyLich)) ([#3516](https://github.com/mihonapp/mihon/pull/3516))
- Fix crash when putting app in background ([@AntsyLich](https://github.com/AntsyLich)) ([#3523](https://github.com/mihonapp/mihon/pull/3523))
- Fix support for non-system SAF providers ([@AntsyLich](https://github.com/AntsyLich)) ([#3530](https://github.com/mihonapp/mihon/pull/3530))
- Fix chapter's `memo` field not being updated for existing chapters ([@cuong-tran](https://github.com/cuong-tran)) ([#3538](https://github.com/mihonapp/mihon/pull/3538))
- Fix app not reading `tachiyomix.extensionLib` extension metadata ([@AntsyLich](https://github.com/AntsyLich)) ([#3545](https://github.com/mihonapp/mihon/pull/3545), [#3559](https://github.com/mihonapp/mihon/pull/3559))
- Fix reader navigator slider steps not updating after changing chapter ([@AntsyLich](https://github.com/AntsyLich)) ([#3549](https://github.com/mihonapp/mihon/pull/3549))

## [v1.25.3] - 2026-11-04
### Improve
- Update the default user agent string to a current Chrome version so sites and their bot protection no longer treat app requests as an outdated browser (only affects installs that never customized the agent; "Reset user agent" in advanced settings picks it up)
- Details: chapter filters, sorting, display mode and download actions now apply immediately after you visit the scanlator priority screen again — no need to leave and re-open the entry first
- About: tapping the version text copies it to the clipboard, like Mihon and other forks
- Migration: with smart search off, the manual search results now show each candidate's latest chapter number as it is fetched, matching the migration list (new "Fetch chapters in manual search" option in migration options, on by default)
- Reader: a wedged WebGPU swapchain (PowerVR: `getCurrentTexture` returning `Error` on every frame, black screen with no crash) now escalates — the renderer tears the surface down and rebuilds it after ~0.5s of consecutive failures, and parks with a single reported outage instead of retrying forever. Also stops the decode-queue churn the retry storm fed.
### Fix
- **WebGPU reader:** fixed reading position and read-state lagging behind actual progress — sometimes by whole chapters. Progress is now saved even when a page's load has failed, and the WebGPU reader reports the exact page its scroll actually reached, so reopening a manga resumes where you left off and the Manga Details screen reflects it immediately.
- Fix the loading circle shown while the migration list searches a source sitting off-center in the row; it is now aligned with the cover slot where the result will appear
- Reader: rotating the screen no longer kicks you out of a WebGPU reader chapter; the reader now resizes in place instead of recreating the activity
- Reader (longstrip/continuous): reaching the end of the last chapter now pops the menu like the other viewers do
- MangaBaka: entries sitting on the current MangaBaka chapter total are no longer auto-marked Completed while the series is still releasing; only finished series complete on catching up
- Tablet UI: the debug info screen no longer shows a back button in two-pane settings (tapping it dropped out to the phone layout)
- Fix a crash in the scanlator priority reorder screen when dragging a row while the automatic-priority note is shown (`IndexOutOfBoundsException`), and items no longer move from the wrong position in that state
- Reader: the rare thin line between pages in the continuous/webtoon WebGPU viewer. Adjacent pages' tile clip edges were two float derivations of the same boundary and could disagree at a pixel centre, leaving a boundary row to neither page (the background showed through). Both sides now clamp at one shared value, so every row belongs to exactly one page.
- Reader: a page evicted mid-decode could stay latched as decoding, needing the stuck-page sweep to recover it; the worker now always clears its own latch.


## [v1.25.2] - 2026-11-02
### New
- **Downloaded & cached content** screen (More → Data and storage): every download on disk whose entry is not in your library is now listed — grouped by source, with chapter count and disk size — and can be deleted per chapter, per entry, or all at once. Entries whose source extension is gone show as folders; merge children are excluded so a library merge's content is never listed.
- **Chapter cache retention**: a new setting drops cached chapters once unread for a chosen time (12 hours to 30 days; default off). Reading a chapter resets its timer, and the new discovery screen shows each cached chapter's live countdown — or "kept until space is needed" when retention is off.
### Improve
- **Extension updates through the Shizuku installer register immediately.** The install session now asks Android to skip its package-verification step (the Play Protect consult), which previously held every update for 5-10 seconds before the app could see it — exactly what `pm install --skip-verification` does for shell installs. First-time installs are still scanned by the platform, by its own rule; use the Private installer if you also want those to register instantly.
### Fix
- WebGPU reader: fixed a crash when rotating the device while a page was rendering. The renderer could submit a frame to a window the system was tearing down mid-rotation, killing the app with an abort in the GPU driver (SIGABRT in `GPUQueue_submit`). Frames are now drained and gated during surface teardown, so rotation only relayouts the reader.
- Fixed a native crash on the WebGPU reader (abort `JNI ERROR (app bug): accessed deleted Global`, seconds after a black screen following a device loss): a device retired by the recovery ladder could still carry a pending driver-callback record, and freeing its wrapper deleted a reference that record was later marshalled through when the next frame was submitted. Retired devices now free their GPU memory without dropping that reference, and stale callback dispatches are ignored instead of driving spurious recoveries.
- Tablet: pressing the device back button on settings sub-screens opened from the two-pane detail (debug menu, licenses, and similar) no longer collapses the two-pane view to the phone layout
- The debug menu in Settings → Advanced shows its back arrow again when opened from a tablet's two-pane detail


## [v1.25.1] - 2026-11-01
### New
- Scope the library list (CSV) export to specific categories: the export dialog now lists your categories below the column options, and only entries in the checked ones are written — including `Default` for entries in no category. Leave everything checked to export the whole library as before.
### Improve
- Changing the Tablet UI mode now applies on the spot: the interface rebuilds in place instead of waiting for an app restart.
- Books with illustrations (EPUB) turn and scroll much faster now: the images of a document you come back to — turning back a page, or the reader growing its window around you — are kept built instead of being re-read from the archive and re-embedded into the page on every look, and a tap acts the moment you touch the screen instead of waiting on the page's own script.
- Long-pressing an illustration in a book now opens it full screen, with pinch and double-tap zoom and a tap to leave — the same viewer the cover preview uses — together with a Set-as-cover action behind the usual confirmation. This replaces the old tap-to-zoom, which scaled the picture in place and read as broken layout.
- **Network inspection mode** (log level *Extreme*) now captures requests from all of the app's own networking — the E-Hentai API, image loads, downloads, sync — not just source page fetches, and it no longer logs the same request twice for source traffic.
- **Log level *Extra*** ("Log everything") now records request/response headers into the app's log files. The header logging previously went to an output nothing could read, so those lines never appeared anywhere.
### Fix
- Migrating merged entries now keeps the merge intact. Previously, migrating a merged entry tore it apart: the merged entry was unfavorited and replaced by a single standalone entry, leaving every other merged source behind. The merged entry now stays in the library and the new source simply replaces the one the merge was built from (or is added as an extra source when copying). Migrating one source of a merged entry — for example from the mass-migration list — now updates the merge to the new source instead of leaving the old, removed source behind. Read progress, bookmarks and read history carry over as with a normal migration.
- **In-app logs kept losing their content.** The daily log file in the app's `logs` folder was re-opened in overwrite mode on every app start, wiping everything earlier sessions had written — a multi-session day left you with a file holding only the last few boot lines. The file is now appended to, like the upstream logging library always intended, so a day's log keeps its whole history.
- Remove the redundant back button next to the About title on tablet UI: it could pop the About pane out of the two-pane settings layout and drop the app into the phone-style settings screen.
- Fixed the WebGPU reader's pages never loading again on PowerVR-class devices after a GPU memory spike. The reader kept rebuilding its GPU device every second or so while every rebuild leaked the previous one, so memory pressure worsened until the reader sat on a dead device for the rest of the session; recovery now waits for the old surface to actually disconnect, frees the retired device instead of leaking it, backs off between failed attempts, and wakes the pages up when the device finally comes back.
- Fixed a crash when opening a manga's cover preview on a source that serves covers in AVIF, JXL or HEIF (Atsumaru, for one). The preview asked the new image decoder for its raw pixels while another decoder also claimed those formats and won the race, so the app tried to treat an ordinary bitmap as raw pixel data and died on the main thread. The request now reaches the decoder it asked for, and a cover that arrives in an unexpected form leaves the preview empty instead of taking the app down with it.
- Fix the v1.25.0 database migration failing for users updating from v1.24.5, which left the app unable to open its library on launch: a leftover sync trigger referenced the old tables mid-rework, and a duplicate-merge step could null out required fields. The emergency build migrates cleanly; affected libraries keep their entries, read state and categories.
- Fixed the tablet About screen showing a redundant back arrow next to its title: tapping it collapsed the two-pane settings layout into the full-width phone layout. The arrow now hides in the detail pane like on every other settings category, where the settings list itself handles navigation.
- Fixed the migration source picker (the screen that chooses which sources to search when migrating) listing every language of every installed extension: disabled languages appeared in the picker, were saved as migration sources, and were then searched on every migration. The picker now respects the enabled languages configured in Browse settings, and the saved migration-source list corrects itself the next time migration runs.
- The cover on a manga's details screen no longer crops the borders off covers whose proportions differ from the standard 2:3 frame: it now sizes itself to the cover's real shape and shows it whole, matching what tapping the cover opens.


## [v1.25.0] - 2026-10-29
### New
- **Book reader: paged and two-page modes**: books (EPUB) can now be read like a real paper book —
  open the reader settings while a book is open and pick **Paged** (one page per screen, turned
  with a tap, the volume keys or a flick of the slider) or **Two pages** (the classic open spread,
  two columns with a proper gutter) beside the original continuous scroll. Page turns respect the
  book's own writing direction, so a Japanese vertical-writing novel turns the way a Japanese book
  does, and changing the mode keeps your place in the book.
- **Book reader: cover as first page**: most EPUBs keep the cover in their metadata rather than in
  the book's spine, so it was never shown once you started reading. The reader now opens on the
  book's declared cover when there is one (on by default, with a setting to turn it off), and never
  shows it twice for books that already carry a cover page. Applies to the next chapter you open.
- **Navigation 3**: the app's entire UI now runs on Jetpack Navigation 3 (androidx.navigation3),
  replacing the retired Voyager navigator — every screen is a serializable route on one back
  stack, tabs keep their own per-tab stacks on tablets and phones alike, and the two-pane
  settings layout is now a real Navigation 3 scene (list on the left, category on the right on
  tablets). This matches upstream Mihon's migration and should improve predictive-back
  animations and state restoration (upstream mihonapp/mihon#3984). All screens now use AndroidX
  ViewModels with Metro DI instead of Voyager ScreenModels, and the Llamatik demo app submodule
  also no longer depends on Voyager.
- **Subcategory types**: when creating a subcategory (Edit Categories or from library
  selection) you can label it Author, Artist, Illustrator or Designer, so two same-named
  subcategories under one parent are tellable apart; the label shows as a colored tag in
  Edit Categories and in the Set Categories dialog.
- **Book reader: custom CSS & JavaScript injection**: the reader's appearance tab has a new
  **Custom CSS & JS** section — write your own stylesheet or JavaScript and the reader injects it
  into every book. Custom CSS is on by default (it starts empty and can only restyle) and your
  rules are applied after the reader's own, so they win ties; edits apply to the open book the
  moment you confirm them. Custom JavaScript is **off by default** and its toggle carries the
  warning: injected scripts run inside every book you open, so only enable it for code you trust.
  Scripts run in each document right after the reader's own bridge, so a change takes effect from
  the next page you turn.
### Improve
- **WebGPU reader: the between-pages black line, fixed at the root**: the thin dark line that
  could appear between pages in long-strip mode was two layout paths disagreeing by up to half a
  pixel about where one page ends and the next begins — the one that drew sharp tiles rounded the
  boundary to a whole pixel, and everything else (backgrounds, freshly-decoded pages, loading
  placeholders) did not, so the boundary row could be claimed by neither and showed the background
  instead. Every page's boundary is now derived from the same whole-pixel position, so the line
  has nothing left to feed on. It previously appeared rarely, mostly while a neighbouring page was
  still loading — which is exactly when the two disagreeing paths met.
- **WebGPU reader: pages decode in parallel**: the reader now decodes two pages at once instead of
  one, so the page you are looking at no longer queues behind whatever slow page the reader was
  already deep into (a long strip, a large JXL, a slow network read). Small devices stay
  single-threaded on purpose, where two parallel decodes would cost the memory the reader is
  budgeting to protect.
- **Book reader: smoother page turns**: building a book page's markup (which inlines its
  illustrations) now happens off the main thread, so turning the page in an illustrated book no
  longer hitches, and the reader warms the next document's text ahead of you — both in paged mode
  and while scrolling continuously.
- **Book reader: vertical-writing books scroll correctly**: a Japanese or Chinese novel set in
  vertical writing flows sideways, and the reader's page-step used to scroll it down — never
  moving, so every tap skipped a whole section. The step now scrolls the axis the book actually
  uses, and the tap zones follow the writing direction.
- **Database (under the hood)**: the library database now uses Mihon's reworked schema, so the
  fork stays mergeable with upstream. Updating merges duplicate entries — the same series twice
  under one source — into the copy in your library, carrying read state, history, categories and
  tracking to the survivor, and chapter listings can no longer split one chapter into two rows.
  The Updates tab and the widget's unread count also read from the partial unread index instead
  of scanning every fetched chapter, which makes them noticeably snappier on large libraries
  (upstream mihonapp/mihon#3805, mihonapp/mihon#4022).
- **Set Categories**: the dialog now has the same navigation aids as Edit Categories —
  a search field, a sort dropdown (manual / name / date-added / date-modified), and
  expand/collapse chevrons with Expand all / Collapse all, so large category trees are
  browsable without leaving the dialog.
- **Anime-Planet**: rewired against the site's current pages — refreshing now reads your status,
  chapter progress and score back from your Anime-Planet list (true two-way sync), and freshly
  bound entries pick up the manga's total chapter count.
- **WebGPU reader**: loads pages faster — each page's header is sniffed once instead of twice,
  an oversized JXL page is refused before the upscale pass runs instead of after it, and a
  decode adopting its image no longer releases the page it replaced while holding the
  bookkeeping lock (less hitching while many pages resolve).
- **WebGPU reader**: more decode-path speedups — page bytes reach the native decoder with a
  single copy (byte-array ingest) and in 64 KB stream reads instead of 8 KB, its JNI bindings
  are resolved once per process instead of per page, EXIF rotation is a straight-line pass
  instead of a per-pixel formula dispatch, and two per-page lookups (WebGPU availability, page
  identity keys) are cached.
- **Import books**: the file picker now offers only EPUBs — plus the generic
  `application/octet-stream` spelling most file managers give an `.epub`, so folders do not
  appear empty — instead of every file on the device.
- **Broken extensions are flagged where you actually hit them**: the extension error reporter —
  the popup that names the repository a broken extension came from, so you can report it there —
  now hears about failures while browsing a source's listings and while refreshing a manga's
  details or chapter list. These were the two most common ways a site change showed up, and
  until now they stopped at the screen's own error state and told the Extensions page nothing.
  A manual refresh of a manga can raise the report popup (once per distinct failure, as before);
  failures that happen in the background — opening a screen, favoriting a title — are only
  recorded, and appear as the quiet explanation under the extension on the Extensions page.
- **Source feed: no more report popups**: the feed queries every pinned source at once on each
  open, so broken extensions used to stack their report popups over the screen you were
  scrolling. Feed failures are now recorded quietly on the Extensions page, exactly like global
  search — using a source directly still speaks up.
- **Extensions page holds its frame rate**: every download, error or repository tick used to
  rebuild the whole extensions list and re-measure every visible row — including rows whose data
  had not changed — so switching to the extensions tab (or idling on it right after opening the
  app, while extension lists were still fetching in the background) could hold the frame rate
  at half speed until the fetches finished; it only *looked* like switching away and back fixed
  it, because that is when the fetch finished. Unchanged rows are now skipped during those
  rebuilds, so both the tab switch and the settling refresh stay smooth.
- **Debug overlay**: the fps/build-info overlay no longer sits on top of the bottom navigation
  bar (or the side rail on tablets), counts a steady 60 Hz as 60 instead of 59, shows the
  average frame time next to the frame rate, and colour-codes itself green/amber/red so a bad
  frame rate is legible at a glance.
### Fix
- **WebGPU reader: adapter texture limits honoured on low-capability GPUs**: some GPUs
  (PowerVR-class, running Graphics API Compatibility mode) cap textures at 4096px, and several
  textures the renderer sized itself at a hardcoded 8192 — the screen surface, page-turn
  transition caches, colour-filter buffers, the tile cache and the rendered-text font atlas —
  could each fail creation per frame or per page turn on such a device. Every texture the
  renderer sizes itself now clamps to what the adapter reports: oversized content just holds
  less in the tile cache, an oversized glyph renders its spacing only (like a space), and tall
  pages are unaffected because uploads were already tiled.
- **Cold start: splash screen finally lets go**: opening the app the ordinary way — tapping its
  icon — kept the pineapple splash pinned on screen for a full five seconds plus its exit
  animation on every cold start, no matter how quickly the app had actually finished loading
  underneath. (A plain launcher tap carries no deep link, and the splash was waiting for one.)
  The splash now clears as soon as the start screen is ready, so a cold start takes about as
  long as the app genuinely needs — several seconds faster on most devices.
- **Faster backup restore for Mihon and older backups**: restoring a backup that carries no
  update schedule — every backup written by Mihon itself, and Houri backups from before the
  schedule existed — made the restore re-read every chapter row it had just written, once per
  library entry, to work out each title's refresh interval. It now computes the interval from
  the chapter list the backup already carries, cutting one full chapter-table read per entry
  from the restore.
- **Book reader: custom CSS colours text now**: with your own stylesheet active, a rule written on
  `body` — the most common way to restyle the whole page, e.g. `body { color: #000000; … }` —
  coloured the page canvas but left the text itself in the reader's own derived colour, because
  the reader re-asserts its text colour directly on paragraphs. When custom CSS is on, the
  reader's palette now yields the flow to your stylesheet (force dark keeps its overrides), so
  hand-typed colours and headings land everywhere they should.
- **Manga details loading skeleton**: the placeholder now starts where the real details will land
  — below the app bar — instead of at the screen's very top edge, so the content no longer jumps
  downward the moment the details arrive, and it no longer paints chapter rows into the
  gesture-navigation area at the bottom edge.
- **Crash right after onboarding**: finishing onboarding — or tapping the finish button a touch
  too eagerly, twice in the same moment — could crash the app with "NavDisplay backstack
  cannot be empty", because the second tap popped the home screen itself off the navigation
  stack. The finish action now only removes the screen it owns, and the home screen is
  re-seeded as the bottom of the stack before it is ever rendered empty, so no double-tap
  anywhere in the app can leave it rootless (the same class of fix as the back-press one
  below).
- **Legacy reader: zoom settings are honoured**: in the classic pager and long-strip readers,
  a **double tap followed by a pinch zoomed in even with pinch-to-zoom disabled** — the
  pinch was misread as a double-tap-and-drag gesture, which the setting does not govern. The
  two are now told apart for real. Also, with **double tap to zoom** turned off, dragging
  after the double tap still zoomed (the WebGPU reader already treated that setting as
  covering the whole double-tap gesture); and on animated (GIF) pages in the pager,
  **disable zoom in** and the double-tap setting previously had no effect at all. All three
  now answer the same setting the same way in both readers.
- **Crash on back at the home screen**: pressing back — or double-pressing it quickly — could
  crash the app with "NavDisplay backstack cannot be empty". Navigation 3 can deliver a back
  press whose handler is one frame out of date, and the app's own back handlers took that press
  at face value and popped the home screen itself off the stack; every remaining entry then had
  nothing to stand on. The home screen, the tabs and the tracker sheet now treat their root as
  the floor: a back press at the very bottom is left to the system instead of emptying the
  stack.
- **Crash after tapping a source badge on a manga page**: opening a source from a manga that
  had no matching source screen below it in the stack used to silently discard the home screen
  while rebuilding the stack, leaving the app rootless — the next back press then crashed it the
  same way. The home screen is now the floor for that rebuild too, so the source screen simply
  opens on top of it.
- **WebGPU reader crash while backgrounding**: losing the GPU device in the middle of drawing
  a frame — which is what backgrounding a low-memory phone does — could crash the whole app
  with a native `SIGSEGV` from the WebGPU queue: the frame had already passed its "is the
  device alive" check, and the loss landed while it was still drawing, leaving the submit to
  run on a dead handle that nothing in Kotlin could catch. The device is now re-checked right
  before the frame is submitted and the half-drawn frame is dropped, letting the normal
  device-loss recovery take over instead of taking the process down.
- **Rotation**: rotating the device (or rotating in the reader and then leaving a chapter) no
  longer drops you back on the Library tab — the tab you were on, along with the scroll
  position and other saved state of every screen, now survives the orientation change.
- **Upscaling (no-MTL build)**: the Upscaler category is back in Settings — the no-MTL build
  offers Simple upscaling (with the per-manga "Upscale Manga" toggle), but its settings category
  was accidentally hidden along with the AI-translation ones, so global upscaling could never be
  turned on.
- **On-device LLM (mtl)**: an `external/llamatik` submodule checked out past the pinned
  commit no longer breaks the `:llamatik-native` build with a bare "Cannot find source file /
  No SOURCES given to target: llama_jni" — CMake now detects the missing JNI sources and
  reports the one-command fix instead; the submodule pin itself was restored.
- **Google Drive**: a revoked/expired refresh token no longer fails every sync attempt
  forever — the app signs itself out cleanly so you can sign in again; and on devices
  without a browser, tapping Sign-in toasts a friendly error instead of crashing.
- **Anime-Planet**: rating writes now hit the site's real `rate` endpoint and use its half-star
  (0.5–5.0) scale — scores previously never persisted.
- **Anime-Planet**: starting a re-read no longer marks the entry "Won't Read" on the site.
- **EPUB books**: a book's description no longer prints raw HTML — the `<p>` tags and `&#8212;`-style
  entities that producers author into it now show as readable prose with proper paragraph breaks
  in the manga details screen.
- **Migration**: edited series details now follow the entry when it is migrated or copied to another
  source — a manually cleaned title, corrected author/artist, description, tags or status used to
  stay behind on the old entry (edits are stored against the entry's ID), so the migrated one came
  back with raw source metadata.
- **Local source**: deleting an EPUB or a local manga's downloaded chapters no longer shows a cryptic
  "Failed to determine if … is child of …" error — and it now disappears from the chapter list on
  the first delete instead of needing a second attempt. The post-delete refresh used to race the
  file deletion and open the file mid-delete.
- **Find duplicates**: the toolbar no longer overlaps the list — its rows and the "Find duplicates"
  title used to stack on top of each other in the screen's top-left corner, leaving the entries
  unreadable underneath.
- **Achievements**: unlocked cards' animated gradient now follows the card's rounded corners
  instead of painting sharp square corners that stuck out past every other card in the grid.
- **Extension error popup**: the report popup now scrolls when its content is taller than the
  dialog — on small screens and in landscape the actions at the bottom were cut off entirely,
  with no way to reach them. The padded band around its buttons is also gone: the actions sit
  as compact rows, and the "Don't show again for this version" opt-out no longer renders as a
  full-width, centre-aligned button carrying its own 48dp of padding.


## [v1.24.5] - 2026-10-21
### New
- **AI Translation**: Traditional Chinese (zh-TW) is now available as a target language alongside Simplified Chinese.
- **AI Translation**: When the translation engine is installed but the optional multilingual OCR add-on (PP-OCRv5) is missing, a one-tap "Install multilingual OCR add-on" row appears in the AI models settings. It downloads only the ~16 MB add-on, which is what reads English and other Latin-script pages; Japanese and Chinese work without it.
- **Book reader**: Novels now come with a curated, bundled font set — 16 OpenType families grouped by kind (serif, sans, mono, handwriting) in a picker that previews each family in its own face, alongside the device generics and imported files, so "serif" no longer means a different typeface on every device.
- **Book reader**: Bold-text and force-dark toggles, plus hand-typed hex colours for text and background, so a book's own colours can always be made readable.
- **Local manga (EPUB)**: A book whose EPUB metadata declares a series — calibre's tags, or an EPUB 3 collection marked `series` — is titled "Series: Volume", so a library row is a volume of a series rather than an anonymous fragment.
### Improve
- **WebGPU reader**: Smoother continuous (webtoon-style) scrolling — the background layer drawn under every page used to hand the GPU a fresh buffer and binding on every frame; both are now drawn from a small reusable ring, cutting per-frame GPU allocation churn for as long as the reader is open.
- **Book reader**: Find-in-book searches no longer re-read and re-strip the whole chapter with every keystroke — the plain text is built once per book while it is open, so typing in the search sheet stays quick on large novels. (Find-in-book is also now offered for local EPUB chapters at all — the button previously never appeared for them.)
- **Book reader**: The chosen font's data is base64-encoded once per family instead of on every page render and every typography adjustment, removing the hitch a font change used to cause mid-book.
- **Local manga (EPUB)**: EPUB-as-comic chapters now collect plates declared as SVG `<image>` elements (by `xlink:href`), not only `<img src>` ones.
- **Large libraries**: Global-update's metadata refresh and the category screen no longer load the entire library with per-entry chapter counts just to read titles and count category membership — that full-library pass was the heaviest step of both flows and is now gone.
- **Large libraries**: Routines that only ever needed one field now ask the database for exactly that field — the manga screen's sequel/prequel badge lookup and the "is this source entry already in my library" cross-check run lightweight queries instead of a full-library snapshot, and library cover pruning matches covers without loading your whole collection first.
- **Large libraries**: The database engine now keeps its scratch space and page cache in RAM and memory-maps the file for reads, so the temporary sorts that library-wide queries perform on big collections no longer spill to disk and every read gets a larger cache behind it.
### Fix
- **Large libraries**: On very large libraries, the out-of-memory guard around loading the Library could leave you staring at an empty screen; it now falls back to showing your entries without their unread counts rather than an invisible library.
- **Library**: Using "Search library" on a manga no longer breaks the back stack — Back from the filtered Library now lands you on the manga you came from, first closing the search filter as usual, instead of dropping the manga screen entirely.
- **Browse**: Leaving a source's browse screen for a manga (and coming back from a chapter) keeps the list/grid scroll position, instead of returning to the top of the page.
- **Manga Translator**: Image-translation failures now say what actually went wrong — the service found no readable text on the page, the page exceeds its 8 MB / 10000 px limits, or composing the translated page failed — instead of a bare "MangaTranslator returned empty result".
- **Local manga (EPUB)**: A chapter no longer gets stuck blank when the book references an image that is not actually in the archive — the loader skips (and logs) those references instead of creating a page the reader can never open.
- **Book reader**: Producer-noise hardening for EPUB books — a stray byte-order mark at the start of a text chapter is stripped instead of rendered, and a case variation of the internal `book.internal` URL no longer silently drops a book's images and stylesheets.
- **Book reader**: Links *inside* a book now land on their destination — a footnote link to `chapter.xhtml#note-7` scrolls to that note instead of opening the chapter at the top.
- **Local manga (EPUB)**: Resources whose file names contain a `+` (some producers' `cover+v2.jpg`) no longer 404 when the book tries to show them.
- **WebGPU reader**: Fixed a thin dark line that could appear at page boundaries in continuous (webtoon-style) reading while a neighbouring page was still decoding.
- **WebGPU reader**: Reopening a chapter after the app was killed now resumes on the page you were actually on — when a tall page had been split into segments mid-reading, the reader used to land several pages past your position.
- **WebGPU reader**: With double-tap zoom disabled, a tap on a paged page now registers immediately instead of the reader waiting to see whether a second tap arrives, matching the continuous reader.
- **WebGPU reader**: Zooming out in continuous (webtoon-style) reading no longer silently marks ahead-of-read pages as read — the saved reading position only advances with actual scrolling now, so reopening a chapter lands where you left off even after deep pinch zooms.
- **WebGPU reader**: A page in continuous reading that finished decoding while the reader sat still no longer stays blank until the next touch — the reader now notices pages joining the visible strip as soon as they become part of it.


## [v1.24.4] - 2026-10-20
### New
- **Reader (EPUB books)**: Long-pressing an illustration inside a book now offers "Set as cover", so any image carried in the EPUB can become the cover art of its library entry. Long-pressing text still selects text, as before.
- **Backup restore**: A new "Update library after restore" toggle (on by default) controls whether a restored library immediately kicks off a full background refresh. Previously this always ran and could not be turned off.
### Improve
- **Reader (EPUB books)**: The book's contents list now has a scrollbar, so a long table of contents can be jumped through instead of only flick-scrolled.
- **Tracking**: Logging out of a tracker now keeps your library's tracking records (scores and statuses) by default, so re-logging in re-links them without re-syncing from scratch. A new "Delete tracking records on logout" toggle in *Settings → Tracking* (off by default) restores the old behavior, where signing out also wiped those records from the database.
- **Backup restore**: The restore screen stays responsive while a large backup is validated, and the database folds its write-ahead log when a restore finishes so the restored data is fully settled on disk instead of sitting in a large log file.
- **Local source**: When a book's own title is only the volume half of a series title (an EPUB whose OPF is filled with "and the Prisoner of Azkaban" in a "Harry Potter" folder), chapter names now lead with the series title, so every row says which volume it belongs to. Book tables of contents that carry markup inside their labels no longer show it either.
- **Manga Reader**: Controller stick smoothly scrolls webtoons (and long-strip mode) at a speed based on how far the stick is tilted; adjust **Controller scroll speed** under Settings → Reader → Navigation, or in the reader sheet when the series is in webtoon / long-strip mode
### Fix
- **Discord**: The Discord Rich Presence notification no longer appears when you are not logged in to Discord. It used to pop up briefly even logged out — roughly 30 seconds after closing the app, or when toggling the Rich Presence off — because stopping the background service could start it just to stop it. Removing your active Discord account in the accounts list also now properly signs that session out: the notification goes away instead of the presence continuing behind your back, and switching to another account points it at the right one.
- **Reader**: The vertical seekbar layout is now separate for books and manga. Setting it (side, landscape, or force-horizontal) while reading a book (EPUB) no longer also moves the seekbar for long-strip content (manhwa), and the reverse — each reader keeps its own navigator layout.
- **Book reading (EPUB)**: Clicking a chapter in the contents list now opens at the section it names. Before, a jump into a section that had not been opened in the current reading session could find itself halfway through that section, near its end, or inside the following one: the destination was lost while the section's text was being read, and a freshly rendered page could start from the scroll position of the page it replaced.
- **Local source**: Importing EPUB books no longer crashes the app when the import finishes, because the confirmation toast was shown from a background thread. The toast now appears from the main thread as normal.
- **Backup restore**: A restore could silently skip the last series in the file (up to one internal batch, 249 entries) when no full batch remained before the merged entries were written. The leftover batch is now written, so the restored library matches the backup and the progress bar reaches 100% again.
- **Local source**: Chapters of a folder of imported EPUB books now sort by the volume number recognised in the file name. The list used to be ordered alphabetically by each book's declared title, which set the volumes down out of sequence ("and the Prisoner of Azkaban" before "and the Chamber of Secrets") and printed bogus "Missing N chapters" markers between them.
- **Local source**: EPUB book chapter names no longer have the series title peeled off the front ("and the Prisoner of Azkaban" instead of "Harry Potter and the Prisoner of Azkaban"), and XML markup left inside a book's metadata no longer leaks into chapter titles and the author line.
- **Library**: Large libraries load noticeably faster and stay responsive while updating. Cover-cleanup work no longer runs a second full library scan at the same moment the library is first built, duplicate background library queries were collapsed into one, and title sorting now prepares each title once instead of on every comparison.
- **Book reading**: The typography rows in *Settings → Book reading* were drawn on top of each other into an unreadable pile, because they relied on a scrolling container they were not given there. They now stack normally.


## [v1.24.3] - 2026-10-19
### New
- **Book reader**: *Import books* in **Settings → Book reading** copies one or more EPUBs off your device into a new folder in your local source, under a name you choose. Several files at once become one series. Files that are not EPUBs are skipped and reported, and an existing folder of the same name is never overwritten.
- **Books (EPUB)**: Added a **Book reading** category to Settings, so book typography — font, size, colours, spacing, margins, hyphenation, vertical writing — can be changed without opening a book first. It is the same set of settings as the *Reading appearance* tab inside the reader, and the two stay in step.
### Improve
- **Reader**: The chapter list you open from the reader now has a draggable scrollbar on the right, like the library and every other long list. Previously, getting from chapter 1 to chapter 400 meant flicking.
### Fix
- **Library**: A chapter name from a book now shows on two lines instead of one, so a long title like *Harry Potter and the Prisoner of Azkaban* is recognisable instead of a fragment.
- **Book reader**: The vertical chapter-progress bar counts up from the top again. It was mirroring with the comic reading direction, which put 100% at the top — right for a right-to-left manga, wrong for a document, where progress always runs from the first line down.
- **Tracking**: Keeping up with a MangaBaka series that is still releasing or on hiatus no longer marks it as Completed. Reaching the latest chapter of an ongoing series used to flip your status to Completed every time the chapter count grew, and the entry could never go back to Reading afterwards.
- **Tracking**: Logging out of a tracker now also clears the scores, statuses and progress it had saved locally for your library. Signing out only dropped the credentials, so the next login brought the whole tracked list straight back.
- **Smart categories**: Subcategories bound to a tracker status now only move a manga that is already somewhere under their parent. A series filed under Shounen no longer gets dropped into `Josei → Reading` as well just because both subcategories share a status; being in both branches still updates both.
- **Browse**: Scrolling down a source's results and coming back from an entry or the reader no longer snaps the list to the very top. The list's scroll position was tied to whichever layout was on screen, so it was lost whenever the screen briefly showed its loading state or changed display mode.
- **Book reader**: Tapping a picture inside a book zooms it, centred on the point you tapped. Tap it again to put it back. The page keeps scrolling and text stays selectable, and if anything goes wrong the tap falls through to scrolling exactly as before.
- **Book reader**: Scrolling a book no longer jumps around. Sections were being prepended above you at a height measured before their illustrations had loaded, so the page shifted under you as pictures appeared; sections that carry their own stylesheet also lost it, and were laid out in the previous section's type.
- **Book reader**: A new *Paragraph indent* setting, so first-line indents can be turned off or left to the book. The custom font and background colours you asked for alongside it were already there.
- **Migration**: The *Select sources* list no longer offers every E-Hentai language at once. Each language is its own live source, so entries filed under a language you had switched off still put a row there; only the languages you have enabled are listed now.
- **Book reader**: The reading-appearance settings now show the values you have actually saved. They opened on the defaults and only corrected themselves once you touched something, which made saved settings look like they had not stuck.
- **Book reader**: Picking a font you imported no longer prints your own CSS as a paragraph of text at the top of the page. The `@font-face` rule was being placed loose in the document's `<head>`, which the renderer moved into the page as visible text — so the font never applied either.
- **Library**: Covers in JPEG XL no longer slow the library down. Cover colour sampling asked the system decoder for an image it cannot read, and retried on every scroll because the colour it wanted never arrived. Libraries with `.jxl` covers should now open noticeably faster.
- **Local source**: E-books and single-file comics dropped straight into the local source folder now show up in *Browse*. Only folders were listed, so a `.epub` with no folder around it was invisible.
- **Library**: *Find duplicates* no longer lists the same pair of entries twice. The duplicate check matches in both directions, so every pair was shown once from each side; three mutually-duplicated entries produced three rows for a single thing to review.
- **Books (EPUB)**: Books now show their real title, author, publisher and language instead of their file name. Almost every EPUB was affected: the metadata parser read one field and then swallowed the rest of the book's metadata block, so a title, author and date declared in the file were silently discarded and the filename stood in for all of them.
- **Books (EPUB)**: Fixed a table of contents that listed only every other chapter. Some books showed a handful of entries out of hundreds; others showed them all, which is why it looked like a problem with particular files rather than with the reader. Partly-nested contents (a part heading with chapters under it) lost the nested chapters entirely.
- **Reader**: Long-pressing inside a book (EPUB/novel) no longer opens an image toolbar — Set as cover, Copy, Share, Save — on top of the text-selection toolbar. Long press in a book now only selects text, the way it does in any other reader.
- **Reader**: Fixed a crash when tapping any page action (Set as cover / Copy / Share / Save) while reading a book. A book's pages are text and have no image to act on, so every option in that toolbar threw a `NullPointerException` and closed the app.


## [v1.24.2] - 2026-10-18
### New
- **Books can live in the manga library.** An `.epub` you drop into an ordinary library folder used to
  open as a strip of illustrations and nothing else, because the reader asked the file's format and
  never asked what the entry *was*. Entries now carry a content type, and one that is a novel opens as
  a book — your own stylesheet, your own fonts, your illustrations inline — in the same library as
  everything else.
- **Nothing is taken away.** A folder whose chapters are art rather than prose still opens as a
  comic, because that is what those files are for and some readers depend on it.
- **The guess is wrong sometimes, and it is easy to overrule.** Detection reads the book's spine and
  is a heuristic; a book it calls a comic, or an art book it calls a novel, is one tap from being
  fixed in the manga's info screen. What you choose there sticks — a later library update will not
  quietly undo it.
- **Extensions can say what their entries are**, through a new optional interface. Nothing existing
  changed shape, so every extension you have installed keeps working untouched.
- **Illustrations in a book can be turned off.** Reading a heavily illustrated novel, decoding every
  plate is the slowest part of turning the page. Default is on, which is how it already behaved.
- **Books have a contents list and a chapter search.** Both were implemented on the EPUB side and had
  no way to reach them. The bottom bar now carries a **Contents** and a **Search** button while you are
  reading a book, and they hide themselves again for a chapter that cannot answer — an extension with
  no table of contents simply does not get the button, rather than getting one that opens nothing.
- **Contents** follows the book's own structure, indenting nested entries the way a printed one
  does, and opens scrolled to wherever you are in the book. Tapping an entry goes to that section.
  Entries belonging to a different chapter of the book are still listed, greyed out, so you can see
  the shape of the volume even though you would have to open that chapter to reach them.
- **Search** looks through the chapter you are in as you type, and lists the surrounding text of
  every match — the snippet is the only way to tell two hits of the same word apart, and it is
  selectable so you can copy a passage out. Tapping a result jumps to it. Matching is
  case-insensitive.
- **A book's reading appearance is adjustable at last.** Font size and scale, family, text and page
  colours, line and paragraph spacing, letter spacing, margins, justification, hyphenation, vertical
  writing and inline illustrations were all stored settings with no way to reach them — they now
  live in a **Reading appearance** tab in the reader's settings, offered exactly when the open
  chapter is a book. Colours are a fixed palette, and picking a page colour also picks a text colour
  that stays readable on it, so a dark page can never again mean invisible text.
- **You can add your own fonts to the book reader.** The font picker in the Reading appearance tab
  now has an **Add a font** option: pick a `.ttf`, `.otf` or `.woff` file from your device and it
  is offered beside the built-in families, for every book you read. The file is copied into the
  app's own storage once — nothing is left pointing at your files.
- **The chapter selector now knows what a book's chapters are.** A book is one library chapter, so
  the selector offered a single row — or, for a folder of novels, a list of *other books*. While
  reading a book it now lists the volume's own sections from its table of contents, and tapping one
  jumps there. Books whose source has no table of contents get the old list back rather than a
  dead button.
### Improve
- **A book's settings only offer what a book can use.** While reading an epub, the reader's settings
  were the manga reader's: scale types, crop borders, page spreads, tap-zone layouts, page flash,
  chapter transitions, colour filters — none of which do anything to text, and several of which
  were visible for no reason. A book now shows General, trimmed to the options that apply, plus its
  own Reading appearance tab, and nothing it cannot act on.
- **A book's chapter buttons now step its chapters.** The previous/next chapter buttons skipped a
  whole book — one library chapter — so inside a novel they were useless. They now move through the
  book's own sections, and leave for the next volume only at either end of the book.
- **A book reads like the continuous manga reader.** Scrolling used to stop dead at the end of each
  chapter, and the only way onward was a tap or a button. The next chapter now flows in as you
  reach the bottom of the one you are reading — and the previous one flows back in above you when
  you scroll up — so a book reads as one long page. Pushing past the very end of the book moves on
  to the next volume.
- **The page slider now shows chapter progress.** For a book it used to offer the chapter list as a
  row of "pages" — a chapter navigator wearing the wrong label, competing with the contents list.
  It now shows how far through the chapter on screen you are, as a percentage, and dragging it
  moves through that chapter.
- **The book reader can be navigated at all.** Taps, swipes and volume keys did nothing in an EPUB, so
  a chapter with no text on the first screen — which is what most books open on, since their first
  file is usually the cover — left you stuck. Tapping an edge now moves, tapping the middle opens the
  menu, and holding opens the page actions. Running off the end of a chapter turns to the next one;
  running off the end of the book opens the menu, which is where the chapter list already was.
- **Books reopen where you stopped.** It always opened at the first page of a chapter regardless of
  how far you had read. Position is now remembered *within* a chapter as well as between them, and is
  measured in characters rather than pages — so a book reopens on the same paragraph at a different
  font size, on a tablet, or in landscape, instead of jumping to a page boundary that means something
  different on each.
- **Books in other encodings render correctly.** Chapters relied on the XML declaration inside the file
  to say what encoding they were in, and the reader was discarding it. It says so itself now, so a
  Shift_JIS or EUC-JP book is no longer mojibake.
- **Chapter dates are no longer off by centuries in some languages.** A chapter's publication date was
  interpreted using the phone's own calendar, which on some devices shifted every year. Dates are now
  read the same way everywhere.
- **A book with a broken reference no longer stops your library scanning.** One malformed file could
  fail the whole scan of the folder it sat in.
- **Changing the reading appearance now changes the page you are on.** Font size, colour, margins and
  the rest re-apply to the open chapter immediately, instead of waiting for you to reopen it — which is
  what made those settings look broken rather than lazy.
- **Searching a chapter is no longer re-read from scratch on every keystroke.** The search followed
  typing, and each pause re-read and re-parsed the whole book from disk, so typing a word meant reading
  the chapter several times over. It is now prepared once and reused.
- **Long novels open as fast as short ones.** Opening a book read every file it contained before the
  first screen could appear, so a five-hundred-file novel took visibly longer to open than a short
  one — the longer the book, the longer the wait. The section you open on is read first now and the
  rest as you turn, so a novel opens on its first page almost immediately however long it is.
### Fix
- **Reading many books no longer slows down or runs out of memory.** Each book you opened kept its file
  mapped open for as long as the app was running, and nothing ever gave it back, so a reading session
  that got through fifty books was holding fifty of them. It is released when you leave a chapter now.
  Opening a book also got measurably faster: every stylesheet, illustration and font it asked for was
  found by re-scanning the whole archive from the beginning, so a book with a lot of files paid for
  every one of them.
- **A damaged book can no longer take the app down with it.** Sizes inside a book file are whatever
  that file claims they are, and they were being trusted without a limit. A single oversized or
  deliberately crafted file could exhaust memory, or push the reader off a chapter, or crash it while
  drawing a cover. There are now ceilings on how much of a chapter, one file, or one cover the reader
  will hold at once — set far above anything a real book uses.
- **A book cannot silently redirect you off the page.** A file carrying a "refresh to this address"
  instruction replaced the page you were reading, without you tapping anything.
- **Links inside a book no longer take over the reader.** Tapping a link to a website used to load that
  website *inside* the reader, on top of the page you were part-way through. They now open in your
  browser instead, and the book stays where you left it.
- **The contents and search buttons no longer appear before they can work.** They were offered as soon
  as the chapter started loading, and opening one in that window showed an empty list and reported the
  book as having no contents. The contents list also failed to notice when a chapter's pages arrived
  after it had already decided which entries were reachable.
- **A font that broke a line no longer made the appearance settings look broken.** Choosing a font whose
  name contained certain characters left the style update silently failing, so the page kept its old
  appearance and the setting seemed to do nothing.
- **Opening a book shows the book again.** The reader resolved every relative reference inside a
  chapter against the *root of the archive* instead of against that chapter's own folder, so a chapter
  kept in `OEBPS/Text/chapter1.xhtml` looked for its stylesheet at `styles/main.css` rather than
  `OEBPS/styles/main.css` and never found it. Every illustration, embedded font and relative link in
  the book missed the same way, and silently — no error anywhere. Books were rendering as bare text
  wearing only the reader's own styling, which is what made the reader look simply broken.
- **A book can no longer end up in the picture reader.** The reader picked which viewer to use before it
  had loaded the chapter, so it could never tell a book from a comic, and handed every EPUB to the image
  reader — which then had nothing to draw. It now decides from what loaded the chapter, and once a
  chapter is known to be a book nothing else is allowed to try. If the book reader somehow cannot handle
  it the reader says so, instead of quietly showing a blank page that looks like a problem with your file.
- **One missing image no longer holds up a whole chapter.** A file the book referred to but did not
  contain used to leave the engine waiting on a URL scheme nothing could answer, which held up the
  chapter's layout. It now shows a broken image and carries on.
- **Searching no longer lands you in the wrong part of the chapter.** A book with a file that could not
  be read — a broken download, one file too large — left a gap in its numbering, and every search result
  after that gap pointed at the wrong section or at nothing at all. Results now resolve against the
  document they were actually found in.
- **A section name containing a percent sign no longer throws off the contents jump.** Following a
  contents entry whose target was named something like `100%d` sent the reader to the wrong place,
  because the scroll instruction was assembled by substituting into a template that had more than one
  placeholder.
- **The contents sheet no longer leaks what it holds open.** Closing it left the reader's search and
  contents work running in the background for as long as the app stayed open, each holding on to the
  book it was working through. That work is now stopped when the sheet closes.
- **A contents list that lists nothing from this chapter says so.** A book-wide contents whose entries
  all point at other chapters was reported as a book with no contents at all, rather than as one whose
  contents are somewhere else.
- **A non-white page no longer hides the text.** Setting a page colour left the book's own text
  colour standing, so a dark page meant dark text on it — unreadable, and with nothing in the
  settings to suggest why. Choosing a page colour now also chooses a text colour that contrasts
  with it, unless you picked a text colour yourself.
- **A book's illustrations, stylesheets and embedded fonts render.** Books were being shown at a
  made-up internal address, and the reader's engine only loads the files a page asks for — its
  images, its fonts, its own styling — when the page's address is a real web one. Every file a
  book asked for was being refused before the reader ever saw the request, so a novel showed its
  text and nothing else, with no error anywhere: the reader looked simply broken rather than short
  one illustration. Books now live at a reserved address the engine accepts — and the illustrations
  and stylesheets of the chapter you are reading are carried inside the page itself, so they
  arrive the same way the text does and cannot be refused by anything. (A file without a
  conventional extension — a WebP, an image stored as `.bin` — is also identified by its contents
  when its name does not say.)
- **Reaching the end of a section now turns it.** A tap's job — scroll, and turn the page when
  there is nothing left to scroll — was delegated to a script inside the book's own page, and when
  that script did not run, every tap silently did nothing: the page slider was the only way from
  one chapter of a book to the next. Turning is now decided by the reader itself, so it cannot be
  disabled by the book, the engine, or anything else.
- **The black background works on books, and a dark background no longer hides the text.** Choosing
  black in the reader's general settings painted only the window behind the page and left the
  book's own text colour standing — which for most books is black text on a black page, i.e.
  invisible. A dark background now colours the page itself and the text switches to the colour
  that reads on it. A page colour you picked yourself in the Reading appearance tab still wins.
- **MangaBaka knows the last chapter.** The tracker never read how many chapters a series has, so
  progress showed as a bare number with no total, the chapter picker offered a ten-thousand-entry
  range with no end in sight, and finishing a series never marked it completed — the reader could
  not tell which chapter was the last one. The count now comes from MangaBaka's own series data,
  for newly bound series and existing ones alike.
- **Achievements no longer unlock during onboarding.** The preferences behind them are evaluated
  the moment the app starts, so a value that was true only because it *shipped* true could earn
  its achievement before you had chosen anything — including whether to have achievements at
  all, which is one of the things onboarding asks. Nothing unlocks until onboarding is finished.
  A backup restore is unaffected, and still brings back everything you had.
- **A book's real title, author and description show in the library.** Books whose publisher
  bound the metadata to the `dcterms:` prefix instead of the usual `dc:` showed nothing but their
  file names — the local source was reading the book's own catalogue with a parser that only
  understood one spelling of it. It now reads the catalogue the book engine already parsed, which
  understands both spellings, and a book with several authors lists them all.
- **A book's cover is its declared cover.** The library thumbnail was whatever image turned up
  first in the book — for most novels that is a publisher logo. It is now the cover the book
  itself declares, falling back to the first illustration only when the book declares none, and
  a DRM-locked book no longer writes a thumbnail of noise.


## [v1.24.1] - 2026-10-16
### New
- **Extensions can update themselves.** A new setting in *Advanced → Extensions* installs new
  versions of your extensions in the background, on a schedule you pick (every 6 hours to weekly).
  It is only available with the **Private** or **Shizuku** installer, because those are the two
  that can install without showing a system dialog — with the other two the row says so instead of
  quietly doing nothing. Off by default.
- **Both built-in libraries can now be refreshed from Browse.** Open the Local or EPUB library and
  use *Refresh library* in the overflow menu: it re-reads the folder on disk and re-syncs every
  series you have in your library, so a newly added book shows up without waiting for a library
  update run.
- **A reading mode can be set per category.** Category manager → overflow → *Reading mode* pins
  every series in that category to a viewer (webtoon for a long-strip shelf, for instance).
  Individual series keep their own mode, and a subcategory can override its parent.
### Improve
- **The EPUB library is visible without hunting for it.** Both built-in libraries bypass the
  "other" language filter in the source list, global search and the migration source picker, so
  your local library can be searched and migrated into regardless of which languages you have
  enabled.
- **Restoring a backup is faster again, on large libraries.** Two things were making a big restore
  slow. The first was one database transaction per series: the restore opened and committed a
  transaction for every single entry, each with its own hand-off to the database writer, so a
  library of tens of thousands of series spent most of its time committing rather than writing.
  Entries are now written in batches of 250 in one transaction, with a single bad entry still
  falling back to being restored on its own so one failure cannot take a batch with it. The second
  was that the whole backup was decoded into memory before anything was written; it is now read
  straight off the file a few entries at a time, which also means a large backup no longer has to
  fit in memory all at once.
- **The library count after a restore is counted in SQL** rather than by reading every series back
  out of the database just to measure the list.
### Fix
- **The app no longer crashes on launch if the About screen was the last screen you were on.**
  Voyager saves the screen stack between launches, and the About screen's `readResolve` handed back
  something that is not serializable, so restoring it threw during every cold start that had it in
  its history.
- **Finishing a series is now recognised the moment you finish it,** rather than only after you
  closed and reopened the reader. The reader was judging "have I read everything" against the
  chapter list as it was when the reader *opened*, so the chapter that completed the series still
  counted as unread.
- **The "new manga" notification no longer fires once per chapter.** It appeared for every chapter
  you finished in your first sitting on a series, for the same stale-list reason as above.
- **Swiping back to the last page no longer re-fires the chapter-complete webhook or sound.** The
  completion ran every time the last page was *shown*, including the swipe back that re-showed it.
- **The spoiler warning now judges chapters by number, not by list position.** It could only ever
  fire for whoever finished chapter one, and mislabelled a legitimate read of the last chapter of a
  partially-read series.
- **Stopping a reread restores what you had actually read.** It used to restore *everything*: it
  guessed from the last-fetch timestamp, which marking a chapter read or unread does not change, so
  every chapter of the series looked older than the reread. A series with 10 of 50 chapters read
  went to 50/50 the moment you hit Stop.
- **The reread chip on a series opens the right dialog.** While a reread was running, the chip said
  "Rereading" but opened the *Start* dialog, whose confirm then did nothing.
- **Sequels & Prequels no longer shows relations from a series that does not have them.** If the
  tracker bound to a series reported no relations, the app carried on asking other trackers and the
  MangaDex fallback, and showed whatever they answered under that series — including a "Source"
  that the bound tracker does not list at all. Now the first tracker that answers is taken at its
  word, and a series with no relations shows no row.
- **Anime-Planet now saves your score.** The rating was passed to the API layer and never sent, so
  a score picked in the app never reached the remote list. Status and progress already did.
- **Comick now reads back what it stored.** Its update ignored the server's reply, so a score or
  progress the site had not accepted stayed in the app looking saved. The first write of a track —
  on binding — also left the rating out entirely.
- **Incognito reading is detected again,** so the achievement that counts incognito chapters can
  unlock. The reader resolved the flag once, before the series had loaded, and so only ever saw the
  global switch — never the per-extension one.
- **Achievement streaks count properly.** Every reading streak was compared against *today* rather
  than against the previous day, so any run of three or more days broke on the third day and
  "Three Day Run" — and everything above it — was unreachable no matter how consistent you were.
- **Achievement days follow your calendar, not Greenwich's.** Streaks, daily counters and the
  rotating achievement pool all rolled over at midnight UTC, so for most of the world they turned
  over during the evening — and the weekend window opened on Friday night.
- **The "read 5 today" and "search 5 times" achievements now need five.** Six of the rotating daily
  goals fell through to the default threshold of 1, so the first chapter of the day unlocked a tier
  described as five.
- **Rotating achievements now record anything at all.** They were only ever drawn when you opened
  the achievements screen, so until you did, every count was thrown away and none of the daily or
  weekly cards could unlock.
- **Daily achievements reset independently.** They shared one day stamp, so the first one to be
  updated each day reset only itself and the others carried yesterday's total into today.
- **The "switch reader direction 20 times" achievement now counts switches.** It counted the
  opposite — rebuilding the viewer in the *same* direction, which happens on every chapter — so
  reading a series straight through filled it and genuinely flipping the direction never did.
- **Reading time is no longer rounded up to a whole minute per chapter.** A chapter abandoned
  seconds in still counted as a minute, which reached the reading-time tiers — and the rank that
  reads the total — far faster than the time you actually spent reading.
- **The WASM engine runs the module it downloads** instead of returning the input backwards. It
  compiled the module and discarded it, so the "WASM Whisperer" achievement unlocked on any trigger
  and a caller that sent the result to a site got a value with the right shape and the wrong
  contents.
- **A thin line between pages in the long-strip WebGPU reader is fixed.** It came and went, and
  showed up most often where a page had not finished loading yet. The loading placeholder was drawn
  at a slightly different height from the space the reader had set aside for it, so it either
  painted over the row of real artwork above it or left a gap.
- **Bookmark colours now show in the reader's chapter list and in the Updates tab.** Both were
  dropping the colour on the way to the screen — the reader's list because its chapter model never
  carried the field, and the Updates list because its bookmark icon was always drawn in the theme's
  default colour. Colours set on a chapter show everywhere the bookmark does.
- **EPUB books open in the book reader.** The reader picked which viewer to use before it had loaded
  the chapter's contents, so it could never tell a text chapter from a picture one and every book
  fell through to the image viewer.
- **Coming back from a series no longer jumps to the top of the list** in Browse, and the same for
  any other list you come back to from a details screen.


## [v1.24.0] - 2026-10-15
### New
- **EPUB books can now be read as books.** A new built-in **EPUB library** source appears in Browse.
  Drop a folder of `.epub` files under your local source directory (in a `novels` folder) and each
  folder becomes a series, with one chapter per book inside it. Previously an EPUB could only be added
  as a manga, where it was read as a pile of illustrations and the text was never shown.
- **Reading appearance settings for books**: font, font size, font scale, text colour, background
  colour, smooth text, justified alignment, hyphenation, line spacing, paragraph spacing, character
  spacing, and left/right/top/bottom margins. Alignment, hyphenation and vertical writing each have
  a **"Follow book"** option, so a book that already looks right keeps its own styling and you only
  change what you want changed.
- **Vertical writing (縦書き)** is supported, which Japanese and Chinese novels usually expect.
- **Your place in a book is remembered by where you got to, not by page number.** Text reflows
  differently on every screen size and at every font size, so remembering "page 4" dropped you
  somewhere different each time. Progress now follows the actual paragraph, and the percentage shown
  reflects how much of the book is really left rather than how many files it has.
- **Books with a table of contents can be navigated by it**, and text can be searched within the
  current book.
- **A switch for the cover loading spinner** on a series' details page, in Settings > Appearance >
  Manga Info. On by default, as before; turn it off to get Mihon's plain skeleton placeholder
  instead of the spinner while the cover loads.
### Improve
- **EPUB files are parsed properly now.** Titles, authors, publishers and descriptions appear instead
  of coming out blank for books that used their own namespace prefixes; the contents page is read from
  both modern and older EPUB formats; embedded images, stylesheets and fonts load; and a DRM-locked
  book now says so plainly rather than opening to a run of blank pages. Font-obfuscated books - the
  common "protected" kind - read normally, as they always should.
- **The rotating daily and weekly challenges now actually rotate.** Only the three and four
  achievements currently on show made progress - everything else in the rotating pool was still
  being counted in the background and quietly unlocking itself the first time you happened to do
  the thing, so the pool drained instead of turning over. The library's Achievements tab now puts
  the active ones first, marks the rest as "not in rotation", and shows a real progress bar
  ("3/5") instead of a bare number. The rotation also rolls over when you open the app rather than
  only when you happen to visit the tab.
- Rotating progress is no longer lost when an unlock is refused, and two achievements marking at
  once no longer overwrite each other's count.
- Restoring a backup is dramatically faster on large libraries. Restoring a backup with tens of
  thousands of series spent most of its time on work that had nothing to do with the data itself:
  it posted a progress notification once per entry (each one a round trip to the system UI), it
  looked up reading history two database queries per history row instead of two per series, it
  re-read the whole category list and the whole merge table for every series, it rebuilt the
  edited-titles cache once per series when a backup carries an edit for most of the library, it
  compared known URLs against a list instead of a set when ordering the restore, and it kicked off
  a full library refresh while the restore was still writing - both fighting over the same
  database. All of that is now proportional to how long the restore takes rather than to how many
  series it contains.
- Category sorting now reaches the subcategories too. Pick an order in Edit Categories and the
  Library's subcategory chips, its folder layout and the category filter list all follow it, at
  every level and without breaking the parent-child nesting.
### Fix
- **Finishing a series counted again every time you reopened it.** Re-reading the last page of a
  series you had already finished counted another completion, so "Complete 5 manga" could be earned
  from a single series opened five times. Starting a real reread of a series releases the claim, so
  finishing it again counts as the new work it is.
- **The three "complete a manga in <mode>" achievements unlocked from a single chapter read.**
  Left to Right, Right to Left and Vertical Scroller all say "complete a manga", and unlocking any
  of them needed only the first chapter read in that mode. They now unlock when a series is actually
  finished, and the LTR weekly tier counts completions rather than chapters. "Versatile Reader"
  (finish the same series both as a pager and as webtoon) was unreachable and now works.
- **Incognito reads never counted.** The reader is the one achievement source that fires in
  incognito, but its hook sat inside the block that deliberately skips incognito - so Shadow
  Reader and the incognito daily challenge could not be earned at all. Library counts (organic
  chapters, streaks, binge) are still left alone, as intended.
- **Panorama Lover could not be earned.** The double-page hook existed but nothing ever called it.
  It now fires from the one place both the pager and WebGPU viewers report a two-page spread.
- **Reread Nostalgia, Eternal Return and Living Library were unreachable.** They are lifetime tiers
  ("reread 5 / 20 / 100 manga") but were checked against the size of a single rereading action,
  which is always one. They now read a lifetime counter.
- **"Perfect Week" rewarded ordinary reading.** Its requirement was seven days on which you read
  *anything*, ever, rather than seven series finished in seven days. It now measures the latter, and
  "Binge Guilt" (a 100-chapter day followed by three days of nothing) works too.
- **Speed Reader could not be earned.** Nothing ever started the clock it measures. Finishing a
  20+ chapter series inside an hour now works, timed from your first chapter read of that series.
- **A day with a skip still earned "Perfect Day".** That challenge reads "10 chapters without
  skipping" and nothing recorded the skipping half of it.
- Two challenges matched the wrong hours: Morning Pages fired before 6 AM rather than before 8, and
  Midnight Pages fired in the small hours rather than after 11 PM. "Streak Keeper" also unlocked on
  the first day read rather than the second.
- Eight more achievements had no way to be earned and now do: Cutout Connoisseur, AST Searcher,
  Offline Polyglot, Tag Explorer, Translation Sprint, Perfect Week Challenges, Double Mango and
  Pineapple King.
- A wipe now also clears the new completion bookkeeping, so re-earning after a wipe behaves like a
  fresh install instead of being silently skipped.
- **Marking a chapter unread not resetting the position in the new reader.** Read a chapter partway
  through, mark it unread, and the WebGPU reader reopened it at the page you had stopped on. The
  saved page was being reset correctly, but that reader also keeps the page, offset and zoom in
  its own store, which is how it restores your place across a process kill - and it was trusting
  that copy without checking that a position still existed. The other three readers were already
  correct.
- **Every list losing its place on the way back.** Opening a series from a browse list or a search
  and pressing back dropped the list at the very top instead of where you left it. The screen was
  coming back under a saveable-state key the app had never seen, so its scroll position - and
  anything else it remembered, such as how far a collapsed list was - started over. The fix keeps
  one slot per screen so it finds its own state again, while still giving two copies of one screen
  separate keys, so re-opening a settings screen quickly cannot crash.
- **History opening chapters the merge hides.** Continuing a series from History could hand back an
  upload the details screen does not show. With scanlator A holding chapters 1-2 and B holding
  1-70, finishing A's chapter 2 and resuming from History opened B's chapter 1 instead of chapter 3.
  History now uses the same rules as the details list, the reader and the library's continue button.
- **Library badge and History progress ignoring the smart scanlator merge switch.** Turning the
  merge off un-merges the chapter list, so every upload is a chapter again - but the badge and the
  History "read of total" kept counting one chapter per number, so two scanlators each holding
  chapters 1-10 showed 20 chapters in the list and 10 on the badge. Both follow the switch now,
  which also fixes the unfinished-manga filter in History.
- **Blacklisting one upload of a chapter a scanlator published twice.** Blacklisting a single upload
  instead of every upload of that chapter and scanlator left it in the counts: read the version you
  had blacklisted and the chapter still counted as read while its unread twin was the one on screen.
  The badge and the History progress now honour both forms of blacklist entry.
- **Back on a screen opened from an extension dropping you into the Library.** Open a source from
  Browse and press back and you were sent to the Library tab, with the source screen still sitting
  behind it - so the next back press walked you back into the source you had just left. The
  "back goes to the Library tab" shortcut was competing with the back stack pop and could win the
  press; it now takes it only when there is nothing left to pop.
- **Migrating a single entry was a dead end that called the series "not found".** With "Use smart
  search" off - the default - migrating one entry opened the migration list, which immediately
  opened a search screen of its own on top of it. The list underneath was already showing "No match
  found" for the entry, because the smart search had been skipped on purpose, so it claimed nothing
  matched on any of your sources however many you had configured. Getting out took backing out of
  the search and then answering an exit dialog, and it could simply drop you back into the search.
  A lone entry with smart search off now opens the search screen itself, which searches every
  source you selected for migration, and one back press leaves it.


## [v1.23.21] - 2026-10-14
### Fix
- Library unread badge no longer disagrees with the chapter list. When several scanlator groups
  upload the same chapter, the badge counted a chapter as read as soon as *any* group had it, but
  the merged chapter list showed whichever group ranked best for coverage - so a series could read
  as 7 unread on the cover while every chapter in its list looked unread. The merge now keeps the
  upload you already read or bookmarked, so the two always agree.
- History tab no longer counts blacklisted chapters towards "read of total", matching the library
  badge and the chapter list.
- History tab's bookmarked progress no longer misses bookmarked extras and oneshots, which have no
  chapter number to be counted by.
- The **Smart scanlator merge** switch now takes effect straight away instead of needing a restart,
  including for the library's continue-reading button. Turning it off now really does list every
  upload from every group, duplicates included, so the chapter list behaves like a stock reader.


## [v1.23.20] - 2026-10-14
### New
- **Toggle for the Cloudflare bypass banner** (Advanced → Network, on by default). Turn it off to
  stop the banner naming the host and solver while a challenge is being worked around. The status
  bar follows the same setting, so nothing is left showing.
### Improve
- **Duplicate chapters from every scanlator are hidden by default.** If several groups publish the
  same series, you saw each chapter once per group and had to hide the extra groups by hand, one
  manga at a time. Now one upload per chapter number is shown, preferring the group with the most
  complete coverage — the one that actually holds the story without holes, not simply the one with
  the most files. Series where the groups split the work (one has 1–50, another 51–100) keep every
  chapter: the preferred group wins the numbers it has and another covers the rest, so nothing goes
  missing. Drag the order on **Scanlator priority** to pick a different favourite, or turn the whole
  thing off with **Smart scanlator merge** in Advanced settings (it is now on by default, and the
  toggle in the details menu does what it says).
- **Scanlator priority now explains itself.** When no order is set, the list shows the order the
  merge is actually using, labelled as automatic, instead of listing group names alphabetically.
### Fix
- **Chapter counts were inflated by duplicate uploads.** A series carried by several scanlator
  groups reported several times its real length — in the library entry, in the unread badge, and in
  the History tab's progress. The count now counts chapters rather than uploads. Chapters with no
  number (extras, one-shots) are still counted individually, and the achievements that read the
  chapter total now reflect the real chapter count rather than the upload count.
- **Crash when re-entering a settings screen.** Opening a settings category, going back, and
  re-opening it before the previous animation finished could crash the app with
  `IllegalArgumentException: Key ... was used multiple times`. The settings categories are
  singletons, so the second visit reused the first one's saveable-state key.
- **Library entries showing "source not installed" after a restart.** Manga already in your
  library could show the red warning badge until you restarted the app a second time, even
  though the source was installed. The app was reading its source list before the installed
  extensions had finished loading, so every library entry briefly resolved to a placeholder.
- **"Clear logs" not clearing anything.** The crash logs are written to the external cache
  directory, but both the auto-pruning and "clear logs" were scanning the internal one, so old
  logs were never deleted and the "clear logs" action reported a count of zero.
- **WebGPU reader losing the device after a recovery attempt** (`CreateAndroidSurfaceKHR` /
  `VK_ERROR_NATIVE_WINDOW_IN_USE_KHR`), blank pages on some devices. The retry built a new
  swapchain before releasing the old one, so it collided with itself.
- **Blacklisting a chapter hid every upload of it.** When a scanlator had published the same chapter
  number twice, blacklisting one version took the other with it. Existing blacklists are untouched.
- **Edit Categories and the Library disagreed on category order.** Only the subcategory rows were
  being sorted by the selected mode, so with any mode other than Manual the top-level rows stayed in
  database order while the Library's tabs used the chosen one (#40).
- **Editing a cover left the previous one on screen until the app restarted.** Every library layout
  remembered the cover for a series keyed on its id alone, so the new cover's cache key was never
  seen and the already-loaded image kept being reused.


## [v1.23.19] - 2026-10-13
### New
- **The WebGPU reader now sizes its memory to your device and to what is actually free.** Previously
  the reader only knew one thing about your hardware — whether it had less than 3 GB of RAM — and
  made every caching decision from that single yes/no. It now measures your phone's real size class
  and keeps an eye on how much memory is genuinely available, adjusting how many pages stay decoded
  and how much tile cache the renderer keeps. A phone with 12 GB that is currently memory-starved
  behaves like the small phone it is right now, and recovers when memory frees up. If you have been
  manually shrinking caches or avoiding long strips on a low-memory device, this should need less
  fiddling.
### Improve
- **Memory pressure now recovers instead of leaving the reader permanently hobbled.** A single
  system memory warning used to permanently reduce the renderer's internal tile cache for the rest
  of the session, with nothing able to bring it back — so one unlucky warning while using another
  app cost you smoother zoom and scrolling until you closed the reader. The reader now restores the
  full cache once memory returns, after a short settle period so it cannot flicker between states.
- **The reader now reacts to memory pressure between system warnings.** Android only sends its
  memory warnings when it is already deciding which app to reclaim, which is usually after the
  reader has already absorbed the spike. A separate background check now watches for pressure
  directly, which matters most for very tall webtoon strips — each one costs roughly 47 MB of
  graphics memory.
### Fix
- **A harmless HDR warning was logged as an error on every screen change.** On displays that do not
  support HDR brightness tracking at all, the renderer logged an exception with a full stack trace
  each time a page was opened. The display was answering correctly; the renderer was asking about a
  feature that device does not have.
- **Some large pages failed to open, showing only a black screen with
  `resize: dst too large`.** The WebGPU renderer capped the first mip-map step at a much smaller
  size than the rest of the pipeline accepts, so any page between roughly 16 and 64 megapixels —
  a large scan or double-page spread — passed every size check and then failed outright while
  building its zoom levels. The limit now matches the rest of the pipeline, so those pages open.
- **Long webtoon strips could get stuck on a loading placeholder and never recover.** After
  scrolling far into a strip, one page would spin forever and the reader would keep retrying it
  in the background. The retry was silently dropped by the page loader, so the page's cached
  image was never re-cut, and the internal retry counter reset itself each time so recovery
  never gave up. Affected pages now reload, and a page that genuinely cannot load stops retrying
  instead of spinning for the rest of the session.
- **Long webtoon strips no longer re-download themselves over and over.** A tall strip needs
  roughly twice its own size in image cache — its original plus the pieces it is cut into — so on
  the default cache size it could re-fetch and re-cut the whole strip every time you scrolled,
  leaving a page that never settled. Segments are now restored one at a time when the cache is too
  small to hold them all, and giving up is bounded and reported rather than looping.
  **Tip:** raising *More → Reader → Image cache size* above the default (250 MB and up) is still
  the best fix for very tall strips.


## [v1.23.18] - 2026-10-13
### New
- **Find duplicates:** the library overflow menu now has a **Find duplicates** screen listing every entry that has more than one copy, with how many each one has. Tap one to open the existing duplicate review, where you can open either side or jump straight to migration. Matches on shared titles and on the same entry being tracked on the same tracker
- **FlareSolverr support:** if a site's Cloudflare check defeats the built-in solver, point Houri at your own FlareSolverr instance (Advanced -> Network), turn it on, and it will use that first - retrying with the user agent the challenge was solved under. There is a **Test FlareSolverr and update user agent** action that solves a probe page so you can confirm the setup works, and a banner naming the host and method while a challenge is being worked around. Falls back to the built-in solver whenever the instance is unreachable, and resets the user agent if it stops answering
- **Per-category update schedules:** a category can be given its own update interval — daily, every few days, weekly, fortnightly, or monthly — from the category manager, alongside the status binding above. Entries in that category stop being checked on every global run until their interval has elapsed, and where an entry sits in several categories the shortest interval wins so a daily request is never overridden by a monthly one. Categories left on the default keep following your global library update setting exactly as before
- **Smart categories:** a category can now be bound to a tracker status from the category manager, and entries move between those categories on their own — finish chapter 1 on a remote tracker and the entry leaves *Wish to read* and lands in *Reading* on its own, and finishing the last chapter moves it to *Completed*. Categories you have not bound are never touched, so a folder you happen to have named *Reading* is left alone. This follows a status change however it happens: while you read, when you change the status yourself, or when a tracker refresh picks up a change you made elsewhere
### Fix
- **Smart scanlator merge:** chapters hidden by a merge no longer show up in the reader or get reached with next/previous — and resuming a hidden chapter from history no longer fails to open
- **Manga covers:** the full-screen cover view now updates the moment you replace a cover, instead of sitting on the previous image until you reopen it
- **Long strips (manhua):** a page too tall to decode is no longer split into a strip with a missing chunk when memory runs out mid-split — the whole image is used instead, which is slower but correct, instead of a scrambled page that looked fine
- **WebGPU reader:** if the GPU is lost while the app is in the background, the reader now retries rebuilding it instead of giving up on the first attempt — which used to leave every page broken for the rest of the session
- **Chapter loading:** pages are no longer fetched twice. The page queue never de-duplicated and two workers drained it at once, so most pages in a chapter were downloaded two or more times — doubling data use, and pushing fragile sources into failures that caused yet more retries
- **WebGPU reader:** pages no longer stick on a blank placeholder when the GPU device is lost — recovery now asks for a fresh GPU adapter instead of reusing the one the lost device already consumed, so the reader picks back up instead of failing every page decode for the rest of the session
- **WebGPU reader:** a page whose image could not be read is no longer able to wedge the reader for the whole session — a second attempt now discards the cached copy and fetches it again, and a page whose download silently dies is recovered after 30 seconds instead of hanging forever with no indication anything went wrong
- **WebGPU reader:** double-page mode keeps its height matching for spreads whose partner is slow to load, instead of giving up after a fraction of a second
- **WebGPU reader:** low memory now also releases the tile and filter caches, not just decoded pages — on a device with under 2 GB of RAM the next frame no longer has to rebuild them from scratch
- **WebGPU reader:** dual-page mode no longer risks drawing a spread whose textures were already freed, and the loading pineapple no longer keeps showing a texture bound to a GPU device that has been lost
- **Tracker manager:** the logout icon no longer overlaps the logged-in checkmark and the priority star — it now appears only while you're actually swiping a row, and steps aside for those icons the rest of the time
- **Category sorting:** sorting categories by **Most items** or **Least items** now reorders the library's category tabs too, instead of quietly falling back to your manual order while the management screen showed a different one
- **Search:** a new **Exact author** toggle narrows search results to entries whose author or artist is the name you searched for, so looking up *John Smith* no longer turns up *John Smithson*. Sources decide what to return, so this filters what they returned — if a source never fills in creator names, nothing will match until it does. Off by default; search works exactly as before
- **Storage:** downloaded update APKs now clean themselves up. The APK left in the app's cache was never removed, so it survived every update and kept occupying tens of megabytes; it is now deleted once the version it represents is the one you're running, and an update you download and then never install is dropped after a week


## [v1.23.17] - 2026-10-11
### New
- **HDR reading in the WebGPU reader:** HDR-tagged pages (PQ/HLG and UltraHDR) now display with the display's full brightness on HDR-capable panels, and tone-map cleanly to SDR everywhere else
- **Tracker manager:** swipe a tracker row left to log out of it, even when that tracker still shows as logged in — handy when a session has expired or stale credentials are lingering and tapping the row only reopens the login screen
### Improve
- **WebGPU continuous reader:** border cropping now works in the strip too — with crop-borders on, each page's borders are trimmed away and the page fills the screen edge to edge, like the paged reader already did
- **WebGPU reader:** smoother long sessions — GPU objects are released properly as pages scroll by, tiles cache up to what your device's texture limit allows, and an idle reader no longer redraws frames for nothing
- **WebGPU reader:** the webtoon pinch-to-zoom preference is now honored in the WebGPU reader too, and pinch can be turned off outright
- **WebGPU reader:** more stable on device loss and surface resize — a lost GPU device is recovered without crashing, and rotating or resizing rebuilds the surface instead of showing a stale frame
### Fix
- **Library folders:** folder tiles now render as compact rows in list display mode instead of a single oversized card that swallowed most of the screen — the grid layout is unchanged
- **Discord rich presence:** the presence art and download button are Houri's own — it was still showing Komikku's emoji, and its download button sent you to Komikku's page instead of ours. Every screen shares the app icon for now; distinct per-screen art comes back once Houri has its own assets in the Discord developer portal
- **WebGPU page transitions:** turning a page no longer crashes when the GPU refuses to create a texture view — the transition cache falls back to drawing without cached art instead of failing the whole turn
- **ComicK tracker:** rebuilt on Comick's official API — logging in opens your browser (grant "keep me signed in" and library write access), and your reading status, progress, and ratings sync properly again. Existing ComicK links need a one-time re-login and re-link to your Comick account
- **Google Drive sync:** signing in no longer crashes or gets stuck when Google rejects the authorization code — failures now show a clear message in the app, and re-linking keeps your existing refresh token instead of silently breaking sync
- **WebGPU long-strip reader:** very tall pages (long webtoon strips) no longer fail to build their preview levels — zoomed-out reading through them is sharp again instead of sampling the full-resolution image
- **WebGPU long-strip reader:** scrolling down through a page no longer drops an unrelated page in below it, cutting the current page off, until you scroll far enough for it to correct itself


## [v1.23.16] - 2026-10-10
### Fix
- **Fixed pages refusing to load in the WebGPU long-strip reader.** A long page is cut into pieces to be decodable, and if the device dropped one of those pieces from its cache the reader kept asking for a file that was no longer there and never recovered — leaving a blank strip at that spot, most often when a new chapter started loading while you were still scrolling. The reader now asks the source for that piece again, and a page it has retried too many times is handed back to be retried instead of being abandoned for the rest of the session.


## [v1.23.15] - 2026-10-09
### New
- **Opening a category now lands on its first subcategory when the "All" subcategory is turned off.** Previously it still showed every title from every subcategory combined, with no "All" chip left to narrow it back down.
### Fix
- **Fixed global search hanging the app with covers that never load.** Using global search left every result spinning and the app unusable until it was force-closed; searching a single source was unaffected. Only on Houri and Komikku previews — Mihon and Chimahon were never affected.
- **A single broken page no longer stops every other page from loading.** The WebGPU reader decoded everything on one thread, with no timeout at all in the path, so a page that never finished decoding left the rest of the chapter queued behind it indefinitely.
- Pages that arrive as something other than an image — an error or captcha page served in place of a real page — are now spotted before they reach the image decoder, and your source is asked for that page again instead of the reader giving up on it.


## [v1.23.14] - 2026-10-09
### New
- **Are you? Are you? Coming to the tree?** They strung up a man, and say he murdered three.
### Fix
- Source grids no longer switch to a list-shaped skeleton while the next page is loading — the loading placeholders now stay grid-shaped, matching the covers already on screen.
- **Fixed the continuous reader running away from you at a chapter boundary.** Scrolling into the next chapter kept snapping back, so the pages moved down faster than you could read them.
- **Fixed pages in a chapter you had scrolled into sometimes never loading.** They sat on their placeholder until you left and came back.
- Removed covers are now actually kept for as long as the retention setting says. The setting could be ignored entirely: the timestamp that starts the window was not always written, several ways of leaving the library skipped it, and a details refresh could delete a cover belonging to a title already removed.
- **Fixed your zoom being forgotten when you reopen a chapter.** In continuous and webtoon the zoom-out slider goes down to 1%, but anything under half size was being rounded up to 50% before it was saved — so a chapter you had zoomed out to read reopened zoomed in. Very zoomed-in paged pages were clipped for the same reason.


## [v1.23.13] - 2026-10-09
### New
- **I'm in the thick of it and everybody knows.** Hey guys, Houri dev here. I'm in the thick of it
  and everybody knows. Or do they? This release answers the age-old question of "even though we can,
  should we?". Spoiler alert: the answer is **yes**.
### Improve
- **A new manga cover now shows up straight away** instead of the old one sticking around until the
  app is restarted. The details screen was holding its own copy of the series, so the change was
  written to disk but the screen kept drawing the cover it already had cached.
- **The nudge buttons in the cover editor now move the framing the way they say.** Pressing "up"
  showed more of the cover below the one you were looking at rather than above it.
- **Scrolling in the WebGPU reader no longer queues behind the page loader.** Deciding whether a
  double-page spread needed its two halves resized to match ran on the same lock the loader uses to
  release finished pages, so every frame waited on it - and the placeholder spinner took that same
  lock just to draw itself. Both now read the values they need directly, so the loader can catch up
  without the display waiting.
### Fix
- **Fixed a removed cover lingering on screen after removing several titles at once.** Bulk removal
  stamped the cached files so their retention window started at the removal, but never recorded the
  new timestamp on the title, so the cover that was still visible was never told to redraw. Removing
  a single title at a time already did this.
- **Fixed wide pages getting a black band above and below them in WebGPU double-page mode.** The
  "rotate to fit" option that turns a landscape page a quarter turn so it fills its half of the
  spread worked in the classic reader but did nothing at all in the WebGPU one, which left the page
  fitted to half the screen width - a short strip with empty space above and below it that only
  zooming cleared.
- **Fixed tapping a tap zone in vertical reading mode dragging the page sideways instead of turning
  it.** When "navigate by panning" was on (the default) and you were zoomed into a page - so there
  was room to pan sideways - the next/previous zone slid the page across its own width rather than
  moving down to the next page, which dropped you in the opposite corner. Zoomed-out reading was
  unaffected, which is why it looked intermittent.
- **Fixed the reader getting stuck on a page until the app was force-killed.** Scrolling quickly
  could leave a page permanently loading, and the recovery meant to rescue it was the thing keeping
  it there: every retry created the condition that scheduled the next one, so it span thousands of
  times a second and dragged the memory up with it. Retries are now spaced out, and a page that
  keeps failing is rebuilt from scratch rather than retried against state that has wedged it.
- **Fixed the cover editor carrying on working after the page it was cropping was gone**, which
  logged a spurious failure while tearing down.
- **Fixed the cover dialog going on showing the cover you had just replaced.** Setting a custom
  cover from the crop editor left the full-screen cover viewer drawing the old image, because it
  only asked for the cover once per manga rather than once per cover - so the change did not show
  up until you left the screen and came back.
- **Fixed blank and frozen pages in the WebGPU reader.** A page that was still being prepared when
  it scrolled past - or when a very tall page was split into pieces - had its image uploaded to the
  GPU and then dropped without being released, so every occurrence quietly cost tens of megabytes
  of graphics memory until the reader began failing to load pages at all.
- **Fixed the WebGPU loading screen sometimes showing nothing but a black page.** The percentage
  readout was drawn only once the loading icon had finished uploading, so on the very first frame
  after opening a chapter - or after the GPU was reset - there was no icon *and* no percentage.
- **Fixed WebGPU webtoon chapters skipping pages after a very tall page was split.** Each piece of
  a tall page is shorter than the space reserved for it while it loads, so a reader partway down
  one could be left looking at the next page while the reader still thought they were on the
  previous one - and nothing corrected them.
- **Fixed a page removed by a split never being replaced in the WebGPU continuous reader.** The
  page that had been split away stayed cached as a window entry, so its slot stayed blank and
  everything below it was out by the number of new pieces until the reader moved on.
- **Fixed the WebGPU reader briefly reporting the wrong page and skipping one right after a tall
  page was split**, because the page it re-anchored onto was measured from a position that no
  longer existed.
- **Fixed a page rescued from being stuck still leaking its graphics memory.** Six different places
  gave up on a page, and each released a slightly different set of things - so a page dropped by the
  "this page is wedged, rebuild it" recovery kept the two images held aside for the before/after
  comparison. They now all release the same things in the same order.


## [v1.23.12] - 2026-10-08
### New
- **I'm in the thick of it and everybody knows.** Hey guys, Houri dev here. I'm in the thick of it and everybody knows. Or do they? This release answers the age-old question of "even though we can, should we?". Spoiler alert: the answer is **yes**.
### Improve
- **A new manga cover now shows up straight away** instead of the old one sticking around until the
  app is restarted. The details screen was holding its own copy of the series, so the change was
  written to disk but the screen kept drawing the cover it already had cached.
- **The nudge buttons in the cover editor now move the framing the way they say.** Pressing "up"
  showed more of the cover below the one you were looking at rather than above it.
### Fix
- **Fixed the reader getting stuck on a page until the app was force-killed.** Scrolling quickly could
  leave a page permanently loading, and the recovery meant to rescue it was the thing keeping it
  there: every retry created the condition that scheduled the next one, so it span thousands of
  times a second and dragged the memory up with it. Retries are now spaced out, and a page that keeps
  failing is rebuilt from scratch rather than retried against state that has wedged it.
- **Fixed the cover editor carrying on working after the page it was cropping was gone**, which
  logged a spurious failure while tearing down.


## [v1.23.11] - 2026-10-08
### Fix
- **Fixed a crash that closed the app while reading in the WebGPU reader.** 1.23.10 released the
  small graphics buffers used to draw the loading spinner and chapter title card as soon as they
  were drawn, which is earlier than the GPU had actually read them. The submitted work then referred
  to freed memory and the app went down — most visibly on opening any chapter that still had pages
  loading. Those buffers are now released only once the GPU reports that frame finished.
- **Fixed the continuous reader slowly losing framerate over a long session.** Placeholder pages
  drew with graphics memory that was never handed back, so a long chapter accumulated more and more
  of it and the frame rate sagged the longer you read. The memory is now reclaimed, and a frame that
  draws none of it costs nothing.


## [v1.23.10] - 2026-10-07
### Improve
- **Improved scrolling smoothness in the continuous (webtoon) reader.** Finding a page's position
  inside a chapter scanned the whole page list from the start every time, and the reader did dozens
  of those per frame, so a long chapter paid for it constantly. Positions are now looked up directly,
  and pages inside a single frame are resolved once instead of re-walked.
- **The "an extension is not working" popup now shows which extension it is about** — its icon, plus
  its version, repository and failing source as compact tags — so you can tell at a glance what broke
  and whether the "don't show again" you are about to press applies to the version you are running.
- **Extension error popups can now be switched off.** Each popup offers a button to disable them,
  which asks for confirmation first and explains that broken extensions stay listed on the extensions
  page. You can also switch them off, and back on, at any time in **Advanced settings**.
- **The error shown underneath a failing extension can now be switched off.** It is on by default,
  and is separate from the popup switch: turning this off hides only the message under the row and
  leaves the popups working. Failures are still recorded either way, so switching it back on shows
  what is broken right now rather than starting from scratch.
### Fix
- **Fixed the continuous reader jumping forward a screen and a half every time a page finished
  loading.** A page still loading only reserved one screen's worth of height, so the moment the real
  image arrived — often several times that height on a webtoon — your position inside it was scaled
  up to match, and the view leapt ahead mid-scroll. Your place in the strip now stays put.
- **Fixed pages of the next chapter never appearing when scrolling into it.** Reaching a chapter
  boundary made the reader repeatedly switch the active chapter back and forth as you crossed it,
  which restarted the chapter loader and rebuilt the page window on every pass, so the pages you were
  scrolling towards got dropped and re-queued. The chapter now advances once, as you enter it.
- **Fixed the whole screen being left blank when zoomed out in the continuous reader.** The reader only
  ever drew a fixed number of pages either side of the current one, so zooming out far enough that
  more pages fit than that cut the bottom of the screen off. It now draws as far as the zoom requires.
- **Fixed stuttering that got worse the longer you read.** Placeholder pages — the loading spinner and
  the chapter title card — allocated graphics memory on every frame and never released it, so a long
  session slowly exhausted it and the framerate sagged. That memory is now released each frame.
- **Fixed a long pause the first time a chapter name in a non-Latin script was drawn**, such as a
  Japanese or Korean title. Each character had to be rendered and uploaded individually, rebuilding the
  whole font atlas repeatedly while the screen was live. Characters are now prepared in one batch.
- **Fixed the continuous reader resuming in the wrong place.** The saved scroll position was restored
  by an absolute page number where a position relative to the current page was expected, so reopening a
  chapter could land you well away from where you left off.
- **Fixed a brief flash of a stale image when opening a chapter**, where a frame could reach the screen
  before anything had been drawn into it.
- **Fixed extensions being listed as broken when nothing had actually gone wrong.** The error list
  treated any message written at error level as a failure, including a few cases that are deliberate
  checks rather than faults — most visibly an extension containing NSFW content while that content is
  switched off, which marked a perfectly working extension as broken and offered to report it. Only
  real exceptions are tracked now.
- **Fixed extensions staying marked as broken on the extensions page after they started working
  again.** Most failures that reach the error watchdog are a site being briefly unavailable rather
  than a scraper that needs fixing — a server error, a rate limit, a dropped connection — and those
  clear up on their own. An extension that answers again now drops its error, so it stops being
  listed as broken once it is plainly working.
- **Fixed the extension error popups appearing during a global search.** Searching every source at once
  meant each broken one produced its own popup, and they queued up over the results. Global searches no
  longer raise them; the failure is still listed on the extensions page, and using a broken source on
  its own still reports it.
- **Fixed the long-strip reader running out of memory and stalling the whole app.** A page that could
  not finish loading was put back into the decode queue on every single frame, because the reader
  re-queued anything it was asked to display. Each attempt allocated fresh decoder and network memory
  and forced a garbage collection, so the heap filled as fast as it could be emptied — which took down
  the entire app, not just the reader. Pages are now queued once, when the reader first asks for them,
  and recovery is left to the existing check that runs only when something actually changes.
- **Fixed pages in the long-strip reader staying at 0% while the pages around them loaded.** The page
  you were looking at was placed at the back of the decode queue instead of the front, so anything the
  reader had speculatively loaded jumped ahead of it and it could wait indefinitely. The page on screen
  now decodes first.


## [v1.23.9] - 2026-10-07
### Fix
- **Fixed the long-strip reader running out of memory and stalling the whole app.** A page that could
- **Fixed pages in the long-strip reader staying at 0% while the pages around them loaded.** The page


## [v1.23.8] - 2026-10-07
### Fix
- **Failed extensions now say why they failed.** The extensions screen showed only a spinner or an
- **Fixed an extension keeping a stale icon after being updated.** The icon was cached for as long as
- **Fixed the update button doing nothing on some extensions.** If a repository was re-signed, the
- **Fixed extensions failing to load leaving the app waiting indefinitely.** If loading extensions
- **Failed extensions can now be reported to the right place.** An extension that fails usually means
- **Extensions are found faster in large libraries.** Looking up which extension owns a source
- **Fixed a spinning gap in the long-strip reader where a page should be.** Scrolling quickly could
- **Fixed two-page spread height matching silently giving up on a chapter.** If a side was still being
- **Loading pages in the long-strip reader now show the percentage as well as the spinner**, including


## [v1.23.7] - 2026-10-06
### Improve
- **The reader no longer wakes the processor every quarter-second while you are not waiting for a
- **Backup sync now waits for a low battery to charge, like the other scheduled jobs do.** Sync was
- **Cover images no longer fade in when you have turned animations off system-wide.** The fade still
- **The feed tabs now show a layout-shaped placeholder while loading** instead of a spinner. The
### Fix
- **Fixed pages in the long-strip reader loading forever.** A page could end up marked as still being
- **Fixed already-loaded pages unloading themselves in the long-strip reader.** Scrolling out of a
- **Fixed the cover preview freezing when it opened.** Building the preview image generated its whole
- **Fixed a transparent strip down the right and bottom edge of upscaled pages on the NPU path.** The
- **Fixed upscaled pages occasionally coming back partly grey or not displaying at all.** A cached page
- **Fixed upscaling silently giving up for the rest of the session on devices where a model cannot
- **Fixed the app closing on very large pages when upscaling.** Two size checks in the native upscaler
- **Fixed upscaled pages running out of memory on large or long pages.** Encoding a page back to an
- **Fixed the library re-saving every cover colour on every pause.** Switching away from the app, opening
- **Fixed titles disappearing from library covers in rare cases.** A stored colour whose text-colour half
- **Fixed mangled library covers on rare covers whose header decodes oddly.** Such a cover could store a
- **Fixed library covers losing their colours when opening a series.** The grid colours were being


## [v1.23.6] - 2026-10-06
### Improve
- **The long-strip reader now loads pages in the order you read them.** Pages behind the current one
- **Library loading placeholders line up with the real grid.** The skeletons used wider spacing than
### Fix
- **Fixed duplicated and misnumbered pages around chapter boundaries in the long-strip reader.** When
- **Fixed the seek bar when reading between chapters.** The page list is now watched for all three
- **Fixed "pure black dark mode" on the Dynamic theme below Android 12.** The black override reached
- **Fixed cover-based theming below Android 12.** That release has no system palette to read a theme


## [v1.23.5] - 2026-10-05
### New
- **Removed covers can now be kept for a while.** Taking a manga out of the library used to delete
### Improve
- **Filling a series in from MangaBaka now brings the artist across.** Only the title was carried
- **Dynamic color now works below Android 12.** The Monet theme samples your wallpaper's own colours
- **Source pages now show placeholders shaped like the results you are waiting for.** Opening a
### Fix
- **Fixed losing your place in a chapter after updating the app.** Installing the update shut the app
- **Fixed very tall pages rendering on top of themselves.** An image too tall for the decoder is cut
- **Fixed very tall pages breaking a chapter after you left and came back.** A cut page was
- **Fixed the reader getting stuck on very tall pages.** Where a page was cut, the reader's page
- **Fixed the GPU reader jumping to the wrong page after a very tall page was cut.** It could send
- **Fixed very tall pages failing to reload after the image cache was cleared or trimmed.** Cutting
- **Fixed chapters with a very tall page resuming on the wrong page.** Splitting a page into pieces


## [v1.23.4] - 2026-10-04
### New
- This release is dedicated to my ex-friend **Juliana**, slinger of slurs and the core reason our world doesn't have gender equality yet. If it wasn't for her pissing me off so much, this release would've taken a lot longer.
- **Export the database as a file.** Settings > Data > Export now has an "Export database" row that writes a self-contained snapshot wherever you choose. Recent changes are folded in first, so the file carries every commit rather than only what happened to be flushed — importing it elsewhere no longer silently rolls back the last few chapters read, category edits and tracker rows. The snapshot is verified before the export is reported as successful.
### Improve
- **Every library layout now has its own loading skeleton.** The library and the source browse pages load into a placeholder that matches the layout you selected — list, compact grid, cover-only, comfortable, panorama, staggered — instead of one generic grid that made the content jump as it arrived.
- **Category management fits small screens.** On phones the row actions collapse into an overflow menu so the category name stays readable, and the bulk-action bar wraps instead of pushing its last two buttons off screen.
### Fix
- **Edits to manga entries now persist.** Titles, authors, artists, covers, descriptions, tags and status you set from the manga info editor were kept in a file outside the database, so they were lost on uninstall, missing from database exports, and invisible to backups. They now live in the database alongside everything else. Existing edits are migrated in automatically the first time the app starts.
- **Dynamic theming works again.** Cover-based theming on the manga page and in the reader was sampling the palette from a second image request that failed whenever the cover could not be re-fetched, and the resolved colour was stored in a map that was not safe to read and write from different threads. The palette is now taken from the cover already on screen. Monet also picks up wallpaper and accent changes without a restart, and falls back to your own accent instead of an unrelated static theme on devices without the Android 12 system palette.
- **The "Start reading" button follows the cover colour**, with a label colour chosen for readability rather than for contrast against arbitrary cover colours.
- **Restoring a backup no longer floods the screen with achievement toasts.** Restoring writes every previously unlocked achievement at once; that was announced one toast and one sound at a time. A restore now produces a single summary line, and an unlock earned during normal reading is never announced twice.
- **Achievements are more reliable.** "Wipe achievement data" now also resets the lifetime counters it left behind (downloads, categories, sources, tracker updates and friends), so those achievements can actually be earned again; finishing a manga reports one combined result instead of two; the collection achievements no longer re-scan the whole catalogue on every unlock; and a failure in one step of a chapter read no longer discards every step after it.
- **Turning a setting on now unlocks its achievement even if you turned it on before.** The preference watchers used to only react to changes and were not installed at all while achievements were disabled, so settings restored from a backup never qualified.
- **On-device AI settings follow the RAM gate consistently.** Disabling the gate from About now takes effect immediately rather than after a restart, and the upscaler model downloads are hidden on devices the gate is protecting instead of being offered and then crashing.
- **The tracking filter finds tracked entries again.** The library's tracked/untracked filter and the per-tracker filters were reading a track list that had been filtered down to a single tracker, so anything you tracked elsewhere looked untracked.
- **The tracker dialog stops flickering.** It rendered your locally stored tracking data and then swapped the whole thing for freshly fetched data a moment later. It now refreshes first and shows one set of information.
- **Long webtoon strips no longer duplicate as you scroll.** A page too tall to decode is cut into segments; the cut could run twice for the same page and append a second copy of every segment, so the page count kept growing. The page counter and the saved reading position also indexed the page list with a segment's synthetic index, which broke both once a strip was cut.
- **Extra-long webtoon strips no longer show the wrong page.** Only strips far beyond the usual length were affected: when a strip needed more than sixteen segments, the numbering for its segments ran into the numbering range of the page after it, and the reader could hand back a page from elsewhere in the chapter. The reserved range per page is now large enough that a strip has to be implausibly long to reach it.
- **Dynamic theming applies to the manga page again.** The colour was being sampled from a cover held in GPU memory, which cannot be read directly, so the theme stayed untinted on most devices. It is now sampled from a readable copy, and doing so no longer stutters the screen as the details page opens.
- **Completing a manga no longer counts twice.** Both completion paths already recorded a cleared backlog entry, and the reader recorded it a second time on top, which inflated that lifetime counter and could award its tiers twice as fast as intended.
- **Restoring a backup still sends achievement webhooks.** Bulk mode was suppressing the outgoing webhook along with the toasts and sounds, so every achievement earned during a restore went unrecorded by anything listening for them.
- **Migrating your old manga edits no longer stalls on one stale entry.** An edit for a series no longer in your library made the whole import roll back on every launch, so none of your other edits were carried over. Each edit is now brought across on its own, and the ones with nothing to attach to are reported and skipped.
- **Monet theming on Android 8–11 no longer risks a crash.** Reading the wallpaper and system accent colours on those releases now checks the API level first.
- **Changing the translation target language or provider now takes effect.** Translated pages were saved under a key that recorded neither, so switching either kept serving the pages already on disk and the setting looked like it was being ignored. Saved pages are now re-used only when they match the language, provider and model they were produced with.
- **A dropped connection no longer silently ends translation for the chapter.** When a translation provider could not be reached, the page was recorded as "skipped" rather than "failed" — which meant it was never retried, the chapter never showed an error, and the pages stayed in their original language. Pages the provider failed on are now retried, and are offered for retry in the reader.
- **Pausing translation actually pauses.** Cancelling the worker immediately restarted it, so queued pages kept draining.
- **Long webtoon strips are only paid for once.** Pages produced by cutting a tall strip were never saved, so every visit re-ran detection, text recognition and the translation request for them. The reader also no longer creates folders merely by looking for a cached page.
- **The status chip reports on the page you are looking at.** It previously reflected the chapter as a whole, so a chapter with one translated page showed "Translated" over pages that were still in their original language — including pages that had deliberately been left untranslated.
- **Mihon upstream**: FlexibleAdapter now comes from its original `eu.davidea` 5.1.0 release on Maven Central instead of a JitPack fork that is no longer served.


## [v1.23.3] - 2026-10-03
### Improve
- Bookmark colours are now included in backups. Recolouring a chapter's bookmark was left out of the backup file, so restoring a backup brought every bookmark back without its colour
- The read button on a series page now takes its colour from that series' cover, in the same spirit as cover-based dynamic theming, with the label colour chosen to stay readable on a light or a dark cover
- The build without AI features no longer offers the AI settings categories — translation and upscaling are gone from both the settings list and settings search on that build, where they could not do anything anyway
- That build now also says so in About, and next to its version in settings, so it is not mistaken for a broken copy of the main app
- On-device text recognition can now use PaddleOCR v5 instead of the built-in model. It is about a third of the size — 16.6 MB rather than 43.6 MB — which is the trade: measured on a test page, it reads hand-lettered Japanese less accurately than the built-in model, so it is offered as a space saving rather than an upgrade, and the built-in model stays the default
- Cloud Gemini translations now ask for a structured JSON reply matched to the input lines, so the translation no longer depends on the model returning a recognisable list format. A line that cannot be translated for content reasons is replaced on its own instead of causing the whole page to fail
- The reader's translation indicator now names the stage a page is actually in — detecting, translating, inpainting, or typesetting — instead of guessing from the page count, and pages that could not be translated now say why rather than only that they were skipped
### Fix
- Fix all translations on a page disappearing when one text region was too short. A short line of translated text - a sound effect, a single character - made the image service throw while drawing, which discarded every other translation on that page
- Fix a crash when the on-device model failed to swap over. Unloading the previous model while loading a new one was not guarded like every other unload, so a failure there could escape the local translation path instead of falling back like other local model errors do
- Fix a failed model import being able to block all future model imports. An interrupted copy of a GGUF file could leave a partial file behind that the model list never showed, while it still counted against the storage needed to import anything else
- Fix translated text disappearing from pages sent to the image translation service. When a single word was too wide to fit a line, the wrapping code replaced the line it had just finished instead of starting a new one, so everything before that word was dropped from the page
- Fix AI translation quietly giving up on a page. When the on-device Gemini model returned nothing and offline fallback was turned off, the untranslated page was displayed and counted as done instead of being marked as failed, so it could never be retried. A provider that crashed also reported itself as an un-downloaded model, sending you to download something you already had. Pages that are too large or malformed to process now say so plainly
- Fix a page being dropped at the moment it is submitted for AI translation, which could happen when a chapter was translating faster than it was being read. A page rejected for being unreadable no longer leaves a stalled entry behind that held up the rest of the chapter
- Fix translated title and description being cached against the wrong provider when Gemini Nano was in use, so they were thrown away and re-translated on every visit
- Fix scrolling stuttering in the strip and continuous readers, especially on long manhwa pages. Every page shown was being read into memory twice over - once on the main thread, which could visibly stall the scroll - even though the only features that need a page in memory are off by default
- Fix a wide page's first half disappearing behind the page before it when rotating the device with Split wide pages on, so the two halves could not be tapped through in order
- Fix browsing E-Hentai getting steadily slower the longer you browsed. With detailed logging enabled the background log writer could stop and never restart, after which every log line piled up in a queue nothing was emptying
- Fix very tall long-strip pages failing to open with an error instead of loading. A page taller than 16384 pixels exceeds what the image decoder accepts, which is common for manhwa strips, so those pages could not be read at all. They are now cut into several pages at full resolution rather than scaled down, so the whole strip is readable with nothing lost


## [v1.23.2] - 2026-10-01
### New
- Sort categories and subcategories by name, date added, or date modified from the category management screen, and have that choice also drive the order of the library's category tabs. Sorting only changes what you see — your manual order is kept and returns whenever you switch back to Manual
- Filter global search results by source category, using the same categories as the Sources tab, alongside the existing pinned/all filter
- Color your chapter bookmarks to record why you kept them. Select a bookmarked chapter, tap the color button in the selection bar, and its bookmark icon takes that color. Bookmarks with no color look exactly as they always did, and colors stay on your device — recoloring doesn't mark the chapter as changed or re-sync it
- Filter a manga's chapter list by bookmark color, from the same chapter filter sheet as the unread and bookmarked filters. Pick any combination of colors to show only bookmarks tagged with one of them
- Nudge and zoom buttons on the custom cover crop screen. A directional pad shifts the image one small step at a time and a pair of buttons zooms in and out, and holding any of them keeps repeating — so you can line a cover up precisely on a small screen, or on a phone whose touchscreen drifts, instead of having to drag and guess
- The Private extension installer is now offered in release builds, not just debug ones. It is the only installer that needs no install permission at all, so it keeps working when Android's "install unverified apps" setting is unavailable or has lapsed — which now means a 24 hour wait and a warning that can switch itself back off. Extensions installed privately are checked against the repository signing key before they are stored, and a copy that fails verification is discarded instead of replacing a working extension
### Improve
- The History tab's "Resume" button now skips back to the most recent entry you haven't finished, instead of always starting from the most recent entry of all
- Achievements use noticeably less battery while you read. Checking whether you'd already earned something no longer rescans the whole achievement list for every single check, so finishing a chapter does far less work than before
### Fix
- Blacklisted chapters now stay blacklisted everywhere. They were already hidden in a series' chapter list and in the reader, but they could still turn up among your page previews, could still be queued for a download, and both the library's next-unread pick and the History tab's Resume button could still land on one
- Fix a restored backup resetting your per-series scanlator setup. Your scanlator priority, range rules, blacklisted chapters and light-novel flag were never written into the backup file, so restoring one handed every series back with those choices cleared and every chapter you had blacklisted visible again. Backups taken before now still restore - those series simply come back with no scanlator setup, the same as a fresh install
- Fix crash when tapping selected text in a manga description with a mouse after selecting it by touch
- Fix the cover crop preview starting too small and overshooting when zooming to fit, so the exported crop matches what you see
- Fix the library chapter badge counting blacklisted chapters that the details screen already hides
- Fix the AI translation toggle flashing on manga details pages when the feature is turned off
- Fix a thin line showing between pages in the WebGPU reader's webtoon mode
- Fix the release announcement posted on Discord listing raw commit names instead of the written release notes
- Fix being able to pinch the custom cover crop zoomed out past the point where the image fills the frame, which could save a cover with empty space around it
- Fix the private extension installer taking a very long time to load on a large extension collection. It was re-reading and re-checking every private extension once per installed extension, so the cost grew with the square of how many you had — with 100 private extensions it did 10,000 archive reads before loading a single source. Each private extension is now read and verified once, and those reads now happen in parallel instead of one at a time, so cold start and resume no longer crawl. This is what makes the private installer — the one that needs no install permission — practical on a big collection


## [v0.20.0] - 2026-06-27
### Added
- Add support for `tachiyomix` 1.6 extensions ([@AntsyLich](https://github.com/AntsyLich))
- Add support for `tachiyomix` extension index format ([@AntsyLich](https://github.com/AntsyLich)) ([#3349](https://github.com/mihonapp/mihon/pull/3349))
- Add vertical chapter navigator for long strip mode ([@AntsyLich](https://github.com/AntsyLich)) ([#3377](https://github.com/mihonapp/mihon/pull/3377))
- Add option to open app settings from system settings ([@JFronny](https://github.com/JFronny)) ([#3180](https://github.com/mihonapp/mihon/pull/3180))

### Changed
- Change the term "extension repo" to "extension store" ([@AntsyLich](https://github.com/AntsyLich)) ([#3349](https://github.com/mihonapp/mihon/pull/3349))
- Change the term "Obsolete" to "Orphaned" for extensions ([@AntsyLich](https://github.com/AntsyLich)) ([#3383](https://github.com/mihonapp/mihon/pull/3383))
- Remove text limit of manga notes ([@AntsyLich](https://github.com/AntsyLich)) ([#3410](https://github.com/mihonapp/mihon/pull/3410))
- Tweak Catppuccin color scheme for clarity ([@xenitane](https://github.com/xenitane)) ([#3436](https://github.com/mihonapp/mihon/pull/3436))
- Update app shortcuts to use app icon color scheme ([@NGB-Was-Taken](https://github.com/NGB-Was-Taken)) ([#2835](https://github.com/mihonapp/mihon/pull/2835))

### Improved
- Batch database operations during backup restore for improved performance ([@Lolle2000la](https://github.com/Lolle2000la)) ([#3267](https://github.com/mihonapp/mihon/pull/3267))

### Fixed
- Add missing `outlineVariant` color to Nord theme ([@CompileConnected](https://github.com/CompileConnected)) ([#3184](https://github.com/mihonapp/mihon/pull/3184))
- Continue reading button missing when unread filter is off ([@AntsyLich](https://github.com/AntsyLich)) ([#3382](https://github.com/mihonapp/mihon/pull/3382))
- Fix random `FileNotFoundException` when reading chapters ([@leodyversemilla07](https://github.com/leodyversemilla07)) ([#3154](https://github.com/mihonapp/mihon/pull/3154))

## [v0.19.9] - 2026-04-11
### Fixed
- Regression with installing/updating extension ([@AntsyLich](https://github.com/AntsyLich))

## [v0.19.8] - 2026-04-11
### Added
- Add dedicated "Support Us" screen ([@AntsyLich](https://github.com/AntsyLich)) ([#3200](https://github.com/mihonapp/mihon/pull/3200))
- Add a one time popup asking for donation from long time users ([@AntsyLich](https://github.com/AntsyLich)) ([#3203](https://github.com/mihonapp/mihon/pull/3203))

### Changed
- Don't prompt users to enable Google Play services if disabled or unavailable ([@leodyversemilla07](https://github.com/leodyversemilla07)) ([#3152](https://github.com/mihonapp/mihon/pull/3152))
- Make "Support Us" more prominent in More screen ([@AntsyLich](https://github.com/AntsyLich)) ([#3196](https://github.com/mihonapp/mihon/pull/3196))

### Improved
- Show informative error when trying to add unapproved titles to list on MAL ([@MajorTanya](https://github.com/MajorTanya)) ([#3155](https://github.com/mihonapp/mihon/pull/3155))
- Show AniList publishing type based on country of origin ([@zweimach](https://github.com/zweimach)) ([#3100](https://github.com/mihonapp/mihon/pull/3100))

### Fixed
- Fix app trying to split long strip when not needed ([@leodyversemilla07](https://github.com/leodyversemilla07)) ([#3121](https://github.com/mihonapp/mihon/pull/3121))

### Removed
- Remove the empty "Updated to X.Y.Z" popup after app update ([@AntsyLich](https://github.com/AntsyLich)) ([#3203](https://github.com/mihonapp/mihon/pull/3203))

## [v0.19.7] - 2026-03-23
Same as v0.19.6

## [v0.19.6] - 2026-03-23
### Fixed
- Fix app crashing when trying to add extension repo with existing signature ([@AntsyLich](https://github.com/AntsyLich)) ([`cb0e329`](https://github.com/mihonapp/mihon/commit/cb0e329))
- Potentially fix 'database is locked' crash ([@AntsyLich](https://github.com/AntsyLich)) ([`f8e82b9`](https://github.com/mihonapp/mihon/commit/f8e82b9))
- Fix occasional crash when mass installing/uninstalling extension using `PackageManager` ([@AntsyLich](https://github.com/AntsyLich)) ([`42daa3f`](https://github.com/mihonapp/mihon/commit/42daa3f))
- Fix app crash on startup on some Android TV ([@AntsyLich](https://github.com/AntsyLich)) ([`b40f1d3`](https://github.com/mihonapp/mihon/commit/b40f1d3))

## [v0.19.5] - 2026-03-20
### Changed
- Retry in reader now redownloads image ([@AntsyLich](https://github.com/AntsyLich)) ([#3089](https://github.com/mihonapp/mihon/pull/3089))

### Fixed
- Fix performance regression introduced in v0.19.4 ([@AntsyLich](https://github.com/AntsyLich)) ([#3082](https://github.com/mihonapp/mihon/pull/3082))
- Fix duplicate key crash in duplicate detection ([@leodyversemilla07](https://github.com/leodyversemilla07)) ([#3040](https://github.com/mihonapp/mihon/pull/3040))
- Fix MangaUpdates HTTP 4XX errors ([@leodyversemilla07](https://github.com/leodyversemilla07)) ([#3021](https://github.com/mihonapp/mihon/pull/3021))
- Fix WebView JavaScript dialogs popup after screen is closed ([@leodyversemilla07](https://github.com/leodyversemilla07)) ([#3041](https://github.com/mihonapp/mihon/pull/3041))
- Fix extension actions disappearing after installing and uninstalling in same session ([@leodyversemilla07](https://github.com/leodyversemilla07)) ([#3049](https://github.com/mihonapp/mihon/pull/3049))

## [v0.19.4] - 2026-02-25
### Added
- Automatically remove downloads on Suwayomi after reading, configurable via extension settings ([@cpiber](https://github.com/cpiber)) ([#2673](https://github.com/mihonapp/mihon/pull/2673))
- Display author & artist name in MAL search results ([@MajorTanya](https://github.com/MajorTanya)) ([#2833](https://github.com/mihonapp/mihon/pull/2833))
- Add filter options to Updates tab ([@MajorTanya](https://github.com/MajorTanya)) ([#2851](https://github.com/mihonapp/mihon/pull/2851))
- Add bookmarked chapters to chapter download options ([@NarwhalHorns](https://github.com/NarwhalHorns)) ([#2891](https://github.com/mihonapp/mihon/pull/2891))
- Add `src:` prefix to search the library by source ID ([@MajorTanya](https://github.com/MajorTanya)) ([#2927](https://github.com/mihonapp/mihon/pull/2927))
  - Add `src:local` as a way to search for Local Source entries ([@MajorTanya](https://github.com/MajorTanya)) ([#2928](https://github.com/mihonapp/mihon/pull/2928))

### Improved
- Minimize memory usage by reducing in-memory cover cache size ([@Lolle2000la](https://github.com/Lolle2000la)) ([#2266](https://github.com/mihonapp/mihon/pull/2266))
- Optimize MAL search queries ([@MajorTanya](https://github.com/MajorTanya)) ([#2832](https://github.com/mihonapp/mihon/pull/2832))
- Reword download reindexing message to avoid confusion ([@MajorTanya](https://github.com/MajorTanya)) ([#2874](https://github.com/mihonapp/mihon/pull/2874))
- Optimize tracked library filter ([@NarwhalHorns](https://github.com/NarwhalHorns)) ([#2977](https://github.com/mihonapp/mihon/pull/2977))
- Utilize tracker for library duplicate detection ([@NarwhalHorns](https://github.com/NarwhalHorns)) ([#2978](https://github.com/mihonapp/mihon/pull/2978))

### Changed
- Update tracker icons ([@AntsyLich](https://github.com/AntsyLich)) ([#2773](https://github.com/mihonapp/mihon/pull/2773))
- Add a small increment to chapter number before comparison to fix progress sync issues for Suwayomi ([@cpiber](https://github.com/cpiber)) ([#2657](https://github.com/mihonapp/mihon/pull/2675))
- Add all pages of adjacent chapters in the UI instead of only the first or last three ([@AntsyLich](https://github.com/AntsyLich)) ([#2995](https://github.com/mihonapp/mihon/pull/2995))
- Going back now first clears search query on browse extension tab ([@cuong-tran](https://github.com/cuong-tran)) ([#2906](https://github.com/mihonapp/mihon/pull/2906))
- Automatic library updates now run even when connected to a VPN ([@AntsyLich](https://github.com/AntsyLich)) ([#2773](https://github.com/mihonapp/mihon/pull/2773))

### Fixed
- Fix reader tap zones triggering after scrolling is stopped by tapping ([@NGB-Was-Taken](https://github.com/NGB-Was-Taken)) ([#2680](https://github.com/mihonapp/mihon/pull/2680))
- Fix shizuku installer not updating installed extensions ([@NGB-Was-Taken](https://github.com/NGB-Was-Taken)) ([#2697](https://github.com/mihonapp/mihon/pull/2697))
- Fix mass migration not using the same search queries as individual migration ([@AntsyLich](https://github.com/AntsyLich)) ([#2736](https://github.com/mihonapp/mihon/pull/2736))
- Fix reader not saving read duration when changing chapter ([@AntsyLich](https://github.com/AntsyLich), [@KotlinHero](https://github.com/KotlinHero)) ([#2784](https://github.com/mihonapp/mihon/pull/2784))
- Fix pre-1970 upload date display in chapter list ([@MajorTanya](https://github.com/MajorTanya)) ([#2779](https://github.com/mihonapp/mihon/pull/2779))
- Fix crash when trying to install/update extensions while shizuku is not running ([@NGB-Was-Taken](https://github.com/NGB-Was-Taken)) ([#2837](https://github.com/mihonapp/mihon/pull/2837))
- Fix Add Repo input not taking up the full dialog width ([@cuong-tran](https://github.com/cuong-tran)) ([#2816](https://github.com/mihonapp/mihon/pull/2816))
- Fix migration's selected sources order not preserved ([@AntsyLich](https://github.com/AntsyLich)) ([#2993](https://github.com/mihonapp/mihon/pull/2993))
- Fix migration dialog not showing for consecutive prompts from the same screen ([@AntsyLich](https://github.com/AntsyLich)) ([#2994](https://github.com/mihonapp/mihon/pull/2994))
- Fix extension install/update stuck at pending ([@AntsyLich](https://github.com/AntsyLich)) ([#3000](https://github.com/mihonapp/mihon/pull/3000))
- Fix crash when downloading multiple chapters with Arabic locale ([@MajorTanya](https://github.com/MajorTanya)) ([#2676](https://github.com/mihonapp/mihon/pull/2676))

### Other
- Enable logcat logging on stable and debug builds without enabling verbose logging ([@NGB-Was-Taken](https://github.com/NGB-Was-Taken)) ([#2836](https://github.com/mihonapp/mihon/pull/2836))

## [v0.19.3] - 2025-11-07
### Improved
- Improved various aspects of the WebView multi window support ([@TheUnlocked](https://github.com/TheUnlocked)) ([#2662](https://github.com/mihonapp/mihon/pull/2662))

### Removed
- Revert "Fix reader tap zones triggering after scrolling was stopped by the user" due to introduction of regression ([@AntsyLich](https://github.com/AntsyLich)) ([#2670](https://github.com/mihonapp/mihon/pull/2670))

### Fixed
- Fix WebView crash introduced in 0.19.2 ([@bapeey](https://github.com/bapeey)) ([#2649](https://github.com/mihonapp/mihon/pull/2649))
- Fix extra padding appearing in reader after user interactions ([@AntsyLich](https://github.com/AntsyLich)) ([#2669](https://github.com/mihonapp/mihon/pull/2669))
- Fix long strip reader not scrolling on consecutive taps ([@AntsyLich](https://github.com/AntsyLich)) ([#2670](https://github.com/mihonapp/mihon/pull/2670))

## [v0.19.2] - 2025-11-02
### Added
- Advanced setting to limit download filenames to ASCII characters. This is provided only as a workaround for OSes that do not properly handle standard Unicode filenames. This setting is generally not recommended and should only be used as a last resort ([@raxod502](https://github.com/radian-software)) ([#2305](https://github.com/mihonapp/mihon/pull/2305))
- Option to customize the number of concurrent source and page downloads ([@AntsyLich](https://github.com/AntsyLich)) ([#2637](https://github.com/mihonapp/mihon/pull/2637))

### Changed
- Increased default concurrent page downloads to 5 ([@AntsyLich](https://github.com/AntsyLich)) ([#2637](https://github.com/mihonapp/mihon/pull/2637))

### Improved
- Spoofing of `X-Requested-With` header to support newer WebView versions ([@Guzmazow](https://github.com/Guzmazow)) ([#2491](https://github.com/mihonapp/mihon/pull/2491))
- Download support for chapters with the same metadata. Now a hash based on chapter's url is appended to download filename to tell them apart, letting you download both. Existing downloaded chapters will continue to work normally ([@raxod502](https://github.com/radian-software)) ([#2305](https://github.com/mihonapp/mihon/pull/2305))
- Auto refresh extension list whenever a repository is added or removed ([@c2y5](https://github.com/c2y5)) ([#2483](https://github.com/mihonapp/mihon/pull/2483))
- Added proper multi window support in WebView instead of treating everything as a redirect ([@TheUnlocked](https://github.com/TheUnlocked)) ([#2584](https://github.com/mihonapp/mihon/pull/2584))

### Fixed
- Fix height of description not being calculated correctly if images are present ([@Secozzi](https://github.com/Secozzi)) ([#2382](https://github.com/mihonapp/mihon/pull/2382))
- Fix migration progress not updating after manual search ([@Secozzi](https://github.com/Secozzi)) ([#2484](https://github.com/mihonapp/mihon/pull/2484))
- Fix category migration flag being ignored due to incorrect check against chapter flag ([@Secozzi](https://github.com/Secozzi)) ([#2484](https://github.com/mihonapp/mihon/pull/2484))
- Fix disabling incognito mode from notification ([@NGB-Was-Taken](https://github.com/NGB-Was-Taken)) ([#2512](https://github.com/mihonapp/mihon/pull/2512))
- Fix mass migration advanced search query building ([@AntsyLich](https://github.com/AntsyLich)) ([#2629](https://github.com/mihonapp/mihon/pull/2629))
- Fix migration dialog migrating to wrong entry ([@AntsyLich](https://github.com/AntsyLich)) ([#2631](https://github.com/mihonapp/mihon/pull/2631))
- Fix migration "Attempt to invoke virtual method" crash ([@AntsyLich](https://github.com/AntsyLich)) ([#2632](https://github.com/mihonapp/mihon/pull/2632))
- Fix reader "Unable to edit key" error ([@AntsyLich](https://github.com/AntsyLich)) ([#2634](https://github.com/mihonapp/mihon/pull/2634))
- Fix extension download stuck in pending state in some cases ([@c2y5](https://github.com/c2y5)) ([#2483](https://github.com/mihonapp/mihon/pull/2483))
- Fix scrollbar not showing when animator duration scale animation is turned off ([@anirudhsnayak](https://github.com/anirudhsnayak)) ([#2398](https://github.com/mihonapp/mihon/pull/2398))
- Fix date picker not allowing the same start and finish date in negative time zones ([@AntsyLich](https://github.com/AntsyLich), [@kashish-aggarwal21](https://github.com/kashish-aggarwal21)) ([#2617](https://github.com/mihonapp/mihon/pull/2617))
- Fix reader tap zones triggering after scrolling was stopped by the user ([@Naputt1](https://github.com/Naputt1), [@AntsyLich](https://github.com/AntsyLich)) ([#2518](https://github.com/mihonapp/mihon/pull/2518))
- Fix reader page indicator being partially visible on some devices ([@AntsyLich](https://github.com/AntsyLich)) ([#1908](https://github.com/mihonapp/mihon/pull/1908))
- Fix inconsistent system bar and reader app bar background ([@AntsyLich](https://github.com/AntsyLich)) ([#1908](https://github.com/mihonapp/mihon/pull/1908))
- Fix transparent system bar background in reader on Android 15+ ([@AntsyLich](https://github.com/AntsyLich)) ([#1908](https://github.com/mihonapp/mihon/pull/1908))

### Other
- Delegate Suwayomi tracker authentication to extension ([@cpiber](https://github.com/cpiber)) ([#2476](https://github.com/mihonapp/mihon/pull/2476))
- Fix Kitsu tracker to conform to tracker data structure properly ([@cpiber](https://github.com/cpiber)) ([#2609](https://github.com/mihonapp/mihon/pull/2609))
- Update Suwayomi tracker to use GraphQL API instead of REST API ([@cpiber](https://github.com/cpiber)) ([#2585](https://github.com/mihonapp/mihon/pull/2585))

## [v0.19.1] - 2025-08-07
### Changed
- LocalSource now reads ComicInfo.xml file for chapter (if available) to display chapter title, number and scanlator ([@raxod502](https://github.com/radian-software)) ([#2332](https://github.com/mihonapp/mihon/pull/2332))

### Removed
- Predictive back support ([@AntsyLich](https://github.com/AntsyLich)) ([#2362](https://github.com/mihonapp/mihon/pull/2362))

### Fixed
- Fix scrollbar sometimes not showing during scroll or not reaching the bottom with few items ([@anirudhsnayak](https://github.com/anirudhsnayak)) ([#2304](https://github.com/mihonapp/mihon/pull/2304))
- Fix local source EPUB files not loading ([@AntsyLich](https://github.com/AntsyLich)) ([#2369](https://github.com/mihonapp/mihon/pull/2369))
- Fix title text color in light mode on mass migration list ([@AntsyLich](https://github.com/AntsyLich)) ([#2370](https://github.com/mihonapp/mihon/pull/2370))
- Fix 'Default' category showing in library with no user-added categories ([@AntsyLich](https://github.com/AntsyLich)) ([#2371](https://github.com/mihonapp/mihon/pull/2371))
- Fix crash when opening filter sheet with an empty library ([@krysanify](https://github.com/krysanify/)) ([#2355](https://github.com/mihonapp/mihon/pull/2355))
- Fix mark as read/unread not working for selected library items ([@krysanify](https://github.com/krysanify/)) ([#2355](https://github.com/mihonapp/mihon/pull/2355))

## [v0.19.0] - 2025-08-04
### Added
- Add more Kaomoji for empty/error screens ([@ianfhunter](https://github.com/ianfhunter/)) ([#1909](https://github.com/mihonapp/mihon/pull/1909))
- Add user manga notes ([@imkunet](https://github.com/imkunet), [@AntsyLich](https://github.com/AntsyLich)) ([#428](https://github.com/mihonapp/mihon/pull/428))
  - Fix user notes not restoring when manga doesn't exist in DB ([@AntsyLich](https://github.com/AntsyLich)) ([#1945](https://github.com/mihonapp/mihon/pull/1945))
- Add markdown support for manga descriptions ([@Secozzi](https://github.com/Secozzi)) ([#1948](https://github.com/mihonapp/mihon/pull/1948))
  - Use simpler markdown flavour ([@Secozzi](https://github.com/Secozzi)) ([#2000](https://github.com/mihonapp/mihon/pull/2000))
  - Use Github markdown flavour for Github releases & fix bullet list alignment ([@Secozzi](https://github.com/Secozzi)) ([#2024](https://github.com/mihonapp/mihon/pull/2024))
  - Add option to toggle image loading ([@Secozzi](https://github.com/Secozzi)) ([#2076](https://github.com/mihonapp/mihon/pull/2076))
- Add Nord Theme ([@Riztard](https://github.com/Riztard)) ([#1951](https://github.com/mihonapp/mihon/pull/1951))
- Option to keep read manga when clearing database ([@AwkwardPeak7](https://github.com/AwkwardPeak7)) ([#1979](https://github.com/mihonapp/mihon/pull/1979))
- Add advanced option to always update manga title from source ([@FlaminSarge](https://github.com/FlaminSarge)) ([#1182](https://github.com/mihonapp/mihon/pull/1182))
- Full predictive back support ([@AntsyLich](https://github.com/AntsyLich)) ([#2085](https://github.com/mihonapp/mihon/pull/2085))
- Add Catppuccin theme (mocha for dark and latte for light, mauve accent) ([@claymorwan](https://github.com/claymorwan/)) ([#2117](https://github.com/mihonapp/mihon/pull/2117))
- Manga mass migration ([@AntsyLich](https://github.com/AntsyLich), [@jobobby04](https://github.com/jobobby04)) ([#2110](https://github.com/mihonapp/mihon/pull/2110), [#2336](https://github.com/mihonapp/mihon/pull/2336), [#2338](https://github.com/mihonapp/mihon/pull/2338), [`f119386`](https://github.com/mihonapp/mihon/commit/f119386))

### Improved
- Significantly improve browsing speed (near instantaneous) ([@AntsyLich](https://github.com/AntsyLich)) ([#1946](https://github.com/mihonapp/mihon/pull/1946))
- Deduplicate entries when browsing ([@AntsyLich](https://github.com/AntsyLich)) ([#1957](https://github.com/mihonapp/mihon/pull/1957))
- Update non-library manga data when browsing ([@AntsyLich](https://github.com/AntsyLich)) ([#1967](https://github.com/mihonapp/mihon/pull/1967))
- Surface image loading error in Reader ([@AwkwardPeak7](https://github.com/AwkwardPeak7)) ([#1981](https://github.com/mihonapp/mihon/pull/1981))
- Include source headers when opening failed images from reader ([@AwkwardPeak7](https://github.com/AwkwardPeak7)) ([#2004](https://github.com/mihonapp/mihon/pull/2004))
- Added autofill support to tracker login dialog ([@AntsyLich](https://github.com/AntsyLich)) ([#2069](https://github.com/mihonapp/mihon/pull/2069))
- Added option to hide missing chapter count ([@User826](https://github.com/User826), [@AntsyLich](https://github.com/AntsyLich)) ([#2108](https://github.com/mihonapp/mihon/pull/2108))
- Use median to determine smart update interval, making it more resilient to long hiatuses ([@Kladki](https://github.com/Kladki)) ([#2251](https://github.com/mihonapp/mihon/pull/2251))
- Optimize library code to potentially better handle big user libraries ([@AntsyLich](https://github.com/AntsyLich)) ([#2329](https://github.com/mihonapp/mihon/pull/2329), [#2341](https://github.com/mihonapp/mihon/pull/2341))

### Changed
- Display all similarly named duplicates in duplicate manga dialogue ([@NarwhalHorns](https://github.com/NarwhalHorns), [@AntsyLich](https://github.com/AntsyLich)) ([#1861](https://github.com/mihonapp/mihon/pull/1861))
  - Display chapter count on items in duplicate manga dialogue ([@NarwhalHorns](https://github.com/NarwhalHorns)) ([#1963](https://github.com/mihonapp/mihon/pull/1963))
- Update Facebook and Reddit icons ([@Joehuu](https://github.com/Joehuu)) ([#1994](https://github.com/mihonapp/mihon/pull/1994))
- Switch default user agent to Android Chrome ([@AntsyLich](https://github.com/AntsyLich)) ([#2048](https://github.com/mihonapp/mihon/pull/2048))
- Changed log in button text when processing tracker login ([@AntsyLich](https://github.com/AntsyLich)) ([#2069](https://github.com/mihonapp/mihon/pull/2069))
- Disable reader's 'Keep screen on' setting by default ([@AntsyLich](https://github.com/AntsyLich)) ([#2095](https://github.com/mihonapp/mihon/pull/2095))
- Update manga without chapters even if restricted by source ([@AntsyLich](https://github.com/AntsyLich)) ([#2224](https://github.com/mihonapp/mihon/pull/224))
- Make local source default chapter sorting match file explorer behavior ([@AntsyLich](https://github.com/AntsyLich)) ([#2224](https://github.com/mihonapp/mihon/pull/224))
- Include Manga `initialized` status in backup ([@AwkwardPeak7](https://github.com/AwkwardPeak7)) ([#2285](https://github.com/mihonapp/mihon/pull/2285))

### Fixed
- Fix Bangumi search results including novels ([@MajorTanya](https://github.com/MajorTanya)) ([#1885](https://github.com/mihonapp/mihon/pull/1885))
- Fix next chapter button occasionally jumping to the last page of the current chapter ([@perokhe](https://github.com/perokhe)) ([#1920](https://github.com/mihonapp/mihon/pull/1920))
  - Fix page number not appearing when opening chapter ([@perokhe](https://github.com/perokhe)) ([#1936](https://github.com/mihonapp/mihon/pull/1936))
- Fix backup sharing from notifications not working when app is in background ([@JaymanR](https://github.com/JaymanR))([#1929](https://github.com/mihonapp/mihon/pull/1929))
- Fix mark existing duplicate read chapters as read option not working in some cases ([@AntsyLich](https://github.com/AntsyLich)) ([#1944](https://github.com/mihonapp/mihon/pull/1944))
- Fix app bar action tooltips blocking clicks ([@Bartuzen](https://github.com/Bartuzen)) ([#1928](https://github.com/mihonapp/mihon/pull/1928))
- Fix unintended app permissions due to Firebase misconfiguration ([@AntsyLich](https://github.com/AntsyLich)) ([#1960](https://github.com/mihonapp/mihon/pull/1960))
- Fix navigation issue after migrating a duplicated entry from History tab ([@cuong-tran](https://github.com/cuong-tran)) ([#1980](https://github.com/mihonapp/mihon/pull/1980))
- Fix duplicate requests in WebView due to empty reasonPhrase ([@AwkwardPeak7](https://github.com/AwkwardPeak7)) ([#2003](https://github.com/mihonapp/mihon/pull/2003))
- Fix content under source browse screen top appbar is interactable ([@AntsyLich](https://github.com/AntsyLich)) ([#2026](https://github.com/mihonapp/mihon/pull/2026))
- Fix crash when trying use source sort filter without a pre-selection ([@AntsyLich](https://github.com/AntsyLich)) ([#2036](https://github.com/mihonapp/mihon/pull/2036))
- Fix empty layout not appearing in browse source screen in some cases ([@NarwhalHorns](https://github.com/NarwhalHorns)) ([#2043](https://github.com/mihonapp/mihon/pull/2043))
- Fix Pill not following the local text style ([@AntsyLich](https://github.com/AntsyLich)) ([`f8cb506`](https://github.com/mihonapp/mihon/commit/f8cb506))
- Fix downloader stopping after failing to create download directory of a manga ([@AntsyLich](https://github.com/AntsyLich)) ([#2068](https://github.com/mihonapp/mihon/pull/2068))
- Fix pressing `Enter` while searching also triggering navigation back on physical keyboards ([@AwkwardPeak7](https://github.com/AwkwardPeak7)) ([#2077](https://github.com/mihonapp/mihon/pull/2077))
- Ensure app waits for Cloudflare challenge to complete before continuing ([@AwkwardPeak7](https://github.com/AwkwardPeak7)) ([#2200](https://github.com/mihonapp/mihon/pull/2200))

### Removed
- Remove Okhttp networking from WebView Screen ([@AwkwardPeak7](https://github.com/AwkwardPeak7)) ([#2020](https://github.com/mihonapp/mihon/pull/2020))

## [v0.18.0] - 2025-03-20
### Added
- Add option to always decode long strip images with SSIV ([@AntsyLich](https://github.com/AntsyLich)) ([`c5655e8`](https://github.com/mihonapp/mihon/commit/c5655e8803bc32d0931657f0b7bc6afeab70feaf))
  - Change option label ([@AntsyLich](https://github.com/AntsyLich)) ([#1835](https://github.com/mihonapp/mihon/pull/1835))
- Added option to enable incognito per extension ([@sdaqo](https://github.com/sdaqo), [@AntsyLich](https://github.com/AntsyLich)) ([#157](https://github.com/mihonapp/mihon/pull/157))
- Add button to favorite manga from history screen ([@Animeboynz](https://github.com/Animeboynz)) ([#1733](https://github.com/mihonapp/mihon/pull/1733))
- Add Monochrome theme (made with e-ink displays in mind) ([@MajorTanya](https://github.com/MajorTanya)) ([#1752](https://github.com/mihonapp/mihon/pull/1752))
- Support for private tracking with AniList and Bangumi ([@NarwhalHorns](https://github.com/NarwhalHorns)) ([#1736](https://github.com/mihonapp/mihon/pull/1736))
  - Add private tracking support for Kitsu ([@MajorTanya](https://github.com/MajorTanya)) ([#1774](https://github.com/mihonapp/mihon/pull/1774))
- Add option to export minimal library information to a CSV file ([@Animeboynz](https://github.com/Animeboynz), [@AntsyLich](https://github.com/AntsyLich)) ([#1161](https://github.com/mihonapp/mihon/pull/1161))
- Add back support for drag-and-drop category reordering ([@cuong-tran](https://github.com/cuong-tran)) ([#1427](https://github.com/mihonapp/mihon/pull/1427))
- Add option to mark duplicate read chapters as read after library update or while reading ([@AntsyLich](https://github.com/AntsyLich)) ([#1785](https://github.com/mihonapp/mihon/pull/1785), [#1791](https://github.com/mihonapp/mihon/pull/1791), [#1870](https://github.com/mihonapp/mihon/pull/1870))
- Display staff information on Anilist tracker search results ([@NarwhalHorns](https://github.com/NarwhalHorns)) ([#1810](https://github.com/mihonapp/mihon/pull/1810))
- Add `id:` prefix search to library to search by internal DB ID ([@MajorTanya](https://github.com/MajorTanya)) ([#1856](https://github.com/mihonapp/mihon/pull/1856))
- Add back option to disable unread chapter badge in library ([@AntsyLich](https://github.com/AntsyLich)) ([#1871](https://github.com/mihonapp/mihon/pull/1871))

### Changed
- Sliders UI ([@AntsyLich](https://github.com/AntsyLich)) ([#1840](https://github.com/mihonapp/mihon/pull/1840))
- Apply "Downloaded only" filter to all entries regardless of favourite status ([@NGB-Was-Taken](https://github.com/NGB-Was-Taken)) ([#1603](https://github.com/mihonapp/mihon/pull/1603))
- Ignore hidden files/folders for Local Source chapter list ([@BrutuZ](https://github.com/BrutuZ)) ([#1763](https://github.com/mihonapp/mihon/pull/1763))
- Migrate to newer Bangumi API ([@MajorTanya](https://github.com/MajorTanya)) ([#1748](https://github.com/mihonapp/mihon/pull/1748))
  - Now showing manga starting dates in search
  - Reduced request load by 2-4x in certain situations
- Bump default user agent ([@AntsyLich](https://github.com/AntsyLich)) ([#1833](https://github.com/mihonapp/mihon/pull/1833))
- Changed the label of chapter swipe settings and renamed the group to "Behavior" ([@AntsyLich](https://github.com/AntsyLich)) ([#1870](https://github.com/mihonapp/mihon/pull/1870))

### Fixed
- Fix MAL `main_picture` nullability breaking search if a result doesn't have a cover set ([@MajorTanya](https://github.com/MajorTanya)) ([#1618](https://github.com/mihonapp/mihon/pull/1618))
- Fix Bangumi and MAL tracking 401 errors due to Mihon sending expired credentials ([@MajorTanya](https://github.com/MajorTanya)) ([#1681](https://github.com/mihonapp/mihon/pull/1681), [#1682](https://github.com/mihonapp/mihon/pull/1682))
- Fix certain Infinix, Xiaomi devices being unable to use any "Open link in browser" actions, including tracker setup ([@MajorTanya](https://github.com/MajorTanya)) ([#1684](https://github.com/mihonapp/mihon/pull/1684)) ([#1776](https://github.com/mihonapp/mihon/pull/1776))
- Fix App's preferences referencing deleted categories ([@cuong-tran](https://github.com/cuong-tran)) ([#1734](https://github.com/mihonapp/mihon/pull/1734))
- Fix backup/restore of category related preferences ([@cuong-tran](https://github.com/cuong-tran)) ([#1726](https://github.com/mihonapp/mihon/pull/1726))
- Fix WebView sending app's package name in `X-Requested-With` header, which led to sources blocking access ([@AwkwardPeak7](https://github.com/AwkwardPeak7)) ([#1812](https://github.com/mihonapp/mihon/pull/1812))
- Fix an issue where tracker reading progress is changed to a lower value ([@Animeboynz](https://github.com/Animeboynz)) ([#1795](https://github.com/mihonapp/mihon/pull/1795))
- Attempt to fix crash when migrating or removing entries from library ([@FlaminSarge](https://github.com/FlaminSarge)) ([#1828](https://github.com/mihonapp/mihon/pull/1828))

### Removed
- Remove alphabetical category sort option ([@AntsyLich](https://github.com/AntsyLich)) ([#1781](https://github.com/mihonapp/mihon/pull/1781))

### Other
- Add zoned "Current time" to debug info and include year & timezone in logcat output ([@MajorTanya](https://github.com/MajorTanya)) ([#1672](https://github.com/mihonapp/mihon/pull/1672))
- Add application package ID to debug info ([@MajorTanya](https://github.com/MajorTanya)) ([#1847](https://github.com/mihonapp/mihon/pull/1847))

## [v0.17.1] - 2024-12-06
### Changed
- Bump default user agent ([@AntsyLich](https://github.com/AntsyLich)) ([`76dcf90`](https://github.com/mihonapp/mihon/commit/76dcf903403d565056f44c66d965c1ea8affffc3))

### Improved
- Bangumi search now shows the score and summary of a search result ([@MajorTanya](https://github.com/MajorTanya)) ([#1396](https://github.com/mihonapp/mihon/pull/1396))
- Extension repo URLs are now auto-formatted ([@AntsyLich](https://github.com/AntsyLich), [@MajorTanya](https://github.com/MajorTanya)) ([`22d8aad`](https://github.com/mihonapp/mihon/commit/22d8aad598bea8f00f2831779e45a6645392ca0f))

### Fixed
- Fix "currentTab was used multiple times" ([@AntsyLich](https://github.com/AntsyLich)) ([`371c143`](https://github.com/mihonapp/mihon/commit/371c1432e218f6dcf129f05405dceb2cd351c647))
- Fix a rare crash when invoking "Mark previous as read" action ([@AntsyLich](https://github.com/AntsyLich)) ([`f508d10`](https://github.com/mihonapp/mihon/commit/f508d10ad13560d7316df8642bc93fe66c05b9a8))
- Fix long strip images not loading in some old devices ([@AntsyLich](https://github.com/AntsyLich)) ([`06efc3b`](https://github.com/mihonapp/mihon/commit/06efc3b25c5af51f42448af27a269ee459d9093d))
  - Switch to hardware bitmap in reader only if device can handle it ([@AntsyLich](https://github.com/AntsyLich)) ([`e6d96bd`](https://github.com/mihonapp/mihon/commit/e6d96bd348ea5d18a005d6465222ad5f5123103e))
  - Add option to lower the threshold for hardware bitmaps ([@AntsyLich](https://github.com/AntsyLich)) ([`dcddac5`](https://github.com/mihonapp/mihon/commit/dcddac5daaff3ec89c8507c35dc13d345ffdb6d7))
    - Improve hardware bitmap threshold option ([@AntsyLich](https://github.com/AntsyLich)) ([`d6dfd24`](https://github.com/mihonapp/mihon/commit/d6dfd24548eaa05a8c3e478068fe2e08f2ee4473))
  - Always use software bitmap on certain devices ([@MajorTanya](https://github.com/MajorTanya)) ([#1543](https://github.com/mihonapp/mihon/pull/1543))
- Fix crash after removing last category while it's active in library ([@cuong-tran](https://github.com/cuong-tran)) ([#1450](https://github.com/mihonapp/mihon/pull/1450))
- Fix reader transition color scheme in auto background mode ([@cuong-tran](https://github.com/cuong-tran)) ([#1487](https://github.com/mihonapp/mihon/pull/1487))
- Fix app update error notification disappearing ([@cuong-tran](https://github.com/cuong-tran)) ([#1476](https://github.com/mihonapp/mihon/pull/1476))
- Fix browser not opening in some cases in Honor devices ([@AntsyLich](https://github.com/AntsyLich), [@MajorTanya](https://github.com/MajorTanya)) ([#1520](https://github.com/mihonapp/mihon/pull/1520))

## [v0.17.0] - 2024-10-26
### Added
- Option to disable reader zoom out ([@Splintorien](https://github.com/Splintorien)) ([#302](https://github.com/mihonapp/mihon/pull/302))
- Source name and tracker urls to app generated `ComicInfo.xml` file ([@Shamicen](https://github.com/Shamicen)) ([#459](https://github.com/mihonapp/mihon/pull/459))
- Option to migrate in Duplicate entry dialog ([@sirlag](https://github.com/sirlag)) ([#492](https://github.com/mihonapp/mihon/pull/492))
- Upcoming screen to visualize expected update dates ([@sirlag](https://github.com/sirlag)) ([#420](https://github.com/mihonapp/mihon/pull/420))
  - Only show upcoming updates in the future ([@sirlag](https://github.com/sirlag)) ([#606](https://github.com/mihonapp/mihon/pull/606))
  - Add Quantity Badge to Upcoming Screen ([@Animeboynz](https://github.com/Animeboynz), [@AntsyLich](https://github.com/AntsyLich)) ([#1250](https://github.com/mihonapp/mihon/pull/1250))
- Crash screen error message to the top of the crash log generated from that screen ([@FooIbar](https://github.com/FooIbar)) ([#742](https://github.com/mihonapp/mihon/pull/742))
- Support for 7Zip and RAR5 archives ([@FooIbar](https://github.com/FooIbar)) ([#949](https://github.com/mihonapp/mihon/pull/949))
- Extra configuration options to e-ink page flashes ([@sirlag](https://github.com/sirlag)) ([#625](https://github.com/mihonapp/mihon/pull/625))
- 8-bit+ AVIF image support ([@WerctFourth](https://github.com/WerctFourth)) ([#971](https://github.com/mihonapp/mihon/pull/971))
- Smart update dialog message when no predicted released date exists ([@Animeboynz](https://github.com/Animeboynz)) ([#977](https://github.com/mihonapp/mihon/pull/977))
- Option to copy reader panel to clipboard ([@Animeboynz](https://github.com/Animeboynz)) ([#1003](https://github.com/mihonapp/mihon/pull/1003))
- Copy Tracker URL option to tracker sheet ([@mm12](https://github.com/mm12)) ([#1101](https://github.com/mihonapp/mihon/pull/1101))
- A button to exclude all scanlators in exclude scanlators dialog ([@AntsyLich](https://github.com/AntsyLich)) ([`84b2164`](https://github.com/mihonapp/mihon/commit/84b2164787a795f3fd757c325cbfb6ef660ac3a3))
- Open in browser option to reader menu ([@mm12](https://github.com/mm12)) ([#1110](https://github.com/mihonapp/mihon/pull/1110))
  - Reorder reader menu overflow items ([@AntsyLich](https://github.com/AntsyLich)) ([`788235f`](https://github.com/mihonapp/mihon/commit/788235feeca241228eac0561339dd07b5ea0b77d))
- Option to skip downloading duplicate read chapters ([@shabnix](https://github.com/shabnix)) ([#1125](https://github.com/mihonapp/mihon/pull/1125))
- Add confirmation dialog when adding repo via URI ([@Animeboynz](https://github.com/Animeboynz)) ([#1158](https://github.com/mihonapp/mihon/pull/1158))
- Add "show entry" action to download notifications ([@mm12](https://github.com/mm12), [@AntsyLich](https://github.com/AntsyLich)) ([#1159](https://github.com/mihonapp/mihon/pull/1159))
- Option to update trackers when chapter marked as read ([@Animeboynz](https://github.com/Animeboynz), [@AntsyLich](https://github.com/AntsyLich)) ([#1177](https://github.com/mihonapp/mihon/pull/1177), [#1365](https://github.com/mihonapp/mihon/pull/1365), [#1374](https://github.com/mihonapp/mihon/pull/1374))
- Toast to restart app when User-Agent is changed ([@NGB-Was-Taken](https://github.com/NGB-Was-Taken)) ([#1204](https://github.com/mihonapp/mihon/pull/1204))
- Added more profile compilation status (p) ([`c8bb78d`](https://github.com/mihonapp/mihon/commit/c8bb78d91afc2824baaca999f0095559c49d1306))
- Add option to opt out of Analytics and Crashlytics ([@Animeboynz](https://github.com/Animeboynz)) ([#1237](https://github.com/mihonapp/mihon/pull/1237))
  - Rework Firebase setup ([@AntsyLich](https://github.com/AntsyLich)) ([`15e3f28`](https://github.com/mihonapp/mihon/commit/15e3f28aa36bec3c31f212c572ab57ce960cc862))
- Added random library sort ([@jackhamilton](https://github.com/jackhamilton)) ([#1317](https://github.com/mihonapp/mihon/pull/1317))
  - Make sure random library sort is at the bottom ([@AntsyLich](https://github.com/AntsyLich)) ([`2e2c8d3`](https://github.com/mihonapp/mihon/commit/2e2c8d36c1e23bf274c7c19f1242e14b0c7afbc1))
- Confirmation dialog when removing privately installed extensions ([@Animeboynz](https://github.com/Animeboynz), [@AntsyLich](https://github.com/AntsyLich)) ([#1320](https://github.com/mihonapp/mihon/pull/1320))
- Option to backup non-library read entries ([@Animeboynz](https://github.com/Animeboynz), [@jobobby04](https://github.com/jobobby04), [@AntsyLich](https://github.com/AntsyLich)) ([#1324](https://github.com/mihonapp/mihon/pull/1324))

### Changed
- Read archive files from memory instead of temporarily extracting to internal storage ([@FooIbar](https://github.com/FooIbar)) ([#326](https://github.com/mihonapp/mihon/pull/326))
  - Fix dual page split ([@FooIbar](https://github.com/FooIbar)) ([#485](https://github.com/mihonapp/mihon/pull/485))
- Bump default user agent ([@AntsyLich](https://github.com/AntsyLich)) ([`8160b47`](https://github.com/mihonapp/mihon/commit/8160b47ff5fbbd9b32caeb462b5be881fabd3449))
- Wait for sources to be initialized before performing source related tasks ([@jobobby04](https://github.com/jobobby04)) ([`a08e03f`](https://github.com/mihonapp/mihon/commit/a08e03f5cbf3f4e6be1de35f97ef8ebb26a1210e))
- Duplicate entry dialog UI ([@sirlag](https://github.com/sirlag)) ([#492](https://github.com/mihonapp/mihon/pull/492))
- Extension trust system
  - Store extension repo details from `repo.json` in database ([@sirlag](https://github.com/sirlag)) ([#506](https://github.com/mihonapp/mihon/pull/506))
    - Fix extension repo migration not triggering ([@AntsyLich](https://github.com/AntsyLich)) ([`9672ea8`](https://github.com/mihonapp/mihon/commit/9672ea8b1b06f464800e310c96e060ead182f7ca))
    - Refactor the ExtensionRepoService to use DTOs ([@MajorTanya](https://github.com/MajorTanya)) ([#573](https://github.com/mihonapp/mihon/pull/573))
    - Fix extension repo name is used to construct URL instead of baseUrl ([@MajorTanya](https://github.com/MajorTanya)) ([#572](https://github.com/mihonapp/mihon/pull/572))
    - Fix crash with `TypeReference` issue when creating extension repo ([@AntsyLich](https://github.com/AntsyLich)) ([#574](https://github.com/mihonapp/mihon/pull/574), [`e020ae5`](https://github.com/mihonapp/mihon/commit/e020ae5ed558e80742ef0ad8bfa0f69af0959d5a))
      - Fix mishap in [`e020ae5`](https://github.com/mihonapp/mihon/commit/e020ae5ed558e80742ef0ad8bfa0f69af0959d5a) ([@AntsyLich](https://github.com/AntsyLich)) ([`6965e59`](https://github.com/mihonapp/mihon/commit/6965e59a643c67a2bf81b3c69ec70268e5da5797))
    - Backup and Restore ([@Animeboynz](https://github.com/Animeboynz)) ([#1057](https://github.com/mihonapp/mihon/pull/1057))
  - Trust extension by repo ([@AntsyLich](https://github.com/AntsyLich)) ([#570](https://github.com/mihonapp/mihon/pull/570))
- From M2 ripple to M3 ([@FooIbar](https://github.com/FooIbar)) ([#675](https://github.com/mihonapp/mihon/pull/675))
- Increased continue reading button size ([@AntsyLich](https://github.com/AntsyLich), [@Animeboynz](https://github.com/Animeboynz)) ([`e17f70f`](https://github.com/mihonapp/mihon/commit/e17f70f7226ea031fc1f962c9dfea3e404ba53ad))
- Global search "Has result" choice is now sticky ([@AntsyLich](https://github.com/AntsyLich)) ([`5a61ca5`](https://github.com/mihonapp/mihon/commit/5a61ca5535fe0d9e8e7bcb9e665ba2f9cb0cf649))
- Make category backup/restore not dependant on library backup ([@AntsyLich](https://github.com/AntsyLich)) ([`56fb4f6`](https://github.com/mihonapp/mihon/commit/56fb4f62a152e87a71892aa68c78cac51a2c8596))
- Rename backup restore error log file ([@AntsyLich](https://github.com/AntsyLich)) ([`2858ef8`](https://github.com/mihonapp/mihon/commit/2858ef835fec8d7278b1d0cad1b5664104d1e4b0))
- Keyboard type in add extension repo dialog ([@xbjfk](https://github.com/xbjfk)) ([#764](https://github.com/mihonapp/mihon/pull/764))
- Adjust collapse/open animation on manga description ([@AntsyLich](https://github.com/AntsyLich), [@ivaniskandar](https://github.com/ivaniskandar)) ([`1c16fc7`](https://github.com/mihonapp/mihon/commit/1c16fc79c2ac4c4be30308fed84ffb371dab5902))
- Kitsu domain to `kitsu.app` ([@MajorTanya](https://github.com/MajorTanya)) ([#1106](https://github.com/mihonapp/mihon/pull/1106))
- Respect privacy settings in extension update notification ([@Animeboynz](https://github.com/Animeboynz)) ([#1156](https://github.com/mihonapp/mihon/pull/1156))
- Hide keyboard when a Tracker SearchResultItem is clicked ([@Animeboynz](https://github.com/Animeboynz)) ([#1168](https://github.com/mihonapp/mihon/pull/1168))
- Enable 'Split Tall Images' by default ([@Smol-Ame](https://github.com/Smol-Ame)) ([#1185](https://github.com/mihonapp/mihon/pull/1185))
- Ignore "intent://" urls on webview ([@bapeey](https://github.com/bapeey)) ([#1193](https://github.com/mihonapp/mihon/pull/1193))
- Make reader chapter navigator slightly wider on small screens (p) ([#1202](https://github.com/mihonapp/mihon/pull/1202))
- Re-enable fetching chapters list for entries with licenced status ([@Animeboynz](https://github.com/Animeboynz)) ([#1230](https://github.com/mihonapp/mihon/pull/1230))
- Change casing for Extention Repos String ([@Animeboynz](https://github.com/Animeboynz)) ([#1248](https://github.com/mihonapp/mihon/pull/1248))
- Retain remote last chapter read if it's higher than the local one for EnhancedTracker ([@brewkunz](https://github.com/brewkunz)) ([#1301](https://github.com/mihonapp/mihon/pull/1301))
- Adjust expandable fab animation (p) ([`eb6092b`](https://github.com/mihonapp/mihon/commit/eb6092bd0cfa09694985a8bafdd8bbf2815190a1))
- "Invalidate downloads index" to "Reindex downloads" ([@AntsyLich](https://github.com/AntsyLich)) ([`d2afbfe`](https://github.com/mihonapp/mihon/commit/d2afbfe4ede283076aae40633c79c3f90b4390e7))

### Improved
- Reader performance
  - Avoid unnecessary copying when processing reader image ([@FooIbar](https://github.com/FooIbar)) ([#691](https://github.com/mihonapp/mihon/pull/691))
  - Significantly improve performance when loading extremely long images in long strip mode ([@FooIbar](https://github.com/FooIbar)) ([#692](https://github.com/mihonapp/mihon/pull/692))
  - Use `Bitmap.Config.HARDWARE` if possible to improve image loading speed ([@wwww-wwww](https://github.com/wwww-wwww)) ([#687](https://github.com/mihonapp/mihon/pull/687))
  - Improve preloading in long strip mode ([@FooIbar](https://github.com/FooIbar)) ([#1076](https://github.com/mihonapp/mihon/pull/1076))
- Performance when looking up specific files ([@raxod502](https://github.com/raxod502)) ([#728](https://github.com/mihonapp/mihon/pull/728))
- Chapter number parsing ([@Naputt1](https://github.com/Naputt1)) ([`6a80305`](https://github.com/mihonapp/mihon/commit/6a80305d6c572da6c08c0c69f5c25ff26ecf7383))
- Error message on restoring if backup decoding fails ([@vetleledaal](https://github.com/vetleledaal)) ([#1056](https://github.com/mihonapp/mihon/pull/1056))

### Removed
- Legacy download folder names no longer supported ([@AntsyLich](https://github.com/AntsyLich)) ([`e55e5f6`](https://github.com/mihonapp/mihon/commit/e55e5f6f64f872475d370d6ce0c186e2601776e4))
- Remove legacy broken source and history backup ([@AntsyLich](https://github.com/AntsyLich)) ([`518abf0`](https://github.com/mihonapp/mihon/commit/518abf032ccb9bb45d197927be2a5faca4167d29))
- Remove more unnecessary permissions from Firebase dependency ([@AntsyLich](https://github.com/AntsyLich)) ([`02af9b1`](https://github.com/mihonapp/mihon/commit/02af9b1acf9f590d29560bc3fc90d206e8e6e1af))
  - Fix mishap in `02af9b1` ([@AntsyLich](https://github.com/AntsyLich)) ([`f22767d`](https://github.com/mihonapp/mihon/commit/f22767d863a0fa001f93f24092cd5ade87350502))

### Fixed
- Extracting `ComicInfo.xml` from local source archives ([@FooIbar](https://github.com/FooIbar)) ([#325](https://github.com/mihonapp/mihon/pull/325))
- Chapter download indicator ([@ivaniskandar](https://github.com/ivaniskandar)) ([`d8b9a9f`](https://github.com/mihonapp/mihon/commit/d8b9a9f593911569ff2bceb49b4f020978d0d2e1))
- Issues with shizuku in a multi user setup ([@Redjard](https://github.com/Redjard)) ([#494](https://github.com/mihonapp/mihon/pull/494))
- Fix reader page image not being decoded until it's visible ([@FooIbar](https://github.com/FooIbar)) ([#563](https://github.com/mihonapp/mihon/pull/563))
- Reader chapter progress slider visuals ([@FooIbar](https://github.com/FooIbar)) ([#674](https://github.com/mihonapp/mihon/pull/674))
- Extension being marked as not installed instead of untrusted after updating with private installer ([@AntsyLich](https://github.com/AntsyLich)) ([`2114514`](https://github.com/mihonapp/mihon/commit/21145144cdf550aa775047603e06e261951ebc42))
- Extension update counter not updating due to extension being marked as untrusted ([@AntsyLich](https://github.com/AntsyLich)) ([`2114514`](https://github.com/mihonapp/mihon/commit/21145144cdf550aa775047603e06e261951ebc42))
- `Key "extension-XXX-YYY" was already used` crash ([@AntsyLich](https://github.com/AntsyLich)) ([`2114514`](https://github.com/mihonapp/mihon/commit/21145144cdf550aa775047603e06e261951ebc42))
- Navigation layout tap zones shifting after zooming out in webtoon readers ([@FooIbar](https://github.com/FooIbar)) ([#767](https://github.com/mihonapp/mihon/pull/767))
- Some extension not loading due to missing classes ([@AwkwardPeak7](https://github.com/AwkwardPeak7)) ([#783](https://github.com/mihonapp/mihon/pull/783))
- Theme colors in accordance to upstream changes ([@CrepeTF](https://github.com/CrepeTF), [@AntsyLich](https://github.com/AntsyLich)) ([#766](https://github.com/mihonapp/mihon/pull/766), [#963](https://github.com/mihonapp/mihon/pull/963), [#976](https://github.com/mihonapp/mihon/pull/976), [9a34ace](https://github.com/mihonapp/mihon/commit/9a34ace09c66274e6c2b3f9446058a0fa99d4bd0))
- Crash when requesting folder access on non-conforming devices ([@mainrs](https://github.com/mainrs)) ([#726](https://github.com/mihonapp/mihon/pull/726))
- Fix unexpected skips in strong skipping mode ([@FooIbar](https://github.com/FooIbar)) ([#940](https://github.com/mihonapp/mihon/pull/940))
- Bugged color for Date/Scanlator in chapter list for read chapters ([@ivaniskandar](https://github.com/ivaniskandar)) ([`15d9992`](https://github.com/mihonapp/mihon/commit/15d999229fcce865001d5fa77d0163e6e80e38db))
- Categories having same `order` after restoring backup ([@Cologler](https://github.com/Cologler)) ([`119bcbf`](https://github.com/mihonapp/mihon/commit/119bcbf8ed2415664922ea77fadf0da1165d1732))
- Filter by "Tracking" temporarily stuck after signing out of tracker ([@AntsyLich](https://github.com/AntsyLich)) ([#987](https://github.com/mihonapp/mihon/pull/987))
  - Fix login prompts despite being logged in to trackers in Manga screen ([@AntsyLich](https://github.com/AntsyLich)) ([`cbcd8bd`](https://github.com/mihonapp/mihon/commit/cbcd8bd6682023f728568f2b44da26124618aed7))
- JXL image downloading and loading ([@FooIbar](https://github.com/FooIbar)) ([#993](https://github.com/mihonapp/mihon/pull/993))
- Crash when using `%` in category name ([@Animeboynz](https://github.com/Animeboynz), [@FooIbar](https://github.com/FooIbar)) ([#1030](https://github.com/mihonapp/mihon/pull/1030))
- Fix item disappearing when fast scrolling ([@cuong-tran](https://github.com/cuong-tran)) ([#1035](https://github.com/mihonapp/mihon/pull/1035))
- Library is backed up while being disabled ([@AntsyLich](https://github.com/AntsyLich)) ([`56fb4f6`](https://github.com/mihonapp/mihon/commit/56fb4f62a152e87a71892aa68c78cac51a2c8596))
- Crash on list with only sticky header ([@cuong-tran](https://github.com/cuong-tran)) ([#1083](https://github.com/mihonapp/mihon/pull/1083))
- Crash when trying to clear cookies of some source ([@FooIbar](https://github.com/FooIbar)) ([#1084](https://github.com/mihonapp/mihon/pull/1084))
- MAL search results not showing start dates ([@MajorTanya](https://github.com/MajorTanya)) ([#1098](https://github.com/mihonapp/mihon/pull/1098))
- Android SDK 35 API collision ([@AntsyLich](https://github.com/AntsyLich)) ([`fdb9617`](https://github.com/mihonapp/mihon/commit/fdb96179c6373eb0a8e7d6daea671a315d5ce5f0))
- Manga next update calculation when considering custom fetch interval ([@cuong-tran](https://github.com/cuong-tran)) ([#1206](https://github.com/mihonapp/mihon/pull/1206))
- WheelPicker Manual Input ([@Animeboynz](https://github.com/Animeboynz)) ([#1209](https://github.com/mihonapp/mihon/pull/1209))
- EnhancedTracker not auto binding when adding manga to library ([@brewkunz](https://github.com/brewkunz)) ([#1298](https://github.com/mihonapp/mihon/pull/1298))
- Step count in settings slider ([@abdurisaq](https://github.com/abdurisaq)) ([#1356](https://github.com/mihonapp/mihon/pull/1356))
- Freezing in some screens due to blocking call ([@cuong-tran](https://github.com/cuong-tran)) ([#1364](https://github.com/mihonapp/mihon/pull/1364))
- Crash when removing non-existent tracked entry from tracker ([@cuong-tran](https://github.com/cuong-tran)) ([#1380](https://github.com/mihonapp/mihon/pull/1380))

### Other
- Code cleanup
  - Minor refactor of theming when expressions ([@MajorTanya](https://github.com/MajorTanya)) ([#396](https://github.com/mihonapp/mihon/pull/396))
  - Inside `WorkerInfoScreen` ([@AntsyLich](https://github.com/AntsyLich)) ([`5aec8f8`](https://github.com/mihonapp/mihon/commit/5aec8f8018236a38106483da08f9cbc28261ac9b))
  - Inside `ChapterDownloadIndicator`, `MangaChapterListItem` ([@AntsyLich](https://github.com/AntsyLich)) ([`b7e091d`](https://github.com/mihonapp/mihon/commit/b7e091d5d039e00cababc7daf555280df6cf9c03))
  - MangaCoverFetcher ([@ivaniskandar](https://github.com/ivaniskandar)) ([`1365695`](https://github.com/mihonapp/mihon/commit/13656959ae0606736f6ca9eb62699dc23e467c2f))
- Cleanup `LibraryScreenModel` `LibraryMap.applySort` and some more ([@AntsyLich](https://github.com/AntsyLich)) ([`2beb89d`](https://github.com/mihonapp/mihon/commit/2beb89d53163a6d288f8acdebe0f5d26fea8ab3e))
- Address `overridePendingTransition` deprecation ([@MajorTanya](https://github.com/MajorTanya)) ([#410](https://github.com/mihonapp/mihon/pull/410))
- Prioritize extension classes and files over app ([@beer-psi](https://github.com/beer-psi)) ([#433](https://github.com/mihonapp/mihon/pull/433))
- Use compose pager implementation ([@ivaniskandar](https://github.com/ivaniskandar)) ([`84984ef`](https://github.com/mihonapp/mihon/commit/84984ef7e1d7242924120cd2f171cb9dd75bc916))
- Switch to coil3 from coil2 ([@ivaniskandar](https://github.com/ivaniskandar)) ([`f72b6e4`](https://github.com/mihonapp/mihon/commit/f72b6e4d7c1f2f93d705402e4d80c94160bef54d))
  - Fix GIF not playing ([@jobobby04](https://github.com/jobobby04)) ([`59bedb3`](https://github.com/mihonapp/mihon/commit/59bedb33ff59ad5db1df2e93567a2266fb63eacc))
- Accommodate db for sync support ([@kaiserbh](https://github.com/kaiserbh)) ([#450](https://github.com/mihonapp/mihon/pull/450))
- Fix webtoon last visible item position calculation ([@FooIbar](https://github.com/FooIbar)) ([#562](https://github.com/mihonapp/mihon/pull/562))
- Migrate from `com.google.accompanist:accompanist-webview` to `io.github.kevinnzou:compose-webview` ([@sirlag](https://github.com/sirlag)) ([#569](https://github.com/mihonapp/mihon/pull/569))
- Rewrite migrations ([@ghostbear](https://github.com/ghostbear)) ([#577](https://github.com/mihonapp/mihon/pull/577))
  - Further improve migration ([@ghostbear](https://github.com/ghostbear)) ([#588](https://github.com/mihonapp/mihon/pull/588))
  - Fix migrations not running ([@ghostbear](https://github.com/ghostbear)) ([#604](https://github.com/mihonapp/mihon/pull/604))
  - Fix MigratorTest after updating to Kotlin 2 ([@cuong-tran](https://github.com/cuong-tran)) ([#896](https://github.com/mihonapp/mihon/pull/896))
  - Add MigratorTest to build script ([@cuong-tran](https://github.com/cuong-tran)) ([#896](https://github.com/mihonapp/mihon/pull/896))
  - Fix UI freeze after migration ([@AntsyLich](https://github.com/AntsyLich)) ([`3f1d28c`](https://github.com/mihonapp/mihon/commit/3f1d28c3833e6b868152149ed02b3fb8c54eccef))
  - Fix some migrations never running ([@MajorTanya](https://github.com/MajorTanya), [@AntsyLich](https://github.com/AntsyLich)) ([#1030](https://github.com/mihonapp/mihon/pull/1030))
- Add ProGuard rule to keep `mihon` namespace classes ([@MajorTanya](https://github.com/MajorTanya)) ([#605](https://github.com/mihonapp/mihon/pull/605))
- Use gradle plugins to share build configuration instead of subprojects ([@AntsyLich](https://github.com/AntsyLich)) ([`e448e40`](https://github.com/mihonapp/mihon/commit/e448e40406e8d9916120a278e42829a6f1b25a7a))
- Remove dependency on compose material 2 components ([@AntsyLich](https://github.com/AntsyLich)) ([`fb94230`](https://github.com/mihonapp/mihon/commit/fb9423028eb017c110cb805f2d0601e5b02e50f9))
- Upload PR build artifacts to GitHub ([@FooIbar](https://github.com/FooIbar)) ([#941](https://github.com/mihonapp/mihon/pull/941))
- Refactor archive support with libarchive ([@FooIbar](https://github.com/FooIbar)) ([#949](https://github.com/mihonapp/mihon/pull/949))
  - Add safeguard to prevent ArchiveInputStream from being closed twice ([@null2264](https://github.com/null2264)) ([#967](https://github.com/mihonapp/mihon/pull/967))
  - Move archive related code to :core:archive ([@AntsyLich](https://github.com/AntsyLich)) ([`bd7b354`](https://github.com/mihonapp/mihon/commit/bd7b35419861df6d426d6ec0a188391910d0f615))
- Replace detekt with ktlint via spotless ([@AntsyLich](https://github.com/AntsyLich)) ([#1130](https://github.com/mihonapp/mihon/pull/1130), [#1136](https://github.com/mihonapp/mihon/pull/1136), [#1138](https://github.com/mihonapp/mihon/pull/1138))
  - Refrain from running spotless on weblate files ([@AntsyLich](https://github.com/AntsyLich)) ([`32d2c2a`](https://github.com/mihonapp/mihon/commit/32d2c2ac1bc224cbda2f09a4023d7d120ea0e954))
- Use feature flags in compose compiler plugin ([@AntsyLich](https://github.com/AntsyLich)) ([`8f9a325`](https://github.com/mihonapp/mihon/commit/8f9a325895bb7b94c2ec92dd969094fc30b3b5e2))
- PagerPageHolder: lazy init loading indicator ([@AntsyLich](https://github.com/AntsyLich), [@ivaniskandar](https://github.com/ivaniskandar)) ([`a45eb5e`](https://github.com/mihonapp/mihon/commit/a45eb5e5288159dbbbbb5f92140ce0dd32a8f3ab))
- Collect MangaScreen state with lifecycle ([@AntsyLich](https://github.com/AntsyLich), [@ivaniskandar](https://github.com/ivaniskandar)) ([`03eb756`](https://github.com/mihonapp/mihon/commit/03eb756ecba0692d88d3a76254afc4c157fa225b))
- Add stable marker to Manga data class ([@AntsyLich](https://github.com/AntsyLich), [@ivaniskandar](https://github.com/ivaniskandar)) ([`03eb756`](https://github.com/mihonapp/mihon/commit/03eb756ecba0692d88d3a76254afc4c157fa225b))
- Use DTOs to parse tracking API responses ([@MajorTanya](https://github.com/MajorTanya)) ([#1103](https://github.com/mihonapp/mihon/pull/1103))
  - Fix Kitsu ratingTwenty being typed as String ([@MajorTanya](https://github.com/MajorTanya)) ([#1191](https://github.com/mihonapp/mihon/pull/1191))
  - Fix Kitsu `synopsis` nullability ([@MajorTanya](https://github.com/MajorTanya)) ([#1233](https://github.com/mihonapp/mihon/pull/1233))
  - Fix AniList `ALSearchItem.status` nullibility ([@Secozzi](https://github.com/Secozzi)) ([#1297](https://github.com/mihonapp/mihon/pull/1297))
- Migrate some classpaths to gradle plugins ([@AntsyLich](https://github.com/AntsyLich)) ([`fc1c804`](https://github.com/mihonapp/mihon/commit/fc1c804bfda1d76c0399bbb6214e75b3def951cc))
- Add crashlytics to standard builds ([@AntsyLich](https://github.com/AntsyLich)) ([`3c611b9`](https://github.com/mihonapp/mihon/commit/3c611b95fb79e5ac972019b76c7b24f46a3087fd))
- Switch to stable compose ([@AntsyLich](https://github.com/AntsyLich)) ([`2baffa6`](https://github.com/mihonapp/mihon/commit/2baffa62cade1abd978d5fd03151b47fc87fd31e))
- Switch from inorichi injekt to kohesive Injekt ([@AntsyLich](https://github.com/AntsyLich)) ([#1205](https://github.com/mihonapp/mihon/pull/1205))
  - Use custom injekt register with inorichi patch ([@AntsyLich](https://github.com/AntsyLich)) ([`83fd474`](https://github.com/mihonapp/mihon/commit/83fd4746eda1b99f35292b0c2211e606a421b3eb))
- Use TextFieldState in BasicTextField where applicable (p) ([#1201](https://github.com/mihonapp/mihon/pull/1201))
- Bump NDK version ([@AntsyLich](https://github.com/AntsyLich)) ([#1203](https://github.com/mihonapp/mihon/pull/1203))
- Move firebase permission removal to standard flavor ([@AntsyLich](https://github.com/AntsyLich)) ([`be671b4`](https://github.com/mihonapp/mihon/commit/be671b42cefd70180644e01bb065a18cb7701bf9))
- Adjust distinct checker in WidgetManager and run on default dispatcher (p) ([`9b8ab6a`](https://github.com/mihonapp/mihon/commit/9b8ab6acc25a5f99c9c5eebf9cc250975931c57c))
- Update resources exclusion rules (p) ([`481cfed`](https://github.com/mihonapp/mihon/commit/481cfedf08576cecfbb35616837bd8f627d8f959))
- Bump compile sdk to 35 (p) ([`37419cd`](https://github.com/mihonapp/mihon/commit/37419cdc26c2b5c4f8583fc2ba439b08fab42856))
- ChapterNavigator: dispatch page change only when needed (p) ([`f84d9a0`](https://github.com/mihonapp/mihon/commit/f84d9a08b4af768b1e9920c43cc445c86f5427fc))
- Remove usage of deprecated accompanist SystemUiController ([@AntsyLich](https://github.com/AntsyLich)) ([`2ba3f06`](https://github.com/mihonapp/mihon/commit/2ba3f0612c08c7021fed2f6d96cd538da2f34a13))
- Run PR check when base strings are changed ([@AntsyLich](https://github.com/AntsyLich)) ([`4051f18`](https://github.com/mihonapp/mihon/commit/4051f180a2e36e8a2cde6c55f0bea7952fdc4704))
  - Fix PR build check ([@AntsyLich](https://github.com/AntsyLich)) ([`9503082`](https://github.com/mihonapp/mihon/commit/9503082d44b5bd868ee1bfc42741dc978d1d9047))
- Cleanup .gitignore files ([@AntsyLich](https://github.com/AntsyLich)) ([`afa5002`](https://github.com/mihonapp/mihon/commit/afa50029882655af8d5eea40aed7644fce4564d8))
- Pass uncaught exception to default handler in GlobalExceptionHandler (so it's reported to crashlytics) ([@AntsyLich](https://github.com/AntsyLich)) ([`f3a2f56`](https://github.com/mihonapp/mihon/commit/f3a2f566c8a09ab862758ae69b43da2a2cd8f1db))

## [v0.16.5] - 2024-04-09
### Added
- Relative date for up to a week in the future ([@sirlag](https://github.com/sirlag)) ([#415](https://github.com/mihonapp/mihon/pull/415))
- Advance setting to install custom color profiles ([@wwww-wwww](https://github.com/wwww-wwww)) ([#523](https://github.com/mihonapp/mihon/pull/523))

### Changed
- Permanently enable 32-bit color mode ([@wwww-wwww](https://github.com/wwww-wwww)) ([#523](https://github.com/mihonapp/mihon/pull/523))

### Fixed
- Wrong dates in Updates and History tab due to time zone issues ([@sirlag](https://github.com/sirlag)) ([#402](https://github.com/mihonapp/mihon/pull/402))
  - Fix extra date header introduced by parent PR ([@sirlag](https://github.com/sirlag)) ([#415](https://github.com/mihonapp/mihon/pull/415))
  - Fix build time in about screen displayed in UTC ([@AntsyLich](https://github.com/AntsyLich)) ([`aed53d3`](https://github.com/mihonapp/mihon/commit/aed53d3bdc85ce0e899fbb90b9f9cad0f1b86480))
- App infinitely retries tracker update instead of failing after 3 tries ([@MajorTanya](https://github.com/MajorTanya)) ([#411](https://github.com/mihonapp/mihon/pull/411))
- Crash on Pixel devices (was introduced due to compose update) ([@AntsyLich](https://github.com/AntsyLich)) ([`ab06720`](https://github.com/mihonapp/mihon/commit/ab067209661eceefc04c65f6bdbfcaa8a1264651))
- Crash when opening some heif/heic images ([@az4521](https://github.com/az4521)) ([#466](https://github.com/mihonapp/mihon/pull/466))
- Crash when putting app in background while track date selection dialog is open ([@ivaniskandar](https://github.com/ivaniskandar)) ([`c348fac`](https://github.com/mihonapp/mihon/commit/c348fac78fac479fb123bd617c01c78b9ca851d5))
- Dates for saved images not following the specification (fixes date issue mainly on Samsung devices) ([@MajorTanya](https://github.com/MajorTanya)) ([#552](https://github.com/mihonapp/mihon/pull/552))
- Colors getting distorted when opening CMYK jpeg images ([@wwww-wwww](https://github.com/wwww-wwww)) ([#523](https://github.com/mihonapp/mihon/pull/523))

## [v0.16.4] - 2024-02-27
### Changed
- Don't include custom user agent for MAL (circumvents MAL block) ([@AntsyLich](https://github.com/AntsyLich)) ([`085ad8d`](https://github.com/mihonapp/mihon/commit/085ad8d44637c375a8ed24aba3a6f75f5b0cc9ee))

## [v0.16.3] - 2024-01-30
### Added
- Copy extension debug info when clicking logo or name in the extension details screen ([@MajorTanya](https://github.com/MajorTanya)) ([#271](https://github.com/mihonapp/mihon/pull/271))

### Changed
- Hide display cutoff setting in reader settings sheet if fullscreen is disabled ([@Riztard](https://github.com/Riztard)) ([#241](https://github.com/mihonapp/mihon/pull/241))
- Library update error filename to `mihon_update_errors.txt` from `tachiyomi_update_errors.txt` ([@mjishnu](https://github.com/mjishnu)) ([#253](https://github.com/mihonapp/mihon/pull/253))

### Fixed
- Bottom sheet UI issues on non-tablet devices ([@theolm](https://github.com/theolm)) ([#182](https://github.com/mihonapp/mihon/pull/182))
- Crash when switching screen while a list is scrolling ([@theolm](https://github.com/theolm)) ([#272](https://github.com/mihonapp/mihon/pull/272))
- Newly installed extensions not being recognized by Mihon ([@AwkwardPeak7](https://github.com/AwkwardPeak7)) ([#275](https://github.com/mihonapp/mihon/pull/275))
- Failing to refresh MAL token being inferred as token expiration ([@AntsyLich](https://github.com/AntsyLich)) ([`0f4de03`](https://github.com/mihonapp/mihon/commit/0f4de03d7a77b52490dc9a95e96a308b93b26e4f))

### Other
- Add `detekt` (kotlin code analyzer) to the project ([@theolm](https://github.com/theolm)) ([#216](https://github.com/mihonapp/mihon/pull/216))

## [v0.16.2] - 2024-01-28
### Changed
- Backup now contains scanlator filter of a series ([@jobobby04](https://github.com/jobobby04)) ([#166](https://github.com/mihonapp/mihon/pull/166))
- App icon scaling ([@AntsyLich](https://github.com/AntsyLich)) ([`26815c7`](https://github.com/mihonapp/mihon/commit/26815c7356111394665467c1e81255ac9ee33c1a))
- Tracker OAuth client to Mihon's (fixes login issue for Shikimori tracker) ([@AntsyLich](https://github.com/AntsyLich)) ([`e3f33e2`](https://github.com/mihonapp/mihon/commit/e3f33e24f5e928ac8a85d1f500fd42d4715fc6b5))
- Tracker user agents ([@AntsyLich](https://github.com/AntsyLich), [@kitsumed](https://github.com/kitsumed)) ([`e3f33e2`](https://github.com/mihonapp/mihon/commit/e3f33e24f5e928ac8a85d1f500fd42d4715fc6b5))
- Crash log filename to `mihon_crash_logs.txt` from `tachiyomi_crash_logs.txt` ([@MajorTanya](https://github.com/MajorTanya)) ([#234](https://github.com/mihonapp/mihon/pull/234))
- Don't try to refresh MAL token after refresh token expires ([@AntsyLich](https://github.com/AntsyLich)) ([`32188f9`](https://github.com/mihonapp/mihon/commit/32188f9f65009a18250674ef1bd6e57d351c1fba))

### Fixed
- "Flash screen on page change" making the screen full black ([@AntsyLich](https://github.com/AntsyLich)) ([`38d6ab8`](https://github.com/mihonapp/mihon/commit/38d6ab80ce868707829dbc81de4170afe3c2f2a5))
- Faulty MangaUpdates score in database ([@AntsyLich](https://github.com/AntsyLich)) ([`a024218`](https://github.com/mihonapp/mihon/commit/a024218410953a389b8af4880fa7ae6cc30124a2))
- Updating extension not reflecting correctly ([@AntsyLich](https://github.com/AntsyLich)) ([`cb06898`](https://github.com/mihonapp/mihon/commit/cb068984303f811692531bf6f14902ae118d8ac7))
- Inconsistent button height in "Data and storage" for some languages ([@theolm](https://github.com/theolm)) ([#202](https://github.com/mihonapp/mihon/pull/202))
- Chapter not being marked as read locally when refreshing Enhanced Trackers ([@Secozzi](https://github.com/Secozzi)) ([#219](https://github.com/mihonapp/mihon/pull/219))

### Other
- Make `last_modified_at` field in database be `0` on insert ([@kaiserbh](https://github.com/kaiserbh)) ([#113](https://github.com/mihonapp/mihon/pull/113))
- Remove usage of `.not()` where possible in code ([@AntsyLich](https://github.com/AntsyLich)) ([`3940740`](https://github.com/mihonapp/mihon/commit/39407407f282dbb7fa972b12053c26b3e3bd66d8))
- Use type-safe project accessors ([@theolm](https://github.com/theolm)) ([#194](https://github.com/mihonapp/mihon/pull/194))
- Legacy tracker model properties now has the same type as the domain ones ([@AntsyLich](https://github.com/AntsyLich)) ([#245](https://github.com/mihonapp/mihon/pull/245))

## [v0.16.1] - 2024-01-18
### Changed
- Branding to Mihon (for references we missed) ([@AntsyLich](https://github.com/AntsyLich)) ([`6539406`](https://github.com/mihonapp/mihon/commit/653940613d661eb371aab3b3c3a8181e4e308c43))
- Preview builds are now called Beta builds ([@AntsyLich](https://github.com/AntsyLich)) ([`3c3a1cd`](https://github.com/mihonapp/mihon/commit/3c3a1cd448ab1f653ddd12b2afe0cba38968d1b9))

### Fixed
- App icon not following the [specification](https://developer.android.com/develop/ui/views/launch/icon_design_adaptive) ([@AntsyLich](https://github.com/AntsyLich)) ([`1849715`](https://github.com/mihonapp/mihon/commit/18497154183356bb0d469b27827f9f7d6b7a3130))
- MangaUpdates default score being set to -1.0 ([@AntsyLich](https://github.com/AntsyLich)) ([`99fd273`](https://github.com/mihonapp/mihon/commit/99fd2731f5d9d374700e89fa67d4d5bf611bbafa))

## [v0.16.0] - 2024-01-16
### Changed
- Branding to Mihon ([@AntsyLich](https://github.com/AntsyLich))
- Minimum supported Android version to 8 ([@AntsyLich](https://github.com/AntsyLich)) ([`dfb3091`](https://github.com/mihonapp/mihon/commit/dfb3091e380dda3e9bfb64bf5c9a685cf3a03d0e))

[unreleased]: https://github.com/mihonapp/mihon/compare/v0.20.0...main
[v0.20.0]: https://github.com/mihonapp/mihon/compare/v0.19.9...v0.20.0
[v0.19.9]: https://github.com/mihonapp/mihon/compare/v0.19.8...v0.19.9
[v0.19.8]: https://github.com/mihonapp/mihon/compare/v0.19.7...v0.19.8
[v0.19.7]: https://github.com/mihonapp/mihon/compare/v0.19.6...v0.19.7
[v0.19.6]: https://github.com/mihonapp/mihon/compare/v0.19.5...v0.19.6
[v0.19.5]: https://github.com/mihonapp/mihon/compare/v0.19.4...v0.19.5
[v0.19.4]: https://github.com/mihonapp/mihon/compare/v0.19.3...v0.19.4
[v0.19.3]: https://github.com/mihonapp/mihon/compare/v0.19.2...v0.19.3
[v0.19.2]: https://github.com/mihonapp/mihon/compare/v0.19.1...v0.19.2
[v0.19.1]: https://github.com/mihonapp/mihon/compare/v0.19.0...v0.19.1
[v0.19.0]: https://github.com/mihonapp/mihon/compare/v0.18.0...v0.19.0
[v0.18.0]: https://github.com/mihonapp/mihon/compare/v0.17.1...v0.18.0
[v0.17.1]: https://github.com/mihonapp/mihon/compare/v0.17.0...v0.17.1
[v0.17.0]: https://github.com/mihonapp/mihon/compare/v0.16.5...v0.17.0
[v0.16.5]: https://github.com/mihonapp/mihon/compare/v0.16.4...v0.16.5
[v0.16.4]: https://github.com/mihonapp/mihon/compare/v0.16.3...v0.16.4
[v0.16.3]: https://github.com/mihonapp/mihon/compare/v0.16.2...v0.16.3
[v0.16.2]: https://github.com/mihonapp/mihon/compare/v0.16.1...v0.16.2
[v0.16.1]: https://github.com/mihonapp/mihon/compare/v0.16.0...v0.16.1
[v0.16.0]: https://github.com/mihonapp/mihon/compare/a9c7cbf...v0.16.0
