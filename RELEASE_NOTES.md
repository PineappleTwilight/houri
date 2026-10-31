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

- **Network inspection mode** (log level *Extreme*) now captures requests from all of the app's own networking — the E-Hentai API, image loads, downloads, sync — not just source page fetches, and it no longer logs the same request twice for source traffic.
- **Log level *Extra*** ("Log everything") now records request/response headers into the app's log files. The header logging previously went to an output nothing could read, so those lines never appeared anywhere.

### Fix

- **In-app logs kept losing their content.** The daily log file in the app's `logs` folder was re-opened in overwrite mode on every app start, wiping everything earlier sessions had written — a multi-session day left you with a file holding only the last few boot lines. The file is now appended to, like the upstream logging library always intended, so a day's log keeps its whole history.

### Fix

- Fixed a crash when opening a manga's cover preview on a source that serves covers in AVIF, JXL or HEIF (Atsumaru, for one). The preview asked the new image decoder for its raw pixels while another decoder also claimed those formats and won the race, so the app tried to treat an ordinary bitmap as raw pixel data and died on the main thread. The request now reaches the decoder it asked for, and a cover that arrives in an unexpected form leaves the preview empty instead of taking the app down with it.
- Fix the v1.25.0 database migration failing for users updating from v1.24.5, which left the app unable to open its library on launch: a leftover sync trigger referenced the old tables mid-rework, and a duplicate-merge step could null out required fields. The emergency build migrates cleanly; affected libraries keep their entries, read state and categories.
