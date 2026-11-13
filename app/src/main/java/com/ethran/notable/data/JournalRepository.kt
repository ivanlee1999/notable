package com.ethran.notable.data

import androidx.room.withTransaction
import com.ethran.notable.data.db.AppDatabase
import com.ethran.notable.data.db.Kv
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class JournalEntry(
    val notebookId: String,
    val date: String?,
    val createdAt: Long,
    val topics: List<String> = emptyList(),
)

/** Organization stays separate from the notebook documents older sync clients rewrite. */
@Singleton
class JournalRepository @Inject constructor(
    private val appRepository: AppRepository,
    private val db: AppDatabase,
) {
    private val organization = JournalOrganizationStore(db)

    val entries: Flow<List<JournalEntry>> = db.kvDao().observeJournalEntries().map {
        organization.all()
        db.kvDao().getJournalEntries().map(::decodeJournalEntry)
            .sortedWith(compareBy({ it.createdAt }, { it.notebookId }))
    }

    suspend fun createEntry(initialTopics: List<String> = emptyList(), parentFolderId: String? = null): Pair<String, String> = db.withTransaction {
        var parent = parentFolderId
        val ancestors = mutableSetOf<String>()
        while (parent != null) {
            check(ancestors.add(parent)) { "Folder ancestry is invalid" }
            val folder = db.folderDao().get(parent) ?: error("Folder is missing")
            check(folder.deletedAt == null) { "Folder is in Trash" }
            parent = folder.parentFolderId
        }
        val now = Instant.now()
        val zone = ZoneId.systemDefault()
        val ids = appRepository.createNewNote(parentFolderId) ?: error("Unable to create note")
        val book = appRepository.bookRepository.getById(ids.first) ?: error("Note is missing")
        appRepository.bookRepository.update(book.copy(
            title = DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm", Locale.getDefault()).format(now.atZone(zone))
        ))
        organization.capture(ids.first, journalDate(now.toEpochMilli(), zone), now.toEpochMilli(), initialTopics)
        ids
    }

    suspend fun setTopics(notebookId: String, topics: List<String>) = updateTopics(notebookId) { topics }

    suspend fun addTopic(notebookId: String, topic: String) = updateTopics(notebookId) { it + topic }

    suspend fun removeTopic(notebookId: String, topic: String) = updateTopics(notebookId) { topics ->
        val key = normalizeTopics(listOf(topic), topics).firstOrNull()?.let(::asciiLower)
        topics.filterNot { asciiLower(it) == key }
    }

    private suspend fun updateTopics(notebookId: String, change: (List<String>) -> List<String>) {
        db.withTransaction {
            val book = appRepository.bookRepository.getById(notebookId) ?: error("Note is missing")
            check(book.deletedAt == null) { "Note is in Trash" }
            organization.updateTopics(notebookId, book.createdAt.time, change)
        }
    }
}

internal const val JOURNAL_ENTRY_PREFIX = "journal.entry."

internal fun journalDate(time: Long, zone: ZoneId = ZoneId.systemDefault()): String =
    Instant.ofEpochMilli(time).atZone(zone).toLocalDate().toString()

internal fun decodeJournalEntry(row: Kv): JournalEntry {
    val entry = Json.decodeFromString<JournalEntry>(row.value)
    check(row.key == JOURNAL_ENTRY_PREFIX + entry.notebookId) { "Journal note identity is invalid" }
    entry.date?.let(java.time.LocalDate::parse)
    return entry
}
