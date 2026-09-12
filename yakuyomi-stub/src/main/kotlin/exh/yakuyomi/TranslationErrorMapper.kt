package exh.yakuyomi

/**
 * Pure, testable mapper from raw pipeline/LLM failure strings to user-actionable
 * messages. Extracted from [TranslationManager.friendlyError] which was a 40-line
 * `when` block inside a 500-line manager, making it untestable and duplicated
 * for `translateMangaInfo` failure handling.
 *
 * Single-responsibility: only string -> user message, no I/O, no state.
 */
object TranslationErrorMapper {
    fun toUserMessage(raw: String?): String {
        if (raw.isNullOrBlank()) return "Translation failed (unknown reason)"
        val lower = raw.lowercase()
        return when {
            "not enough memory" in lower || ("requires at least 3gb" in lower) ->
                "This device doesn't have enough RAM for AI translation (needs 3GB+). Translation is disabled on this device."
            "not enough storage" in lower ->
                "Not enough storage for translation — free up space and retry"
            "invalid config" in lower ->
                "Translation misconfigured — check Settings → Translation → Advanced"
            "not downloaded" in lower || "is the model downloaded" in lower ->
                "On-device model not downloaded — download it in Settings → Translation (Local)"
            "not bundled" in lower || ("runtime" in lower && "mlc" in lower) ->
                "The on-device LLM runtime isn't bundled in this build — enable a cloud provider or install a build with the local runtime"
            "models not ready" in lower || ("model" in lower && "download" in lower) ->
                "AI models not installed — download them in Settings → Translation"
            "gemini nano" in lower && "unavailable" in lower ->
                "Gemini Nano not available on this device — it fell back to the cloud provider. Check the provider/API key in Settings → Translation"
            "invalid page bitmap" in lower ->
                "Image corrupted or too large — skipping page"
            "all filtered" in lower ->
                "No translatable text survived filtering — page skipped"
            "api key" in lower && ("not configured" in lower || "blank" in lower) ->
                "No API key set — add one in Settings → Translation"
            "429" in lower || "rate limit" in lower ->
                "Provider rate-limited you (HTTP 429) — wait and retry, or switch models"
            "401" in lower || "403" in lower || "unauthorized" in lower || "forbidden" in lower ->
                "API key rejected — check it's valid for the selected provider"
            "404" in lower || "not found" in lower ->
                "Model not found — check the model name for the selected provider"
            "400" in lower || "bad request" in lower ->
                "Provider rejected the request — wrong model or unsupported target language"
            "no usable translation" in lower || "empty response" in lower ->
                "Provider returned an empty/invalid translation — try a different model"
            "timeout" in lower || "timed out" in lower ->
                "Provider timed out — check your connection and retry"
            "queue overflow" in lower ->
                "Too many pages queued — retry the chapter"
            "image too large" in lower ->
                "Page image too large (>30MB) — compressed or skipped"
            else -> "Translation failed: $raw"
        }
    }
}
