package exh.yakuyomi

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode

@Execution(ExecutionMode.CONCURRENT)
class TranslationPromptIdAlignTest {
    private val queries = listOf("犬だ", "こんにちは", "さようなら")

    @Test
    fun `prompt emits stable ids and requires them in output`() {
        val prompt = buildTranslationPrompt(
            texts = listOf("犬だ", "こんにちは"),
            sourceLang = "JA",
            targetLang = "en",
            breadcrumb = "",
            isEnFix = false,
        )

        prompt.contains("<|1|>犬だ") shouldBe true
        prompt.contains("<|2|>こんにちは") shouldBe true
        prompt.contains("<|n|>") shouldBe true
    }

    @Test
    fun `exact ids map in order`() {
        alignTranslationLines(
            listOf("<|1|> A dog!", "<|2|> Hello!", "<|3|> Goodbye!"),
            queries,
        ) shouldBe listOf("A dog!", "Hello!", "Goodbye!")
    }

    @Test
    fun `reordered ids restore query order`() {
        alignTranslationLines(
            listOf("<|3|> Goodbye!", "<|1|> A dog!", "<|2|> Hello!"),
            queries,
        ) shouldBe listOf("A dog!", "Hello!", "Goodbye!")
    }

    @Test
    fun `ids tolerate surrounding spaces`() {
        alignTranslationLines(
            listOf("<|  2  |> Hello!", "<|1|> A dog!"),
            queries.take(2),
        ) shouldBe listOf("A dog!", "Hello!")
    }

    @Test
    fun `missing ids fall back to source text`() {
        alignTranslationLines(
            listOf("<|2|> Hello!"),
            queries,
        ) shouldBe listOf("犬だ", "Hello!", "さようなら")
    }

    @Test
    fun `duplicate ids keep first valid occurrence`() {
        alignTranslationLines(
            listOf("<|1|> A dog!", "<|1|> A hound!", "<|2|> Hello!", "<|3|> Goodbye!"),
            queries,
        ) shouldBe listOf("A dog!", "Hello!", "Goodbye!")
    }

    @Test
    fun `out of range extra ids are ignored`() {
        alignTranslationLines(
            listOf("<|1|> A dog!", "<|2|> Hello!", "<|3|> Goodbye!", "<|4|> Bonus!", "<|0|> Zero!"),
            queries,
        ) shouldBe listOf("A dog!", "Hello!", "Goodbye!")
    }

    @Test
    fun `no ids fall back to positional behavior`() {
        alignTranslationLines(listOf("a", "b"), queries) shouldBe listOf("a", "b", "さようなら")
        alignTranslationLines(listOf("a", "b", "c", "d"), queries) shouldBe listOf("a", "b", "c")
        alignTranslationLines(listOf("a", "b", "c"), queries) shouldBe listOf("a", "b", "c")
    }

    @Test
    fun `parser keeps id lines and non numeric tags intact`() {
        parseTranslationLines("<|2|> Hello!\n<|1|> A dog!") shouldBe listOf("<|2|> Hello!", "<|1|> A dog!")
        parseTranslationLines("<|note|> aside") shouldBe listOf("<|note|> aside")
        parseTranslationLines("- first\n- second") shouldBe listOf("first", "second")
        parseTranslationLines("1. first\n2) second") shouldBe listOf("first", "second")
        parseTranslationLines("first\nsecond") shouldBe listOf("first", "second")
    }
}
