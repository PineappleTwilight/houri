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

- **WebGPU reader:** fixed reading position and read-state lagging behind actual progress — sometimes by whole chapters. Progress is now saved even when a page's load has failed, and the WebGPU reader reports the exact page its scroll actually reached, so reopening a manga resumes where you left off and the Manga Details screen reflects it immediately.
