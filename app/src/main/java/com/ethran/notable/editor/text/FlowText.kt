package com.ethran.notable.editor.text

import com.ethran.notable.data.db.Block
import com.ethran.notable.markdown.MarkdownBlocks
import com.ethran.notable.sync.couch.TextVersions
import java.util.Date
import java.util.UUID

/** Page-local document order. Positioned annotations always retain their original coordinates. */
object FlowText {
    fun isFlow(block: Block): Boolean = block.x == null || block.y == null

    fun ordered(blocks: List<Block>): List<Block> = blocks.filter { isFlow(it) && it.kind != TextVersions.KIND }.sortedWith { a, b ->
        byteCompare(a.orderKey, b.orderKey).takeIf { it != 0 } ?: byteCompare(a.id, b.id)
    }

    sealed interface Section {
        data class Text(val blocks: List<Block>) : Section {
            val source: String get() = MarkdownBlocks.join(blocks.map { it.text.orEmpty() })
        }
        data class Content(val block: Block) : Section
    }

    /** A non-text block is a boundary, including a kind this client does not understand yet. */
    fun sections(blocks: List<Block>): List<Section> = buildList {
        val run = mutableListOf<Block>()
        fun finish() { if (run.isNotEmpty()) { add(Section.Text(run.toList())); run.clear() } }
        for (block in ordered(blocks)) {
            if (block.kind == "md") run += block
            else { finish(); add(Section.Content(block)) }
        }
        finish()
    }

    data class Plan(val blocks: List<Block>, val removedIds: List<String>, val resultIds: List<String> = emptyList())

    /**
     * Reconcile only the edited run. Unchanged paragraphs keep their identities, even after a
     * move; edited paragraphs reuse remaining original ids in relative order. Rebalancing keys
     * only when topology changes handles legal empty/tied old keys without a new index format.
     * The caller guards concurrent text changes/deletions transactionally before applying this.
     */
    fun reconcile(
        pageId: String,
        current: List<Block>,
        original: List<Block>,
        source: String,
        now: Date,
        deviceId: String,
        retiredIds: Set<String> = emptySet(),
        newId: () -> String = { UUID.randomUUID().toString() },
    ): Plan {
        if (source == MarkdownBlocks.join(original.map { it.text.orEmpty() })) return Plan(emptyList(), emptyList(), original.map { it.id })
        val text = MarkdownBlocks.split(source)
        val currentById = current.associateBy { it.id }
        val eligible = original.filter { it.id !in retiredIds && currentById[it.id]?.let { b -> isFlow(b) && b.kind == "md" } == true }
        val unused = eligible.toMutableList()
        val assigned = arrayOfNulls<Block>(text.size)
        text.forEachIndexed { index, paragraph ->
            val exact = unused.indexOfFirst { it.text.orEmpty() == paragraph }
            if (exact >= 0) assigned[index] = unused.removeAt(exact)
        }
        for (index in assigned.indices) if (assigned[index] == null && unused.isNotEmpty()) assigned[index] = unused.removeAt(0)
        val rewritten = text.mapIndexed { index, paragraph ->
            val base = assigned[index]
            val latest = base?.let { currentById[it.id] }
            val target = latest ?: newId().let { id -> currentById[id] ?: Block(id = id, pageId = pageId, createdAt = now) }
            if ((base != null && paragraph == base.text.orEmpty()) || paragraph == target.text.orEmpty()) target
            else target.copy(
                text = paragraph, updatedAt = now, deviceId = deviceId,
            )
        }
        val oldIds = original.map { it.id }.toSet()
        val removed = eligible.map { it.id }.filter { id -> rewritten.none { it.id == id } }
        val ordered = ordered(current)
        val topologyChanged = rewritten.map { it.id } != original.map { it.id }
        if (!topologyChanged) return Plan(rewritten.filter { it != currentById[it.id] }, removed, rewritten.map { it.id })

        // Intended creation ids can already exist after a process died between the first
        // canonical write and rebasing a newer draft. Replace them at their current anchor.
        val replacedIds = oldIds + rewritten.map { it.id }
        val first = ordered.indexOfFirst { it.id in replacedIds }.let { if (it < 0) ordered.size else it }
        val before = ordered.take(first).filterNot { it.id in replacedIds }
        val after = ordered.drop(first).filterNot { it.id in replacedIds }
        val reindexed = (before + rewritten + after).mapIndexed { index, block ->
            val key = (index + 1).toString().padStart(10, '0')
            if (block.orderKey == key) block else block.copy(orderKey = key, updatedAt = now, deviceId = deviceId)
        }
        return Plan(reindexed.filter { it != currentById[it.id] }, removed, rewritten.map { it.id })
    }

    private fun byteCompare(a: String, b: String): Int {
        val x = a.toByteArray(Charsets.UTF_8); val y = b.toByteArray(Charsets.UTF_8)
        for (i in 0 until minOf(x.size, y.size)) {
            val result = (x[i].toInt() and 255).compareTo(y[i].toInt() and 255)
            if (result != 0) return result
        }
        return x.size.compareTo(y.size)
    }
}
