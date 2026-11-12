package com.ethran.notable.data

import androidx.room.withTransaction
import com.ethran.notable.data.db.AppDatabase
import com.ethran.notable.data.db.Kv
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
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
    val date: String,
    val createdAt: Long,
    val topics: List<String> = emptyList(),
)

/** Local organization, kept apart from the notebook documents older sync clients rewrite. */
@Singleton
class JournalRepository @Inject constructor(
    private val appRepository: AppRepository,
    private val db: AppDatabase,
) {
    private val dao get() = db.kvDao()

    val entries: Flow<List<JournalEntry>> = db.kvDao().observeJournalEntries().map { rows ->
        rows.map(::decodeJournalEntry).sortedWith(compareBy({ it.createdAt }, { it.notebookId }))
    }

    suspend fun createEntry(initialTopics: List<String> = emptyList()): Pair<String, String> = db.withTransaction {
        val now = Instant.now()
        val zone = ZoneId.systemDefault()
        val ids = appRepository.createNewNote() ?: error("Unable to create note")
        val book = appRepository.bookRepository.getById(ids.first) ?: error("Note is missing")
        appRepository.bookRepository.update(book.copy(
            title = DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm", Locale.getDefault()).format(now.atZone(zone))
        ))
        val topics = if (initialTopics.isEmpty()) emptyList()
            else normalizeTopics(initialTopics, knownTopics())
        write(JournalEntry(ids.first, journalDate(now.toEpochMilli(), zone), now.toEpochMilli(), topics))
        ids
    }

    suspend fun setTopics(notebookId: String, topics: List<String>) = updateTopics(notebookId) { topics }

    suspend fun addTopic(notebookId: String, topic: String) = updateTopics(notebookId) { it + topic }

    suspend fun removeTopic(notebookId: String, topic: String) = updateTopics(notebookId) { topics ->
        topics.filterNot { topicKey(it) == topicKey(topic) }
    }

    private suspend fun updateTopics(notebookId: String, change: (List<String>) -> List<String>) {
        db.withTransaction {
            val book = appRepository.bookRepository.getById(notebookId) ?: error("Note is missing")
            check(book.deletedAt == null) { "Note is in Trash" }
            val stored = dao.get(JOURNAL_ENTRY_PREFIX + notebookId)?.let(::decodeJournalEntry)
                ?: JournalEntry(notebookId, journalDate(book.createdAt.time), book.createdAt.time)
            write(stored.copy(topics = normalizeTopics(change(stored.topics), knownTopics())))
        }
    }

    private suspend fun knownTopics(): List<String> = dao.getJournalEntries().map(::decodeJournalEntry)
        .sortedWith(compareBy({ it.createdAt }, { it.notebookId }))
        .flatMap { it.topics }

    private suspend fun write(entry: JournalEntry) {
        dao.set(Kv(JOURNAL_ENTRY_PREFIX + entry.notebookId, Json.encodeToString(entry)))
    }
}

internal const val JOURNAL_ENTRY_PREFIX = "journal.entry."

internal fun journalDate(time: Long, zone: ZoneId = ZoneId.systemDefault()): String =
    Instant.ofEpochMilli(time).atZone(zone).toLocalDate().toString()

private val topicWhitespace = Regex("\\s+")

internal fun topicName(value: String): String = value.trim().removePrefix("#").trim()
    .replace(topicWhitespace, " ")

internal fun topicKey(value: String): String = topicName(value).lowercase(Locale.ROOT)

internal fun normalizeTopics(values: List<String>, known: List<String> = emptyList()): List<String> {
    val names = linkedMapOf<String, String>()
    known.forEach { value ->
        val name = topicName(value)
        if (name.isNotEmpty()) names.putIfAbsent(topicKey(name), name)
    }
    return values.map(::topicName).filter { it.isNotEmpty() }
        .distinctBy(::topicKey).map { names[topicKey(it)] ?: it }
}

internal fun decodeJournalEntry(row: Kv): JournalEntry {
    val entry = Json.decodeFromString<JournalEntry>(row.value)
    check(row.key == JOURNAL_ENTRY_PREFIX + entry.notebookId) { "Journal note identity is invalid" }
    java.time.LocalDate.parse(entry.date)
    return entry
}
