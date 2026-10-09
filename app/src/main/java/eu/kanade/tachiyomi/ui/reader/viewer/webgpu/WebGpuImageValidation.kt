// KMK -->
package eu.kanade.tachiyomi.ui.reader.viewer.webgpu

// Deciding whether a page's bytes are worth handing to the native decoder.
//
// This runs before any native work and reads a bounded number of bytes off the front of the page,
// so unlike everything else in the decode path it cannot itself hang. Two jobs:
//
// One is diagnosis. A source that answers a page request with an HTML error page and a 200 status -
// the single most common way this goes wrong in practice - otherwise reaches ImageDecoder as an
// opaque UnknownFormatException, and the reader shows "Unsupported image format" for what is
// actually a captcha page or an expired CDN link. Naming the fault is the difference between a
// report a user can act on and one they cannot.
//
// The other is keeping bad bytes away from the native decoders entirely. Brotli and JXL - both
// linked into the decoder - have a history of malformed input driving them into an unbounded loop,
// and the decode worker is a single thread that every other page queues behind, so one such page
// stops the whole chapter from loading. A check that refuses the bytes first is the only defence
// that does not depend on the decoder's own robustness.
//
// Dimensions are read only where the header places them at a fixed offset. Where finding them
// needs a segment walk, the verdict still carries the format and simply reports null dimensions:
// saying less is better than reporting a number parsed wrong and acting on it.

/** What [PageBytesValidator.inspect] concluded about a page's bytes. */
internal enum class ImageBytesFault {
    /** Nothing recognisable starts here - typically an HTML or JSON error body served with a 200. */
    NOT_AN_IMAGE,

    /** Dimensions were parsed and cannot describe a real page. */
    IMPLAUSIBLE_DIMENSIONS,
}

/** An image container recognised by signature, in the order the signatures can be told apart. */
internal enum class ImageFormat(val label: String) {
    JPEG("jpeg"),
    PNG("png"),
    GIF("gif"),
    WEBP("webp"),
    BMP("bmp"),
    JXL("jxl"),
    AVIF("avif"),
    HEIF("heif"),
    TIFF("tiff"),
    ICO("ico"),
}

/**
 * The outcome of inspecting a page's bytes.
 *
 * [fault] null means the bytes passed. Dimensions are null when the format was recognised but the
 * header does not put them somewhere cheap to read, which is not a fault - the native decoder is
 * still the authority on those, and it will say so itself.
 */
internal data class ImageBytesVerdict(
    val format: ImageFormat?,
    val fault: ImageBytesFault?,
    val width: Int? = null,
    val height: Int? = null,
    val detail: String? = null,
) {
    val isAcceptable: Boolean get() = fault == null

    companion object {
        fun rejected(
            fault: ImageBytesFault,
            format: ImageFormat?,
            detail: String,
            width: Int? = null,
            height: Int? = null,
        ) =
            ImageBytesVerdict(format, fault, width, height, detail)
    }
}

/**
 * A page narrower or shorter than this is a thumbnail, an icon, or a misparse - not a manga page.
 *
 * The decoder independently refuses anything at or under 4px on a side, so this sits well above
 * that: it exists to catch a header whose dimensions are plainly wrong, not to duplicate the
 * decoder's own floor.
 */
private const val MIN_PLAUSIBLE_EDGE = 16

/**
 * A page wider than this is a misparse rather than a scan.
 *
 * Height is deliberately far more generous, because a webtoon strip legitimately runs to tens of
 * thousands of pixels on one axis and rejecting those would reject exactly the pages this viewer
 * exists to read well.
 */
private const val MAX_PLAUSIBLE_WIDTH = 50_000
private const val MAX_PLAUSIBLE_HEIGHT = 200_000

/** Bytes needed before a format can be named at all. */
private const val SNIFF_BYTES = 16

/** How much of a rejected body is quoted back, so the failure says what actually arrived. */
private const val PREVIEW_BYTES = 16

/**
 * Reads [bytes] as a big-endian unsigned 32-bit value, or null when it does not reach [offset].
 *
 * Long rather than Int: a garbage header routinely yields values above Int.MAX, and that is
 * precisely the case worth catching, so it must survive the parse rather than wrap into something
 * that looks plausible.
 */
private fun beU32(bytes: ByteArray, offset: Int): Long? {
    if (offset < 0 || offset + 4 > bytes.size) return null
    return ((bytes[offset].toLong() and 0xFF) shl 24) or
        ((bytes[offset + 1].toLong() and 0xFF) shl 16) or
        ((bytes[offset + 2].toLong() and 0xFF) shl 8) or
        (bytes[offset + 3].toLong() and 0xFF)
}

private fun beU16(bytes: ByteArray, offset: Int): Int? {
    if (offset < 0 || offset + 2 > bytes.size) return null
    return ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)
}

private fun leU16(bytes: ByteArray, offset: Int): Int? {
    if (offset < 0 || offset + 2 > bytes.size) return null
    return (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)
}

private fun leU32(bytes: ByteArray, offset: Int): Long? {
    if (offset < 0 || offset + 4 > bytes.size) return null
    return ((bytes[offset].toLong() and 0xFF)) or
        ((bytes[offset + 1].toLong() and 0xFF) shl 8) or
        ((bytes[offset + 2].toLong() and 0xFF) shl 16) or
        ((bytes[offset + 3].toLong() and 0xFF) shl 24)
}

/**
 * Absolute value of a 32-bit field that was read unsigned.
 *
 * BMP writes its height as a signed value, and a top-down image is the documented negative case
 * rather than a corruption - but reading it unsigned turns -1200 into ~4 billion, which no sane
 * bound can accept.
 */
private fun magnitude32(raw: Long): Long =
    if (raw >= 0x80000000L) 0x1_0000_0000L - raw else raw

/** True when [bytes] starts with [magic], as unsigned bytes. */
private fun startsWith(bytes: ByteArray, magic: IntArray): Boolean {
    if (bytes.size < magic.size) return false
    for (i in magic.indices) {
        if ((bytes[i].toInt() and 0xFF) != magic[i]) return false
    }
    return true
}

/** True when [bytes] starts with [ascii], compared as unsigned bytes. */
private fun startsWithAscii(bytes: ByteArray, ascii: String, offset: Int = 0): Boolean {
    if (offset < 0 || offset + ascii.length > bytes.size) return false
    for (i in ascii.indices) {
        if ((bytes[offset + i].toInt() and 0xFF) != ascii[i].code) return false
    }
    return true
}

/**
 * Classifies a page's bytes and, where the header makes it cheap, reads its dimensions.
 *
 * Pure and total: it never throws, never loops on attacker-controlled data, and never reads past
 * the end of the buffer. Every accessor it uses returns null instead of indexing out of range,
 * which is what keeps a two-byte "page" from taking the reader down on the way to reporting that
 * it is too short to be an image.
 */
internal object PageBytesValidator {

    fun inspect(bytes: ByteArray): ImageBytesVerdict {
        if (bytes.isEmpty()) {
            return ImageBytesVerdict.rejected(ImageBytesFault.NOT_AN_IMAGE, null, "empty body")
        }
        if (bytes.size < SNIFF_BYTES) {
            return ImageBytesVerdict.rejected(
                ImageBytesFault.NOT_AN_IMAGE,
                null,
                "only ${bytes.size} byte(s); too short to carry an image signature",
            )
        }

        val sniffed = sniff(bytes) ?: return ImageBytesVerdict.rejected(
            ImageBytesFault.NOT_AN_IMAGE,
            null,
            "no known image signature; body starts ${preview(bytes)}",
        )

        val (format, dimensions) = sniffed
        if (dimensions == null) {
            return ImageBytesVerdict(format, null)
        }

        val (width, height) = dimensions
        val implausible = when {
            width < MIN_PLAUSIBLE_EDGE || height < MIN_PLAUSIBLE_EDGE ->
                "header declares ${width}x$height, below the ${MIN_PLAUSIBLE_EDGE}px floor"
            width > MAX_PLAUSIBLE_WIDTH -> "header declares width $width, past $MAX_PLAUSIBLE_WIDTH"
            height > MAX_PLAUSIBLE_HEIGHT -> "header declares height $height, past $MAX_PLAUSIBLE_HEIGHT"
            else -> null
        }

        return if (implausible != null) {
            ImageBytesVerdict.rejected(ImageBytesFault.IMPLAUSIBLE_DIMENSIONS, format, implausible, width, height)
        } else {
            ImageBytesVerdict(format, null, width, height)
        }
    }

    /** A short, printable rendering of the first bytes, for the "what did we actually get" log line. */
    private fun preview(bytes: ByteArray): String {
        // Wide enough for the diagnoses this exists for. Eight bytes renders an HTML error body as
        // "<!DOCTYP", which names nothing - the whole value of this is being able to read the body
        // back and see it is a captcha page, an error envelope, or a login redirect.
        val n = minOf(bytes.size, PREVIEW_BYTES)
        val hex = buildString(n * 3) {
            for (i in 0 until n) {
                if (i > 0) append(' ')
                append("%02x".format(bytes[i].toInt() and 0xFF))
            }
        }
        // Printable ASCII alongside, since "an HTML page" is the diagnosis half the time and the
        // hex alone makes a reader guess at it.
        val ascii = buildString(n) {
            for (i in 0 until n) {
                val c = bytes[i].toInt() and 0xFF
                append(if (c in 0x20..0x7E) c.toChar() else '.')
            }
        }
        return "[$hex] \"$ascii\""
    }

    /** Recognised format plus its dimensions when they sit at a fixed offset, else null dimensions. */
    private fun sniff(bytes: ByteArray): Pair<ImageFormat, Pair<Int, Int>?>? {
        return when {
            startsWith(bytes, intArrayOf(0xFF, 0xD8, 0xFF)) -> ImageFormat.JPEG to jpegDimensions(bytes)
            startsWith(bytes, intArrayOf(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)) ->
                ImageFormat.PNG to pngDimensions(bytes)

            startsWithAscii(bytes, "GIF87a") || startsWithAscii(bytes, "GIF89a") ->
                ImageFormat.GIF to gifDimensions(bytes)

            startsWithAscii(bytes, "RIFF") && startsWithAscii(bytes, "WEBP", 8) ->
                ImageFormat.WEBP to webpDimensions(bytes)

            startsWithAscii(bytes, "BM") -> ImageFormat.BMP to bmpDimensions(bytes)
            startsWithAscii(bytes, "II", 0) && beU16(bytes, 2)?.let { it == 42 } == true ->
                ImageFormat.TIFF to null

            startsWithAscii(bytes, "MM", 0) && beU16(bytes, 2)?.let { it == 42 } == true ->
                ImageFormat.TIFF to null

            startsWith(bytes, intArrayOf(0x00, 0x00, 0x01, 0x00)) -> ImageFormat.ICO to null
            isJxlContainer(bytes) -> ImageFormat.JXL to null
            startsWith(bytes, intArrayOf(0xFF, 0x0A)) -> ImageFormat.JXL to null
            isIsoBmff(bytes) -> isoBmffFormat(bytes) to null
            else -> null
        }
    }

    /** PNG's IHDR is required to be the first chunk, so width and height are at fixed offsets. */
    private fun pngDimensions(bytes: ByteArray): Pair<Int, Int>? {
        if (!startsWithAscii(bytes, "IHDR", 12)) return null
        val width = beU32(bytes, 16) ?: return null
        val height = beU32(bytes, 20) ?: return null
        if (width > Int.MAX_VALUE || height > Int.MAX_VALUE) return null
        return width.toInt() to height.toInt()
    }

    /** GIF's logical screen descriptor is fixed-layout and always present. */
    private fun gifDimensions(bytes: ByteArray): Pair<Int, Int>? {
        val width = leU16(bytes, 6) ?: return null
        val height = leU16(bytes, 8) ?: return null
        return width to height
    }

    /** BMP's DIB header is fixed-layout in the BITMAPINFOHEADER and BITMAPV4/V5 forms. */
    private fun bmpDimensions(bytes: ByteArray): Pair<Int, Int>? {
        val dibSize = leU32(bytes, 14) ?: return null
        if (dibSize < 12L) return null
        val width = leU32(bytes, 18) ?: return null
        val height = leU32(bytes, 22) ?: return null
        // Magnitude before the range check: a top-down BMP stores a negative height, read here as a
        // large unsigned value, and testing that against Int.MAX_VALUE first would reject every
        // top-down BMP as garbage and leave the sign handling unreachable.
        val w = magnitude32(width)
        val h = magnitude32(height)
        if (w > Int.MAX_VALUE.toLong()) return null
        return w.toInt() to h.toInt()
    }

    /**
     * WebP only exposes dimensions cheaply in the extended container; the lossy and lossless
     * variants pack theirs inside a compressed bitstream, which is the decoder's job to read.
     */
    private fun webpDimensions(bytes: ByteArray): Pair<Int, Int>? {
        if (!startsWithAscii(bytes, "VP8X", 12)) return null
        val width = leU32(bytes, 24)?.let { (it and 0xFFFFFFL) + 1L } ?: return null
        val height = leU32(bytes, 27)?.let { (it and 0xFFFFFFL) + 1L } ?: return null
        if (width > Int.MAX_VALUE || height > Int.MAX_VALUE) return null
        return width.toInt() to height.toInt()
    }

    /**
     * Walks JPEG markers to the first SOFn, which is where the dimensions live.
     *
     * Bounded twice over: by the buffer length, and by [JPEG_MARKER_SCAN_LIMIT] segments, so a
     * corrupt length field cannot walk this into a long loop. Standalone markers (DHT, DQT, SOI)
     * carry no length and are skipped without advancing; the rest advance by their declared length.
     */
    private fun jpegDimensions(bytes: ByteArray): Pair<Int, Int>? {
        var offset = 2 // past SOI
        var segments = 0
        while (offset + 3 < bytes.size && segments < JPEG_MARKER_SCAN_LIMIT) {
            segments++
            if ((bytes[offset].toInt() and 0xFF) != 0xFF) return null
            val marker = bytes[offset + 1].toInt() and 0xFF
            offset += 2
            when (marker) {
                // Start of frame, excluding the DHT/JPG/DAC markers that share the 0xC0..0xCF block.
                in 0xC0..0xCF -> {
                    if (marker == 0xC4 || marker == 0xC8 || marker == 0xCC) return null
                    // From the segment length: length(2) | precision(1) | height(2) | width(2).
                    val height = beU16(bytes, offset + 3) ?: return null
                    val width = beU16(bytes, offset + 5) ?: return null
                    return width to height
                }
                // No payload: resynchronise on the next 0xFF.
                0x01, 0xD0, 0xD1, 0xD2, 0xD3, 0xD4, 0xD5, 0xD6, 0xD7, 0xD8, 0xD9 -> Unit
                else -> {
                    val length = beU16(bytes, offset) ?: return null
                    if (length < 2) return null
                    offset += length
                }
            }
        }
        return null
    }

    /** JXL's ISOBMFF-wrapped container form: a 12-byte signature box ahead of the codestream. */
    private fun isJxlContainer(bytes: ByteArray): Boolean =
        bytes.size >= 12 &&
            bytes[0].toInt() and 0xFF == 0x00 &&
            bytes[1].toInt() and 0xFF == 0x00 &&
            bytes[2].toInt() and 0xFF == 0x00 &&
            bytes[3].toInt() and 0xFF == 0x0C &&
            startsWithAscii(bytes, "JXL ", 4)

    /**
     * Any ISO base media file (AVIF, HEIF) opens with an ftyp box.
     *
     * Only the box type is checked, which sits at offset 4. The major brand follows it at offset 8
     * and is what tells AVIF from HEIF - checking that slot for "ft" instead would compare the box
     * type against itself and never match a real file.
     */
    private fun isIsoBmff(bytes: ByteArray): Boolean =
        bytes.size >= 12 && beU32(bytes, 4) == ISO_FTYP_BOX

    private fun isoBmffFormat(bytes: ByteArray): ImageFormat {
        val brand = buildString(4) {
            for (i in 8..11) append((bytes[i].toInt() and 0xFF).toChar())
        }
        return if (brand.startsWith("avi") || brand.startsWith("avis")) ImageFormat.AVIF else ImageFormat.HEIF
    }
}

/** 'ftyp', the box type every ISO base media file starts with. */
private const val ISO_FTYP_BOX = 0x66747970L

/**
 * How many markers [PageBytesValidator.jpegDimensions] will walk before giving up.
 *
 * A real JPEG reaches its SOF within a handful of segments; this only bounds the corrupt case,
 * where a length field can point the walk forward one byte at a time.
 */
private const val JPEG_MARKER_SCAN_LIMIT = 64
// KMK <--
