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
- Details: chapter filters, sorting, display mode and download actions now apply immediately after you visit the scanlator priority screen again — no need to leave and re-open the entry first
- About: tapping the version text copies it to the clipboard, like Mihon and other forks
- Migration: with smart search off, the manual search results now show each candidate's latest chapter number as it is fetched, matching the migration list (new "Fetch chapters in manual search" option in migration options, on by default)
- Reader: a wedged WebGPU swapchain (PowerVR: `getCurrentTexture` returning `Error` on every frame, black screen with no crash) now escalates — the renderer tears the surface down and rebuilds it after ~0.5s of consecutive failures, and parks with a single reported outage instead of retrying forever. Also stops the decode-queue churn the retry storm fed.

### Fix

- Fix the loading circle shown while the migration list searches a source sitting off-center in the row; it is now aligned with the cover slot where the result will appear
- Reader: rotating the screen no longer kicks you out of a WebGPU reader chapter; the reader now resizes in place instead of recreating the activity
- Reader (longstrip/continuous): reaching the end of the last chapter now pops the menu like the other viewers do
- MangaBaka: entries sitting on the current MangaBaka chapter total are no longer auto-marked Completed while the series is still releasing; only finished series complete on catching up
- Tablet UI: the debug info screen no longer shows a back button in two-pane settings (tapping it dropped out to the phone layout)

- Fix a crash in the scanlator priority reorder screen when dragging a row while the automatic-priority note is shown (`IndexOutOfBoundsException`), and items no longer move from the wrong position in that state

- Reader: the rare thin line between pages in the continuous/webtoon WebGPU viewer. Adjacent pages' tile clip edges were two float derivations of the same boundary and could disagree at a pixel centre, leaving a boundary row to neither page (the background showed through). Both sides now clamp at one shared value, so every row belongs to exactly one page.
- Reader: a page evicted mid-decode could stay latched as decoding, needing the stuck-page sweep to recover it; the worker now always clears its own latch.
