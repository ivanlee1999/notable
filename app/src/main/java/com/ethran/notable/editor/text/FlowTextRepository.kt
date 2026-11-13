package com.ethran.notable.editor.text

import androidx.room.withTransaction
import com.ethran.notable.data.AppRepository
import com.ethran.notable.data.db.AppDatabase
import com.ethran.notable.data.db.Block
import com.ethran.notable.data.db.Kv
import com.ethran.notable.data.db.PageWithData
import com.ethran.notable.io.ThumbnailBackfillQueue
import com.ethran.notable.io.ThumbnailGenerator
import com.ethran.notable.markdown.MarkdownBlocks
import com.ethran.notable.sync.SyncClock
import com.ethran.notable.sync.couch.TextVersions
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.util.Date
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

data class FlowRun(val token: String, val original: List<Block>, val source: String, val intendedIds: List<String> = emptyList())

sealed interface FlowPart {
    data class Text(val token: String) : FlowPart
    data class Content(val block: Block) : FlowPart
}

data class FlowDocument(
    val pageId: String,
    val notebookId: String,
    val parts: List<FlowPart>,
    val runs: Map<String, FlowRun>,
    val hasCanvas: Boolean,
    val versions: List<Block>,
    val revision: Long = 0,
) {
    fun snapshot() = FlowDraft(pageId, revision, runs.values.map { run ->
        StoredFlowRun(run.token, run.original.map(StoredFlowBlock::from), run.source, run.intendedIds)
    })
}

@Serializable
data class StoredFlowBlock(
    val id: String, val text: String?, val orderKey: String, val payload: String,
    val x: Int?, val y: Int?, val width: Int?, val height: Int?,
    val createdAt: Long, val updatedAt: Long, val deviceId: String,
) {
    fun block(pageId: String) = Block(id = id, pageId = pageId, text = text, orderKey = orderKey,
        payload = payload, x = x, y = y, width = width, height = height,
        createdAt = Date(createdAt), updatedAt = Date(updatedAt), deviceId = deviceId)

    companion object {
        fun from(block: Block) = StoredFlowBlock(block.id, block.text, block.orderKey, block.payload,
            block.x, block.y, block.width, block.height, block.createdAt.time, block.updatedAt.time, block.deviceId)
    }
}

@Serializable
data class StoredFlowRun(val token: String, val original: List<StoredFlowBlock>, val source: String,
    val intendedIds: List<String> = emptyList())

@Serializable
data class FlowDraft(val pageId: String, val revision: Long, val runs: List<StoredFlowRun>)

data class FlowSaveResult(val originals: Map<String, List<Block>>, val conflicts: Set<String>, val versions: List<Block>)

/** Existing block rows and a small durable draft record; no second synced document or schema. */
@Singleton
class FlowTextRepository @Inject constructor(
    private val db: AppDatabase,
    private val app: AppRepository,
    private val thumbnails: ThumbnailGenerator,
    private val backfill: ThumbnailBackfillQueue,
    private val contentTagger: com.ethran.notable.data.ContentTagger,
) {
    private val mutex = Mutex()
    private val revisions = AtomicLong()
    private val written = mutableMapOf<String, Long>()
    private val json = Json { ignoreUnknownKeys = true }
    fun nextRevision(): Long = revisions.incrementAndGet()
    private fun key(pageId: String) = "editor.flow-draft.$pageId"
    fun observe(pageId: String): Flow<List<Block>> = app.blockRepository.observeByPage(pageId)

    suspend fun load(pageId: String): FlowDocument {
        val data = app.pageRepository.getWithDataById(pageId) ?: error("This page is no longer available.")
        val runs = linkedMapOf<String, FlowRun>()
        val parts = FlowText.sections(data.blocks).map { section -> when (section) {
            is FlowText.Section.Text -> {
                val token = section.blocks.first().id
                runs[token] = FlowRun(token, section.blocks, section.source)
                FlowPart.Text(token)
            }
            is FlowText.Section.Content -> FlowPart.Content(section.block)
        } }.toMutableList()
        val draft = db.kvDao().get(key(pageId))?.let { json.decodeFromString<FlowDraft>(it.value) }
        require(draft == null || draft.pageId == pageId) { "The saved draft belongs to another page." }
        for (stored in draft?.runs.orEmpty()) {
            val original = stored.original.map { it.block(pageId) }
            if (stored.source == MarkdownBlocks.join(original.map { it.text.orEmpty() })) continue
            val ids = original.map { it.id }.toSet() + stored.intendedIds
            val replacing = runs.values.firstOrNull { run -> run.original.any { it.id in ids } }
            // A process may stop after the canonical transaction but before draft cleanup.
            // Recognise that exact transport content rather than inserting its paragraphs twice.
            if (replacing != null && MarkdownBlocks.split(stored.source) == MarkdownBlocks.split(replacing.source)) continue
            if (replacing != null) {
                val index = parts.indexOfFirst { it is FlowPart.Text && it.token == replacing.token }
                runs.remove(replacing.token)
                parts[index] = FlowPart.Text(stored.token)
            } else parts += FlowPart.Text(stored.token)
            runs[stored.token] = FlowRun(stored.token, original, stored.source, stored.intendedIds)
        }
        if (runs.isEmpty()) {
            val token = "new-${UUID.randomUUID()}"
            runs[token] = FlowRun(token, emptyList(), "")
            parts += FlowPart.Text(token)
        }
        return FlowDocument(pageId, data.page.notebookId, parts, runs, FlowTextLayout.hasCanvas(data),
            data.blocks.filter { it.kind == TextVersions.KIND }, nextRevision())
    }

    suspend fun persist(draft: FlowDraft) = mutex.withLock {
        if (draft.revision <= (written[draft.pageId] ?: -1)) return@withLock
        db.kvDao().set(Kv(key(draft.pageId), json.encodeToString(draft)))
        written[draft.pageId] = draft.revision
    }

    suspend fun save(draft: FlowDraft): FlowSaveResult = mutex.withLock {
        var localText: Triple<String, List<Block>, Long>? = null
        // The source lands durably before any canonical write. A failed transaction keeps it.
        if (draft.revision >= (written[draft.pageId] ?: -1)) {
            db.kvDao().set(Kv(key(draft.pageId), json.encodeToString(draft)))
            written[draft.pageId] = draft.revision
        }
        val result = db.withTransaction {
            val page = app.pageRepository.getById(draft.pageId) ?: error("This page was removed. Your draft is kept on this device.")
            val book = app.bookRepository.getById(page.notebookId)
            check(book != null && book.deletedAt == null) { "This notebook is in Trash. Your draft is kept on this device." }
            val retired = app.deletedBlockRepository.getByPage(draft.pageId).map { it.blockId }.toSet()
            val device = app.kvProxy.getSyncSettings().deviceId
            val now = SyncClock.nowDate()
            val conflicts = linkedSetOf<String>()
            val ids = linkedMapOf<String, List<String>>()
            var changed = false
            val authoredIds = mutableSetOf<String>()
            for (run in draft.runs) {
                val original = run.original.map { it.block(draft.pageId) }
                val latest = app.blockRepository.getByPage(draft.pageId)
                val before = original.associateBy { it.id }
                val current = latest.associateBy { it.id }
                if (run.source == MarkdownBlocks.join(original.map { it.text.orEmpty() })) {
                    ids[run.token] = original.map { it.id }; continue
                }
                val intended = run.intendedIds.iterator()
                val plan = FlowText.reconcile(draft.pageId, latest, original, run.source, now, device, retired,
                    newId = {
                        val id = if (intended.hasNext()) intended.next() else UUID.randomUUID().toString()
                        check(id !in retired) { "A pending text identity was removed. Your draft is retained." }
                        id
                    })
                val missing = original.any { it.id in retired || current[it.id]?.let { b -> b.kind != "md" || !FlowText.isFlow(b) } != false }
                val divergent = plan.blocks.any { b -> before[b.id]?.let { base ->
                    TextVersions.conflicts(base.text, current[b.id]?.text, b.text.orEmpty())
                } ?: false } ||
                    plan.removedIds.any { id -> current[id]?.text != before[id]?.text }
                val desired = MarkdownBlocks.split(run.source)
                val creationCollision = plan.resultIds.withIndex().any { (index, id) ->
                    id !in before && current[id]?.let { b -> b.kind != "md" || !FlowText.isFlow(b) || b.text.orEmpty() != desired.getOrNull(index) } == true
                }
                val ordered = FlowText.ordered(latest)
                val positions = ordered.indices.filter { ordered[it].id in before }
                val newBoundary = positions.isNotEmpty() && ordered.subList(positions.first(), positions.last() + 1)
                    .any { it.id !in before && it.kind != "md" }
                if (missing || divergent || creationCollision || newBoundary) {
                    val base = original.firstOrNull() ?: Block(id = run.token, pageId = draft.pageId)
                    val recovery = base.copy(id = TextVersions.versionId(base.id, run.source), kind = TextVersions.KIND,
                        text = run.source, x = null, y = null, width = null, height = null,
                        updatedAt = now, deviceId = device)
                    check(recovery.id !in retired) { "This recovered version was removed. Your draft is kept on this device." }
                    if (current[recovery.id] == null) app.blockRepository.upsertAll(listOf(recovery))
                    conflicts += run.token; changed = true; continue
                }
                app.blockRepository.upsertAll(plan.blocks)
                plan.blocks.filter { it.kind == "md" && it.text != current[it.id]?.text }.forEach { authoredIds += it.id }
                app.blockRepository.deleteByIds(plan.removedIds)
                app.deletedBlockRepository.record(draft.pageId, plan.removedIds, now)
                ids[run.token] = plan.resultIds
                changed = changed || plan.blocks.isNotEmpty() || plan.removedIds.isNotEmpty()
            }
            if (changed) app.pageRepository.touchUpdatedAt(draft.pageId)
            val final = app.blockRepository.getByPage(draft.pageId).associateBy { it.id }
            if (conflicts.isEmpty() && authoredIds.isNotEmpty()) localText = Triple(page.notebookId, authoredIds.mapNotNull(final::get), contentTagger.currentGeneration())
            // Canonical content and cleanup share a commit, so process death cannot leave a
            // creation draft whose planned identities were already written as new paragraphs.
            if (conflicts.isEmpty() && draft.revision >= (written[draft.pageId] ?: -1)) db.kvDao().delete(key(draft.pageId))
            FlowSaveResult(ids.mapValues { (_, values) -> values.mapNotNull(final::get) }, conflicts,
                final.values.filter { it.kind == TextVersions.KIND })
        }
        if (result.conflicts.isEmpty() && draft.revision >= (written[draft.pageId] ?: -1)) {
            written[draft.pageId] = draft.revision
        }
        backfill.enqueue(thumbnails.invalidate(listOf(draft.pageId)).toList())
        localText?.let { (book, blocks, generation) -> contentTagger.submit(book, draft.pageId, com.ethran.notable.data.ContentTagSource.Text(blocks), generation) }
        result
    }

    /** An explicit recovery creates new identities, never resurrects a retired paragraph. */
    suspend fun keepBoth(draft: FlowDraft, tokens: Set<String>, clearDraftThrough: Long = draft.revision) = mutex.withLock {
        val localText = db.withTransaction {
            val page = app.pageRepository.getById(draft.pageId) ?: error("This page is no longer available.")
            val book = app.bookRepository.getById(page.notebookId)
            check(book != null && book.deletedAt == null) { "This notebook is in Trash." }
            val authoredIds = mutableSetOf<String>()
            for (run in draft.runs.filter { it.token in tokens && it.source.isNotBlank() }) {
                val current = app.blockRepository.getByPage(draft.pageId)
                val plan = FlowText.reconcile(draft.pageId, current, emptyList(), run.source, SyncClock.nowDate(),
                    app.kvProxy.getSyncSettings().deviceId)
                app.blockRepository.upsertAll(plan.blocks)
                authoredIds += plan.resultIds
            }
            app.pageRepository.touchUpdatedAt(draft.pageId)
            if (clearDraftThrough >= (written[draft.pageId] ?: -1)) {
                db.kvDao().delete(key(draft.pageId)); written[draft.pageId] = clearDraftThrough
            }
            Triple(page.notebookId, app.blockRepository.getByPage(draft.pageId).filter { it.id in authoredIds && it.kind == "md" }, contentTagger.currentGeneration())
        }
        backfill.enqueue(thumbnails.invalidate(listOf(draft.pageId)).toList())
        if (localText.second.isNotEmpty()) contentTagger.submit(localText.first, draft.pageId, com.ethran.notable.data.ContentTagSource.Text(localText.second), localText.third)
    }
}
