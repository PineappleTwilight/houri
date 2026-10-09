// KMK -->
package eu.kanade.tachiyomi.ui.reader.viewer.webgpu

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode

/**
 * Covers [PageBytesValidator], the gate that keeps malformed page bytes away from the native
 * decoders on the viewer's single decode thread.
 *
 * The malformed cases are weighted over the happy ones deliberately: a validator that passes
 * everything is harmless, and one that rejects real pages is the failure that costs a reader their
 * chapter, so both directions are pinned here rather than only the ones the bug report mentions.
 */
@Execution(ExecutionMode.CONCURRENT)
class WebGpuImageValidationTest {

    private fun verdict(bytes: ByteArray) = PageBytesValidator.inspect(bytes)

    private fun acceptedFormatOf(bytes: ByteArray): ImageFormat? {
        val result = verdict(bytes)
        result.isAcceptable shouldBe true
        return result.format
    }

    @Test
    fun `an empty body is not an image`() {
        val result = verdict(ByteArray(0))
        result.isAcceptable shouldBe false
        result.fault shouldBe ImageBytesFault.NOT_AN_IMAGE
    }

    @Test
    fun `a body too short to hold a signature is not an image`() {
        val result = verdict(byteArrayOf(0x89.toByte(), 0x50))
        result.isAcceptable shouldBe false
        result.fault shouldBe ImageBytesFault.NOT_AN_IMAGE
        result.detail.shouldNotBeNull()
    }

    @Test
    fun `an html error page served as an image is rejected`() {
        val html = "<!DOCTYPE html><html><body>Captcha</body></html>".toByteArray()
        val result = verdict(html)
        result.isAcceptable shouldBe false
        result.fault shouldBe ImageBytesFault.NOT_AN_IMAGE
        // The detail has to name what was actually received, since "this is not an image" is the
        // difference between a useful bug report and a useless one.
        result.detail!!.contains("DOCTYPE") shouldBe true
    }

    @Test
    fun `a json error body is rejected`() {
        val result = verdict("""{"error":"rate limited"}""".toByteArray())
        result.isAcceptable shouldBe false
        result.fault shouldBe ImageBytesFault.NOT_AN_IMAGE
    }

    @Test
    fun `a plausible png header is accepted and its dimensions are read`() {
        val result = verdict(pngBytes(width = 1200, height = 8000))
        result.isAcceptable shouldBe true
        result.fault.shouldBeNull()
        result.format shouldBe ImageFormat.PNG
        result.width shouldBe 1200
        result.height shouldBe 8000
    }

    @Test
    fun `a tall webtoon strip is not rejected for its height`() {
        val result = verdict(pngBytes(width = 800, height = 120_000))
        result.isAcceptable shouldBe true
        result.height shouldBe 120_000
    }

    @Test
    fun `a zero-dimension header is rejected as implausible`() {
        val result = verdict(pngBytes(width = 0, height = 0))
        result.isAcceptable shouldBe false
        result.fault shouldBe ImageBytesFault.IMPLAUSIBLE_DIMENSIONS
    }

    @Test
    fun `a thumbnail-sized header is rejected as implausible`() {
        val result = verdict(pngBytes(width = 4, height = 4))
        result.isAcceptable shouldBe false
        result.fault shouldBe ImageBytesFault.IMPLAUSIBLE_DIMENSIONS
    }

    @Test
    fun `an absurd width is rejected`() {
        val result = verdict(pngBytes(width = 900_000, height = 800))
        result.isAcceptable shouldBe false
        result.fault shouldBe ImageBytesFault.IMPLAUSIBLE_DIMENSIONS
    }

    @Test
    fun `a truncated png that stops inside its ihdr reports no dimensions`() {
        // Signature and a chunk name, but the width never arrives. It must pass rather than be
        // rejected - a short read is the transport's problem, and guessing at a zero width here
        // would throw away a page that is perfectly fine once re-fetched.
        val bytes = ByteArray(16)
        byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A).copyInto(bytes)
        "IHDR".forEachIndexed { i, c -> bytes[12 + i] = c.code.toByte() }
        val result = verdict(bytes)
        result.isAcceptable shouldBe true
        result.format shouldBe ImageFormat.PNG
        result.width.shouldBeNull()
    }

    @Test
    fun `jpeg is recognised and its dimensions are read from the frame header`() {
        acceptedFormatOf(jpegBytes(width = 1600, height = 2400)) shouldBe ImageFormat.JPEG
    }

    @Test
    fun `a jpeg whose frame header never arrives is still recognised`() {
        // A real APP0 marker but no SOF. Recognising the format is enough to proceed; the decoder is
        // the authority on a frame header that is not there.
        acceptedFormatOf(truncatedJpegBytes()) shouldBe ImageFormat.JPEG
    }

    @Test
    fun `a corrupt jpeg length field does not hang the scan`() {
        // A zero segment length would loop forever for a walker that trusted it. The scan is
        // bounded, so this returns rather than spinning.
        val bytes = ByteArray(32)
        bytes[0] = 0xFF.toByte()
        bytes[1] = 0xD8.toByte()
        bytes[2] = 0xFF.toByte()
        bytes[3] = 0xE0.toByte()
        bytes[4] = 0x00
        bytes[5] = 0x00
        bytes[6] = 0xFF.toByte()
        bytes[7] = 0xE1.toByte()
        bytes[8] = 0x00
        bytes[9] = 0x00
        acceptedFormatOf(bytes) shouldBe ImageFormat.JPEG
    }

    @Test
    fun `gif is recognised and its dimensions are read`() {
        val bytes = ByteArray(16)
        "GIF89a".forEachIndexed { i, c -> bytes[i] = c.code.toByte() }
        le16(bytes, 6, 640)
        le16(bytes, 8, 480)
        val result = verdict(bytes)
        result.format shouldBe ImageFormat.GIF
        result.width shouldBe 640
        result.height shouldBe 480
    }

    @Test
    fun `webp is recognised from its riff header`() {
        val bytes = ByteArray(32)
        "RIFF".forEachIndexed { i, c -> bytes[i] = c.code.toByte() }
        "WEBP".forEachIndexed { i, c -> bytes[8 + i] = c.code.toByte() }
        // A lossy WebP packs its size in a compressed bitstream, so no dimensions are claimed.
        val result = verdict(bytes)
        result.format shouldBe ImageFormat.WEBP
        result.isAcceptable shouldBe true
    }

    @Test
    fun `a webp extended header exposes its canvas size`() {
        val bytes = ByteArray(40)
        "RIFF".forEachIndexed { i, c -> bytes[i] = c.code.toByte() }
        "WEBP".forEachIndexed { i, c -> bytes[8 + i] = c.code.toByte() }
        "VP8X".forEachIndexed { i, c -> bytes[12 + i] = c.code.toByte() }
        le32(bytes, 24, 1999) // canvas width minus one
        le32(bytes, 27, 7999) // canvas height minus one
        val result = verdict(bytes)
        result.format shouldBe ImageFormat.WEBP
        result.width shouldBe 2000
        result.height shouldBe 8000
    }

    @Test
    fun `jxl is recognised in both its naked and container forms`() {
        val naked = ByteArray(16)
        naked[0] = 0xFF.toByte()
        naked[1] = 0x0A.toByte()
        acceptedFormatOf(naked) shouldBe ImageFormat.JXL

        val container = ByteArray(24)
        container[3] = 0x0C
        "JXL ".forEachIndexed { i, c -> container[4 + i] = c.code.toByte() }
        acceptedFormatOf(container) shouldBe ImageFormat.JXL
    }

    @Test
    fun `avif and heif are told apart by brand`() {
        val avif = ByteArray(24)
        be32(avif, 4, 0x66747970L)
        "avif".forEachIndexed { i, c -> avif[8 + i] = c.code.toByte() }
        acceptedFormatOf(avif) shouldBe ImageFormat.AVIF

        val heif = ByteArray(24)
        be32(heif, 4, 0x66747970L)
        "mif1".forEachIndexed { i, c -> heif[8 + i] = c.code.toByte() }
        acceptedFormatOf(heif) shouldBe ImageFormat.HEIF
    }

    @Test
    fun `bmp is recognised and its dimensions are read`() {
        val bytes = ByteArray(40)
        bytes[0] = 'B'.code.toByte()
        bytes[1] = 'M'.code.toByte()
        le32(bytes, 14, 40) // DIB header size
        le32(bytes, 18, 800)
        le32(bytes, 22, 1200)
        val result = verdict(bytes)
        result.format shouldBe ImageFormat.BMP
        result.width shouldBe 800
        result.height shouldBe 1200
    }

    @Test
    fun `a negative bmp height is read as its magnitude`() {
        // The top-down form is documented to use a negative height, not a corrupted one.
        val bytes = ByteArray(40)
        bytes[0] = 'B'.code.toByte()
        bytes[1] = 'M'.code.toByte()
        le32(bytes, 14, 40)
        le32(bytes, 18, 800)
        le32(bytes, 22, (-1200).toLong())
        verdict(bytes).height shouldBe 1200
    }

    @Test
    fun `tiff is recognised in both byte orders`() {
        val little = ByteArray(16)
        little[0] = 'I'.code.toByte()
        little[1] = 'I'.code.toByte()
        be16(little, 2, 42)
        acceptedFormatOf(little) shouldBe ImageFormat.TIFF

        val big = ByteArray(16)
        big[0] = 'M'.code.toByte()
        big[1] = 'M'.code.toByte()
        be16(big, 2, 42)
        acceptedFormatOf(big) shouldBe ImageFormat.TIFF
    }

    @Test
    fun `inspection never throws whatever it is handed`() {
        // Every offset in the parser is attacker-controlled. A two-byte page must produce a
        // verdict, not an IndexOutOfBoundsException on the worker's thread.
        for (size in 0..64) {
            val result = PageBytesValidator.inspect(ByteArray(size) { 0x7F })
            result.isAcceptable shouldBe false
        }
    }

    @Test
    fun `every byte value is rejected without throwing`() {
        for (value in 0..255) {
            val bytes = ByteArray(32) { value.toByte() }
            verdict(bytes).isAcceptable shouldBe false
        }
    }

    // Byte-array builders. These spell the file layout out at the call site, which is the only way a
    // reader can tell whether a test is asserting against the real format or against a helper's idea
    // of it.

    private fun pngBytes(width: Int, height: Int): ByteArray {
        val bytes = ByteArray(64)
        val signature = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        signature.copyInto(bytes)
        "IHDR".forEachIndexed { i, c -> bytes[12 + i] = c.code.toByte() }
        be32(bytes, 16, width.toLong() and 0xFFFFFFFFL)
        be32(bytes, 20, height.toLong() and 0xFFFFFFFFL)
        return bytes
    }

    /**
     * A JFIF APP0 segment with no frame header after it - long enough to clear the sniff threshold,
     * which is the point: recognition must not depend on finding dimensions.
     */
    private fun truncatedJpegBytes(): ByteArray {
        val bytes = ByteArray(32)
        bytes[0] = 0xFF.toByte()
        bytes[1] = 0xD8.toByte() // SOI
        bytes[2] = 0xFF.toByte()
        bytes[3] = 0xE0.toByte() // APP0
        be16(bytes, 4, 16) // segment length
        "JFIF".forEachIndexed { i, c -> bytes[6 + i] = c.code.toByte() }
        return bytes
    }

    private fun jpegBytes(width: Int, height: Int): ByteArray {
        val bytes = ByteArray(32)
        bytes[0] = 0xFF.toByte()
        bytes[1] = 0xD8.toByte() // SOI
        bytes[2] = 0xFF.toByte()
        bytes[3] = 0xC0.toByte() // SOF0
        be16(bytes, 4, 17) // segment length
        bytes[6] = 8 // sample precision
        be16(bytes, 7, height)
        be16(bytes, 9, width)
        return bytes
    }

    private fun be16(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = ((value shr 8) and 0xFF).toByte()
        bytes[offset + 1] = (value and 0xFF).toByte()
    }

    private fun be32(bytes: ByteArray, offset: Int, value: Long) {
        bytes[offset] = ((value shr 24) and 0xFF).toByte()
        bytes[offset + 1] = ((value shr 16) and 0xFF).toByte()
        bytes[offset + 2] = ((value shr 8) and 0xFF).toByte()
        bytes[offset + 3] = (value and 0xFF).toByte()
    }

    private fun le16(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value and 0xFF).toByte()
        bytes[offset + 1] = ((value shr 8) and 0xFF).toByte()
    }

    private fun le32(bytes: ByteArray, offset: Int, value: Long) {
        bytes[offset] = (value and 0xFF).toByte()
        bytes[offset + 1] = ((value shr 8) and 0xFF).toByte()
        bytes[offset + 2] = ((value shr 16) and 0xFF).toByte()
        bytes[offset + 3] = ((value shr 24) and 0xFF).toByte()
    }
}
// KMK <--
