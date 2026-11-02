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

- **Extension updates through the Shizuku installer register immediately.** The install session now asks Android to skip its package-verification step (the Play Protect consult), which previously held every update for 5-10 seconds before the app could see it — exactly what `pm install --skip-verification` does for shell installs. First-time installs are still scanned by the platform, by its own rule; use the Private installer if you also want those to register instantly.

### Fix
