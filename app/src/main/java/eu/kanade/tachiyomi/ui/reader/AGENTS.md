# ui/reader/ — Reader

Fullscreen reading surface. Deliberately a classic `Activity`, NOT a Nav3 route (fullscreen, `singleTask`, S-Pen remote actions, container transforms).

## Entry

- `ReaderActivity.kt` — `singleTask` Activity. `ReaderActivity.newIntent(context, mangaId, chapterId, page)`.
  Uses AndroidX `viewModels<ReaderViewModel> { graph.viewModelFactory }` + `graph.inject(this)` with
  `@Inject lateinit var` fields. `ReaderViewModel.kt` holds chapter/page state (AndroidX ViewModel).

## Key directories

| Path | Purpose |
|------|---------|
| `viewer/` | `Viewer.kt` + `ViewerConfig.kt` contracts; `pager/` (strip) and `navigation/` (webtoon/LTR/RTL/vertical) viewers; `ReaderPageImageView`, progress/transition views |
| `viewer/PageTranslator.kt`, `viewer/ReaderTranslation.kt` | MTL translation overlay hookup — engine itself lives in `yakuyomi-engine/` (see its AGENTS.md) |
| `loader/` | `PageLoaderFactory` → `PageLoader`: `HttpPageLoader`, `ArchivePageLoader`, `DirectoryPageLoader`, `DownloadPageLoader`, `EpubPageLoader` |
| `model/` | `ReaderChapter`, `ReaderPage`, `ReaderItem`, `ViewerChapters`, `ChapterTransition`, `InsertPage` |
| `setting/` | `ReaderPreferences`, `ReadingMode`, `ReaderSettingsScreenModel`, `ReaderOrientation`, `ReaderBottomButton` |
| `setting/Upscale*.kt` | On-device upscale stack (MTL-gated): `UpscaleReaderHook` → `UpscaleEngine` (+ `UpscaleBackendDetector`), `UpscaleModelManager`, `UpscaleCacheManager`, `UpscaleMangaStore`, `UpscalePreferences` |
| `chapter/`, `loader/` | `ReaderChapterItem`, chapter loading pipeline |

## WebGPU viewer (`viewer/webgpu/`)

`WebGpuViewer.kt` is wiring only — fields, pager callbacks, chapter swap, navigation. Everything with
a state machine or a retry ladder is a top-level extension beside it, one concern per file:

| File | Concern |
|------|---------|
| `WebGpuViewer.kt` | the class: fields, `fetchPage`/`onPageChange` wiring, `setChapters`, navigation, lifecycle |
| `WebGpuPageModel.kt` | `ViewerPage` hierarchy + split-aware `prev`/`next` neighbour links |
| `WebGpuPageCache.kt` | page residency, eviction policy, `fetchPage` resolution, split reconciliation (`syncPageList`) |
| `WebGpuDecodeQueue.kt` | what the worker owes a page (`queueForDecode`) + the stuck-page retry ladder |
| `WebGpuDecodePipeline.kt` | bytes → GPU image for one page |
| `WebGpuViewerWorkers.kt` | the three long-lived coroutines (decode worker, liveness sweep, progress spinner) |
| `WebGpuViewerImageState.kt` | preferences → filters, transition, zoom, HUD |
| `WebGpuSpread.kt` / `WebGpuSpreadHeightMatch.kt` | dual-page pairing / height matching |
| `WebGpuZoom.kt`, `WebGpuExif.kt` | per-page presentation, reapplied after every decode and every image swap |
| `WebGpuPlaceholderPages.kt` | `ProgressPage` / `ErrorPage` / `TransitionPage` — drawn procedurally |
| `WebGpuRestore.kt` | deferred reading-position restore, deferred until the target page has real heights |

The render engine is a git **submodule** (`external/webgpuviewer-houri`, `:webgpuviewer`); the app
never fetches images itself — it hands the library an `ImagePage` per page and the library draws it.

**Read `viewer/webgpu/AGENTS.md` before debugging anything in this folder.** It holds the thread
model, the page state machine, the stuck-page ladder, the device-recovery rule (an adapter is
consumed and cannot be reused), a symptom→cause table, and the landmines that have already cost
an incident each.

## The novel reader's page modes: continuous, paged, double (KMK)

The viewer has three layouts, one setting (`pref_novel_page_mode`): **continuous** (the original
scroll, sections appended ahead of the reader), **paged** (one column per screen, turned like a
paper book) and **double** (two columns per screen, the open spread).

Pagination is CSS multi-column inside the same WebView: `body { overflow: hidden; height: <viewport> }`
plus `column-count` splits the flow into column boxes, and the **body itself becomes the scroll
container** — `overflow: hidden` boxes are script-scrollable but not gesture-scrollable, which is
exactly the division a paged book wants. `KmkPaged` (in `NovelPageLayout.kt`) is installed only in
paged documents; it measures the reader's computed margins, sizes the column box, and exposes a
*normalised* position where forward is always `+1` — the sign of `scrollLeft` is the bridge's
business, because a vertical-writing (縦書き) or RTL book lays columns right-to-left and scrolls
toward **negative** offsets. The host never learns the sign, only `pagedForwardIsLeft` for the tap
zones.

Everything the continuous layer does is inert in a paged document: no probe (nothing scrolls for
it to watch), no appends (a reflowed column box would renumber the reader's page), no stuck-edge
tracking. Position arrives from the turns themselves; read-ahead warms the next document's markup
when the reader nears the last page. A mode change re-renders the open document, carrying the
reader's place as the document fraction — the one quantity that survives a reflow.

**The document build runs on `Dispatchers.Default`.** The inliner base64-encodes every plate and
stylesheet a document reads — megabytes of string surgery on an illustrated book — and building
on the main thread cost a visible frame hitch on every turn. `showDocument` reads, inlines and
builds in the background (guarded by the request token after each suspension), and only the
`loadDataWithBaseURL` hop and the state the reader sees land on main. The continuous appends build
their fragments the same way, and `readAhead` warms one document past wherever the reader is
heading.

**The flow direction is the document's answer.** `NovelScrollBridge.FLOW` reports, after layout,
whether the flow runs horizontally (vertical writing or RTL). Continuous mode's `step()` scrolls
the matching axis — a vertical-writing book scrolled only vertically never moved, so every tap used
to read as "the document's edge" and skipped a whole section at a time — and the tap zones flip
with it (`forwardOnRight`). Paged mode gets the same fact from its bridge at setup.

## The cover as a first page (KMK)

Most EPUBs keep the cover out of the spine — it lives in the package metadata (`cover-image` or the
legacy `<meta name="cover">`), so a spine-walking reader never shows it. `EpubTextSource` (in
`core/archive`) prepends a synthesized cover page at index 0 when the reader's `wantsCoverPage`
lambda says so (default **on**; the app combines it with "show illustrations", since a text-only
reading has no use for a plate), the book declares a cover, and the spine's first document does not
already embed it. Every document after it shifts up by one — search hits, positions and the page
list all speak the same shifted coordinates, and `NovelProgress.Position` carries the document's
**URL** beside its index so a stored position still resolves after the list was renumbered. The
cover's markup is a complete document naming the cover image by archive path, resolved through the
archive-root base; the reader's injected stylesheet styles it (`body.kmk-cover`,
`img#kmk-cover-img` — emitted in every document, inert everywhere but the cover).

## Novel / text reader (`viewer/novel/`, KMK)

Renders text chapters (EPUB, web novel) in a WebView rather than in a native text widget. Chosen
because an EPUB *is* an XHTML document with the publisher's own stylesheet — drop caps, ruby,
columns, vertical writing, tables. Re-implementing that in Compose means re-implementing a browser.
The reader's preferences are layered on as an injected stylesheet; the document keeps its own.

| File | Concern |
|------|---------|
| `NovelWebViewer.kt` | The WebView, taps and keys, document/chapter turning, the continuous window's orchestration, and the `ViewerProvider` that selects it |
| `NovelDocument.kt` | Turns one spine item's XHTML into a document the engine can be handed |
| `NovelPageLayout.kt` | The book as discrete pages: the column CSS, the `KmkPaged` bridge script, and the pure parsers for its replies. Pure |
| `NovelContinuous.kt` | The book as one continuous scroll: segment markers, the probe, append/prepend/trim/seek scripts. Pure strings |
| `NovelInline.kt` | Rewrites a document's images and stylesheets into `data:` URIs. Pure |
| `NovelFonts.kt` | Fonts the reader imported, stored and offered beside the built-in families |
| `NovelBuiltinFonts.kt` | The curated variable fonts bundled in `app/src/main/assets/novel-fonts/`, grouped by kind. No Android imports, so it is unit-testable |
| `NovelScrollBridge.kt` | `window.KmkReader` — how the host measures and scrolls the document — the `FLOW` direction probe, and `quoteForJs` |
| `NovelNavigation.kt` | Contents rows and search results: the pure mapping from source models to renderable ones |
| `NovelReaderScreenModels.kt` | `NovelTocScreenModel` + `NovelSearchViewModel` |
| `NovelTypography.kt` | The settings value. No Android imports, so it is unit-testable |
| `NovelReaderTheme.kt` | The general reader theme's background, as the book's stylesheet can use it |
| `NovelTypographyCss.kt` | Turns that value into a stylesheet; `styleTag` escapes `</`; also styles the synthesized cover page |
| `NovelProgress.kt` | Reading position and percentage progress. A `Position` carries the document's URL beside its index, so a restore survives the list being renumbered |

The two sheets themselves are in `presentation/reader/NovelReaderDialogs.kt`, beside the rest of the
reader's dialogs.

Typography settings *and* per-chapter reading positions both live in `NovelTypographyPreferences`
(`reader/setting/`), not here — the viewer reads them through it and subscribes to `changes()` for
the live re-apply. A separate store for either would be a second answer to the same question.

**Tri-state preferences.** `justified`, `hyphenation`, `verticalWriting`, `paragraphIndent`,
`boldText` and `forceDark` are `Boolean?` where `null` means *follow the publisher*, stored as the
string `"follow"`. They are nullable on purpose: a default of `false` would mean "emit
`text-align: left !important`", which silently restyles a Japanese light novel out of vertical
writing and cannot be turned off again.

**Bold text weights the body, not the book.** `boldText` emits `font-weight: 700` (or `400` for
"off") on the text-bearing set only — `h1`–`h6`, `strong`, `b` and `em` are excluded on purpose, so
a book's own hierarchy and emphasis keep their meaning and bolding the body does not rough up the
chapter headings. Both answers emit under the same `!important` as every other override: a publisher
that bolded `body` must be overridable by "off".

**Force dark is a palette rewrite, not a filter.** With `forceDark` on, `resolvePalette` fixes the
palette in place before any rule is emitted: the canvas is the background the appearance tab chose,
else `NovelTypography.FORCE_DARK_BACKGROUND` (`#121212` — Material's dark surface rather than black,
so an illustration edge that ends short of the page frame still reads), and the text colour is
**derived** from that canvas with `contrastingForeground`. Derived, not taken: the one way this
feature fails is dark text on the dark plate it stands on, and a text colour picked before force dark
went on is exactly how that happens. And because nearly every book paints its flow through classed
elements, the palette is worthless until those backgrounds go: `FORCE_DARK_NEUTRAL` makes every
element except `html`, `body` and the image containers transparent while the canvas and the
illustrations keep theirs — inverting an illustration is how dark reading becomes unreadable
negatives.

**Hand-typed colours are canonicalised, or they are not accepted.** Each colour palette ends in a
custom chip that opens `HexColorDialog`, whose OK is gated on `NovelTypography.normalizedColor`: it
takes `#rgb`/`#rrggbb` with the `#` optional and returns the one expanded lower-case `#rrggbb` form,
formatted with `Locale.ROOT` like every other number this folder emits. The single spelling is
load-bearing — it is what makes "a colour the reader picked" and "the chip it rode in on" a plain
string comparison everywhere the value is later compared, including the palette's own selected state,
which is also how a *non-blank value no palette entry equals* presents as a custom choice rather than
as a broken one. The field pre-fills only genuinely custom values, so a palette entry never
round-trips as editable text.

**The stylesheet is never empty.** Even for default typography, because that is the only way a user
can turn *off* a publisher's own justification. Properties that default to "follow the publisher" are
simply omitted when null.

**`!important` everywhere on purpose.** An EPUB stylesheet sets `font-size` on `body` or a class; on
specificity grounds those win over a host rule and the user's font size silently does nothing. This is
the single most common complaint about EPUB readers.

**The base URL is per document, and it is the highest-consequence detail in the folder.** It comes
from `TextPage.baseHref` through `TextBaseUrlSource`, which for an EPUB is the *directory* the spine
item sits in — not the archive root. With the root as the base, a document at `OEBPS/Text/ch1.xhtml`
resolves `../styles/main.css` to `styles/main.css` instead of `OEBPS/styles/main.css`: every
stylesheet, illustration and embedded font 404s and the book renders as bare text wearing only the
reader's own styling. Nothing errors, which is exactly why it presents as "the reader shows nothing"
rather than "a file is missing".

**Injected at two points, for two reasons.** `NovelDocument` puts `<base>` and the charset at the
*top* of the head and the reader's stylesheet at the *end*. The base has to precede every element
with a relative URL — including the publisher's own `<link>`, which the preload scanner may have
resolved already — and a charset declaration is only honoured near the top of a document. The
stylesheet has to be last because `!important` against `!important` is decided by source order. A
publisher's own `<base>` is stripped first: the first one in a document wins, so theirs would
silently outrank ours.

**The bridge is a `<script>`, and its result is JSON.** `evaluateJavascript` hands back the *JSON
encoding* of the script's return value, so a script returning `NaN` arrives as the five characters
`null`. `NovelScrollBridge.number` has to read that as "no value", or the position save breaks on
every document.

**Turning is decided by the host, natively — never by asking the document.** A tap scrolls
`WebView.scrollBy` and reads `scrollY` back after a `post`: no movement means the document ran out
of room in the tapped direction, and the document turns. This used to be two `evaluateJavascript`
round trips through the injected bridge, which made turning depend on the book's own `<script>`
having run — and where it did not, every tap silently did nothing and the page navigator was the
only way from one chapter to the next, which is exactly how it was reported. The bridge survives
only for what the *laid-out document* alone can answer: the fraction a reading position is stored
as, and the element a contents entry names. Both degrade to nothing without the bridge, which is
survivable — a book opens at the top of a section instead of where it was left. A document that
has not laid out yet (`contentHeight` 0) answers every scroll with "no movement", so turning is
guarded on it — turning on that reading is what skipped a document on the first tap of a
freshly-opened book.

**A missing resource answers 404, not null.** `shouldInterceptRequest` returning null hands the URL
back to the platform, which then waits on a request nothing answers — so one missing plate holds the
document's layout hostage. A 404 shows a broken image and gets out of the way.

**The URL space is real `https`, on a reserved host.** A book's documents and resources live under
`https://book.internal/…` — the `WebViewAssetLoader` pattern. It was an invented `epub-archive://`
scheme first, and that was why "images are not rendered" outlived the first round of fixes: the
platform documents that a `loadDataWithBaseURL` base "must be an HTTP(S) URL" and that WebView
restricts resource requests using custom schemes, so `<img>`, `<link>` and `@font-face` requests
were refused before `shouldInterceptRequest` was ever consulted. The text still rendered — which is
what made it read as "the reader is broken" rather than "one file is missing".

**A document's own images and stylesheets are inlined as `data:` URIs (`NovelInline`).** The https
origin fixed the class of failure above and images *still* did not render on the reporting device,
which is the point of this rule: intercepted subresource loading is the one link in the book path
that can fail without a single log line, and it has now failed three times. So the references a
reader actually reads — `<img>`, SVG `<image>`, and `<link rel="stylesheet">` with the `url()`s
inside it — are resolved and embedded into the document itself before it is handed over, travelling
the same channel as the text, which has never once failed to arrive. Per-resource and per-document
ceilings degrade oversized books to the streaming path rather than to a missing chapter — the
reference is re-spelled *absolute* for that path to answer (a relative one in an appended fragment
would resolve against the live document's directory; see the inlining-cache rule below) — and
external URLs are never copied. The interception path stays — for over-ceiling files and anything
the inliner does not cover — and the two read from the same archive lease.

**A reader-imported font reaches the document the same way (`NovelFonts`).** The picked file is
copied into private storage (a SAF grant is not the document's to inherit, and the reader's own
hardening closes `file://`), the family is stored as `user/<name>`, and the `@font-face` rides in
the document's head as a `data:` URI — built into `NovelDocument.build`'s head and into
`liveTypographyJs`, so a live family change re-declares the face. `quoteFamily` quotes any name
that is not a CSS identifier: `user/My Font` unquoted would drop the whole `font-family` rule and
the setting would silently do nothing.

**The picker shows real faces, and the app carries its own (`NovelBuiltinFonts`).** The font row
(`NovelFontPickerDialog`) opens a list built for a reading decision: an import row first, the CSS
generics labelled with what they mean, the bundled families grouped serif / sans / mono /
handwriting — the order of classical type-setting, which is the order a reader decides in — and the
reader's own imports last, each deletable behind its own confirm that resets the setting when it
deletes the family in use. Bundled families store as `builtin/<Family>`, a prefix that cannot collide
with a generic keyword, and the whole value doubles as the CSS family name, so the declared
`@font-face` and the chosen `font-family` cannot disagree. Each bundled file is a *variable* font,
one file per family, all of them OFL with the licence text shipped in the same directory — one file
covers the whole weight axis, which is what gives `boldText` a real 700 instead of the engine's
synthetic bold. Family lookup is case-insensitive on purpose: a value restored from another device,
migrated, or typed by hand still finds its file.

**Faces are decoded once per family and kept behind a small LRU.** `NovelWebViewer.readerFontCache`
is an access-order `LinkedHashMap` capped at four faces (`READER_FONT_CACHE_FACES`) — a face is half
a megabyte of bytes held as a `data:` URI across documents, so an unbounded cache pins every family
ever displayed for the life of the chapter, and a picker session that compares a dozen of them
otherwise allocates without end. Four is wide enough for the families a comparing reader swings
between and keeps whatever is evicted cheap to re-read. A preview failure is not a filter: a family
whose file cannot be decoded for the preview falls back to its category's generic CSS keyword and
still lists, so nothing an import declared is silently hidden from the reader.

**An import is validated, because a font library is where non-font files go to die.**
`NovelFonts.import` answers `ImportResult` — `Success`, `NotAFont`, `TooLarge`, `Unreadable` — and
the picker toasts what arrived instead of what was asked for. Accepting every file a provider labels
a font would list a face that silently falls back, and the reader would conclude the feature is
broken rather than the file. The document picker first offers the `font` MIME types, plus the
spellings providers mislabel under — `application/octet-stream` included.

**CSS in the head has to be in a `<style>`, and the wrapper belongs to the injector.** `faceCss`
returns a bare `@font-face` rule and `NovelDocument.build` takes it as `extraHead`. It was emitted
loose, which is not a place CSS can be: the parser meets a character token in head insertion mode,
has nothing to do with it, closes the head and opens a body to hold it — so the rule rendered as
the paragraph of literal text the reader reported seeing, *and* declared a font the document was
not using, so choosing an imported font silently did nothing either. Two failures from one missing
tag. `NovelDocument.styleTag` wraps it (and escapes `</`, since the family is a reader-chosen string
and can carry a closing tag); `DocumentHarness` asserts the rule is inside a `<style>`, inside the
head, and cannot break out of one.

**Reader-supplied CSS and JS ride the same channel (custom injection).** The Reading appearance tab
holds two toggles and two editors: custom CSS (on by default — the value starts empty and can only
restyle) and custom JavaScript (**off by default**, with the security note on its toggle row, because
an injected script runs with the reader's trust inside every downloaded book). `NovelDocument` emits
the CSS as the document's **last** `<style>` (`id="kmk-custom-css"`, `</` escaped the way every
reader style is) so `!important` ties fall the reader's way, and the JS in its **own** `<script>`
after the bridge — reader code cannot take the bridge down with it, and `window.KmkReader` exists
when it runs. Reader-authored code is trusted, so it is injected verbatim — the element-sequence
escaping (`</`→`<\/` for style, `</script`→`<\/script` for script) is the only rewriting, and it is
meaning-preserving in both languages. CSS changes ride the live swap: `liveTypographyJs` removes
and re-creates `#kmk-custom-css` (or removes it when the toggle went off — a build-time copy left
standing would keep applying the old rules). JS has no live swap — re-running code per change would
double-register everything it listens for — so a JS edit or toggle lands on the next page turned,
which the editor's hint states. The custom-CSS preference signals therefore ride
`NovelTypographyPreferences.changes()` and the JS ones deliberately do not. And while the sheet is
active, the typography's palette *yields the colour on the element ladder* (`NovelTypographyCss`
takes the sheet's own presence as a flag): a `body { color: … }` in the last stylesheet only ever
colours the `body` element — text inside a `p` keeps the paragraph's own matched colour and never
inherits from the body — so the ladder's re-assertion used to win the text while the user's rule
won only the canvas. With the sheet active the ladder drops its colour (sizing and spacing stay),
the palette stays on `html, body` where a custom rule overrides it by source order, and force dark
keeps its ladder because its neutral already made classed flow backgrounds transparent — a
publisher colour there then needs re-asserting or the book goes dark-on-dark.

**Progress is a character offset, not a page index.** A page index in a reflowable document means
something different on every device and at every font size; `NovelProgress.Position`
(`documentIndex:characterOffset:characterLength`) is stable across all of them. Chapters are weighted
by character length so a book with 200 one-paragraph interstitial files does not read as 2% done.
Persisted per chapter in a `__PRIVATE_` preference, not a column: it is a property of how a document
happens to lay out today, not of the chapter, and losing it costs one lost position.

**The viewer subscribes to `NovelTypographyPreferences.changes()` itself.** Read once at construction
so the first document is never painted unstyled, then observed for the rest of its life — otherwise the
settings screen re-lays-out a book nobody reopens and the reader concludes the setting does nothing.
The job is cancelled in `destroy()`, because the activity's scope outlives a viewer that a
reading-mode switch has already torn down.

**The general reader theme's background reaches the book, through the appearance tab's value.** The
novel viewer merges `readerTheme`'s colour into the typography it applies: a dark theme (black, gray,
automatic-in-dark) becomes the document's `background-color`, and the existing
`effectiveFontColor` rule derives a contrasting text colour from it — choosing *black* in the
general settings used to paint only the window behind a transparent WebView, leaving the
publisher's own black text invisible on it. A white theme forces nothing (white is "no opinion";
the container already shows white), and an appearance-tab page colour always wins as the more
specific choice. The merge is in `NovelWebViewer.withReaderThemeBackground`, the policy in
`NovelReaderTheme`, and the subscription covers both settings so a change applies to the open
document rather than to the next one.

**A book loads a document at a time — and then grows into a window (`NovelContinuous`).**
`TextIncrementalSource` — optional, like every interface an extension may or may not implement —
splits the eager `getTextPageList` into `getTextPageIndex` (the spine, no text read) and
`getTextPageText` (one document). `TextPageLoader.ensureText` reads a page's markup the first time
the viewer shows it, and `showDocument` carries a monotonic request token so a stale read cannot
displace a newer tap.

On top of that, the live document is the book's *continuous* scroll: every section in the DOM —
including the one the document loaded with — sits in a `kmk-seg` marker, and a throttled probe
(`NovelContinuous.PROBE`) walks them to report which section the viewport is over and how far in.
Near the bottom, the next section is appended into the same DOM (`innerHTML` on a detached node,
so a book's scripts cannot run); near the top, the previous one is prepended with the scroll
compensation in the same synchronous script, so no frame shows the shift. The window is trimmed to
two sections either side of the current one. The probe's report drives the same things a page
report drives in the continuous manga viewer: the page counter (`onPageSelected`), the saved
position, the contents sheet's marker, and the chapter-progress slider. A push that keeps trying to
scroll past the end of the *book* — where no append can answer — turns to the next volume, the way
the continuous manga reader runs off its last page; mid-book, "nowhere to scroll" means an append
has not landed, and is not a turn. A tap, a button or a contents jump re-renders fresh and starts a
new window on purpose.

The settings UI for all of the above is the **Reading appearance** tab in `ReaderSettingsDialog`
(`NovelTypographySettingsPage`), offered only while the open chapter is text — the same signal that
chose the renderer. Its rows are hand-built from `presentation-core`'s `SettingsItems` rather than the
settings framework: the values are floats the stylesheet reads as page fractions, and tri-states,
which the framework's int-slider and switch helpers cannot express.

**While a book is open, the settings sheet offers only what a book can use.** The reading-mode tab
is not offered at all (a book's renderer is fixed; every row there — scale types, crop, spreads,
per-viewer tap layouts — is an image-reader setting that would silently do nothing), the colour
filter waits for actual bitmap pages (`hasPagesFlow` excludes text, because a book's documents *are*
its page-list entries and a count alone cannot tell the two apart), and the general tab drops its
image-reader rows (long-tap page actions, chapter transitions, page flash, auto webtoon). What
remains — background theme, page number, navigator layout, fullscreen, keep-screen-on, completion
sounds — is everything a book can act on, beside its own Reading appearance tab.

**Long-press is split on what the finger is on, and only the WebView's hit test knows.** On text it
answers false and the engine's own selection proceeds; on an `IMAGE_TYPE`/`SRC_IMAGE_ANCHOR_TYPE`
hit (`NovelWebViewer.setOnLongClickListener`) the `NovelImageViewerDialog` opens — the illustration
full screen over the book's background, pinch and double-tap zoom through the same
`ReaderPageImageView` the cover preview uses, and a Set-as-cover action behind the shared
`SetCoverDialog` confirmation. A tap on the un-zoomed image dismisses it. The reader's taps never
zoom in place: an earlier design scaled the `<img>` with a CSS transform and made every tap in the
book pay a `takeImageTap` script round trip before it could scroll; both halves are gone (the bridge
asserts their removal in `DocumentHarness`), and a tap always means scroll, page or menu,
synchronously. The long-press sheet's crash history stays designed out: the viewer is offered *only*
for an illustration (never on a text press) and its bytes are resolved for that image alone, by
`ReaderViewModel.getBookImageBytes` (cover action: `setBookImageAsCover`) from the loader
(`resourceSource` for a `book.internal` URL, base64 decode for a `data:` URI `NovelInline` embedded),
never from `ReaderPage.stream`, which a text page does not have. The URL comes from the hit test,
which is either of those two spellings; an external image opens no viewer at all. Known gaps: an
inline-SVG `<image>` and a CSS background are not hit-tested as images, so no viewer appears for
them — the extra `Message`-based `requestFocusNodeHref` hop exists because for an image inside a
link the synchronous hit test's `extra` names the link's target, not the picture.

**Inlining results are cached per document, and un-inlinable references are re-spelled absolute.**
`NovelWebViewer.inlineCache` keeps the finished `NovelInline` builds (whole documents and continuous
fragments under separate key kinds — a document and its body fragment are different strings) for
`INLINE_CACHE_DOCUMENTS` documents per chapter, because the continuous window's trim-and-regrow and
a turn back both re-look at documents that were already built; without it every re-look re-read the
plates out of the archive and re-encoded them as base64, which was the visible lag on illustrated
books. Callers (`showDocument`, `appendAhead`, `prependBehind`) all build inside
`Dispatchers.Default`; only the map hand-over is synchronized. A plate that stays relative when it
cannot be inlined would resolve against the *live document's* directory inside an appended fragment,
which has no base of its own — so `NovelInline.rewriteAttribute` re-spells it absolute instead, the
same URL the main document's serving path already answers, and leaves only truly external (and
unresolvable) references untouched.

## Contents and search

`TextTocSource` and `TextSearchSource` are implemented by the EPUB source and had no UI at all. The
two sheets close that, and both are offered from the bottom bar **only when the open chapter's
loader can answer** (`TextPageLoader.tocSource` / `.searchSource`) — a button that opens an empty
sheet reads as a bug in the reader rather than in the book.

**A jump's destination belongs to its [showDocument] request.** `jumpTo`/`jumpToFraction` pass the
anchor or fraction *into* `showDocument`, which sets the pending position when the request starts and
is what a plain call (a turn, a chapter open) clears. Setting it after the call was the original
design and it silently broke every contents jump into a section the reader had not opened yet: `EPUB`
pages are answers to `TextIncrementalSource`, so the target document's markup is read **asynchronously
after** `showDocument` returns, and the `renderDocument` at the end of that read wiped the intent that
the jump had just set — landing wherever the chapter's stored position (or nothing) said: mid-chapter,
near its end, or in the section the window then grew underneath. Every position is still applied in
`onPageFinished`, after layout: measured earlier, an anchor resolves to the top of an empty page.

**A load is a fresh address, or the engine inherits the scroll of the document it replaced.** Every
document of a book folder loads at the same directory base URL, so an untagged `loadDataWithBaseURL`
looked to the engine like the old page continuing — and a fresh document could start from the scroll
offset of the one that ended: random landing positions, clamped to the bottom of a shorter document,
and after that the window growing below the viewport leaves the reader in the *next* section. Each
load therefore carries its own `#kmk-load-<request>` fragment (nothing a book can name), and the
finish guard ignores the fragment since a WebView has exactly one finish in flight.

**Growth scripts are stamped with the request that built their document.** An append/prepend/trim can
outlive its document: a jump re-renders while its read is in flight, and a script that already left
for the renderer cannot be cancelled. The document declares `window.KmkEpoch` (its own request number)
and each script refuses to run against a different one, answering `false` — which the host reads back
with `NovelContinuous.isStale` and drops, instead of moving `windowFirst`/`windowLast` of a window
that just ended, or worse, letting a stale *prepend* scroll the reader with its own compensation. The
render also cancels the window job in flight, for everything that has not left yet.

**A contents entry for another chapter is shown, not dropped.** A whole-book contents points at files
the open chapter never loaded. Those rows render disabled (`NovelTocEntry.reachable`) so the structure
of the book stays visible; silently doing nothing on tap reads as a broken button.

**The chapter selector is the contents list, for a book.** A book is one library chapter, so
`Dialog.ChapterList` renders the ToC sheet for a text chapter instead of the library's chapter list —
for a novel that list was a single row, and for a folder of novels a list of *other books*. Both
entries to the sheet go through one `showNovelContentsSheet` in `ReaderActivity`, because the wiring —
which documents this chapter loaded, which the reader is inside, the model's scope — is most of its
body and a second copy would drift. A chapter whose source cannot answer falls back to the library
list rather than to a dead button.

**A search hit carries a document offset *and* a document length, in the source's plain text.** The
host holds markup, the source searched plain text, and dividing one by the other lands tens of
percent of a page out — so the ratio crosses over and only the ratio. `toSearchResults` keeps hits
whose source reported no length (an extension predating the field) at the top of their document
rather than dropping them. See `TextSearchHit` for the whole argument.

**Contents fragment ids are untrusted.** They come out of the book's own TOC and end up inside a
script, so they go through `NovelScrollBridge.quoteForJs` — including `</` and U+2028/U+2029, which a
JS parser treats as script terminators and Kotlin does not. `DocumentHarness` asserts the
break-out cases.

**Viewer selection is by loader, and it never falls back.** `NovelViewerProvider` claims a chapter
whose loader is a `TextPageLoader` — which is exactly what `PageLoaderRegistry` picks for a
`TextSource` and nothing else. An extension therefore gets this reader the moment it implements
`TextSource`, with no change here.

Two rules make the fall-through impossible, and both were learned the hard way:

- **Decide from the loader, not from the page content.** `page.textPage` was the original signal and
  it is wrong twice over: it needs the page list to exist, and while it does not it reports null —
  indistinguishable from "not a text chapter", which is precisely how an EPUB reached the pager. The
  loader is assigned by `ChapterLoader` before a single page is read, and lands in the same state
  update that publishes `viewerChapters`, so it is available exactly as early as anything could be.
- **`ViewerRegistry` asks `declinesTextChapters`, so an image viewer is never even offered a text
  chapter.** A `null` from a provider means "not my kind of chapter", not "not this instance of it". If
  no provider claims a text chapter, `create` throws rather than handing an EPUB to a pager that can
  only render it blank.

`ReaderActivity.updateViewer` guards on `currentChapter?.pageLoader == null` — not on pages. A viewer
that can decide from chapter content would otherwise be consulted before there was anything to decide
on and decline, and nothing would rebuild it later. `ReaderActivity.setChapters` calls back in.

**The chosen renderer is logged.** `updateViewer` logs which viewer it built and whether the chapter
was text. A wrong choice is invisible from the UI — the reader opens, the app bar looks right, the
page is blank — so that line is what makes the next report diagnosable instead of a guessing game.

**The novel viewer turns chapters itself.** It has no transition page to draw the way the pager and
webtoon viewers do, so reaching a chapter boundary goes through `ReaderViewModel.goToAdjacentChapter`,
which reuses the same `loadAdjacent` path the toolbar buttons use. Not `onPageSelected`: that returns
early while `chapter.pages` is null, which is exactly the case for a chapter not yet loaded.

**For a book, the bottom navigator is a chapter navigator and says so.** The prev/next buttons call
`NovelWebViewer.nextSection`/`previousSection` — the same `advanceDocument`/`retreatDocument` a tap
at either edge runs, so a button can never mean "skip the whole book" (one library chapter *is* the
book) nor disagree with the gesture about what "next" means. Their enabled state is
`hasNextSection`/`hasPreviousSection`, recomputed off the same state changes that repaint the bars.
The slider stays, as a *chapter-progress* slider (`chapterFraction` through `ReaderAppBars`, decided
from the loader): a book has no pages to count, so the bar shows the reader's place in the chapter
on screen as a percentage, and dragging it seeks through that chapter — `seekFraction` mutes the
probe's write-back until the drag ends, so a slow seek cannot fight the thumb. Sequential movement
is scrolling, taps and the buttons; non-sequential is the contents list.

Resources (images, CSS, fonts) are streamed by the `TextPageLoader`'s `resourceSource` over the
reserved `https://book.internal/` origin — see `EpubArchiveUrls` in `core/archive` — and, for the references a
document actually reads, inlined into it by `NovelInline` (see above: the inliner and the server
read the same archive lease).

## Page splitting

A page too tall for the decoder is cut into segments by `HttpPageLoader` and **replaces** its parent
in the chapter's list, mid-session. `ReaderChapter.pageListVersion` is how the viewers learn; the
mechanics are in `model/SplitPageIndex.kt` (`internal`), `ReaderChapter.replacePages` (identity
indexes) and `WebGpuPages`→`WebGpuPageModel.linkedNeighbour`. Anything that walks the page chain
must handle a page that is no longer in the list.

## Pager / webtoon split

Strip and continuous reading use separate image processors (split 2026-08-31):
`PagerPageImageProcessor.kt`, `PagerJoinedItemsBuilder.kt`, `WebtoonPageImageProcessor.kt`.
`WebtoonBaseHolder.viewer` is public for cross-holder access. Keep the two paths separate.

## Zoom gating — which setting owns which gesture

The legacy viewers have two zoom opt-outs (pinch-to-zoom, double-tap zoom) plus "disable zoom
in", and **the gesture that a setting does not govern must never ride in through the back of
one it does**:

- **A quick scale is a double-tap-zoom gesture; a pinch is a pinch-to-zoom gesture.** In the
  webtoon viewer (`WebtoonFrame` → `WebtoonRecyclerView`) the two are told apart by the
  pointer count of the event the scale detector is currently processing, which the frame
  captures in `dispatchTouchEvent` and passes down as `fromQuickScale`. Quick scale is armed
  in `onScaleBegin` only when that count is 1 *and* the second tap's finger is still down —
  the old check read only `isDoubleTapping`, which stays set until the pointer lifts, so a
  pinch begun on the held second tap was classified as a quick scale and then bypassed the
  pinch gate in `onScale` with pinch-to-zoom disabled. A pinch that follows a double tap must
  not zoom with the setting off.
- **"Double tap to zoom" off covers the whole double-tap gesture in both readers.** The
  WebGPU viewer resolves one flag (`WebGpuConfig.resolveDoubleTapZoom` ties
  `doubleTapGesturesEnabled` to it, upstream `34fb12b`); the pager matches it by switching
  SSIV's quick scale off with the setting (`ReaderPageImageView.setupZoom` →
  `setQuickScaleEnabled`). Left alone, a disabled double-tap zoom still zoomed whenever the
  second tap was dragged.
- **`ReaderPageImageView` is rebound, not recreated** (RecyclerView rebinds; the pager's
  translation swap calls `setImage` twice on one view), so every config write there is made
  both ways — `isZoomEnabled`, the PhotoView `setZoomable`, and the double-tap listener are
  applied per bind. A one-way `isZoomEnabled = false` was how a reused view could stay
  zoom-locked (and an animated GIF page ignored the zoom settings entirely, because PhotoView
  owns its own gestures).

## EPUB as comic (`loader/EpubPageLoader.kt`)

The local source can also offer an EPUB as an image chapter: `getImagesFromPages` walks every `<img>`
in every spine document, which is the producer's promise, not the archive's. `getPages` therefore
gates each collected path on `reader.contains(path)` before it may become a page — a page this reader
can never stream is not a page — drops the rest with a WARN log naming each path, and keeps the
surviving page indexes **dense**: a gap in the list reads, screens away from the missing image, as a
broken neighbourhood rather than as one absent file. The stream lambda itself still answers
`error("Missing EPUB image: $path")` per path rather than returning null, because null is how a page
gets stuck forever.

Series metadata rides the same container: `source-local`'s `EpubReaderExtensions.fillMetadata` leads
the chapter name with the series the book *declares* — calibre's stamped pair, or an EPUB 3
collection confirmed as a series — as `"Series: Volume"`, skipping the prefix when the book's own
title already opens with the series name (a whole book shelved in a series folder would only double
it), and keeping the folder-number heuristic only for books that declare nothing. How the two
spellings are read out of the OPF is in `core/archive/AGENTS.md`.

## Komikku additions (KMK)

`ChapterCompleteSoundPlayer.kt` + `ChapterCompleteSoundPackManager.kt` (chapter-finish audio),
`WebhookReadingTimeTracker.kt` (reading-time webhook events). New KMK strings → `KMR` + `i18n-kmk/`.

## Conventions

- Reader stays an `Activity` — do not migrate it into Nav3.
- State in `ReaderViewModel` (AndroidX); inner models extend `mihon.core.viewmodel.StateViewModel` or plain `ViewModel`.
- Upscale changes stay behind the MTL gate; per-series toggles via `UpscaleMangaStore`.
- Logging: `xLogE()` / `xLog()` from `exh.log` for KMK code; `logcat {}` for Mihon code.

## Anti-patterns

- Do not launch the reader through a Nav3 route push — use `ReaderActivity.newIntent(...)`.
- Do not merge the pager and webtoon image processors back into one path.
- Do not grow `WebGpuViewer.kt` past wiring — a new state machine belongs in its own file as a
  `WebGpuViewer` extension, so the class stays readable as a whole.
- Do not build an `ImagePage` and drop the reference without `cleanup()` — it owns uploaded GPU
  textures.
- Do not give a text preference a non-null `false` default. `null` = follow the publisher; `false` =
  force it, and it cannot then be unforced.
- Do not re-run `loadDataWithBaseURL` to apply a typography change — it drops the scroll position on
  every slider drag. Swap the stylesheet in the live DOM (`liveTypographyJs`). A *page-mode* change
  is the one exception: it is a different layout, not a style, and it re-renders deliberately.
- Do not build a novel document's markup on the main thread. The inliner base64-encodes every plate
  the document reads; `showDocument` reads, inlines and builds on `Dispatchers.Default` and only the
  load hop lands on main. A new cost in that path belongs behind the same `withContext`.
- Do not decide a novel document's flow direction from the reader's own settings — a book's
  stylesheet has as much say as the tri-state does. Ask the laid-out document
  (`NovelScrollBridge.FLOW`, or the paged bridge's setup reply) and act on its answer.
- Do not load a novel document at `EpubResourceReader.BASE_URL`. That is the archive root and is only
  right for a spine item that already sits there; take the base from the page. (The synthesized cover
  page is the one deliberate exception: it is built to resolve against the root.)
- Do not let a viewer provider return null for a chapter it is the only renderer for. That is
  not a fallback, it is handing the reader to something that cannot display the content.
- Do not add a method to `TextSource` / `TextResourceSource` / `TextPage`. Extensions implement them
  across a classloader boundary, so a new abstract method breaks every one that predates it — add a
  separate optional interface instead, as `TextBaseUrlSource` does.
- Do not wrap a `suspend` call in `runCatching`. Its block is not a suspend lambda; it does not
  compile. Use `try`/`catch`.
- Do not interpolate an id, fragment or path from a book into a script without `quoteForJs`.
