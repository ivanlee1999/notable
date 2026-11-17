package com.ethran.notable.recognition

import androidx.room.withTransaction
import com.ethran.notable.data.db.AppDatabase
import com.ethran.notable.data.db.Notebook
import com.ethran.notable.di.ApplicationScope
import com.ethran.notable.di.IoDispatcher
import com.ethran.notable.editor.text.FlowText
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.*
import javax.inject.Inject
import javax.inject.Singleton

/** Only display strings leave this reader; canonical Notebook/Page values stay unmodified. */
@Singleton
class NoteTitles @Inject constructor(
    private val db: AppDatabase,
    private val cloud: CloudTranscriptionRepository,
    @param:ApplicationScope private val scope: CoroutineScope,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) {
    private val notebooks = mutableMapOf<String, StateFlow<String>>()
    private val pages = mutableMapOf<String, StateFlow<String?>>()
    private val started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000, replayExpirationMillis = 0)

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Synchronized
    fun notebook(id: String): StateFlow<String> = notebooks.getOrPut(id) {
        db.invalidationTracker.createFlow("Notebook").map { db.notebookDao().getById(id) }
            .distinctUntilChanged().flatMapLatest { book ->
                if (book == null) flowOf("Untitled")
                else if (book.title.isNotBlank()) flowOf(book.title)
                else firstPageTitle(book).map { it ?: "Untitled" }
            }.flowOn(io).stateIn(scope, started, "Untitled")
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private fun firstPageTitle(book: Notebook, index: Int = 0): Flow<String?> {
        val id = book.pageIds.getOrNull(index) ?: return flowOf(null)
        return page(id, book.id).flatMapLatest { title ->
            if (title != null) flowOf(title) else firstPageTitle(book, index + 1)
        }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Synchronized
    fun page(id: String, notebookId: String): StateFlow<String?> = pages.getOrPut("$notebookId/$id") {
        db.invalidationTracker.createFlow("Notebook", "Page", "Block").map {
            db.withTransaction {
                val page = db.pageDao().getById(id)
                val book = db.notebookDao().getById(notebookId)
                if (page?.notebookId != notebookId || book == null || id !in book.pageIds) false to null
                else true to (page.title?.takeIf { it.isNotBlank() }
                    ?: automaticTitle(FlowText.ordered(db.blockDao().getByPage(id))
                        .filter { it.kind == "md" }.joinToString("\n\n") { it.text.orEmpty() }))
            }
        }.distinctUntilChanged().flatMapLatest { (live, title) ->
            when {
                !live -> flowOf(null)
                title != null -> flowOf(title)
                else -> cloud.observeTitle(id)
            }
        }.flowOn(io).stateIn(scope, started, null)
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun names(books: List<Notebook>): Flow<Map<String, String>> = if (books.isEmpty()) flowOf(emptyMap())
        else combine(books.map { book -> if (book.title.isNotBlank()) flowOf(book.title) else notebook(book.id) }) { names ->
            books.indices.associate { books[it].id to names[it] }
        }
}
