package exh.yakuyomi

import kotlinx.serialization.Serializable
import java.security.MessageDigest

// KMK --> Manga-details metadata translation cache identity (spec 2026-09-23).

/** Fallback source language when the source does not declare one. */
const val MANGA_INFO_DEFAULT_SOURCE_LANG = "JA"

/**
 * Cached on-device translation of a manga's metadata (title + description).
 *
 * The validation fields ([sourceFingerprint], [targetLanguage], [provider], [model],
 * [promptFingerprint]) default to blank so legacy JSON (title + description only)
 * still decodes — but such entries never validate and are replaced on the next request.
 * No migration is required.
 */
@Serializable
data class MangaInfoTranslation(
    val title: String,
    val description: String? = null,
    val sourceFingerprint: String = "",
    val targetLanguage: String = "",
    val provider: String = "",
    val model: String = "",
    val promptFingerprint: String = "",
) {
    /**
     * True only when all five identity fields are non-blank and match the current request.
     * A stale entry must never be displayed as current.
     */
    fun isValidFor(
        sourceFingerprint: String,
        targetLanguage: String,
        provider: String,
        model: String,
        promptFingerprint: String = "",
    ): Boolean {
        if (this.sourceFingerprint.isBlank() || this.targetLanguage.isBlank()) return false
        if (this.provider.isBlank() || this.model.isBlank()) return false
        if (promptFingerprint.isNotBlank() && this.promptFingerprint != promptFingerprint) return false
        if (promptFingerprint.isBlank() && this.promptFingerprint.isNotBlank()) return false
        return this.sourceFingerprint == sourceFingerprint &&
            this.targetLanguage == targetLanguage &&
            this.provider == provider &&
            this.model == model
    }
}

/** Provider readiness for metadata translation. MangaTranslator is image-only and unsupported. */
enum class MangaInfoProviderState {
    READY,
    MANGA_TRANSLATOR_UNSUPPORTED,
    NOT_CONFIGURED,
}

/** Provider/model identity stamped on metadata cache entries for staleness checks. */
@Serializable
data class MangaInfoIdentity(
    val provider: String = "",
    val model: String = "",
)

/**
 * Resolves the source language for a metadata request: the source's declared language when
 * available, [MANGA_INFO_DEFAULT_SOURCE_LANG] otherwise. Kept public so the details screen
 * model can pass `source?.lang` straight through.
 */
fun resolveMangaInfoSourceLang(declaredLang: String?): String {
    val clean = declaredLang?.trim()?.take(20)?.takeIf { it.isNotBlank() } ?: return MANGA_INFO_DEFAULT_SOURCE_LANG
    return clean.uppercase()
}

/**
 * Maps raw provider output lines back to title/description.
 *
 * Lines are consumed in request order (title first, then description) and only for fields
 * that were present in the request. Returns null when output is missing or blank for any
 * present field: per spec, empty or unusable output is an error, never padded with source
 * text and never falling back to the original. Extra output lines are ignored.
 *
 * When the original title is absent, the mapped title stays blank (never the translated
 * description mislabeled as title); the store's blank-title guard then skips persistence
 * while the translated description is still returned.
 */
internal fun mapMangaInfoTranslation(
    title: String?,
    description: String?,
    translated: List<String>,
): Pair<String, String?>? {
    val trimmed = translated.map { it.trim() }
    var index = 0
    val outTitle = if (!title.isNullOrBlank()) {
        if (index >= trimmed.size) return null
        trimmed[index++].takeIf { it.isNotBlank() } ?: return null
    } else {
        ""
    }
    val outDescription = if (!description.isNullOrBlank()) {
        if (index >= trimmed.size) return null
        trimmed[index++].takeIf { it.isNotBlank() } ?: return null
    } else {
        null
    }
    return outTitle to outDescription
}

/**
 * Stable digest of the source metadata a translation was made from: source ID, normalized
 * source title, normalized source description, and source language. Any change invalidates
 * the cached entry. The language is uppercased so "ja", "JA", and " ja " share an identity.
 */
fun buildMangaInfoFingerprint(
    sourceId: Long?,
    title: String,
    description: String?,
    sourceLang: String,
): String {
    fun norm(s: String): String = s.replace(Regex("\\s+"), " ").trim()
    val raw = "${sourceId ?: -1}|${norm(title)}|${norm(description ?: "")}|${norm(sourceLang).uppercase()}"
    val md = MessageDigest.getInstance("SHA-256")
    return md.digest(raw.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
// KMK <--
