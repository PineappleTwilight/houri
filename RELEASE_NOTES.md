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

- Fix a crash in the scanlator priority reorder screen when dragging a row while the automatic-priority note is shown (`IndexOutOfBoundsException`), and items no longer move from the wrong position in that state
