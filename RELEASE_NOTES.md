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

- WebGPU reader: fixed a crash when rotating the device while a page was rendering. The renderer could submit a frame to a window the system was tearing down mid-rotation, killing the app with an abort in the GPU driver (SIGABRT in `GPUQueue_submit`). Frames are now drained and gated during surface teardown, so rotation only relayouts the reader.
