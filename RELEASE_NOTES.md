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

- Update the default user agent string to a current Chrome version so sites and their bot protection no longer treat app requests as an outdated browser (only affects installs that never customized the agent; "Reset user agent" in advanced settings picks it up)

### Fix

- Fix the loading circle shown while the migration list searches a source sitting off-center in the row; it is now aligned with the cover slot where the result will appear

- Fix a crash in the scanlator priority reorder screen when dragging a row while the automatic-priority note is shown (`IndexOutOfBoundsException`), and items no longer move from the wrong position in that state
