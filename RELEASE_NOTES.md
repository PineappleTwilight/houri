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

- Remove the redundant back button next to the About title on tablet UI: it could pop the About pane out of the two-pane settings layout and drop the app into the phone-style settings screen.
- Fixed a crash when opening a manga's cover preview on a source that serves covers in AVIF, JXL or HEIF (Atsumaru, for one). The preview asked the new image decoder for its raw pixels while another decoder also claimed those formats and won the race, so the app tried to treat an ordinary bitmap as raw pixel data and died on the main thread. The request now reaches the decoder it asked for, and a cover that arrives in an unexpected form leaves the preview empty instead of taking the app down with it.
- Fix the v1.25.0 database migration failing for users updating from v1.24.5, which left the app unable to open its library on launch: a leftover sync trigger referenced the old tables mid-rework, and a duplicate-merge step could null out required fields. The emergency build migrates cleanly; affected libraries keep their entries, read state and categories.
- Fixed the tablet About screen showing a redundant back arrow next to its title: tapping it collapsed the two-pane settings layout into the full-width phone layout. The arrow now hides in the detail pane like on every other settings category, where the settings list itself handles navigation.
- Fixed the migration source picker (the screen that chooses which sources to search when migrating) listing every language of every installed extension: disabled languages appeared in the picker, were saved as migration sources, and were then searched on every migration. The picker now respects the enabled languages configured in Browse settings, and the saved migration-source list corrects itself the next time migration runs.
