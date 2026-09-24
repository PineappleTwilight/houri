package exh.yakuyomi

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode

@Execution(ExecutionMode.CONCURRENT)
class TranslationPromptPolicyTest {
    @Test
    fun `policy fingerprint is stable and changes with each field`() {
        val base = TranslationPromptPolicy(
            customInstructions = "Keep the terse tone.",
            formality = "casual",
            sfxPolicy = "mixed",
            glossary = mapOf("裁判" to "judge"),
            fewShotSource = "裁判です",
            fewShotTarget = "It's the judge.",
        )

        base.fingerprint() shouldBe base.fingerprint()
        base.copy(customInstructions = "Use formal language.").fingerprint() shouldNotBe base.fingerprint()
        base.copy(formality = "formal").fingerprint() shouldNotBe base.fingerprint()
        base.copy(sfxPolicy = "preserve").fingerprint() shouldNotBe base.fingerprint()
        base.copy(glossary = mapOf("裁判" to "referee")).fingerprint() shouldNotBe base.fingerprint()
        base.copy(fewShotTarget = "The referee.").fingerprint() shouldNotBe base.fingerprint()
    }

    @Test
    fun `prompt contains structured policy and bounded line protocol`() {
        val policy = TranslationPromptPolicy(
            customInstructions = "Avoid adding plot details.",
            formality = "casual",
            sfxPolicy = "translate",
            glossary = mapOf("犬" to "dog"),
            fewShotSource = "犬だ",
            fewShotTarget = "A dog!",
        )
        val prompt = buildTranslationPrompt(
            texts = listOf("犬だ", "こんにちは"),
            sourceLang = "JA",
            targetLang = "en",
            breadcrumb = "Previous chapter context",
            isEnFix = false,
            glossary = policy.glossary,
            policy = policy,
        )

        prompt.contains("Avoid adding plot details.") shouldBe true
        prompt.contains("Use casual") shouldBe true
        prompt.contains("Translate descriptive sound effects") shouldBe true
        prompt.contains("犬 -> dog") shouldBe true
        prompt.contains("- 犬だ") shouldBe true
        prompt.contains("Return each translated line prefixed") shouldBe true
        prompt.length <= 8000 shouldBe true
    }

    @Test
    fun `parser accepts dash numbered fenced and plain responses`() {
        parseTranslationLines("```text\n- first\n- second\n```") shouldBe listOf("first", "second")
        parseTranslationLines("1. first\n2) second") shouldBe listOf("first", "second")
        parseTranslationLines("first\nsecond") shouldBe listOf("first", "second")
        parseTranslationLinesFromJson("{\"content\":\"- first\\n- second\"}") shouldBe listOf("first", "second")
    }
}
