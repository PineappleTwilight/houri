<!--
Curated release notes for the NEXT version. Append user-facing bullets here as
work lands, grouped under the headings below, then bump versionName and push.
CI renders this into the release body, and `--cut` archives it into
CHANGELOG.md and clears it after the release.

Write for someone deciding whether to update: what changed for them, not which
files moved. See .github/scripts/release_notes.py.
-->

### New
- **Opening a category now lands on its first subcategory when the "All" subcategory is turned off.** Previously it still showed every title from every subcategory combined, with no "All" chip left to narrow it back down.

### Improve

### Fix
- **Fixed global search hanging the app with covers that never load.** Using global search left every result spinning and the app unusable until it was force-closed; searching a single source was unaffected. Only on Houri and Komikku previews — Mihon and Chimahon were never affected.
- **A single broken page no longer stops every other page from loading.** The WebGPU reader decoded everything on one thread, with no timeout at all in the path, so a page that never finished decoding left the rest of the chapter queued behind it indefinitely.
- Pages that arrive as something other than an image — an error or captcha page served in place of a real page — are now spotted before they reach the image decoder, and your source is asked for that page again instead of the reader giving up on it.
