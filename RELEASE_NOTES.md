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
- Fixed a crash when opening a manga's cover preview on a source that serves covers in AVIF, JXL or HEIF (Atsumaru, for one). The preview asked the new image decoder for its raw pixels while another decoder also claimed those formats and won the race, so the app tried to treat an ordinary bitmap as raw pixel data and died on the main thread. The request now reaches the decoder it asked for, and a cover that arrives in an unexpected form leaves the preview empty instead of taking the app down with it.
