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
- **Fixed pages refusing to load in the WebGPU long-strip reader.** A long page is cut into pieces to be decodable, and if the device dropped one of those pieces from its cache the reader kept asking for a file that was no longer there and never recovered — leaving a blank strip at that spot, most often when a new chapter started loading while you were still scrolling. The reader now asks the source for that piece again, and a page it has retried too many times is handed back to be retried instead of being abandoned for the rest of the session.
