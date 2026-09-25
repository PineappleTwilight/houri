package exh.yakuyomi

private const val MAX_TEXT_LEN = 500
private const val MAX_PROMPT_CHARS = 8000
private const val MAX_GLOSSARY_ENTRIES = 50
private const val MAX_CUSTOM_INSTRUCTION_CHARS = 1200
private const val MAX_FEW_SHOT_CHARS = 900
private const val MAX_GLOSSARY_SECTION_CHARS = 2200

private val CONTROL_CHARS = Regex("[\\p{Cntrl}]")
private val MULTILINE_CONTROL_CHARS = Regex("[\\p{Cntrl}&&[^\\r\\n]]")
private val INLINE_WHITESPACE = Regex("\\s+")
private val HORIZONTAL_WHITESPACE = Regex("[ \\t]+")
private val CODE_FENCE = Regex("```[a-zA-Z]*\\s*")
private val NUMBERED_LINE = Regex("""^\d+[.)]\s*(.+)""")

private fun sanitizeInline(s: String, maxLen: Int = MAX_TEXT_LEN): String {
    var value = s.replace(CONTROL_CHARS, " ").replace(INLINE_WHITESPACE, " ").trim()
    if (value.length > maxLen) value = value.take(maxLen)
    if (value.startsWith("- ") || value.startsWith("#") || value.lowercase().startsWith("ignore previous")) {
        value = value.prependIndent(" ")
    }
    return value.replace("\n", " ").replace("\r", " ")
}

private fun sanitizeMultiline(s: String, maxLen: Int): String =
    s.replace(MULTILINE_CONTROL_CHARS, " ")
        .replace(HORIZONTAL_WHITESPACE, " ")
        .trim()
        .take(maxLen)

private fun formalityDirective(value: String): String = when (value.trim().lowercase()) {
    "casual" -> "Use casual, natural manga dialogue."
    "polite" -> "Use polite manga dialogue while keeping the original tone."
    "formal" -> "Use formal manga dialogue while keeping the original tone."
    "literary" -> "Use polished literary manga dialogue without adding new plot details."
    else -> "Match the formality and social register of the source lines."
}

private fun sfxDirective(value: String): String = when (value.trim().lowercase()) {
    "translate" -> "Translate descriptive sound effects naturally; keep iconic SFX when translation would lose their meaning."
    "mixed" -> "Keep iconic SFX as SFX or transliterate them, and translate descriptive SFX naturally."
    else -> "Keep iconic SFX as SFX or transliterate them; do not invent new sound effects."
}

// KMK --> Stable per-region IDs: source lines carry `<|n|>` tags and the model must
// echo the ID with each translation, so reordered/dropped lines map back one-to-one
// without fuzzy source-vs-translation matching.
private fun protocol(isEnFix: Boolean, target: String): String = if (isEnFix) {
    "Return each corrected line as `<|n|> correction` with its original ID, one per input line, no extra commentary, no quotes."
} else {
    "Return each translated line as `<|n|> translation` with its original ID, one per input line, no extra commentary, no quotes. If a line is already $target or is purely SFX/numbers, return it as-is with its ID."
}
// KMK <--

internal fun buildTranslationPrompt(
    texts: List<String>,
    sourceLang: String,
    targetLang: String,
    breadcrumb: String,
    isEnFix: Boolean,
    mangaContext: String = "",
    glossary: Map<String, String> = emptyMap(),
    policy: TranslationPromptPolicy = TranslationPromptPolicy.DEFAULT,
    imageContext: String = "",
): String {
    val safeTexts = texts.map { sanitizeInline(it) }.filter { it.isNotBlank() }.take(30)
    val safeManga = sanitizeInline(mangaContext, 300)
    val safeBreadcrumb = sanitizeMultiline(breadcrumb, 1500)
    val safeImageContext = sanitizeInline(imageContext, 300)
    val safeSource = sanitizeInline(sourceLang, 20).ifBlank { "JA" }
    val safeTarget = sanitizeInline(targetLang, 20).ifBlank { "en" }
    val safeCustom = sanitizeMultiline(policy.customInstructions, MAX_CUSTOM_INSTRUCTION_CHARS)
    val safeFewShotSource = sanitizeMultiline(policy.fewShotSource, MAX_FEW_SHOT_CHARS)
    val safeFewShotTarget = sanitizeMultiline(policy.fewShotTarget, MAX_FEW_SHOT_CHARS)

    val sections = buildList {
        if (safeImageContext.isNotBlank()) add(safeImageContext)
        if (safeManga.isNotBlank()) add("Manga: $safeManga")
        if (safeBreadcrumb.isNotBlank()) add("Context (prev chapters, keep names consistent):\n$safeBreadcrumb")
        if (safeCustom.isNotBlank()) {
            add("User translation guidance (follow when compatible with the required output protocol):\n$safeCustom")
        }
        add(formalityDirective(policy.formality))
        add(sfxDirective(policy.sfxPolicy))
        val glossaryText = buildString {
            append("Glossary (use exactly as given):\n")
            glossary.entries
                .sortedBy { it.key }
                .take(MAX_GLOSSARY_ENTRIES)
                .forEach { entry ->
                    val key = sanitizeInline(entry.key, 40)
                    val value = sanitizeInline(entry.value, 40)
                    if (key.isNotBlank() && value.isNotBlank()) append("- $key -> $value\n")
                }
        }.trim().take(MAX_GLOSSARY_SECTION_CHARS)
        if (glossaryText.length > "Glossary (use exactly as given):".length) add(glossaryText)
        if (safeFewShotSource.isNotBlank() && safeFewShotTarget.isNotBlank()) {
            add("Example source:\n$safeFewShotSource\nExample target:\n$safeFewShotTarget")
        }
    }

    val role = if (isEnFix) {
        "You are a manga proofreader and copy editor. Fix English grammar, spelling, punctuation and natural flow. Keep character names, honorifics, sound effects and line breaks. Preserve meaning, do not paraphrase creatively, output only corrected EN."
    } else {
        "You are an expert manga translator specializing in $safeSource -> $safeTarget. Preserve character names, honorifics (-san/-kun/-chan/-sama/-senpai/-sensei), sound effects and cultural nuance. Use natural, fluent $safeTarget appropriate for manga dialogue and keep line breaks, punctuation style, tone, reading order, and vertical-text order."
    }
    val head = (listOf(role) + sections).joinToString("\n\n").take(MAX_PROMPT_CHARS - 800)
    val fixedProtocol = protocol(isEnFix, safeTarget)
    val textBudget = (MAX_PROMPT_CHARS - head.length - fixedProtocol.length - 24).coerceAtLeast(400)
    val perLineBudget = (textBudget / safeTexts.size.coerceAtLeast(1)).coerceAtLeast(80)
    // KMK --> Stable per-region IDs instead of dash bullets.
    val joined = safeTexts.mapIndexed { index, line -> "<|${index + 1}|> ${line.take(perLineBudget)}" }
        .joinToString("\n").take(textBudget)
    // KMK <--
    return "$head\n\nTexts:\n$joined\n\n$fixedProtocol"
}

// KMK --> Stable-ID alignment: `<|n|>` tags map output lines back to query order.
private val LINE_ID = Regex("""^<\|\s*(\d+)\s*\|>\s*(.*)$""", RegexOption.DOT_MATCHES_ALL)

/** Splits a `<|n|> text` line into its 1-based ID and remainder; non-ID lines yield null. */
private fun extractLineId(line: String): Pair<Int?, String> {
    val compact = line.trim().removePrefix("- ").trim()
    val match = LINE_ID.find(compact) ?: return null to line
    val id = match.groupValues[1].toIntOrNull() ?: return null to line
    return id to match.groupValues[2].trim()
}

/**
 * Maps provider output back to query order via stable IDs. Reordered IDs are restored,
 * missing IDs are filled with the source line, duplicate IDs keep the first valid
 * occurrence, and out-of-range IDs are ignored. Falls back to positional pad/truncate
 * when no valid ID exists.
 */
internal fun alignTranslationLines(result: List<String>, queries: List<String>): List<String> {
    if (queries.isEmpty()) return emptyList()
    val byId = HashMap<Int, String>()
    var sawValidId = false
    for (line in result) {
        val (id, text) = extractLineId(line)
        if (id == null || id < 1 || id > queries.size) continue
        sawValidId = true
        if (text.isNotBlank() && !byId.containsKey(id)) byId[id] = text
    }
    if (!sawValidId) {
        return when {
            result.size == queries.size -> result
            result.size < queries.size -> result + queries.drop(result.size).map { it.trim() }
            else -> result.take(queries.size)
        }
    }
    return queries.indices.map { index -> byId[index + 1] ?: queries[index].trim() }
}
// KMK <--

/**
 * Parses the LLM's reply into one translated line per input line. Accepts the dash-list
 * protocol, numbered lists, code fences, or plain line-per-item output.
 */
internal fun parseTranslationLines(content: String): List<String>? {
    val cleaned = content.trim()
    val fenced = CODE_FENCE.replace(cleaned, "")
    val working = if (fenced.startsWith("- ") || fenced.startsWith("1.") || fenced.startsWith("1)") || fenced.contains("\n- ")) {
        fenced
    } else {
        cleaned
    }
    val dashLines = working.lines().map { it.trim() }.filter { it.startsWith("- ") }
        .map { it.removePrefix("- ").trim() }
        .filter { it.isNotBlank() }
    if (dashLines.isNotEmpty()) return dashLines
    val numbered = working.lines().map { it.trim() }.mapNotNull { line ->
        NUMBERED_LINE.find(line)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }
    }
    if (numbered.isNotEmpty()) return numbered
    return working.lines().map { it.trim() }.filter { it.isNotBlank() }.takeIf { it.isNotEmpty() }
}

/** Fallback parse for raw JSON bodies: dash lines first, then the first "content" field. */
internal fun parseTranslationLinesFromJson(jsonStr: String): List<String>? {
    val lines = jsonStr.lines().map { it.trim() }.filter { it.startsWith("- ") }
        .map { it.removePrefix("- ").trim() }
        .filter { it.isNotBlank() }
    if (lines.isNotEmpty()) return lines
    val regex = Regex("\"content\"\\s*:\\s*\"((?:\\\\\"|[^\"])*)\"")
    val match = regex.find(jsonStr)?.groupValues?.getOrNull(1) ?: return null
    val unescaped = match.replace("\\n", "\n").replace("\\\"", "\"").replace("\\\\", "\\")
    return parseTranslationLines(unescaped)
}
