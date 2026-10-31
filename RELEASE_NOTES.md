<!--
Curated release notes for the NEXT version. Append user-facing bullets here as
work lands, grouped under the headings below, then bump versionName and push.
CI renders this into the release body, and `--cut` archives it into
CHANGELOG.md and clears it after the release.

Write for someone deciding whether to update: what changed for them, not which
files moved. See .github/scripts/release_notes.py.
-->

### New

### Improve

### Fix

- Migrating merged entries now keeps the merge intact. Previously, migrating a merged entry tore it apart: the merged entry was unfavorited and replaced by a single standalone entry, leaving every other merged source behind. The merged entry now stays in the library and the new source simply replaces the one the merge was built from (or is added as an extra source when copying). Migrating one source of a merged entry — for example from the mass-migration list — now updates the merge to the new source instead of leaving the old, removed source behind. Read progress, bookmarks and read history carry over as with a normal migration.
- Fixed a crash when opening a manga's cover preview on a source that serves covers in AVIF, JXL or HEIF (Atsumaru, for one). The preview asked the new image decoder for its raw pixels while another decoder also claimed those formats and won the race, so the app tried to treat an ordinary bitmap as raw pixel data and died on the main thread. The request now reaches the decoder it asked for, and a cover that arrives in an unexpected form leaves the preview empty instead of taking the app down with it.
- Fix the v1.25.0 database migration failing for users updating from v1.24.5, which left the app unable to open its library on launch: a leftover sync trigger referenced the old tables mid-rework, and a duplicate-merge step could null out required fields. The emergency build migrates cleanly; affected libraries keep their entries, read state and categories.
