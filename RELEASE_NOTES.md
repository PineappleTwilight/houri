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

- Reader: a wedged WebGPU swapchain (PowerVR: `getCurrentTexture` returning `Error` on every frame, black screen with no crash) now escalates — the renderer tears the surface down and rebuilds it after ~0.5s of consecutive failures, and parks with a single reported outage instead of retrying forever. Also stops the decode-queue churn the retry storm fed.

### Fix

- Reader: the rare thin line between pages in the continuous/webtoon WebGPU viewer. Adjacent pages' tile clip edges were two float derivations of the same boundary and could disagree at a pixel centre, leaving a boundary row to neither page (the background showed through). Both sides now clamp at one shared value, so every row belongs to exactly one page.
- Reader: a page evicted mid-decode could stay latched as decoding, needing the stuck-page sweep to recover it; the worker now always clears its own latch.
