<!--
Curated release notes for the NEXT version. Append user-facing bullets here as
work lands, grouped under the headings below, then bump versionName and push.
CI renders this into the release body, and `--cut` archives it into
CHANGELOG.md and clears it after the release.

Write for someone deciding whether to update: what changed for them, not which
files moved. See .github/scripts/release_notes.py.
-->

### New

- **Downloaded & cached content** screen (More → Data and storage): every download on disk whose entry is not in your library is now listed — grouped by source, with chapter count and disk size — and can be deleted per chapter, per entry, or all at once. Entries whose source extension is gone show as folders; merge children are excluded so a library merge's content is never listed.
- **Chapter cache retention**: a new setting drops cached chapters once unread for a chosen time (12 hours to 30 days; default off). Reading a chapter resets its timer, and the new discovery screen shows each cached chapter's live countdown — or "kept until space is needed" when retention is off.
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
