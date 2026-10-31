package eu.kanade.tachiyomi.ui.reader.viewer.novel

/**
 * Turns a [NovelTypography] into the stylesheet the reader injects into a document.
 *
 * ### Why a stylesheet and not a native renderer
 *
 * The text arrives as XHTML with the publisher's own CSS — drop caps, ruby annotations, tables,
 * vertical writing, columns. Re-implementing that in Compose means re-implementing a browser. So
 * the document keeps its own styling and the reader's preferences are layered on top.
 *
 * ### Why almost everything is `!important`
 *
 * An EPUB stylesheet sets `font-size` on `body`, on `p`, often on a class. Without `!important`
 * those rules win the cascade on specificity grounds and the user's font size does nothing — the
 * single most common complaint about EPUB readers. Injecting the overrides last with `!important`
 * is the only reliable way to make a preference actually apply.
 *
 * ### The selector ladder
 *
 * Publishers scope text in wildly different ways, so several selectors are targeted at once rather
 * than betting on one. `!important` plus a group selector covers `body`, unclassed elements and the
 * common container classes without needing to know the book's class names.
 *
 * ### A background implies a text colour
 *
 * The colour written into the rules is [NovelTypography.effectiveFontColor], not its `fontColor`
 * field. Emitting each independently is what made every background except white unreadable: the
 * sheet honoured a reader-chosen background and left the text in the publisher's own colour, so a
 * dark plate came out as black on black. Deriving the foreground from the background is the fix, and
 * it is deliberately the only colour logic here — the alternative, guessing a "best" text colour for
 * every book, is what turned a missing rule into a wrong one.
 */
object NovelTypographyCss {

    /**
     * The reader's stylesheet, to be appended after the document's own `<style>` blocks.
     *
     * Always non-empty. Earlier this returned "" for default typography, which broke the one case
     * that matters most: a book whose own stylesheet justifies text, read by someone who turned
     * justification off. Emitting an explicit `text-align: left !important` is what makes "off"
     * mean off. The cost is about a kilobyte of CSS per document.
     *
     * Properties that default to "follow the publisher" ([NovelTypography.justified],
     * [hyphenation], [verticalWriting]) are omitted entirely when null, so the book keeps its own
     * choice unless the reader made one.
     *
     * @param customCssActive true when the reader's own custom CSS is enabled and not blank — the
     *   same condition [NovelDocument] and [NovelWebViewer] use to emit the stylesheet it appends
     *   after this one, so the two answers cannot disagree.
     */
    fun stylesheet(type: NovelTypography, customCssActive: Boolean = false): String {
        val t = resolvePalette(type.sanitized())
        // Not `t.fontColor`: a background the reader chose forces a text colour even when they did
        // not choose one, or the page is the publisher's black text on the reader's black plate.
        // With force dark on, resolvePalette has already made that derivation explicit.
        val textColor = t.effectiveFontColor
        val forDark = t.forceDark == true

        // The colour the ladder below re-asserts on text-bearing elements and headings. With custom
        // CSS on, the palette *yields* it: a rule on `body` in that last stylesheet only ever
        // colours the body element itself — text inside a `p` keeps the paragraph's own matched
        // colour and never inherits from the body, so `body { color: … }` in the reader's
        // hand-typed CSS could not colour the flow while the ladder kept re-asserting (the
        // reported "custom CSS colours the page but not the text" report). The palette stays on
        // `html, body` — a custom rule overrides it there by source order, and a reader who typed
        // nothing of their own still inherits it into the flow. Force dark keeps the ladder: its
        // neutral has already made classed flow backgrounds transparent, and a publisher colour
        // on one of those elements then *needs* the re-assertion, or the book goes dark-on-dark.
        val reassertedColour = if (customCssActive && !forDark) null else textColor

        return buildString {
            appendLine("/* komikku reader typography */")
            appendLine(":root {")
            appendLine("  --kmk-font-size: ${fmt(t.effectiveFontSizePt)}pt;")
            textColor?.let { appendLine("  --kmk-text: $it;") }
            t.backgroundColor?.let { appendLine("  --kmk-bg: $it;") }
            appendLine("  --kmk-line: ${fmt(t.lineSpacing)};")
            appendLine("  --kmk-para: ${fmt(t.paragraphSpacing)}em;")
            appendLine("  --kmk-tracking: ${fmt(t.fontSpacing)}em;")
            appendLine("}")

            // Root sizing. `html` is set too because publisher CSS commonly overrides `body` and
            // inherits from `html`, so scaling only `body` leaves some books unchanged.
            appendLine("html, body {")
            appendLine("  font-size: var(--kmk-font-size) !important;")
            t.fontFamily.takeIf { it.isNotBlank() }?.let {
                appendLine("  font-family: ${quoteFamily(it)} !important;")
            }
            textColor?.let { appendLine("  color: $it !important;") }
            t.backgroundColor?.let { appendLine("  background-color: $it !important;") }
            appendLine("  line-height: var(--kmk-line) !important;")
            appendLine("  letter-spacing: var(--kmk-tracking) !important;")
            t.justified?.let { appendLine("  text-align: ${if (it) "justify" else "left"} !important;") }
            t.hyphenation?.let { appendLine("  ${hyphens(it)} !important;") }
            appendLine("  ${fontSmoothing(t)} !important;")
            t.verticalWriting?.let { appendLine("  ${writingMode(it)} !important;") }
            appendLine("  margin: ${margins(t)} !important;")
            appendLine("  padding: 0 !important;")
            appendLine("  -webkit-text-size-adjust: none !important;")
            appendLine("}")

            // Re-asserted on the text-bearing elements because a publisher rule on `p` or a class
            // outranks a rule on `body` regardless of where it appears in the document. The colour
            // re-assertion yields to active custom CSS (see [reassertedColour]); the sizing and
            // spacing stay, because nobody restyles those page-wide through a `body` rule.
            appendLine(elementsRule(t, reassertedColour))

            appendLine("p, div, blockquote, li, dd, section, article {")
            appendLine("  margin-top: 0 !important;")
            appendLine("  margin-bottom: var(--kmk-para) !important;")
            appendLine("}")
            // Paragraph indent is the publisher's; spacing is ours. Keeping both is what stops the
            // page looking like a wall of unindented lines - so `on` emits the indent and `off` has to
            // emit `0` rather than nothing, or a publisher that indents would keep indenting and the
            // setting would read as having done nothing. `null` omits the rule entirely, which is the
            // only way to say "follow the publisher" once the reader has seen both answers.
            t.paragraphIndent?.let {
                appendLine("""p + p { text-indent: ${if (it) "1em" else "0"} !important; }""")
            }

            // Bold text is a weight on the text-bearing set only. `h1`–`h6` are excluded on purpose:
            // a heading's weight carries the book's hierarchy, and a reader turning body bold on is
            // not saying their chapter headings should look broken. `strong`/`b`/`em` are excluded
            // too, so a book's own emphasised words keep their meaning rather than being flattened
            // onto the body's choice. Both answers emit under `!important` for the same reason every
            // other override does — a publisher that set `body { font-weight: bold }` must be
            // overridable by "off" — and `null`, follow the publisher, emits nothing.
            t.boldText?.let {
                appendLine(
                    "body, p, div, span, li, td, th, blockquote, dd, dt, section, article, figcaption, small " +
                        "{ font-weight: ${if (it) 700 else 400} !important; }",
                )
            }

            // Force dark neutralises the publisher's own colours in a second sentence the palette
            // above cannot express: nearly every book paints the *flow* through classed elements —
            // panels, chapter cards, a `div` with a white background and dark text inside it. Those
            // rules outrank our colour on specificity, so the palette is worthless until their own
            // backgrounds are made transparent. The page canvas and the illustrations keep theirs:
            // `html`/`body` are the palette itself, and inverting an image is how dark reading
            // becomes unreadable negatives.
            if (forDark) appendLine(FORCE_DARK_NEUTRAL)

            // Headings keep the publisher's scale but inherit the colour choice — or, under
            // custom CSS, whatever the last stylesheet computes on the body (same yielding as the
            // ladder above).
            reassertedColour?.let { appendLine("h1, h2, h3, h4, h5, h6 { color: $it !important; }") }

            // An image must never bleed into the margin box. Unconditional rather than margin-gated:
            // a book without reader margins can still carry a full-bleed `<img>` wider than the
            // column, and the alternative to this rule is horizontal scroll mid-sentence — the one
            // direction of reading a book must never open.
            appendLine("img, svg, video { max-width: 100% !important; height: auto !important; }")

            // The synthesized cover page (EpubTextSource prepends one when the book declares a
            // cover its spine never shows). The reader's own rules above would otherwise restyle
            // it like prose - body margins doubled into the column, the plate squeezed under
            // `max-width: 100% / height: auto` - so the cover gets its own sentence, emitted
            // after every other rule for the same reason the whole stylesheet is last: `!important`
            // against `!important` is decided by source order. Inert in every document that is not
            // the cover.
            appendLine("body.kmk-cover { display: flex !important; align-items: center !important; justify-content: center !important; margin: 0 !important; height: 100vh !important; overflow: hidden !important; }")
            appendLine("img#kmk-cover-img { max-width: 100% !important; max-height: 100% !important; width: auto !important; height: auto !important; object-fit: contain !important; }")
            appendLine("/* end komikku typography */")
        }
    }

    /**
     * The same stylesheet wrapped for delivery to the renderer, which is [NovelDocument]'s job to place.
     *
     * The rules are escaped for a `<style>` element on the way in. A closing tag inside a stylesheet ends
     * it in the HTML parser, before any CSS is read, so a font family containing `</style>` would silently
     * truncate every rule after it — including the base font size, which is the one thing the reader is
     * certain to emit. `<\/style>` is not a closing tag to the parser and is the same string to a CSS
     * parser, where `\/` is an escaped solidus.
     */
    fun styleTag(type: NovelTypography, customCssActive: Boolean = false): String =
        "<style id=\"kmk-typography\">${stylesheet(type, customCssActive).replace("</", "<\\/")}</style>"

    /**
     * Resolves [style]'s palette in place when force dark is on, so every rule below the palette can
     * keep reading [NovelTypography.effectiveFontColor] as if it had always been there.
     *
     * The appearance tab's background outranks the forced canvas — it is the *more* specific choice,
     * and the general theme merge in the viewer has already been through it by the time a value
     * arrives here. The text colour is **derived, not taken**: the one way this feature fails is a
     * dark text on the dark page it is standing on, and a `fontColor` the reader picked *before*
     * turning force dark on is exactly how a palette chosen for a light page ends up dark-on-dark.
     * Contrast against the canvas is the answer that cannot lose in either direction.
     *
     * Also the reason `fontColor` — not [NovelTypography.effectiveFontColor] — is written out
     * here: a subsequent derived colour from *this* value must not re-derive from the pre-forced
     * fields.
     */
    private fun resolvePalette(style: NovelTypography): NovelTypography {
        if (style.forceDark != true) return style
        val background = style.backgroundColor ?: NovelTypography.FORCE_DARK_BACKGROUND
        return style.copy(
            backgroundColor = background,
            fontColor = NovelTypography.contrastingForeground(background) ?: NovelTypography.LIGHT,
        )
    }

    /** The rule that takes the flow's own colours away while force dark resolves its palette. */
    private const val FORCE_DARK_NEUTRAL =
        "*:not(html):not(body):not(img):not(svg):not(video):not(picture):not(source) " +
            "{ background-color: transparent !important; }"

    private fun elementsRule(t: NovelTypography, textColor: String?): String = buildString {
        append("body, p, div, span, li, td, th, blockquote, dd, dt, section, article, h1, h2, h3, h4, h5, h6 {")
        append(" font-size: var(--kmk-font-size) !important;")
        append(" line-height: var(--kmk-line) !important;")
        if (t.fontSpacing != 0f) append(" letter-spacing: var(--kmk-tracking) !important;")
        textColor?.let { append(" color: $it !important;") }
        append(" }")
    }

    /**
     * Hyphenation needs both the standard property and WebKit's, and `word-break` only as a
     * fallback — setting `word-break: break-word` unconditionally breaks CJK and ruby layout.
     */
    private fun hyphens(on: Boolean) = if (on) {
        "hyphens: auto; -webkit-hyphens: auto"
    } else {
        "hyphens: manual; -webkit-hyphens: manual"
    }

    private fun fontSmoothing(t: NovelTypography) = if (t.smoothText) {
        "-webkit-font-smoothing: antialiased; -moz-osx-font-smoothing: grayscale"
    } else {
        "-webkit-font-smoothing: subpixel-antialiased"
    }

    private fun writingMode(vertical: Boolean) = if (vertical) {
        "writing-mode: vertical-rl; text-orientation: mixed"
    } else {
        "writing-mode: horizontal-tb"
    }

    /** CSS lengths for the four margins. Horizontal ones are % so they track the page width. */
    private fun margins(t: NovelTypography): String =
        "${percent(t.marginTop)} ${percent(t.marginRight)} ${percent(t.marginBottom)} ${percent(t.marginLeft)}"

    private fun percent(fraction: Float): String = "${fmt(fraction * 100f)}%"

    /** Locale-independent number formatting — a comma decimal separator breaks CSS parsing. */
    private fun fmt(value: Float): String = String.format(java.util.Locale.ROOT, "%.3f", value)
        .trimEnd('0')
        .trimEnd('.')

    /**
     * Quotes a family name so `Georgia, serif` does not parse as one broken family — and so a
     * name that is not a CSS identifier at all still arrives intact.
     *
     * The non-identifier case is not hypothetical: a reader-imported font's family value is
     * `user/<file name>`, which is an arbitrary reader-chosen string with a slash in it. Left
     * unquoted that is a syntax error in the `font-family` declaration, the whole rule is dropped,
     * and the book silently keeps its publisher's font — the exact "the setting does nothing"
     * failure the quoting here exists to prevent. A name is an identifier exactly when it is
     * one: letters, digits, underscore and hyphen, not starting with a digit. Everything else is
     * quoted, and a quote inside the name cannot survive being one, so it is stripped.
     */
    private fun quoteFamily(name: String): String {
        val generic = GENERIC_FAMILIES
        if (name in generic) return name
        val safe = name.replace("\"", "")
        return if (CSS_IDENTIFIER.matches(safe)) safe else "\"$safe\""
    }

    /** A name that needs no quotes: a single CSS identifier. */
    private val CSS_IDENTIFIER = Regex("""-?[A-Za-z_][-A-Za-z0-9_]*""")

    private val GENERIC_FAMILIES = setOf(
        "serif",
        "sans-serif",
        "monospace",
        "cursive",
        "fantasy",
        "system-ui",
    )
}
