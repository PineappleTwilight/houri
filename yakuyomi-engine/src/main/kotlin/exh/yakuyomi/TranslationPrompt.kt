package exh.yakuyomi

internal fun buildTranslationPrompt(
    texts: List<String>,
    sourceLang: String,
    targetLang: String,
    breadcrumb: String,
    isEnFix: Boolean,
    mangaContext: String = "",
): String {
    val joined = texts.joinToString("\n") { "- $it" }
    val mangaSection = if (mangaContext.isNotBlank()) "Manga: $mangaContext\n\n" else ""
    val breadcrumbSection = if (breadcrumb.isNotBlank()) "Context (prev chapters, keep names consistent):\n$breadcrumb\n\n" else ""
    return if (isEnFix) {
        "${mangaSection}${breadcrumbSection}You are a manga proofreader. Fix English grammar, spelling and natural flow. Keep character names, honorifics, sound effects and line breaks. Do not translate, do not paraphrase meaning, output only corrected EN. Texts:\n$joined\n\nReturn each corrected line prefixed with '- ' exactly, one per input line, no extra commentary, no quotes."
    } else {
        "${mangaSection}${breadcrumbSection}You are a manga translator. Translate $sourceLang → $targetLang. Preserve character names, honorifics (-san/-kun/-chan/-sama), sound effects and nuance. Use natural $targetLang, keep line breaks and punctuation style. Output only $targetLang. Texts:\n$joined\n\nReturn each translated line prefixed with '- ' exactly, one per input line, no extra commentary, no quotes."
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
