package com.ethran.notable.recognition

/** Display-only structure. Source and canonical note blocks are never rewritten. */
internal object TranscriptMarkdown {
    sealed interface Block {
        data class Line(val source: String, val heading: Int? = null, val indent: Int = 0,
            val marker: String? = null, val checked: Boolean? = null) : Block
        data class Table(val header: List<String>, val rows: List<List<String>>) : Block
        data class Code(val source: String) : Block
    }
    private val list = Regex("^([ \\t]*)([-+*]|[0-9]{1,9}[.)])[ \\t]+(.*)$")
    private val task = Regex("^\\[([ xX])]([ \\t]+|$)(.*)$")
    private val heading = Regex("^ {0,3}(#{1,6})[ \\t]+(.*)$")
    private val separator = Regex(":?-{3,}:?")

    fun parse(source: String): List<Block> {
        val lines = source.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val blocks = mutableListOf<Block>()
        var index = 0
        while (index < lines.size) {
            val line = lines[index]
            val trimmed = line.trimStart(' ')
            val fence = trimmed.takeWhile { it == '`' || it == '~' }
            if (line.length - trimmed.length <= 3 && fence.length >= 3 && fence.all { it == fence[0] }) {
                val content = mutableListOf<String>()
                index++
                while (index < lines.size) {
                    val close = lines[index].trim()
                    if (close.length >= fence.length && close.all { it == fence[0] }) { index++; break }
                    content += lines[index++]
                }
                blocks += Block.Code(content.joinToString("\n"))
                continue
            }
            val header = cells(line)
            val rule = lines.getOrNull(index + 1)?.let(::cells)
            if (header != null && rule != null && header.size == rule.size && rule.all { separator.matches(it) }) {
                index += 2
                val rows = mutableListOf<List<String>>()
                while (index < lines.size) {
                    val row = cells(lines[index]) ?: break
                    rows += row
                    index++
                }
                blocks += Block.Table(header, rows)
                continue
            }
            val title = heading.matchEntire(line)
            val item = list.matchEntire(line)
            blocks += when {
                title != null -> Block.Line(title.groupValues[2], heading = title.groupValues[1].length)
                item != null -> {
                    val checked = task.matchEntire(item.groupValues[3])
                    Block.Line(checked?.groupValues?.get(3) ?: item.groupValues[3],
                        indent = item.groupValues[1].sumOf { if (it == '\t') 4 else 1 },
                        marker = item.groupValues[2].let { if (it in listOf("-", "+", "*")) "•" else it },
                        checked = checked?.groupValues?.get(1)?.let { it != " " })
                }
                else -> Block.Line(line)
            }
            index++
        }
        return blocks
    }

    /** Escaped pipes and pipes inside code spans belong to a cell, not to the grid. */
    private fun cells(raw: String): List<String>? {
        val line = raw.trim()
        if (line.isEmpty()) return null
        val cells = mutableListOf<String>()
        val content = StringBuilder()
        var pipes = 0
        var codeRun = 0
        var index = 0
        while (index < line.length) {
            val char = line[index]
            when {
                char == '\\' && index + 1 < line.length -> {
                    content.append(char).append(line[index + 1]); index += 2
                }
                char == '`' -> {
                    var end = index + 1
                    while (line.getOrNull(end) == '`') end++
                    val count = end - index
                    if (codeRun == 0) codeRun = count else if (codeRun == count) codeRun = 0
                    content.append(line.substring(index, end)); index = end
                }
                char == '|' && codeRun == 0 -> {
                    cells += content.toString().trim(); content.setLength(0); pipes++; index++
                }
                else -> { content.append(char); index++ }
            }
        }
        if (pipes == 0) return null
        cells += content.toString().trim()
        if (line.startsWith('|')) cells.removeAt(0)
        if (line.endsWith('|') && cells.last().isEmpty()) cells.removeAt(cells.lastIndex)
        return cells.takeIf { it.isNotEmpty() }
    }
}
