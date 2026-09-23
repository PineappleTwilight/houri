package exh.yakuyomi

// KMK --> Cache identity/validation, fingerprint, and source-language fallback coverage.
import android.content.Context
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import java.io.File

@Execution(ExecutionMode.CONCURRENT)
class MangaInfoTranslationCacheTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun fullEntry() = MangaInfoTranslation(
        title = "Translated Title",
        description = "Translated description",
        sourceFingerprint = buildMangaInfoFingerprint(7L, "Original", "Desc", "JA"),
        targetLanguage = "en",
        provider = "openrouter",
        model = "google/gemma-2-9b-it:free",
    )

    @Test
    fun `legacy JSON without identity fields decodes but never validates`() {
        val decoded = json.decodeFromString<MangaInfoTranslation>("""{"title":"T","description":"D"}""")

        decoded.title shouldBe "T"
        decoded.isValidFor("anything", "en", "openrouter", "m") shouldBe false
    }

    @Test
    fun `matching identity validates`() {
        val entry = fullEntry()

        entry.isValidFor(entry.sourceFingerprint, "en", "openrouter", "google/gemma-2-9b-it:free") shouldBe true
    }

    @Test
    fun `any single identity field mismatch invalidates`() {
        val entry = fullEntry()

        entry.isValidFor("other-fingerprint", "en", "openrouter", "google/gemma-2-9b-it:free") shouldBe false
        entry.isValidFor(entry.sourceFingerprint, "de", "openrouter", "google/gemma-2-9b-it:free") shouldBe false
        entry.isValidFor(entry.sourceFingerprint, "en", "gemini", "google/gemma-2-9b-it:free") shouldBe false
        entry.isValidFor(entry.sourceFingerprint, "en", "openrouter", "other-model") shouldBe false
    }

    @Test
    fun `blank identity fields never validate`() {
        val entry = MangaInfoTranslation(title = "T")

        entry.isValidFor("", "", "", "") shouldBe false
    }

    @Test
    fun `fingerprint is stable and sensitive to every input`() {
        val base = buildMangaInfoFingerprint(7L, "Original", "Desc", "JA")

        buildMangaInfoFingerprint(7L, "Original", "Desc", "JA") shouldBe base
        buildMangaInfoFingerprint(7L, "Changed", "Desc", "JA") shouldNotBe base
        buildMangaInfoFingerprint(7L, "Original", "Other", "JA") shouldNotBe base
        buildMangaInfoFingerprint(7L, "Original", "Desc", "EN") shouldNotBe base
        buildMangaInfoFingerprint(8L, "Original", "Desc", "JA") shouldNotBe base
        buildMangaInfoFingerprint(null, "Original", "Desc", "JA") shouldNotBe base
    }

    @Test
    fun `fingerprint ignores surrounding and repeated whitespace`() {
        buildMangaInfoFingerprint(7L, "  Original   Title ", "Desc", "JA") shouldBe
            buildMangaInfoFingerprint(7L, "Original Title", "Desc", "JA")
    }

    @Test
    fun `source language falls back to JA when undeclared`() {
        resolveMangaInfoSourceLang(null) shouldBe "JA"
        resolveMangaInfoSourceLang("") shouldBe "JA"
        resolveMangaInfoSourceLang("   ") shouldBe "JA"
    }

    @Test
    fun `source language uses the declared language uppercased`() {
        resolveMangaInfoSourceLang("EN") shouldBe "EN"
        resolveMangaInfoSourceLang("zh-Hans") shouldBe "ZH-HANS"
        resolveMangaInfoSourceLang(" ja ") shouldBe "JA"
    }

    @Test
    fun `fingerprint is stable across language case and format`() {
        buildMangaInfoFingerprint(7L, "Original", "Desc", "JA") shouldBe
            buildMangaInfoFingerprint(7L, "Original", "Desc", "ja")
        buildMangaInfoFingerprint(7L, "Original", "Desc", "JA") shouldBe
            buildMangaInfoFingerprint(7L, "Original", "Desc", " ja ")
    }

    @Test
    fun `mapping requires translated output for every present field`() {
        mapMangaInfoTranslation("Title", "Desc", listOf("T9n Title", "T9n desc")) shouldBe
            ("T9n Title" to "T9n desc")
        mapMangaInfoTranslation("Title", "Desc", listOf("T9n Title")) shouldBe null
        mapMangaInfoTranslation("Title", "Desc", emptyList()) shouldBe null
    }

    @Test
    fun `mapping rejects blank output instead of falling back to the original`() {
        mapMangaInfoTranslation("Title", "Desc", listOf("  ", "T9n desc")) shouldBe null
        mapMangaInfoTranslation("Title", "Desc", listOf("T9n Title", "")) shouldBe null
        mapMangaInfoTranslation("Title", null, listOf("")) shouldBe null
    }

    @Test
    fun `mapping ignores extra output lines`() {
        mapMangaInfoTranslation("Title", "Desc", listOf("T9n Title", "T9n desc", "Extra")) shouldBe
            ("T9n Title" to "T9n desc")
    }

    @Test
    fun `mapping handles title-only and description-only requests`() {
        mapMangaInfoTranslation("Title", null, listOf("T9n Title")) shouldBe ("T9n Title" to null)
        mapMangaInfoTranslation("Title", "  ", listOf("T9n Title")) shouldBe ("T9n Title" to null)
        val descOnly = mapMangaInfoTranslation("", "Desc", listOf("T9n desc"))
        descOnly shouldNotBe null
        descOnly?.first shouldBe ""
        descOnly?.second shouldBe "T9n desc"
    }

    @Test
    fun `store returns entries only on full identity match`(@TempDir tmp: File) {
        val store = MangaInfoTranslationStore(contextWithFilesDir(tmp))
        val entry = fullEntry()
        store.put(11L, entry)

        store.get(11L) shouldBe entry
        store.getValidated(11L, entry.sourceFingerprint, "en", "openrouter", "google/gemma-2-9b-it:free") shouldBe entry
        store.getValidated(11L, entry.sourceFingerprint, "de", "openrouter", "google/gemma-2-9b-it:free") shouldBe null
        store.getValidated(11L, "stale", "en", "openrouter", "google/gemma-2-9b-it:free") shouldBe null
    }

    @Test
    fun `store treats legacy entries as stale and clear removes them`(@TempDir tmp: File) {
        val store = MangaInfoTranslationStore(contextWithFilesDir(tmp))
        File(File(tmp, "manga_info_translations").apply { mkdirs() }, "12.json")
            .writeText("""{"title":"Old","description":"Old desc"}""")

        store.get(12L)?.title shouldBe "Old"
        store.getValidated(12L, "fp", "en", "openrouter", "m") shouldBe null

        store.clear(12L)
        store.get(12L) shouldBe null
    }

    private fun contextWithFilesDir(dir: File): Context {
        val ctx = mockk<Context>()
        every { ctx.filesDir } returns dir
        return ctx
    }
}
// KMK <--
