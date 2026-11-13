package com.ethran.notable.data

import androidx.room.withTransaction
import com.ethran.notable.data.db.AppDatabase
import com.ethran.notable.data.db.Block
import com.ethran.notable.data.db.Notebook
import com.ethran.notable.data.db.Page
import com.ethran.notable.di.ApplicationScope
import com.ethran.notable.di.IoDispatcher
import com.ethran.notable.recognition.handwritingFingerprint
import com.ethran.notable.recognition.HandwritingPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/** Exact values captured by a successful local write, never by a subsequent preview/load. */
sealed interface ContentTagSource {
    data class Text(val blocks: List<Block>) : ContentTagSource
    data class NotebookTitle(val notebook: Notebook) : ContentTagSource
    data class PageTitle(val page: Page) : ContentTagSource
    data class Ink(val fingerprint: String, val language: String, val text: String) : ContentTagSource
}

@Singleton
class ContentTagger @Inject constructor(
    private val db: AppDatabase,
    private val preferences: AutomaticTagPreferences,
    private val handwriting: HandwritingPreferences,
    @param:ApplicationScope private val scope: CoroutineScope,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) {
    private val organization = JournalOrganizationStore(db)
    private val generation = AtomicLong()

    internal fun currentGeneration(): Long = generation.get()
    fun invalidatePending() { generation.incrementAndGet() }

    fun submit(notebookId: String, pageId: String?, source: ContentTagSource,
        expectedGeneration: Long = currentGeneration()) {
        if (!preferences.enabled.value) return
        scope.launch(io) {
            try { apply(notebookId, pageId, source, expectedGeneration) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Optional metadata must never fail a successful content save. */ }
        }
    }

    internal suspend fun apply(notebookId: String, pageId: String?, source: ContentTagSource,
        expectedGeneration: Long = currentGeneration()) = withContext(io) {
        if (!preferences.enabled.value || expectedGeneration != currentGeneration()) return@withContext
        val text = db.withTransaction { currentText(notebookId, pageId, source) } ?: return@withContext
        val existing = db.notebookDao().getAll().mapTo(mutableSetOf()) { it.id }
        val known = organization.all().filter { it.notebookId in existing }
            .flatMap { it.topics.values }.filter { it.present }.map { it.name }
        val candidates = contentTags(text, known)
        if (candidates.isEmpty()) return@withContext
        db.withTransaction {
            if (!preferences.enabled.value || expectedGeneration != currentGeneration() ||
                currentText(notebookId, pageId, source) != text) return@withTransaction
            val book = db.notebookDao().getById(notebookId) ?: return@withTransaction
            organization.addAutomaticTopics(notebookId, book.createdAt.time, candidates)
        }
    }

    private suspend fun currentText(notebookId: String, pageId: String?, source: ContentTagSource): String? {
        val book = db.notebookDao().getById(notebookId) ?: return null
        if (book.deletedAt != null) return null
        var parent = book.parentFolderId
        val seen = mutableSetOf<String>()
        while (parent != null) {
            if (!seen.add(parent)) return null
            val folder = db.folderDao().get(parent) ?: return null
            if (folder.deletedAt != null) return null
            parent = folder.parentFolderId
        }
        val page = if (pageId == null) null else {
            if (pageId !in book.pageIds) return null
            db.pageDao().getById(pageId)?.takeIf { it.notebookId == notebookId } ?: return null
        }
        return when (source) {
            is ContentTagSource.Text -> {
                if (page == null || source.blocks.isEmpty() || source.blocks.any { it.pageId != page.id || it.kind != "md" }) return null
                val current = db.blockDao().getByPage(page.id).associateBy { it.id }
                if (source.blocks.any { current[it.id] != it }) return null
                source.blocks.sortedWith(compareBy<Block> { it.orderKey }.thenBy { it.id }).joinToString("\n\n") { it.text.orEmpty() }
            }
            is ContentTagSource.NotebookTitle -> source.notebook.takeIf {
                pageId == null && it.id == book.id && it.title == book.title &&
                    it.updatedAt == book.updatedAt && it.updatedBy == book.updatedBy
            }?.title
            is ContentTagSource.PageTitle -> {
                if (page == null) return null
                source.page.takeIf {
                    it.id == page.id && it.notebookId == notebookId && it.title == page.title &&
                        it.updatedAt == page.updatedAt && it.updatedBy == page.updatedBy
                }?.title
            }
            is ContentTagSource.Ink -> {
                val options = handwriting.options.value
                if (page == null || !options.automatic || options.language != source.language ||
                    handwritingFingerprint(db.strokeDao().getByPage(page.id), source.language) != source.fingerprint) null
                else source.text
            }
        }
    }
}
