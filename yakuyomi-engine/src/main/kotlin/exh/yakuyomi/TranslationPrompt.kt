package exh.yakuyomi

/** Builds the breadcrumb-aware translation/fix prompt for a batch of text lines. */
internal fun buildTranslationPrompt(
    texts: List<String>,
    sourceLang: String,
    targetLang: String,
    breadcrumb: String,
    isEnFix: Boolean,
    mangaContext: String = "",
): String {
    val joined = texts.joinToString("\n") { "- $it" }
    // Manga grounding (title/description/tags) comes first — always present, separate from the
    // sliding-window breadcrumbs, so names/honorifics stay consistent across the whole series.
    val mangaSection = if (mangaContext.isNotBlank()) "Manga: $mangaContext\n\n" else ""
    val breadcrumbSection = if (breadcrumb.isNotBlank()) "Context notes (sliding window):\n$breadcrumb\n\n" else ""
    return if (isEnFix) {
        "${mangaSection}${breadcrumbSection}Fix grammar, preserve names, output only EN. Texts:\n$joined\n\nReturn each corrected line prefixed with '- ' exactly, one per input line, no extra commentary."
    } else {
        "${mangaSection}${breadcrumbSection}Translate $sourceLang → $targetLang. Preserve names, honorifics, output only $targetLang. Texts:\n$joined\n\nReturn each translated line prefixed with '- ' exactly, one per input line, no extra commentary."
    }
}

/** Pads or truncates provider output to match the input line count. */
internal fun alignTranslationLines(result: List<String>, queries: List<String>): List<String> {
    return when {
        result.size == queries.size -> result
        result.size < queries.size -> result + queries.drop(result.size).map { it.trim() }
        else -> result.take(queries.size)
    }
}

/**
 * Parses the LLM's reply into one translated line per input line. Accepts the dash-list
 * protocol, numbered lists, code fences, or plain line-per-item output. Returns null when
 * nothing usable is found.
 */
internal fun parseTranslationLines(content: String): List<String>? {
    val cleaned = content.trim()
    // Strip common LLM code fences or markdown wrappers that hide the dash list.
    val fenced = Regex("```[a-zA-Z]*\\s*").replace(cleaned, "")
    val working = if (fenced.startsWith("- ") || fenced.startsWith("1.") || fenced.startsWith("1)") || fenced.contains("\n- ")) {
        fenced
    } else {
        cleaned
    }
    val dashLines = working.lines().map { it.trim() }.filter { it.startsWith("- ") }.map { it.removePrefix("- ").trim() }.filter { it.isNotBlank() }
    if (dashLines.isNotEmpty()) return dashLines
    val numbered = working.lines().map { it.trim() }.mapNotNull { line ->
        Regex("""^\d+[.)]\s*(.+)""").find(line)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }
    }
    if (numbered.isNotEmpty()) return numbered
    val split = working.lines().map { it.trim() }.filter { it.isNotBlank() }
    if (split.isNotEmpty()) return split
    return null
}

/** Fallback parse for raw JSON bodies: dash lines first, then the first "content" field. */
internal fun parseTranslationLinesFromJson(jsonStr: String): List<String>? {
    val lines = jsonStr.lines().map { it.trim() }.filter { it.startsWith("- ") }.map { it.removePrefix("- ").trim() }.filter { it.isNotBlank() }
    if (lines.isNotEmpty()) return lines
    val regex = Regex("\"content\"\\s*:\\s*\"((?:\\\\\"|[^\"])*)\"")
    val match = regex.find(jsonStr)?.groupValues?.getOrNull(1) ?: return null
    val unescaped = match.replace("\\n", "\n").replace("\\\"", "\"").replace("\\\\", "\\")
    return parseTranslationLines(unescaped)
}
