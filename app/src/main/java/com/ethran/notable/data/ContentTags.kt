package com.ethran.notable.data

import java.text.Normalizer

/** Local, conservative matching; returned names are canonical, not raw tag-field input. */
internal fun contentTags(text: String, known: List<String>): List<String> {
    var fence: Pair<Char, Int>? = null
    val prose = text.lineSequence().map { line ->
        val trimmed = line.trimStart()
        val marker = trimmed.firstOrNull()?.takeIf { it == '`' || it == '~' }
        val count = if (marker == null) 0 else trimmed.takeWhile { it == marker }.length
        val open = fence
        if (open != null) {
            if (marker == open.first && count >= open.second && trimmed.drop(count).isBlank()) fence = null
            ""
        } else if (marker != null && count >= 3) {
            fence = marker to count
            ""
        } else line
    }.joinToString("\n")
    val source = Normalizer.normalize(prose, Normalizer.Form.NFC)
        .replace(Regex("`+[^`\\n]*`+"), " ")
        .replace(Regex("(?:https?://|www\\.)\\S+|(?<!\\S)\\S+@\\S+", RegexOption.IGNORE_CASE), " ")
    val folded = asciiLower(source)
    val names = linkedMapOf<String, String>()
    known.sorted().forEach { name -> names.putIfAbsent(asciiLower(name), name) }
    val found = linkedMapOf<String, String>()
    for (key in names.keys.sorted()) {
        val name = names.getValue(key)
        if (name.startsWith('#') || name.codePointCount(0, name.length) < 2) continue
        val phrase = key.split(' ').joinToString("\\s+") { Regex.escape(it) }
        val pattern = "(?<![\\p{L}\\p{M}\\p{N}_#])$phrase(?![\\p{L}\\p{M}\\p{N}_/-])"
        if (Regex(pattern).containsMatchIn(folded)) found[key] = name
    }
    val hashtags = Regex("(?<![\\p{L}\\p{M}\\p{N}_#\\\\])#([\\p{L}\\p{N}_][\\p{L}\\p{M}\\p{N}_/-]*)")
    for (match in hashtags.findAll(source)) {
        val name = match.groupValues[1].trimEnd('-', '/')
        if (name.codePointCount(0, name.length) > 64) continue
        val key = asciiLower(name)
        found.putIfAbsent(key, names[key] ?: name)
    }
    return found.values.toList()
}
