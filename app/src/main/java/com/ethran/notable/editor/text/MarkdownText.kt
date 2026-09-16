package com.ethran.notable.editor.text

/**
 * The markdown a text box understands, parsed into lines and styled spans.
 *
 * **Deliberately a small fixed subset, and deliberately hand-written.** A text box stores markdown
 * *source* (protocol §3.3.1), and each app is free to render it however it likes — but "free to
 * differ" is a licence, not a goal. The same note opened on the BOOX and on the iPad should look
 * like the same note, and the cheapest way to guarantee that is for both apps to implement the
 * *same* small grammar rather than each adopting whichever markdown library its platform happens
 * to offer. Two CommonMark implementations agree about far more than this grammar covers, and
 * disagree in exactly the places nobody can predict.
 *
 * This object is the shared half: pure, free of `android.*`, and mirrored line for line by
 * `MarkdownText.swift` in the iPad app. The platform half turns these lines into a `Spanned` (see
 * [TextBoxLayout]) or an `NSAttributedString`.
 *
 * Unlike [com.ethran.notable.markdown.MarkdownBlocks], nothing here is normative. It decides what
 * the user *sees*, never what is stored or merged, so it can grow a feature without a protocol
 * revision — which is also why it lives here rather than in the `markdown` package the conformance
 * vectors pin.
 */
object MarkdownText {

    /** What a line is, which decides its font size and indent. */
    sealed class LineKind {
        object Body : LineKind()
        data class Heading(val level: Int) : LineKind()
        object Bullet : LineKind()

        /**
         * A numbered item, carrying the number the source wrote — a list starting at 3 keeps its
         * 3, because renumbering it would edit what the user typed.
         */
        data class Numbered(val number: Int) : LineKind()
    }

    /** The inline styling of a run of characters, as a bit set: `**bold `code`**` is both. */
    @JvmInline
    value class SpanTraits(val bits: Int) {
        infix fun union(other: SpanTraits) = SpanTraits(bits or other.bits)
        operator fun contains(other: SpanTraits) = bits and other.bits == other.bits

        companion object {
            val NONE = SpanTraits(0)
            val BOLD = SpanTraits(1 shl 0)
            val ITALIC = SpanTraits(1 shl 1)
            val CODE = SpanTraits(1 shl 2)

            /**
             * A link's visible text. The destination is dropped: a text box is not a browser, and
             * carrying a URL nothing can open only invites drawing it.
             */
            val LINK = SpanTraits(1 shl 3)
        }
    }

    data class Span(val text: String, val traits: SpanTraits = SpanTraits.NONE)

    data class Line(val kind: LineKind, val spans: List<Span>) {
        /** The line's characters with the markup taken out — what gets laid out. */
        val plainText: String get() = spans.joinToString("") { it.text }
    }

    /**
     * Parses [source] into one [Line] per source line.
     *
     * Blank lines are kept as empty body lines rather than dropped: in a text box a blank line is
     * the user asking for space, and swallowing it would close a gap they typed on purpose.
     */
    fun parse(source: String): List<Line> =
        source.replace("\r\n", "\n").replace('\r', '\n').split("\n").map(::parseLine)

    // ---- Block level ----------------------------------------------------------------------

    private fun parseLine(raw: String): Line {
        // Up to three leading spaces are ignored before a marker, the same tolerance CommonMark
        // allows and the same one MarkdownBlocks' fences use. Beyond that the spaces are the
        // user's own indentation and stay in the text.
        val leading = raw.takeWhile { it == ' ' }
        val body = if (leading.length <= 3) raw.substring(leading.length) else raw

        headingPrefix(body)?.let { (level, rest) ->
            return Line(LineKind.Heading(level), parseInline(rest))
        }
        bulletPrefix(body)?.let { return Line(LineKind.Bullet, parseInline(it)) }
        numberedPrefix(body)?.let { (number, rest) ->
            return Line(LineKind.Numbered(number), parseInline(rest))
        }
        return Line(LineKind.Body, parseInline(raw))
    }

    /**
     * `#`, `##` or `###` followed by a space. Four or more hashes is not a heading — it is what a
     * user typing a row of hashes meant, and CommonMark agrees.
     */
    private fun headingPrefix(line: String): Pair<Int, String>? {
        val hashes = line.takeWhile { it == '#' }
        if (hashes.length !in 1..3) return null
        val rest = line.substring(hashes.length)
        if (!rest.startsWith(" ")) return null
        return hashes.length to rest.substring(1)
    }

    private fun bulletPrefix(line: String): String? {
        val first = line.firstOrNull() ?: return null
        if (first != '-' && first != '*' && first != '+') return null
        if (!line.substring(1).startsWith(" ")) return null
        return line.substring(2)
    }

    private fun numberedPrefix(line: String): Pair<Int, String>? {
        val digits = line.takeWhile { it in '0'..'9' }
        // Bounded so a line beginning with a long number is text, not a list item nobody can
        // number — and so the ordinal always fits an Int.
        if (digits.length !in 1..9) return null
        val rest = line.substring(digits.length)
        if (!rest.startsWith(". ")) return null
        return digits.toInt() to rest.substring(2)
    }

    // ---- Inline level ---------------------------------------------------------------------

    /**
     * Splits a line into styled runs.
     *
     * One left-to-right pass, no nesting except inside emphasis: code spans are literal (so
     * `` `**x**` `` shows the asterisks), and a marker with no partner on the same line is itself
     * literal — an unmatched `*` is a bullet somebody typed mid-sentence, not the start of
     * emphasis that runs to the end of the paragraph.
     */
    private fun parseInline(line: String): List<Span> {
        val spans = mutableListOf<Span>()
        val pending = StringBuilder()
        val traits = SpanTraits.NONE
        var i = 0

        fun flush() {
            if (pending.isNotEmpty()) {
                spans.add(Span(pending.toString(), traits))
                pending.setLength(0)
            }
        }

        while (i < line.length) {
            val c = line[i]

            // A backslash escapes the next character, which is the only way to type a literal
            // marker. It escapes anything, so the rule needs no list to stay in step with Swift.
            if (c == '\\' && i + 1 < line.length) {
                pending.append(line[i + 1])
                i += 2
                continue
            }

            if (c == '`') {
                val end = indexOf('`', line, i + 1)
                if (end != null) {
                    flush()
                    spans.add(Span(line.substring(i + 1, end), traits union SpanTraits.CODE))
                    i = end + 1
                    continue
                }
            }

            val marker = emphasisMarker(line, i)
            if (marker != null) {
                val end = indexOfRun(marker.first, marker.second, line, i + marker.second)
                if (end != null) {
                    flush()
                    val added =
                        if (marker.second == 2) SpanTraits.BOLD else SpanTraits.ITALIC
                    // Recursed so `**bold *and italic***` keeps both, and so an inner code span
                    // is still literal inside emphasis.
                    for (span in parseInline(line.substring(i + marker.second, end))) {
                        spans.add(Span(span.text, span.traits union traits union added))
                    }
                    i = end + marker.second
                    continue
                }
            }

            if (c == '[') {
                val close = indexOf(']', line, i + 1)
                if (close != null && close + 1 < line.length && line[close + 1] == '(') {
                    val paren = indexOf(')', line, close + 2)
                    if (paren != null) {
                        flush()
                        for (span in parseInline(line.substring(i + 1, close))) {
                            spans.add(
                                Span(span.text, span.traits union traits union SpanTraits.LINK)
                            )
                        }
                        i = paren + 1
                        continue
                    }
                }
            }

            pending.append(c)
            i += 1
        }

        flush()
        return spans
    }

    /**
     * The emphasis run starting at [index], if one does. Two markers mean bold, one italic; three
     * or more is read as bold plus italic by the recursion above.
     */
    private fun emphasisMarker(line: String, index: Int): Pair<Char, Int>? {
        val c = line[index]
        if (c != '*' && c != '_') return null
        var count = 0
        while (index + count < line.length && line[index + count] == c) count += 1
        // Emphasis has to contain something, so a run at the very end of the line is literal.
        if (index + count >= line.length) return null
        return c to minOf(count, 2)
    }

    private fun indexOf(needle: Char, line: String, start: Int): Int? {
        var i = start
        while (i < line.length) {
            if (line[i] == '\\') { i += 2; continue }
            if (line[i] == needle) return i
            i += 1
        }
        return null
    }

    /** The next run of at least [count] of [character], skipping escaped ones. */
    private fun indexOfRun(character: Char, count: Int, line: String, start: Int): Int? {
        var i = start
        while (i < line.length) {
            if (line[i] == '\\') { i += 2; continue }
            if (line[i] == character) {
                var run = 0
                while (i + run < line.length && line[i + run] == character) run += 1
                if (run >= count) return i
                i += run
                continue
            }
            i += 1
        }
        return null
    }
}
