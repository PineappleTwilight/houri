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

### Improve

### Fix

- WebGPU reader: fixed a crash when rotating the device while a page was rendering. The renderer could submit a frame to a window the system was tearing down mid-rotation, killing the app with an abort in the GPU driver (SIGABRT in `GPUQueue_submit`). Frames are now drained and gated during surface teardown, so rotation only relayouts the reader.
