package exh.yakuyomi

import java.security.MessageDigest

/** Global, provider-neutral translation instructions shared by every text backend. */
data class TranslationPromptPolicy(
    val customInstructions: String = "",
    val formality: String = "auto",
    val sfxPolicy: String = "preserve",
    val glossary: Map<String, String> = emptyMap(),
    val fewShotSource: String = "",
    val fewShotTarget: String = "",
) {
    fun fingerprint(): String {
        val glossaryText = glossary.entries
            .sortedBy { it.key }
            .joinToString("|") { "${it.key}=>${it.value}" }
        val raw = listOf(
            customInstructions.trim(),
            formality.trim().lowercase(),
            sfxPolicy.trim().lowercase(),
            glossaryText,
            fewShotSource.trim(),
            fewShotTarget.trim(),
        ).joinToString("\u0000")
        return MessageDigest.getInstance("SHA-256")
            .digest(raw.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    companion object {
        val DEFAULT = TranslationPromptPolicy()
    }
}
