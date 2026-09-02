package com.ethran.notable.markdown

/**
 * Cutting a markdown document into blocks, and putting it back together — protocol §9.
 *
 * **Nothing here may touch `android.*`.** It is the twin of bopa's `MarkdownBlocks.swift`, it
 * decides a merge result, and it is pinned by a conformance file shared between the two repos — so
 * it has to run as a plain JVM unit test, like the `sync.couch` package it serves.
 *
 * This is deliberately **not** a markdown parser, and that is the whole design. Block boundaries
 * decide block ids, ids decide what the merge treats as the same paragraph, so two implementations
 * in two languages have to agree on them exactly and for ever. Asking each of them what markdown
 * *means* is the one way to guarantee they eventually will not: CommonMark has revisions, two
 * libraries track them at different times, and a dependency bump on one side would silently move a
 * boundary, change an id, and duplicate somebody's paragraph on the next merge.
 *
 * So the contract is defined over the one thing neither language can disagree about — where the
 * blank lines are, with fences excepted. Same instinct as the merge comparing bytes rather than
 * strings, and IEEE-754 bit patterns rather than printed floats.
 *
 * Splitting is a *transport granularity, not a rendering decision*. Each app renders a block with
 * whatever markdown renderer it likes, and the two may legitimately disagree about rendering while
 * never disagreeing about the merge.
 *
 * Pinned by `couch-sync-vectors/markdown-blocks.json`, byte-identical in both repos and diffed by
 * both CIs — see `MarkdownBlocksTest`.
 */
object MarkdownBlocks {

    /** The blocks of [source], in document order. */
    fun split(source: String): List<String> {
        val lines = normalize(source).split("\n")

        val blocks = mutableListOf<String>()
        var current = mutableListOf<String>()
        var fence: Fence? = null
        var index = 0

        // Front matter is one block. Splitting it would put an id on each key and let two devices
        // merge half of one document's front matter with half of another's. It only counts when the
        // first non-blank line opens it *and* a line closes it *before the next blank line*: three
        // dashes on their own are a thematic break, and a document beginning with one must not be
        // swallowed whole.
        //
        // The "before the next blank line" clause is what keeps the decision local. Without it,
        // whether a document opens with front matter depends on whether a `---` turns up anywhere
        // later — so gluing two documents together could retroactively change the meaning of the
        // first one's opening, and the blocks would no longer split back into the blocks they were
        // built from.
        //
        // The first *non-blank* line, not line 1, for the same reason: leading blank lines are
        // separators and are dropped, so the first block always starts at the first non-blank line
        // and `join` never puts anything in front of it. A test on line 1 would find front matter
        // in `join(split(x))` that it had not found in `x`, and split the two differently.
        val start = lines.indexOfFirst { !isBlank(it) }.let { if (it < 0) lines.size else it }
        if (start < lines.size && lines[start] == "---") {
            val close = lines.drop(start + 1).takeWhile { !isBlank(it) }.indexOf("---")
            if (close >= 0) {
                val end = start + 1 + close
                blocks += lines.subList(start, end + 1).joinToString("\n")
                index = end + 1
            }
        }

        while (index < lines.size) {
            val line = lines[index]
            index++

            val open = fence
            if (open != null) {
                current += line
                if (closes(line, open)) fence = null
                continue
            }
            val opened = opensFence(line)
            if (opened != null) {
                current += line
                fence = opened
                continue
            }
            if (isBlank(line)) {
                if (current.isNotEmpty()) {
                    blocks += current.joinToString("\n")
                    current = mutableListOf()
                }
                continue
            }
            current += line
        }
        if (current.isNotEmpty()) blocks += current.joinToString("\n")
        return blocks
    }

    /**
     * The document [blocks] came from. `split(join(blocks)) == blocks` for any blocks [split]
     * produced — which is what lets a page be exported as a `.md` file and read back unchanged.
     *
     * One precondition, and it is not theoretical: at most one block may leave a fence open, and it
     * must be the last. Join a block that opens a fence and never closes it in front of anything
     * else and the fence swallows what follows, so the document no longer splits back into the
     * blocks it was built from. [leavesFenceOpen] is how a caller checks.
     */
    fun join(blocks: List<String>): String = blocks.joinToString("\n\n")

    /**
     * Whether this block ends inside a fence it never closed — a paragraph mid-typing, usually.
     *
     * Such a block is only safe in last position: anywhere else it absorbs the blocks after it
     * (see [join]). An editor that reorders blocks, or pastes one into the middle of a document,
     * has to know that, so the rule is exposed rather than left as a comment.
     */
    fun leavesFenceOpen(block: String): Boolean {
        var fence: Fence? = null
        for (line in normalize(block).split("\n")) {
            val open = fence
            if (open != null) {
                if (closes(line, open)) fence = null
            } else {
                opensFence(line)?.let { fence = it }
            }
        }
        return fence != null
    }

    // region The rules

    /**
     * Strip a byte-order mark, and make every line ending `\n`. Nothing else: tabs are not expanded
     * and interior whitespace is untouched, because both would edit the user's text.
     */
    private fun normalize(source: String): String =
        source.removePrefix("﻿").replace("\r\n", "\n").replace("\r", "\n")

    /** A separator: nothing on the line but spaces and tabs. */
    private fun isBlank(line: String): Boolean = line.all { it == ' ' || it == '\t' }

    private data class Fence(val marker: Char, val count: Int)

    /**
     * The fence this line opens, or null. Up to three leading spaces are allowed — the same
     * allowance CommonMark makes, and the one a fence inside a list item needs. A fourth space is
     * indented code, and treating it as a fence would swallow the rest of the document.
     */
    private fun opensFence(line: String): Fence? {
        val (marker, count, _) = markerRun(line)
        return if (marker != null && count >= 3) Fence(marker, count) else null
    }

    /**
     * Whether this line closes [fence]: at least as long a run of the same character, and nothing
     * after it but trailing whitespace. "At least as long" is what lets a four-backtick fence hold
     * three backticks as content.
     */
    private fun closes(line: String, fence: Fence): Boolean {
        val (marker, count, rest) = markerRun(line)
        return marker == fence.marker && count >= fence.count && isBlank(rest)
    }

    /** The run of fence characters this line starts with after its indent, and what follows it. */
    private fun markerRun(line: String): Triple<Char?, Int, String> {
        var i = 0
        while (i < 3 && i < line.length && line[i] == ' ') i++
        // A fourth space means indented code, never a fence.
        val marker = line.getOrNull(i)
        if (marker != '`' && marker != '~') return Triple(null, 0, "")
        var count = 0
        while (i + count < line.length && line[i + count] == marker) count++
        return Triple(marker, count, line.substring(i + count))
    }

    // endregion
}
