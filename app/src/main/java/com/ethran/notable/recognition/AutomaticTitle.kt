package com.ethran.notable.recognition

/** A display name, never a mutation of the note or its original content. */
fun automaticTitle(source: String?): String? {
    val prefix = Regex("^(?:#{1,6}(?:\\s+|$)|>\\s*|[-*+•]\\s+|[0-9]+[.)]\\s+|\\[[ xX]\\]\\s*)")
    val link = Regex("!?\\[([^]\\n]*)]\\([^\\n)]*\\)")
    val strong = Regex("(?<![\\p{L}\\p{N}])([*_]{2}|~~|`)(.+?)\\1(?![\\p{L}\\p{N}])")
    val emphasis = Regex("(?<![\\p{L}\\p{N}])([*_])([^*_]+?)\\1(?![\\p{L}\\p{N}])")
    for (raw in source.orEmpty().replace("\r\n", "\n").replace('\r', '\n').lineSequence()) {
        var line = raw.trim()
        if (line.isEmpty() || Regex("^(?:`{3,}|~{3,}).*$").matches(line)) continue
        var heading = false
        while (true) {
            if (Regex("^#{1,6}(?:\\s+|$)").containsMatchIn(line)) heading = true
            val next = line.replaceFirst(prefix, "").trim()
            if (next == line) break
            line = next
        }
        if (Regex("^!\\[.*](?:\\(.*\\)|])\\s*$").matches(line)) continue
        if (heading) line = line.replace(Regex("\\s+#+\\s*$"), "")
        line = line.replace(Regex("\\[\\[([^]\\n]+)]]")) { it.groupValues[1].substringAfterLast('|') }
        line = line.trim('|').trim().replace(link) { it.groupValues[1] }
        repeat(4) { line = line.replace(strong) { it.groupValues[2] }.replace(emphasis) { it.groupValues[2] } }
        line = line.replace(Regex("\\\\([\\\\`*_{}\\[\\]()#+\\-.!>|])")) { it.groupValues[1] }
            .replace(Regex("\\s+"), " ").trim()
        if (line.codePoints().noneMatch { Character.isLetter(it) || Character.getType(it) in setOf(Character.DECIMAL_DIGIT_NUMBER.toInt(),
                Character.LETTER_NUMBER.toInt(), Character.OTHER_NUMBER.toInt(), Character.OTHER_SYMBOL.toInt()) }) continue
        return if (line.codePointCount(0, line.length) > 60) line.substring(0, line.offsetByCodePoints(0, 60)).trimEnd() + "…" else line
    }
    return null
}
