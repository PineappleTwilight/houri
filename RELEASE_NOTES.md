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

- Fixed a native crash on the WebGPU reader (abort `JNI ERROR (app bug): accessed deleted Global`, seconds after a black screen following a device loss): a device retired by the recovery ladder could still carry a pending driver-callback record, and freeing its wrapper deleted a reference that record was later marshalled through when the next frame was submitted. Retired devices now free their GPU memory without dropping that reference, and stale callback dispatches are ignored instead of driving spurious recoveries.
