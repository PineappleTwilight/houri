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

- SyncYomi now uses protocol v2 (server-side merge and delta sync), with automatic fallback to v1 on older servers

### Fix

- SyncYomi/sync no longer skips most of the library: entries restored from a backup were missing the favorite stamp the uploader required, so chapter progress never left the device (run **Sync now** once after updating so the full library can upload)
- SyncYomi now applies remote un-favourites and drops chapters tombstoned on another device
- SyncYomi full-library syncs allow up to 5 minutes per request so large libraries stop timing out mid-upload
- SyncYomi no longer fails to apply a sync when tracker/history fields the server omits (proto3 defaults) are missing from the response
