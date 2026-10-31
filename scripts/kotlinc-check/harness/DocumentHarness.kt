// Assertions for NovelDocument (the markup handed to the WebView) and NovelScrollBridge (the JS
// contract with the loaded document). Both are deliberately Android-free, so they link directly.
// Run via scripts/kotlinc-check/run-harness.sh document
import eu.kanade.tachiyomi.ui.reader.viewer.novel.NovelContinuous
import eu.kanade.tachiyomi.ui.reader.viewer.novel.NovelDocument
import eu.kanade.tachiyomi.ui.reader.viewer.novel.NovelInline
import eu.kanade.tachiyomi.ui.reader.viewer.novel.NovelPageLayout
import eu.kanade.tachiyomi.ui.reader.viewer.novel.NovelReaderTheme
import eu.kanade.tachiyomi.ui.reader.viewer.novel.NovelScrollBridge
import eu.kanade.tachiyomi.ui.reader.viewer.novel.NovelTypography
import eu.kanade.tachiyomi.ui.reader.viewer.novel.NovelTypographyCss
import mihon.core.archive.EpubArchiveUrls
import mihon.core.archive.EpubDoctype
import mihon.core.archive.EpubMimeSniff
import mihon.core.archive.MAX_SEARCH_HITS
import tachiyomi.source.local.io.LocalBookFolder
import mihon.core.archive.descriptionTextOf
import mihon.core.archive.indexesOf
import mihon.core.archive.plainTextOf
import mihon.core.archive.stripImages
import java.io.ByteArrayInputStream

private var failures = 0

private fun check(name: String, actual: Any?, expected: Any?) {
    val ok = actual == expected
    if (!ok) failures++
    println("${if (ok) "PASS" else "FAIL"}  $name: expected=$expected actual=$actual")
}

private fun contains(name: String, haystack: String, needle: String) = check(name, haystack.contains(needle), true)

private fun absent(name: String, haystack: String, needle: String) = check(name, haystack.contains(needle), false)

private fun checkTrue(name: String, actual: Boolean) = check(name, actual, true)

private fun containsTrue(name: String, haystack: String, needle: String) = check(name, haystack.contains(needle), true)

/** Runs [EpubDoctype.strip] over a literal document. */
private fun stripped(doc: String): String =
    EpubDoctype.strip(ByteArrayInputStream(doc.toByteArray())).readBytes().decodeToString()

/** A spine item as a real EPUB producer emits it: XML prolog, XHTML doctype, no charset, relative refs. */
private val REAL_SPINE_ITEM = """
    <?xml version="1.0" encoding="utf-8"?>
    <!DOCTYPE html PUBLIC "-//W3C//DTD XHTML 1.1//EN" "http://www.w3.org/TR/xhtml11/DTD/xhtml11.dtd">
    <html xmlns="http://www.w3.org/1999/xhtml" xml:lang="ja" lang="ja">
    <head>
    <title>第一章</title>
    <link rel="stylesheet" type="text/css" href="../style/main.css" />
    </head>
    <body class="p">
    <p>これは最初の段落です。</p>
    <p><img src="../images/plate.jpg" alt="" /></p>
    </body>
    </html>
""".trimIndent()

/** The document sits in `OEBPS/Text`, so `../style/main.css` has to resolve to `OEBPS/style/main.css`. */
private const val BASE = "https://book.internal/OEBPS/Text/"

fun main() {
    val t = NovelTypography.DEFAULT
    val built = NovelDocument.build(REAL_SPINE_ITEM, t, BASE)

    // ---------------- the prolog and the doctype ----------------
    absent("xml prolog dropped", built, "<?xml")
    contains("doctype normalised", built, "<!DOCTYPE html>")
    absent("remote dtd dropped", built, "xhtml11.dtd")

    // ---------------- charset ----------------
    // The prolog carried the encoding and it is gone, so an explicit meta is the only thing left that
    // can stop a Shift_JIS book rendering as mojibake.
    contains("charset declared", built, "charset=utf-8")
    checkTrue(
        "charset before the publisher's stylesheet link",
        built.indexOf("charset=utf-8") < built.indexOf("main.css"),
    )

    // ---------------- base url ----------------
    // This is the one that matters: the document sits in OEBPS/Text, so `../style/main.css` has to
    // resolve to OEBPS/style/main.css and not to style/main.css at the archive root.
    contains("base url emitted", built, """<base href="$BASE">""")
    checkTrue("base url ends with a slash", built.contains("""<base href="$BASE">"""))
    checkTrue("only one base tag", built.split("<base").size - 1 == 1)

    // A publisher's own <base> would outrank ours because the first one in the document wins.
    val withBase = NovelDocument.build(
        """<html><head><base href="http://example.com/"></head><body>x</body></html>""",
        t,
        BASE,
    )
    absent("publisher base removed", withBase, "example.com")
    checkTrue("our base survives", withBase.contains("""<base href="$BASE">"""))

    // ---------------- typography + bridge land in the head ----------------
    contains("typography injected", built, "kmk-typography")
    // The bridge has to be a script *element*. Bare, its source is the first thing on the page, printed
    // in the reader's own font - a very loud way to render nothing.
    contains("bridge inside a script element", built, "<script>")
    val bridgeAt = built.indexOf("window.KmkReader")
    val scriptAt = built.lastIndexOf("<script>", bridgeAt)
    checkTrue("bridge is inside the script element", scriptAt in 0 until bridgeAt)
    checkTrue(
        "bridge closed",
        built.indexOf("</script>", bridgeAt) > bridgeAt,
    )
    checkTrue(
        "typography inside the head",
        built.indexOf("kmk-typography") > built.indexOf("<head>") &&
            built.indexOf("kmk-typography") < built.indexOf("</head>"),
    )
    // The reader's rules are !important because a publisher rule on body outranks a host rule on
    // specificity. !important against !important is decided by source order, so ours has to be last -
    // a publisher that also uses it has to come second.
    checkTrue(
        "typography after the publisher stylesheet link",
        built.indexOf("kmk-typography") > built.indexOf("main.css"),
    )
    // The base is the opposite case: it has to precede every element with a relative URL, and the
    // publisher's own <link> is one.
    checkTrue(
        "base before the publisher stylesheet link",
        built.indexOf("<base href") < built.indexOf("main.css"),
    )

    // ---------------- the book's own content survives ----------------
    contains("body kept", built, "これは最初の段落です。")
    contains("relative image kept", built, """src="../images/plate.jpg"""")
    contains("publisher stylesheet kept", built, """href="../style/main.css"""")

    // ---------------- the reader-imported font ----------------
    // A font the reader brought is declared with @font-face and reaches the document as stylesheet
    // source. Bare text in the <head> is not a place CSS can be: the parser meets a character token
    // in head insertion mode, closes the head and opens a body to hold it, so the rule rendered as
    // the paragraph of literal text it is - and declared a font the document was not using either.
    val faceCss =
        """@font-face { font-family: "user/Inter-VariableFont_opsz,wght"; """ +
            """src: url(data:font/ttf;base64,AAA); }"""
    val withFace = NovelDocument.build(REAL_SPINE_ITEM, t, BASE, extraHead = faceCss)
    val faceAt = withFace.indexOf("@font-face")
    checkTrue("font face present", faceAt > 0)
    checkTrue(
        "font face inside a style element",
        withFace.lastIndexOf("<style", faceAt) in 0 until faceAt &&
            withFace.indexOf("</style>", faceAt) > faceAt,
    )
    checkTrue(
        "font face inside the head",
        faceAt > withFace.indexOf("<head>") && faceAt < withFace.indexOf("</head>"),
    )
    // A family name is reader-chosen, so it is arbitrary text — and a closing tag inside a
    // stylesheet ends it before any CSS is read, taking every later rule with it.
    val breakOut = NovelDocument.build(
        REAL_SPINE_ITEM,
        t,
        BASE,
        extraHead = """@font-face { font-family: "x</style><script>1</script><style>"; }""",
    )
    absent("font face cannot close its style element", breakOut, "</style><script>")
    checkTrue("font face kept after escaping", breakOut.contains("""<\/style>"""))
    // No font chosen is the common case, and it must not leave an empty element behind.
    val noFace = NovelDocument.build(REAL_SPINE_ITEM, t, BASE, extraHead = "")
    absent("no empty style element without a font", noFace, "kmk-reader-font")

    // ---------------- the continuous-reading segment marker ----------------
    // The live document's sections each sit in one marker the probe walks; the marker for the
    // section the document was loaded with is part of the document itself.
    val segged = NovelDocument.build(REAL_SPINE_ITEM, t, BASE, segment = 2 to "OEBPS/Text/ch1.xhtml")
    contains(
        "segment opens before the content",
        segged,
        """<div class="kmk-seg" data-i="2" data-u="OEBPS/Text/ch1.xhtml">""",
    )
    checkTrue(
        "segment wraps the body content",
        segged.indexOf("kmk-seg") < segged.indexOf("これは最初の段落です。") &&
            segged.indexOf("これは最初の段落です。") < segged.indexOf("</div>") &&
            segged.indexOf("</div>") < segged.indexOf("</body>"),
    )
    val unsegmented = NovelDocument.build(REAL_SPINE_ITEM, t, BASE)
    absent("no marker without a segment", unsegmented, "kmk-seg")

    // The request stamp the growth scripts verify against is part of the document itself, in the
    // same script element as the bridge so it is declared before any of them can run. Default `0`
    // for non-reader callers; the number the reader passes is the one its scripts are stamped with.
    checkTrue("document declares its epoch", NovelDocument.build(REAL_SPINE_ITEM, t, BASE).contains("window.KmkEpoch = 0;"))
    val epochDoc = NovelDocument.build(REAL_SPINE_ITEM, t, BASE, epoch = 3)
    contains("stamped document carries the epoch", epochDoc, "window.KmkEpoch = 3;")
    checkTrue(
        "epoch is declared inside a script element",
        epochDoc.indexOf("window.KmkEpoch = 3;") > epochDoc.indexOf("<script>") &&
            epochDoc.indexOf("window.KmkEpoch = 3;") < epochDoc.indexOf("</script>"),
    )

    // ---------------- the reader's custom CSS ----------------
    // The reader's hand-typed sheet rides the document in its own element (stable id, so the live
    // swap can replace it) as the document's LAST stylesheet — `!important` against `!important`
    // is decided by source order, and the promise custom CSS is built on is beating the reader's
    // own rules.
    val customed = NovelDocument.build(REAL_SPINE_ITEM, t, BASE, customCss = "p { margin: 0; }")
    contains("custom css injected", customed, """<style id="kmk-custom-css">p { margin: 0; }""")
    checkTrue(
        "custom css is the last stylesheet",
        customed.indexOf("kmk-custom-css") > customed.indexOf("kmk-typography"),
    )
    checkTrue(
        "custom css inside the head",
        customed.indexOf("kmk-custom-css") > customed.indexOf("<head>") &&
            customed.indexOf("kmk-custom-css") < customed.indexOf("</head>"),
    )
    // A build-time copy left standing would keep applying whatever the CSS was — the live swap
    // removes this element when the toggle goes off, and there is nothing to remove if none exist.
    absent(
        "blank custom css emits nothing",
        NovelDocument.build(REAL_SPINE_ITEM, t, BASE, customCss = ""),
        "kmk-custom-css",
    )
    // Reader-typed text is arbitrary. A closing tag inside the sheet would end the element in the
    // parser before the rule is read, silently dropping everything after it.
    val breakOutCustom = NovelDocument.build(
        REAL_SPINE_ITEM,
        t,
        BASE,
        customCss = """p</style><script>1</script><style>""",
    )
    absent("custom css cannot close its element", breakOutCustom, "</style><script>")
    checkTrue("custom css kept after escaping", breakOutCustom.contains("""<\/style>"""))

    check("fragment extracted", NovelContinuous.bodyFragment("<html><body>HELLO</body></html>"), "HELLO")
    check("fragment without a body is itself", NovelContinuous.bodyFragment("just text"), "just text")

    // ---------------- paged mode: a different document, not a different stylesheet call ----------------
    // A paged document carries the column box and the KmkPaged bridge beside the scroll bridge;
    // a continuous one carries neither. The reader mode is a layout, and this is the whole of it.
    val paged = NovelDocument.build(
        REAL_SPINE_ITEM,
        t,
        BASE,
        pageMode = NovelPageLayout.PageMode.PAGED,
    )
    contains("paged stylesheet injected", paged, "kmk-paged")
    contains("paged bridge installed", paged, "window.KmkPaged")
    absent("continuous document has no paged stylesheet", built, "kmk-paged")
    absent("continuous document has no paged bridge", built, "window.KmkPaged")
    checkTrue(
        "paged columns clip and fill in order",
        paged.contains("column-fill: auto") && paged.contains("overflow: hidden"),
    )
    // The paged box's html/body overrides must win by source order against the typography's own
    // !important group - the same rule that puts the typography after the publisher.
    checkTrue(
        "paged stylesheet after the typography",
        paged.indexOf("kmk-paged") > paged.indexOf("kmk-typography"),
    )
    checkTrue(
        "paged bridge inside a script element",
        paged.indexOf("<script>") < paged.indexOf("window.KmkPaged") &&
            paged.indexOf("window.KmkPaged") < paged.indexOf("</script>"),
    )
    val double = NovelDocument.build(
        REAL_SPINE_ITEM,
        t,
        BASE,
        pageMode = NovelPageLayout.PageMode.DOUBLE,
    )
    contains("double-page build is also paginated", double, "window.KmkPaged")

    // The host-side parsers: the bridge's answers arrive as the JSON encoding of the script's
    // return value, and "cannot parse" must degrade to "do nothing", never to a wrong action.
    checkTrue("layout parsed", NovelPageLayout.parseLayout("""{"n":12,"l":1,"w":1}""")?.screens == 12)
    checkTrue("layout reports leftward forward", NovelPageLayout.parseLayout("""{"n":12,"l":1,"w":1}""")?.forwardIsLeft == true)
    checkTrue("layout reports vertical writing", NovelPageLayout.parseLayout("""{"n":2,"l":1,"w":1}""")?.verticalWriting == true)
    checkTrue("absent layout is null", NovelPageLayout.parseLayout("null") == null)
    checkTrue("garbage layout is null", NovelPageLayout.parseLayout("SyntaxError") == null)
    val turn = NovelPageLayout.parseTurn("""{"m":1,"p":3,"n":10,"s":0,"b":0}""")
    checkTrue("turn parsed", turn != null && turn.moved && turn.screen == 3 && turn.screens == 10)
    check("turn fraction", turn?.fraction ?: -1f, 3f / 9f)
    checkTrue("unmoved turn is a document edge", NovelPageLayout.parseTurn("""{"m":0,"p":9,"n":10,"s":1,"b":0}""")?.moved == false)
    checkTrue("absent turn is null", NovelPageLayout.parseTurn("null") == null)
    check("fraction parsed", NovelPageLayout.parseFraction("0.25") ?: -1f, 0.25f)
    checkTrue("absent fraction is null (NaN arrives JSON-encoded as null)", NovelPageLayout.parseFraction("null") == null)


    // A section's own <style> has to travel with it. Dropped, an appended section is laid out in the
    // *previous* section's type - a different line count for the same text, so the section is not the
    // height the scroll arithmetic assumed and the compensation lands the reader somewhere else.
    check(
        "head styles travel with the fragment",
        NovelContinuous.bodyFragment(
            "<html><head><style>p{font-size:2em}</style><title>t</title></head><body><p>x</p></body></html>",
        ),
        "<style>p{font-size:2em}</style><p>x</p>",
    )
    check(
        "nothing but styles is carried out of the head",
        NovelContinuous.bodyFragment(
            "<html><head><title>t</title><base href='x'/></head><body><p>x</p></body></html>",
        ),
        "<p>x</p>",
    )
    check(
        "a style already in the body is not collected twice",
        NovelContinuous.bodyFragment(
            "<html><head><style>a{}</style></head><body><style>b{}</style><p>x</p></body></html>",
        ).split("<style>").size - 1,
        2,
    )
    check(
        "an empty body is still passed through whole",
        NovelContinuous.bodyFragment("<html><head><style>a{}</style></head><body></body></html>"),
        "<html><head><style>a{}</style></head><body></body></html>",
    )
    checkTrue(
        "wrapper carries the position",
        NovelContinuous.wrap(3, "a b.xhtml", "X").startsWith("""<div class="kmk-seg" data-i="3" data-u="a b.xhtml">"""),
    )
    checkTrue("wrapper closes", NovelContinuous.wrap(3, "u.xhtml", "X").endsWith("</div>"))
    checkTrue(
        "append script carries the fragment quoted",
        NovelContinuous.appendScript("<p>x</p>", 4, "u.xhtml").contains(NovelScrollBridge.quoteForJs("<p>x</p>")),
    )

    // The prepend compensation used to measure the new section in the same tick as the insert, which
    // is short by the height of every image in it - NovelInline writes no width/height, so an image
    // is zero-height until it decodes. An illustrated book therefore jumped, which is the whole of
    // "epubs work but scrolling is so buggy".
    val prepend = NovelContinuous.prependScript("<p>x</p>", 4, "u.xhtml")
    checkTrue("prepend returns synchronously", prepend.contains("return true;"))
    checkTrue("prepend hides the new section while it measures", prepend.contains("visibility = 'hidden'"))
    checkTrue("prepend waits for images", prepend.contains("decode()"))
    checkTrue("prepend measures again after they land", prepend.indexOf("decode()") < prepend.indexOf("function reveal"))
    checkTrue("reveal clears the hiding", prepend.contains("div.style.visibility = '';"))
    // A publisher stylesheet asking for smooth scrolling turns scrollBy into an animation, and a
    // compensation that animates defeats itself.
    checkTrue("prepend compensation is instant", prepend.contains("scrollBehavior = 'auto'"))
    checkTrue("trim compensation is instant", NovelContinuous.trimScript(1, true).contains("scrollBehavior = 'auto'"))

    // ---------------- the growth scripts are stamped with their request ----------------
    // A growth script can outlive the document it was built for: a jump or a turn re-renders while
    // its read is in flight, and a script that already left for the renderer cannot be cancelled. A
    // stale append adds a section of somebody else's chapter to the new document's window, and a
    // stale prepend worse — it scrolls the reader with its own compensation. The document declares
    // the request that built it and each script refuses to run against another.
    val stampedAppend = NovelContinuous.appendScript("<p>x</p>", 4, "u.xhtml", 3)
    checkTrue(
        "append script carries its stamp",
        stampedAppend.contains("window.KmkEpoch !== epoch") && stampedAppend.contains("})(3)"),
    )
    checkTrue(
        "prepend script carries its stamp",
        NovelContinuous.prependScript("<p>x</p>", 4, "u.xhtml", 3).contains("window.KmkEpoch !== epoch"),
    )
    checkTrue(
        "trim script carries its stamp",
        NovelContinuous.trimScript(1, true, 3).contains("window.KmkEpoch !== epoch"),
    )
    checkTrue("a stale append answer is dropped", NovelContinuous.isStale("false"))
    checkTrue("a stale answer survives surrounding whitespace", NovelContinuous.isStale("\nfalse\n"))
    checkTrue("a fresh append answer is kept", !NovelContinuous.isStale("true"))
    checkTrue("no answer is kept", !NovelContinuous.isStale(null))

    // ---------------- inlining: the book's resources travel inside the document ----------------
    // A fetcher that knows the archive: plate.jpg and main.css resolve (the css in turn names
    // bg.png), missing.png does not exist, and example.com is not this book's to copy.
    val inlineDoc = (
        "<html><head>" +
            """<link rel="stylesheet" type="text/css" href="../style/main.css" />""" +
            "</head><body>" +
            """<p>hi</p><img src="../images/plate.jpg" />""" +
            """<img src="../images/missing.png" />""" +
            """<img src="https://example.com/x.jpg" />""" +
            """<image xlink:href="cover.svg" />""" +
            "</body></html>"
        )
    val archive = mapOf(
        "https://book.internal/OEBPS/style/main.css" to
            NovelInline.Resource("body{background:url(../images/bg.png)}".toByteArray(), "text/css"),
        "https://book.internal/OEBPS/images/plate.jpg" to
            NovelInline.Resource(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()), "image/jpeg"),
        "https://book.internal/OEBPS/images/bg.png" to
            NovelInline.Resource(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47), "image/png"),
        "https://book.internal/OEBPS/Text/cover.svg" to
            NovelInline.Resource("<svg/>".toByteArray(), "image/svg+xml"),
    )
    val inlined = NovelInline.inline(inlineDoc, BASE) { url -> archive[url] }

    containsTrue("image inlined", inlined, """src="data:image/jpeg;base64,/9j/"""")
    // The stylesheet becomes a <style> whose own url() was resolved against the css's own URL.
    containsTrue(
        "stylesheet inlined with its url()",
        inlined,
        """<style>body{background:url(data:image/png;base64,iVBOR""",
    )
    // A reference that is this book's file but cannot be inlined is re-spelled absolute, not left
    // relative: a fragment has no base of its own, so a relative spelling would resolve against
    // the live document's directory and point a folder away from the file.
    contains(
        "missing reference re-pointed absolute",
        inlined,
        """src="https://book.internal/OEBPS/images/missing.png"""",
    )
    contains("external image untouched", inlined, """src="https://example.com/x.jpg"""")
    containsTrue("svg image inlined", inlined, """xlink:href="data:image/svg+xml;base64,""")

    // The same, in a *fragment*: the continuous window's appends carry body markup with no base,
    // which is exactly where the relative spelling went wrong.
    val fragmentInlined = NovelInline.inline(
        """<img src="../images/missing.png" />""",
        BASE,
    ) { url -> archive[url] }
    contains(
        "fragment missing reference re-pointed absolute",
        fragmentInlined,
        """src="https://book.internal/OEBPS/images/missing.png"""",
    )

    // ---------------- malformed input ----------------
    // A document that opens <head> and never closes it is common in generated EPUBs; the injection
    // must not depend on finding </head>.
    val unclosedHead = NovelDocument.build("<html><head><title>t</title><body><p>hi</p>", t, BASE)
    contains("unclosed head still injected", unclosedHead, "kmk-typography")
    contains("unclosed head keeps body", unclosedHead, "<p>hi</p>")

    // No <head> at all: one has to be created, or there is nowhere to put the base and the charset.
    val headless = NovelDocument.build("<html><body><p>hi</p></body></html>", t, BASE)
    contains("headless gets a head", headless, "<head>")
    checkTrue("headless gets the base", headless.contains("""<base href="$BASE">"""))

    // A bare fragment, which is what a web-novel source returns.
    val fragment = NovelDocument.build("<p>just a paragraph</p>", t, BASE)
    checkTrue("fragment wrapped", fragment.startsWith("<!DOCTYPE html>"))
    checkTrue("fragment gets the base", fragment.contains("""<base href="$BASE">"""))
    contains("fragment keeps content", fragment, "<p>just a paragraph</p>")

    // An empty document must still produce something loadable rather than an empty string.
    val empty = NovelDocument.build("", t, BASE)
    checkTrue("empty still has a doctype", empty.contains("<!DOCTYPE html>"))
    checkTrue("empty still has the base", empty.contains("""<base href="$BASE">"""))

    // The base url is escaped, so a directory name with a quote cannot break out of the attribute.
    val quoted = NovelDocument.build("<p>x</p>", t, """https://book.internal/OEBPS/a"b/""")
    check("base attribute escaped", quoted.contains("""<base href="https://book.internal/OEBPS/a&quot;b/">"""), true)

    // ---------------- redirects ----------------
    // A <meta http-equiv="refresh"> is honoured by the engine with no involvement from the host, so a
    // book carrying one replaces the document the reader is part-way through, untapped, with a URL of
    // the book's choosing. It is not a rendering concern and has no legitimate use in an EPUB.
    val refreshed = NovelDocument.build(
        """<html><head><meta http-equiv="refresh" content="0;url=http://example.com/"></head><body><p>hi</p></body></html>""",
        t,
        BASE,
    )
    // Counted rather than matched: the prologue's own charset declaration is itself a
    // <meta http-equiv>, so "no http-equiv anywhere" would fail against a correct document. One left
    // is ours; a second would be the publisher's.
    check("only our http-equiv survives", refreshed.split("http-equiv").size - 1, 1)
    absent("refresh target removed", refreshed, "example.com")
    contains("refresh body kept", refreshed, "<p>hi</p>")

    // Quoted or unquoted, uppercase or not: all four spellings the parser accepts.
    for (variant in listOf(
        """<META HTTP-EQUIV=refresh CONTENT="0;url=x">""",
        """<meta http-equiv='REFRESH' content='0;url=x'>""",
        """<meta content="0;url=x" http-equiv="refresh">""",
    )) {
        val out = NovelDocument.build("<html><head>$variant</head><body>x</body></html>", t, BASE)
        absent("meta refresh removed: $variant", out, "url=x")
    }

    // Only refresh goes. A <meta charset> is how the document declares its encoding, and ours is
    // injected at the top of the head - removing the publisher's as well would leave a Shift_JIS book
    // with the prolog gone and nothing to say how it is encoded.
    val withCharset = NovelDocument.build(
        """<html><head><meta charset="Shift_JIS"></head><body>x</body></html>""",
        t,
        BASE,
    )
    contains("publisher charset kept", withCharset, "Shift_JIS")

    // ---------------- the scroll bridge ----------------
    val script = NovelScrollBridge.BRIDGE_SCRIPT
    checkTrue("bridge guards against double install", script.contains("if (window.KmkReader) return"))
    checkTrue("bridge exposes fraction", script.contains("fraction"))
    // Turning no longer goes through the bridge — see NovelScrollBridge's own doc for the reported
    // failure that ended that — so what it carries now is exactly the measurements only the laid-out
    // document can answer. Scrolling among them would be the first step back to a tap that can
    // silently do nothing.
    checkTrue("bridge no longer scrolls", !script.contains("scrollViewport"))
    checkTrue("bridge no longer probes edges", !script.contains("edge"))

    // ---------------- tapping a figure ----------------
    // Zooming used to live here too — a capture-phase listener that scaled the <img> in place, and
    // a takeImageTap flag every tap in the book paid a script round trip to read. The in-place
    // scale read as broken layout, and the round trip delayed the scroll itself; zooming is the
    // long-press viewer's job now (NovelImageViewerDialog). These assert the removal stays total:
    // a tap means scroll, page or menu, on the frame it happened.
    checkTrue("bridge no longer zooms", !script.contains("zoomImage"))
    checkTrue("bridge no longer intercepts taps", !script.contains("addEventListener('click'"))
    checkTrue("no tap flag to read", !script.contains("takeImageTap"))
    checkTrue(
        "no zoomed-class rule left in the stylesheet",
        !NovelTypographyCss.stylesheet(NovelTypography()).contains("kmk-zoomed"),
    )

    // evaluateJavascript hands back the JSON encoding, so a script returning NaN arrives as "null".
    // Reading that as anything but "no value" breaks the position save on every document.
    check("parses a number", NovelScrollBridge.number("42.5"), 42.5)
    check("parses zero", NovelScrollBridge.number("0"), 0.0)
    check("null means no value", NovelScrollBridge.number("null"), 0.0)
    check("undefined means no value", NovelScrollBridge.number("undefined"), 0.0)
    check("empty means no value", NovelScrollBridge.number(""), 0.0)
    check("garbage means no value", NovelScrollBridge.number("{\"a\":1}"), 0.0)
    check("absent means no value", NovelScrollBridge.number(null), 0.0)

    // The restore snippet carries a substitution, so a broken template would silently do nothing.
    checkTrue("set fraction is templated", NovelScrollBridge.SET_FRACTION.contains("%f"))
    check(
        "set fraction substitutes cleanly",
        NovelScrollBridge.SET_FRACTION.replace("%f", "0.25").contains("0.25"),
        true,
    )

    // ---------------- quoting the contents entry ----------------
    // A contents fragment id comes out of the book's own table of contents, so it is untrusted input
    // that ends up inside a script. These are the cases that turn an entry into script execution.
    val anchor = NovelScrollBridge.scrollToAnchor("id", 32)
    checkTrue("anchor script substitutes the id", anchor.contains("\"id\""))
    checkTrue("anchor script substitutes the inset", anchor.endsWith("(\"id\",32)"))
    // onPageFinished fires before an illustrated document's data: images have decoded, and an anchor
    // measured then sits short by the height of every image above it — which is how a contents entry
    // landed a screen away from the section it named. The scroll is taken twice, like the prepend
    // compensation: at the finish, and again once the document's images have all settled.
    checkTrue(
        "anchor scrolls early and re-measures after images",
        anchor.contains("if(!go())return false;") &&
            anchor.indexOf("decode()") > 0 &&
            anchor.contains("Promise.all(w).then(go,go)") &&
            anchor.indexOf("function go()") < anchor.indexOf("decode()"),
    )

    val injected = NovelScrollBridge.scrollToAnchor("\"); alert(1); //", 0)
    checkTrue("quotes escaped", !injected.contains(""""); alert(1); //"""))

    checkTrue("closing tag escaped", NovelScrollBridge.quoteForJs("</script>").contains("""<\/script>"""))
    checkTrue(
        "script break-out escaped",
        !NovelScrollBridge.quoteForJs("</script><img onerror=x>").contains("</script"),
    )

    // U+2028/U+2029 are line terminators to a JS parser but ordinary characters to Kotlin, so a raw
    // one ends the script early in the browser and carries on as source.
    checkTrue("line separator escaped", NovelScrollBridge.quoteForJs("a\u2028b").contains("""\u2028"""))
    checkTrue("paragraph separator escaped", NovelScrollBridge.quoteForJs("a\u2029b").contains("""\u2029"""))
    checkTrue("backslash escaped", NovelScrollBridge.quoteForJs("""a\b""") == """"a\\b"""")
    checkTrue("quote escaped", NovelScrollBridge.quoteForJs("a\"b") == """"a\"b"""")
    checkTrue("control char escaped", NovelScrollBridge.quoteForJs("a\u0001b").contains("""\u0001"""))
    checkTrue("plain text untouched", NovelScrollBridge.quoteForJs("chapter1") == "\"chapter1\"")

    // A fragment id is substituted into a template that also carries a `%d`. Sequential literal
    // replaces mean the second one scans the string the first already produced, so substituting `%s`
    // first lets it rewrite the untrusted id itself: `a%db` came out as `a32b`, the element lookup
    // missed, and the reader landed at the top of the document with nothing to explain it.
    checkTrue("percent-d in a fragment survives", NovelScrollBridge.scrollToAnchor("a%db", 32).contains("\"a%db\""))
    checkTrue("percent-s in a fragment survives", NovelScrollBridge.scrollToAnchor("a%sb", 32).contains("\"a%sb\""))
    checkTrue("percent-d alone survives", NovelScrollBridge.scrollToAnchor("%d", 0).contains("\"%d\""))
    checkTrue("inset still substituted after an id with percent-d", NovelScrollBridge.scrollToAnchor("a%db", 32).endsWith("(\"a%db\",32)"))
    checkTrue(
        "percent-d in an id does not eat the inset",
        NovelScrollBridge.scrollToAnchor("100%d", 8).endsWith("(\"100%d\",8)"),
    )

    // ---------------- the folder an import lands in ----------------
    // The name arrives from a text field and is about to become a path segment. `..` is a folder
    // name in a text field and a way out of the local directory on disk.
    check("a plain name survives", LocalBookFolder.sanitize("Youngest Son 01"), "Youngest Son 01")
    check("trailing space is dropped", LocalBookFolder.sanitize("  Warlock  "), "Warlock")
    check("parent directory refused", LocalBookFolder.sanitize(".."), null)
    check("current directory refused", LocalBookFolder.sanitize("."), null)
    check("empty refused", LocalBookFolder.sanitize(""), null)
    check("whitespace only refused", LocalBookFolder.sanitize("   "), null)
    check("separators become spaces, not holes", LocalBookFolder.sanitize("a/b\\c"), "a b c")
    check("a leading separator does not escape", LocalBookFolder.sanitize("/etc"), "etc")
    // A name that is only separators reduces to nothing, which is the case that would otherwise
    // create a folder called "" and quietly fail at the filesystem instead of here.
    check("separators alone refused", LocalBookFolder.sanitize("///"), null)
    check("control characters dropped", LocalBookFolder.sanitize("a\u0001b"), "ab")
    check("a long name is capped", LocalBookFolder.sanitize("x".repeat(200))?.length, LocalBookFolder.MAX_LENGTH)
    check("a non-ascii name survives", LocalBookFolder.sanitize("転生王子"), "転生王子")

// ---------------- XXE: the doctype never reaches a parser ----------------
    // The defence is on the input rather than on the parser's class name, precisely so that it can be
    // checked here. An assertion about which implementation we got could not fail usefully; the
    // absence of a SYSTEM identifier in these bytes is the property that actually matters.
    val xxe = "<!DOCTYPE r [<!ENTITY x SYSTEM \"file:///etc/passwd\">]><r>&x;</r>"
    absent("external entity declaration stripped", stripped(xxe), "SYSTEM")
    absent("internal subset stripped", stripped(xxe), "<!ENTITY")
    absent("doctype keyword gone", stripped(xxe), "DOCTYPE")

    val xhtmlDoctype =
        "<!DOCTYPE html PUBLIC \"-//W3C//DTD XHTML 1.1//EN\" \"http://www.w3.org/TR/xhtml11/DTD/xhtml11.dtd\">" +
            "<html><body>ok</body></html>"
    absent("dtd-fetching doctype stripped", stripped(xhtmlDoctype), "xhtml11.dtd")
    absent("no doctype survives an XHTML document", stripped(xhtmlDoctype), "DOCTYPE")
    contains("document body survives doctype stripping", stripped(xhtmlDoctype), "<html><body>ok</body></html>")

    // A real structural document: the doctype is removed, the root element and its namespace
    // declaration — the parts the whole parser exists to read — are untouched.
    val opf = "<!DOCTYPE package SYSTEM \"oasis.dtd\">\n" +
        "<package xmlns=\"http://www.idpf.org/2007/opf\" version=\"3.0\"><metadata/></package>"
    absent("opf doctype stripped", stripped(opf), "oasis.dtd")
    contains("opf root kept", stripped(opf), "xmlns=\"http://www.idpf.org/2007/opf\"")
    contains("opf metadata kept", stripped(opf), "<metadata/>")

    // Nothing to strip is a pass-through, and the leading bytes must survive byte for byte.
    check("document without a doctype is unchanged", stripped("<package><metadata/></package>"), "<package><metadata/></package>")
    check("empty input is unchanged", stripped(""), "")

    // A doctype behind a prolog, which is where a producer that emits both actually puts it.
    val behindProlog = "<?xml version=\"1.0\"?>\n<!DOCTYPE html SYSTEM \"http://evil/x.dtd\">\n<html/>"
    absent("doctype after a prolog is stripped", stripped(behindProlog), "evil")
    contains("prolog survives", stripped(behindProlog), "<?xml version=\"1.0\"?>")
    contains("root survives", stripped(behindProlog), "<html/>")

    // Unterminated: there is no complete SYSTEM identifier to act on, so passing it through is safe —
    // and the parser's own DOCTYPE feature rejects the document, which is the right outcome for one
    // that is malformed anyway.
    contains("unterminated doctype is left for the parser to reject", stripped("<!DOCTYPE html SYSTEM \"http://e/"), "http://e/")

    // A `>` inside a quoted public identifier must not end the match early, or a doctype would be
    // half-removed and the rest left to the parser.
    val tricky = "<!DOCTYPE r PUBLIC \"a>b\" \"http://evil/x.dtd\"><r/>"
    absent("quoted > does not end the match early", stripped(tricky), "evil")

    // ---------------- markup transforms ----------------
// Pure heuristics over a book's own markup, and the part most likely to be quietly wrong: each is a
// guess with no other way to tell it guessed badly.
    check("text keeps its words", plainTextOf("<p>hello <b>there</b></p>"), " hello there ")
    absent("script contents are not prose", plainTextOf("<p>a</p><script>var x = 1;</script>"), "var x")
    absent("style contents are not prose", plainTextOf("<style>body{color:red}</style><p>a</p>"), "color:red")
    absent("head contents are not prose", plainTextOf("<head><title>t</title></head><p>a</p>"), "<title>")
    check("whitespace collapsed", plainTextOf("<p>a</p>\n\n<p>b</p>"), " a b ")

    // A dc:description is authored HTML stored as markdown-rendered text, so tags and entities
    // that reach the manga screen print literally. These are the shapes the screenshots showed.
    check("paragraph tags become a paragraph break", descriptionTextOf("<p>a</p><p>b</p>"), "a\n\nb")
    check("an unclosed paragraph also breaks", descriptionTextOf("<p>a<p>b"), "a\n\nb")
    check("br becomes a line break", descriptionTextOf("line one<br/>line two"), "line one\nline two")
    check("numeric entity decodes", descriptionTextOf("<p>south&#8212;and</p>"), "south\u2014and")
    check("hex entity decodes", descriptionTextOf("<p>a&#x2014;b</p>"), "a\u2014b")
    check("named entity decodes", descriptionTextOf("<p>one &amp; two</p>"), "one & two")
    check("unknown named entity passes through", descriptionTextOf("<p>&weird; ok</p>"), "&weird; ok")
    check("inline tags leave their words", descriptionTextOf("<p>the <b>hero</b> returns</p>"), "the hero returns")
    // A stray `<` in prose (a comparison, a bit of math) is not a tag and must not eat the line.
    check("stray < in prose survives", descriptionTextOf("<p>5 < 10 and all</p>"), "5 < 10 and all")
    absent("script contents are not a description", descriptionTextOf("<p>a</p><script>evil()</script>"), "evil")
    check("no markup is left behind", descriptionTextOf("<div><p>x</p></div>"), "x")

    // Images are removed, not broken: a broken image still occupies a line and leaves a gap.
    absent("img removed", stripImages("""<p>a<img src="x.png"/>b</p>"""), "img")
    check("text either side survives", stripImages("""<p>a<img src="x.png"/>b</p>"""), "<p>ab</p>")
    absent("uppercase IMG removed", stripImages("""<p><IMG SRC="x.png"/></p>"""), "IMG")
    absent("attributes removed with it", stripImages("""<img alt="caption" src="a.png"/>"""), "caption")
    absent("empty figure removed", stripImages("<figure><img src='a.png'/></figure>"), "figure")
    absent("empty picture removed", stripImages("<picture><img src='a.png'/></picture>"), "picture")
    absent("css image url removed", stripImages("""<div style="background:url(a.png)">x</div>"""), "a.png")
    check("a figure with a caption is left alone", stripImages("<figure><p>caption</p></figure>"), "<figure><p>caption</p></figure>")

    // Offsets travel in stripped units, so the strip and the offset have to agree.
    val plain = plainTextOf("<p>The quick brown fox</p><img src='x.png'/><p>jumps over</p>")
    val hit = plain.indexesOf("brown", plain.length).single()
    check("offset points at the match in the stripped text", plain.substring(hit.documentOffset, hit.documentOffset + 5), "brown")
    check("length is the stripped length", hit.documentLength, plain.length)
    check("case-insensitive", plain.indexesOf("BROWN", plain.length).size, 1)
    check("no match is empty, not an error", plain.indexesOf("zebra", plain.length).size, 0)
    check("empty text searches to nothing", "".indexesOf("a", 0).size, 0)
    check("needle longer than the text", "ab".indexesOf("abcdef", 2).size, 0)
    check("hit count is capped", "x".repeat(MAX_SEARCH_HITS + 50).indexesOf("x", MAX_SEARCH_HITS + 50).size, MAX_SEARCH_HITS)
    checkTrue("offset is inside the snippet", hit.matchOffset >= 0 && hit.matchOffset < hit.snippet.length)

// ---------------- identifying a resource from its bytes ----------------
    // The extension table answers for a book that named its files properly. This is for the ones that
    // did not, and it exists because the fallback the table's own comment promised ("the host falls
    // back to sniffing") never existed: an unknown extension became application/octet-stream, which a
    // renderer declines to decode, so the plate silently never arrived.
    fun sniff(vararg bytes: Int) = EpubMimeSniff.of(ByteArray(bytes.size) { bytes[it].toByte() })

    check("png", sniff(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0), "image/png")
    check("jpeg", sniff(0xFF, 0xD8, 0xFF, 0xE0, 0, 0, 0, 0, 0, 0, 0, 0), "image/jpeg")
    check("gif", sniff(0x47, 0x49, 0x46, 0x38, 0x39, 0x61, 0, 0, 0, 0, 0, 0), "image/gif")
    check("bmp", sniff(0x42, 0x4D, 0x00, 0, 0, 0, 0, 0, 0, 0, 0, 0), "image/bmp")
    check("avif", sniff(0x66, 0x74, 0x79, 0x70, 0x61, 0x76, 0x69, 0x66, 0, 0, 0, 0), "image/avif")
    check(
        "webp, whose type sits past a variable-length field",
        sniff(0x52, 0x49, 0x46, 0x46, 0x1A, 0, 0, 0, 0x57, 0x45, 0x42, 0x50),
        "image/webp",
    )
    check(
        "webp with a different chunk length is still webp",
        sniff(0x52, 0x49, 0x46, 0x46, 0xFF.toByte().toInt(), 0xFF.toByte().toInt(), 0, 0, 0x57, 0x45, 0x42, 0x50),
        "image/webp",
    )
    // A RIFF that is not WebP — a WAV, which is the other thing a book might carry — must not be
    // served as an image. The wildcard skips the length, never the brand.
    check(
        "a riff that is not webp is not an image",
        sniff(0x52, 0x49, 0x46, 0x46, 0x1A, 0, 0, 0, 0x57, 0x41, 0x56, 0x45),
        null,
    )

    // Two containers that agree everywhere except one brand byte, and one that is a bare two-byte
    // prefix. Ordered longest-first, so none of them reads as another.
    check(
        "jp2 container",
        sniff(0x00, 0x00, 0x00, 0x0C, 0x6A, 0x50, 0x20, 0x20, 0x0D, 0x0A, 0x87, 0x0A),
        "image/jp2",
    )
    check(
        "jxl container is not jp2",
        sniff(0x00, 0x00, 0x00, 0x0C, 0x4A, 0x58, 0x4C, 0x20, 0x0D, 0x0A, 0x87, 0x0A),
        "image/jxl",
    )
    check("jxl naked codestream", sniff(0xFF, 0x0A, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0), "image/jxl")

    // A font served as an octet stream is a book that silently loses its publisher's typography,
    // which reads as "this reader ignores the book's styling" rather than as one missing file.
    check("woff2", sniff(0x77, 0x4F, 0x46, 0x32, 0, 0, 0, 0, 0, 0, 0, 0), "font/woff2")
    check("opentype", sniff(0x4F, 0x54, 0x54, 0x4F, 0, 0, 0, 0, 0, 0, 0, 0), "font/otf")

    // A stylesheet that is not recognised must answer null rather than a guess: a caller that gets
    // null falls back to its own last resort, and one that gets a wrong type never gets to.
    check("xml is not a guessable type", sniff(0x3C, 0x3F, 0x78, 0x6D, 0x6C, 0, 0, 0, 0, 0, 0, 0), null)
    check("random bytes", sniff(0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0A, 0x0B, 0x0C), null)
    check("too short to tell", sniff(0xFF, 0xD8), null)
    check("empty", EpubMimeSniff.of(ByteArray(0)), null)

    // The stream form has to leave the stream where it found it. Handing the renderer a stream already
    // advanced by a sniff is a resource that decodes as garbage — indistinguishable from the bug this
    // exists to fix.
    val peekable = ByteArrayInputStream(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 9, 9, 9, 9))
    check("stream sniff", EpubMimeSniff.ofStream(peekable), "image/png")
    check(
        "stream is rewound",
        peekable.readBytes().take(4).toByteArray().toList(),
        listOf<Byte>(0x89.toByte(), 0x50, 0x4E, 0x47),
    )
    // A stream that does not support mark is the normal case for an archive entry, so the buffering
    // path has to produce the same answer and leave the same bytes.
    val unmarkable = object : java.io.InputStream() {
        private val delegate = ByteArrayInputStream(
            byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 7, 7, 7, 7, 7, 7, 7, 7, 7),
        )
        override fun read(): Int = delegate.read()
        override fun read(b: ByteArray, off: Int, len: Int): Int = delegate.read(b, off, len)
    }
    check("stream sniff without mark support", EpubMimeSniff.ofStream(unmarkable), "image/jpeg")

// ---------------- the shared archive URL space ----------------
    // Two libraries serve books now and both resolve references through this, so a disagreement
    // between them is not detectable by reading either one. It has to be asserted.
    check("root document base url", EpubArchiveUrls.documentBaseUrl(null), EpubArchiveUrls.BASE_URL)
    check("blank href falls back to root", EpubArchiveUrls.documentBaseUrl("   "), EpubArchiveUrls.BASE_URL)
    check("fragment stripped", EpubArchiveUrls.documentBaseUrl("OEBPS/a.xhtml#top"), "https://book.internal/OEBPS/")
    check("query stripped", EpubArchiveUrls.documentBaseUrl("OEBPS/a.xhtml?x=1"), "https://book.internal/OEBPS/")

    // The single most consequential line in the EPUB path: a spine item must load at the directory
    // that *contains* it, or every relative reference in the book 404s and it renders as bare text.
    // The file name is not part of it — a document at `OEBPS/chapter1.xhtml` sits in `OEBPS/`, so
    // `../style/main.css` resolves to `style/main.css` at the root, which is what the publisher meant
    // by writing it. Appending the file name would resolve that one reference up into the book.
    check(
        "document base url is the document's directory",
        EpubArchiveUrls.documentBaseUrl("OEBPS/chapter1.xhtml"),
        "https://book.internal/OEBPS/",
    )
    check(
        "a document nested deeper keeps every level",
        EpubArchiveUrls.documentBaseUrl("OEBPS/Text/chapter1.xhtml"),
        "https://book.internal/OEBPS/Text/",
    )
    check(
        "a document at the root needs no directory",
        EpubArchiveUrls.documentBaseUrl("chapter1.xhtml"),
        EpubArchiveUrls.BASE_URL,
    )
    check(
        "a trailing slash is not treated as the file",
        EpubArchiveUrls.documentBaseUrl("OEBPS/chapter1/"),
        "https://book.internal/OEBPS/",
    )

    // A `..` in a producer's href must not walk the base URL off the reserved host. Note what the
    // invariant actually is: traversal clamps *within* the host, it does not reset to the root. What
    // matters is that the host is unchanged and the path is normalised, because a base URL pointing
    // somewhere the renderer will not be served leaves every relative reference unresolvable.
    val escaped = EpubArchiveUrls.documentBaseUrl("OEBPS/../../etc/passwd")
    checkTrue("traversal stays on the reserved host", escaped.startsWith(EpubArchiveUrls.BASE_URL))
    check("traversal is normalised, not preserved", escaped, "https://book.internal/etc/")

    val climbedToRoot = EpubArchiveUrls.documentBaseUrl("a/b/../../../x.xhtml")
    checkTrue("climbing past the root clamps", climbedToRoot.startsWith(EpubArchiveUrls.BASE_URL))

    // The scheme is plain https now, so the host is the only thing separating the book's own
    // resources from the real web. That is the pair of assertions below, and the second one is
    // load-bearing: claiming every https URL would silently swallow every genuinely external
    // image, stylesheet and footnote a book points at.
    checkTrue("archive url recognised", EpubArchiveUrls.isArchiveUrl("https://book.internal/OEBPS/a.png"))
    checkTrue("external url refused", EpubArchiveUrls.isArchiveUrl("https://example.com/a.png").not())
    checkTrue("scheme is a real web scheme", EpubArchiveUrls.BASE_URL.startsWith("https://"))
    checkTrue("host is the reserved one", EpubArchiveUrls.BASE_URL.startsWith("https://book.internal/"))

    check("archive path extracted", EpubArchiveUrls.archivePathOf("https://book.internal/OEBPS/a.png"), "OEBPS/a.png")
    check("archive path percent-decoded", EpubArchiveUrls.archivePathOf("https://book.internal/OEBPS/My%20a.png"), "OEBPS/My a.png")
    check("fragment dropped", EpubArchiveUrls.archivePathOf("https://book.internal/OEBPS/a.xhtml#s1"), "OEBPS/a.xhtml")
    check("traversal normalised", EpubArchiveUrls.archivePathOf("https://book.internal/OEBPS/../a.png"), "a.png")
    check("external url has no archive path", EpubArchiveUrls.archivePathOf("https://example.com/a.png"), null)
    check("garbage has no archive path", EpubArchiveUrls.archivePathOf("https://book.internal/"), null)

    // ---------------- the general reader theme, as the book sees it ----------------
    // A dark reader theme has to reach the document as a background (the existing
    // effectiveFontColor rule then derives a text colour that contrasts with it), and a light one
    // must reach it as nothing at all — "white" forced into the cascade would strip a publisher's
    // own dark plate for no benefit. The colours are the app's reader-theme palette: pure black,
    // the reader gray, and the white that means "no opinion".
    check("black theme becomes the book's background", NovelReaderTheme.backgroundHex(0xFF000000.toInt()), "#000000")
    check("gray theme becomes the book's background", NovelReaderTheme.backgroundHex(0xFF202125.toInt()), "#202125")
    check("white theme forces nothing", NovelReaderTheme.backgroundHex(0xFFFFFFFF.toInt()), null)

    // Content types are load-bearing for the renderer, not conventional: a stylesheet served as
    // text/plain is ignored and the book loses its own appearance with no error anywhere.
    check("xhtml is html to the engine", EpubArchiveUrls.contentTypeForPath("OEBPS/a.xhtml"), "text/html")
    check("svg keeps its own type", EpubArchiveUrls.contentTypeForPath("a.svg"), "image/svg+xml")
    check("css", EpubArchiveUrls.contentTypeForPath("main.css"), "text/css")
    check("woff2", EpubArchiveUrls.contentTypeForPath("f.woff2"), "font/woff2")

    // The extensions a real archive uses that the original table had no row for. Each of these fell
    // through to a null type, and a null type became application/octet-stream, which a renderer will
    // not decode — so each was an illustration that never appeared.
    check("avif", EpubArchiveUrls.contentTypeForPath("plate.avif"), "image/avif")
    check("jfif", EpubArchiveUrls.contentTypeForPath("plate.jfif"), "image/jpeg")
    check("apng", EpubArchiveUrls.contentTypeForPath("plate.apng"), "image/png")
    check("tiff", EpubArchiveUrls.contentTypeForPath("plate.tif"), "image/tiff")
    check("jpeg 2000", EpubArchiveUrls.contentTypeForPath("plate.jp2"), "image/jp2")
    check("jxl", EpubArchiveUrls.contentTypeForPath("plate.jxl"), "image/jxl")

    // A query string is not part of the extension, and the old fallback handed this function the
    // whole URL rather than the archive path — so `plate.jpg?v=2` stopped being a JPEG.
    check("query string is not part of the extension", EpubArchiveUrls.contentTypeForPath("a.jpg?v=2"), "image/jpeg")
    check("unknown is null not a guess", EpubArchiveUrls.contentTypeForPath("a.bin"), null)
    check("no extension is null", EpubArchiveUrls.contentTypeForPath("plate"), null)

    println(if (failures == 0) "\nALL PASS" else "\n$failures FAILURES")
    if (failures > 0) kotlin.system.exitProcess(1)
}
