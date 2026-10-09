// KMK -->
package eu.kanade.tachiyomi.ui.reader.viewer.webgpu

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode

/**
 * Covers [WebGpuExtensions] and [applyByteTransforms].
 *
 * Not CONCURRENT, unlike every other test class here, and deliberately so. [WebGpuExtensions] is a
 * process-wide singleton, so these tests are not isolated from each other: one installing an
 * extension while another reads [WebGpuExtensions.active] makes the reader see extensions it did not
 * register, and the order assertions fail intermittently. Serialising the class is the honest fix -
 * JUnit still runs it alongside other test classes, only not alongside itself. Every test restores
 * the registry afterwards rather than asserting on whatever another left behind.
 *
 * The composition rules carry the weight here - pass-through on null, chaining between extensions,
 * isolation when one throws - because those are what decide whether a third-party extension can
 * break the reader, and each one is invisible in the interface itself.
 */
@Execution(ExecutionMode.SAME_THREAD)
class WebGpuExtensionsTest {

    private fun restore() {
        WebGpuExtensions.ids().forEach(WebGpuExtensions::uninstall)
    }

    /**
     * An extension that records what it was handed and returns whatever the test decides.
     *
     * Only [onTransform] is exposed, because the post-decode compartment needs a real
     * [ViewerReaderPage] - a GPU-bound type these unit tests deliberately avoid. The transform
     * compartment is the one carrying the composition rules, so that is the one with coverage.
     */
    private class Recording(
        override val id: String,
        override val order: Int = DEFAULT_ORDER,
        private val onTransform: (WebGpuPageContext, ByteArray) -> ByteArray? = { _, b -> b },
    ) : WebGpuExtension {
        val transformCalls = mutableListOf<ByteArray>()

        override suspend fun transformBytes(context: WebGpuPageContext, bytes: ByteArray): ByteArray? {
            transformCalls += bytes
            return onTransform(context, bytes)
        }
    }

    @Test
    fun `an extension returning null passes the bytes through untouched`() = runTest {
        val passthrough = Recording("t.passthrough") { _, _ -> null }
        val input = byteArrayOf(1, 2, 3)
        applyByteTransforms(listOf(passthrough), WebGpuPageRef(), input) shouldBe input
    }

    @Test
    fun `an extension returning a replacement swaps the bytes`() = runTest {
        val swap = Recording("t.swap") { _, _ -> byteArrayOf(9) }
        applyByteTransforms(listOf(swap), WebGpuPageRef(), byteArrayOf(1)) shouldBe byteArrayOf(9)
    }

    @Test
    fun `extensions chain in the order given and each sees the previous one's output`() = runTest {
        val seen = mutableListOf<String>()
        val first = Recording("t.first", order = 0) { _, b ->
            seen += "first:${b.first()}"
            byteArrayOf(2)
        }
        val second = Recording("t.second", order = 1) { _, b ->
            seen += "second:${b.first()}"
            byteArrayOf(3)
        }

        applyByteTransforms(listOf(first, second), WebGpuPageRef(), byteArrayOf(1)) shouldBe byteArrayOf(3)
        seen shouldBe listOf("first:1", "second:2")

        // Reversed input, reversed chain. Ordering belongs to the registry, which sorts on install;
        // this runs what it is handed rather than sorting again, so it cannot be what decides it.
        seen.clear()
        applyByteTransforms(listOf(second, first), WebGpuPageRef(), byteArrayOf(1)) shouldBe byteArrayOf(2)
        seen shouldBe listOf("second:1", "first:3")
    }

    @Test
    fun `a throwing extension is skipped and the rest still run`() = runTest {
        val boom = Recording("t.boom", order = 0) { _, _ -> throw IllegalStateException("boom") }
        val after = Recording("t.after", order = 1) { _, b -> b + byteArrayOf(7) }
        val result = applyByteTransforms(listOf(boom, after), WebGpuPageRef(), byteArrayOf(1))
        result shouldBe byteArrayOf(1, 7)
    }

    @Test
    fun `a mutating extension does damage the shared buffer, which is why the contract forbids it`() = runTest {
        val mutating = Recording("t.mutating") { _, b ->
            b[0] = 99
            throw IllegalStateException("boom")
        }
        val input = byteArrayOf(5, 5)
        applyByteTransforms(listOf(mutating), WebGpuPageRef(), input)
        // Characterisation, not endorsement. The buffer is the reader's live one and is handed over
        // as-is, because copying it per extension per page would mean copying tens of megabytes to
        // defend against a contract violation. An extension that writes to what it is given corrupts
        // the page, which is why transformBytes documents that the array must not be modified.
        input shouldBe byteArrayOf(99, 5)
    }

    @Test
    fun `an extension is only handed the page facts it declared`() = runTest {
        var seen: WebGpuPageRef? = null
        val spy = Recording("t.spy") { ctx, _ ->
            seen = ctx as WebGpuPageRef
            null
        }
        val context = WebGpuPageRef(chapterId = 42L, pageIndex = 7, mangaId = 99L)
        applyByteTransforms(listOf(spy), context, byteArrayOf(1))
        seen?.chapterId shouldBe 42L
        seen?.pageIndex shouldBe 7
        seen?.mangaId shouldBe 99L
    }

    @Test
    fun `registering a second extension under a live id replaces the first`() = runTest {
        try {
            val first = Recording("t.swapable", order = 0) { _, _ -> byteArrayOf(1) }
            val second = Recording("t.swapable", order = 0) { _, _ -> byteArrayOf(2) }
            WebGpuExtensions.install(first)
            WebGpuExtensions.install(second)
            WebGpuExtensions.ids().count { it == "t.swapable" } shouldBe 1
            val result = applyByteTransforms(WebGpuExtensions.active(), WebGpuPageRef(), byteArrayOf(0))
            result shouldBe byteArrayOf(2)
        } finally {
            restore()
        }
    }

    @Test
    fun `active returns extensions in order and skips switched-off ones`() = runTest {
        try {
            WebGpuExtensions.install(Recording("t.late", order = 90))
            WebGpuExtensions.install(Recording("t.early", order = 10))
            WebGpuExtensions.install(Recording("t.off", order = 50))
            WebGpuExtensions.setEnabled("t.off", false)

            val ids = WebGpuExtensions.active().map { it.id }
            ids shouldBe listOf("t.early", "t.late")

            // Ordering and filtering are separate concerns: every registered id is still sorted,
            // switched off or not, and only active() drops the disabled one.
            WebGpuExtensions.ids() shouldBe listOf("t.early", "t.off", "t.late")

            WebGpuExtensions.isEnabled("t.off") shouldBe false
            WebGpuExtensions.isEnabled("t.early") shouldBe true
        } finally {
            restore()
        }
    }

    @Test
    fun `switching off hides an extension and switching it on brings it back`() = runTest {
        try {
            val ext = Recording("t.toggle") { _, b -> b + byteArrayOf(1) }
            WebGpuExtensions.install(ext)
            val input = byteArrayOf(0)

            applyByteTransforms(WebGpuExtensions.active(), WebGpuPageRef(), input) shouldBe byteArrayOf(0, 1)

            WebGpuExtensions.setEnabled("t.toggle", false)
            applyByteTransforms(WebGpuExtensions.active(), WebGpuPageRef(), input) shouldBe input

            WebGpuExtensions.setEnabled("t.toggle", true)
            applyByteTransforms(WebGpuExtensions.active(), WebGpuPageRef(), input) shouldBe byteArrayOf(0, 1)
        } finally {
            restore()
        }
    }

    @Test
    fun `uninstall removes an extension from the chain`() = runTest {
        try {
            val ext = Recording("t.removable") { _, b -> b + byteArrayOf(1) }
            WebGpuExtensions.install(ext)
            WebGpuExtensions.uninstall("t.removable") shouldBe true
            WebGpuExtensions.uninstall("t.removable") shouldBe false
            applyByteTransforms(WebGpuExtensions.active(), WebGpuPageRef(), byteArrayOf(0)) shouldBe
                byteArrayOf(0)
        } finally {
            restore()
        }
    }

    @Test
    fun `switching off something that was never installed reports no change`() = runTest {
        try {
            WebGpuExtensions.setEnabled("t.never", false) shouldBe false
            WebGpuExtensions.isEnabled("t.never") shouldBe false
        } finally {
            restore()
        }
    }

    @Test
    fun `a default extension occupies neither compartment until told otherwise`() = runTest {
        val idle = object : WebGpuExtension {
            override val id: String = "t.idle"
        }
        idle.order shouldBe DEFAULT_ORDER
        idle.transformBytes(WebGpuPageRef(), byteArrayOf(1)) shouldBe null
    }

    @Test
    fun `an empty extension set returns the input array itself`() = runTest {
        val input = byteArrayOf(1, 2, 3)
        applyByteTransforms(emptyList(), WebGpuPageRef(), input) shouldBe input
    }
}
// KMK <--
