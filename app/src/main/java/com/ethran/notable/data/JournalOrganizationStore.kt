package com.ethran.notable.data

import androidx.room.withTransaction
import com.ethran.notable.data.db.AppDatabase
import com.ethran.notable.data.db.Kv
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.time.LocalDate
import java.util.UUID
import javax.inject.Inject

@Serializable
data class TopicRevision(val name: String, val present: Boolean, val counter: Long, val writer: String)

@Serializable
data class OrganizationEntry(
    val notebookId: String,
    val date: String?,
    val createdAt: Long,
    val topics: Map<String, TopicRevision> = emptyMap(),
)

internal const val MAX_ORGANIZATION_NUMBER = 9_007_199_254_740_991L
private const val ORGANIZATION_PREFIX = "journal.organization."
private const val WRITER_KEY = "journal.writer"
private const val COUNTER_KEY = "journal.counter"

internal fun asciiLower(value: String): String = value.map { if (it in 'A'..'Z') it + 32 else it }.joinToString("")
private fun Char.isTopicSpace() = this == ' ' || this in '\t'..'\r'
private fun trimTopic(value: String) = value.trim { it.isTopicSpace() }
private val topicSpaces = Regex("[ \\t\\n\\r\\u000B\\u000C]+")

internal fun topicName(value: String): String =
    trimTopic(trimTopic(value).removePrefix("#")).replace(topicSpaces, " ")

internal fun topicKey(value: String): String = asciiLower(topicName(value))

internal fun normalizeTopics(values: List<String>, known: List<String> = emptyList()): List<String> {
    val names = linkedMapOf<String, String>()
    known.forEach { if (it.isNotEmpty()) names.putIfAbsent(asciiLower(it), it) }
    return values.map { names[asciiLower(trimTopic(it).replace(topicSpaces, " "))] ?: topicName(it) }
        .filter { it.isNotEmpty() }.distinctBy(::asciiLower)
        .map { names[asciiLower(it)] ?: it }
}

private fun compareUtf8(a: String, b: String): Int {
    val x = a.toByteArray(Charsets.UTF_8)
    val y = b.toByteArray(Charsets.UTF_8)
    for (i in 0 until minOf(x.size, y.size)) {
        val c = (x[i].toInt() and 255).compareTo(y[i].toInt() and 255)
        if (c != 0) return c
    }
    return x.size.compareTo(y.size)
}

private fun compareTopic(a: TopicRevision, b: TopicRevision): Int {
    val counter = a.counter.compareTo(b.counter)
    if (counter != 0) return counter
    val writer = compareUtf8(a.writer, b.writer)
    if (writer != 0) return writer
    val removed = (!a.present).compareTo(!b.present)
    return if (removed != 0) removed else compareUtf8(a.name, b.name)
}

fun mergeOrganization(a: OrganizationEntry, b: OrganizationEntry): OrganizationEntry {
    validateOrganization(a)
    validateOrganization(b)
    require(a.notebookId == b.notebookId) { "Cannot merge different journal notes" }
    val capture = when {
        a.date == null && b.date != null -> b
        b.date == null && a.date != null -> a
        a.createdAt != b.createdAt -> if (a.createdAt < b.createdAt) a else b
        else -> if (compareUtf8(a.date.orEmpty(), b.date.orEmpty()) <= 0) a else b
    }
    val topics = (a.topics.keys + b.topics.keys).sortedWith(::compareUtf8).associateWith { key ->
        val left = a.topics[key]
        val right = b.topics[key]
        when {
            left == null -> right!!
            right == null -> left
            compareTopic(left, right) >= 0 -> left
            else -> right
        }
    }
    return capture.copy(topics = topics)
}

private fun validDate(value: String) {
    require(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}").matches(value)) { "Invalid journal date" }
    require(LocalDate.parse(value).toString() == value) { "Invalid journal date" }
}

private fun validateOrganization(entry: OrganizationEntry) {
    require(entry.notebookId.isNotEmpty()) { "Missing journal note identity" }
    require(entry.createdAt in 0..MAX_ORGANIZATION_NUMBER) { "Invalid journal capture time" }
    entry.date?.let(::validDate)
    entry.topics.forEach { (key, topic) ->
        require(topic.name.isNotEmpty() && trimTopic(topic.name).replace(topicSpaces, " ") == topic.name) {
            "Invalid topic name"
        }
        require(key == asciiLower(topic.name)) { "Invalid topic identity" }
        require(topic.writer.isNotEmpty() && topic.counter in 0..MAX_ORGANIZATION_NUMBER) { "Invalid topic revision" }
    }
}

fun encodeOrganization(entry: OrganizationEntry): JsonObject {
    validateOrganization(entry)
    return buildJsonObject {
        put("_id", "journal:${entry.notebookId}")
        put("type", "journal")
        put("schema", 1)
        put("notebookId", entry.notebookId)
        put("date", entry.date?.let(::JsonPrimitive) ?: JsonNull)
        put("createdAt", entry.createdAt)
        putJsonObject("topics") {
            entry.topics.forEach { (key, topic) -> put(key, buildJsonObject {
                put("name", topic.name)
                put("present", topic.present)
                put("counter", topic.counter)
                put("writer", topic.writer)
            }) }
        }
    }
}

fun decodeOrganization(raw: JsonObject): OrganizationEntry {
    fun string(obj: JsonObject, key: String): String {
        val value = obj[key] as? JsonPrimitive
        require(value?.isString == true) { "Invalid organization $key" }
        return value.content
    }
    fun number(obj: JsonObject, key: String): Long {
        val value = obj[key] as? JsonPrimitive
        require(value != null && !value.isString && value.longOrNull != null) { "Invalid organization $key" }
        return value.long
    }
    require(string(raw, "type") == "journal" && number(raw, "schema") == 1L) { "Unsupported organization document" }
    raw["_deleted"]?.let {
        require(it is JsonPrimitive && !it.isString && it.booleanOrNull == false) { "Deleted organization document" }
    }
    val notebookId = string(raw, "notebookId")
    require(string(raw, "_id") == "journal:$notebookId") { "Journal note identity does not match" }
    require(raw.containsKey("date")) { "Missing journal date" }
    val date = if (raw["date"] == JsonNull) null else string(raw, "date")
    val topics = raw["topics"] as? JsonObject ?: error("Missing organization topics")
    return OrganizationEntry(notebookId, date, number(raw, "createdAt"), topics.mapValues { (_, value) ->
        val topic = value as? JsonObject ?: error("Invalid topic revision")
        val present = topic["present"] as? JsonPrimitive
        require(present != null && !present.isString && present.booleanOrNull != null) { "Invalid topic presence" }
        TopicRevision(string(topic, "name"), present.boolean, number(topic, "counter"), string(topic, "writer"))
    }).also(::validateOrganization)
}

/** Canonical desired state. Ink sync may arrive before or after these records. */
class JournalOrganizationStore @Inject constructor(private val db: AppDatabase) {
    private val dao get() = db.kvDao()
    val changes: Flow<List<Kv>> = dao.observeJournalOrganization().distinctUntilChanged()

    suspend fun all(): List<OrganizationEntry> = db.withTransaction { load() }

    suspend fun merge(incoming: OrganizationEntry): OrganizationEntry = db.withTransaction {
        validateOrganization(incoming)
        val current = load().find { it.notebookId == incoming.notebookId }
        val merged = current?.let { mergeOrganization(it, incoming) } ?: incoming
        observeCounter(merged.topics.values.maxOfOrNull { it.counter } ?: 0)
        write(merged)
        merged
    }

    suspend fun capture(notebookId: String, date: String, createdAt: Long, topics: List<String>) = db.withTransaction {
        val records = load()
        check(records.none { it.notebookId == notebookId }) { "Journal entry already exists" }
        val names = normalizeTopics(topics, records.flatMap { it.topics.values.filter { topic -> topic.present }.map { topic -> topic.name } })
        val revision = if (names.isEmpty()) null else nextRevision()
        val entry = OrganizationEntry(notebookId, date, createdAt, names.associate { name ->
            asciiLower(name) to TopicRevision(name, true, revision!!.first, revision.second)
        })
        write(entry)
    }

    suspend fun updateTopics(notebookId: String, createdAt: Long, change: (List<String>) -> List<String>) = db.withTransaction {
        val records = load()
        val current = records.find { it.notebookId == notebookId } ?: OrganizationEntry(notebookId, null, createdAt)
        val visible = current.topics.values.filter { it.present }.map { it.name }
        val known = records.flatMap { it.topics.values.filter { topic -> topic.present }.map { topic -> topic.name } }
        val desired = normalizeTopics(change(visible), known).associateBy(::asciiLower)
        val changed = (current.topics.keys + desired.keys).filter { key ->
            val old = current.topics[key]
            val present = desired.containsKey(key)
            old == null || old.present != present || (present && old.name != desired[key])
        }
        if (changed.isNotEmpty()) {
            val (counter, writer) = nextRevision()
            val topics = current.topics.toMutableMap()
            changed.forEach { key ->
                topics[key] = TopicRevision(desired[key] ?: topics.getValue(key).name, desired.containsKey(key), counter, writer)
            }
            write(current.copy(topics = topics))
        }
    }

    private suspend fun load(): List<OrganizationEntry> {
        // Validate the complete input before changing even one row.
        val legacy = dao.getJournalEntries().map(::decodeJournalEntry)
        val canonical = dao.getJournalOrganization().map { row ->
            decodeOrganization(Json.parseToJsonElement(row.value).jsonObject).also {
                check(row.key == ORGANIZATION_PREFIX + it.notebookId) { "Organization storage identity does not match" }
            }
        }.associateBy { it.notebookId }.toMutableMap()
        observeCounter(canonical.values.flatMap { it.topics.values }.maxOfOrNull { it.counter } ?: 0)
        legacy.filter { it.notebookId !in canonical }.forEach { entry ->
            val names = entry.topics.distinctBy(::asciiLower)
            val migrated = OrganizationEntry(entry.notebookId, entry.date, entry.createdAt, names.associate { name ->
                asciiLower(name) to TopicRevision(name, true, 0, "legacy")
            })
            write(migrated)
            canonical[entry.notebookId] = migrated
        }
        return canonical.values.sortedWith(compareBy({ it.createdAt }, { it.notebookId }))
    }

    private suspend fun observeCounter(value: Long) {
        val stored = dao.get(COUNTER_KEY)?.value?.toLongOrNull()
        check(stored == null || stored in 0..MAX_ORGANIZATION_NUMBER) { "Invalid organization clock" }
        check(dao.get(COUNTER_KEY) == null || stored != null) { "Invalid organization clock" }
        if (value > (stored ?: 0)) dao.set(Kv(COUNTER_KEY, value.toString()))
    }

    private suspend fun nextRevision(): Pair<Long, String> {
        val rawCounter = dao.get(COUNTER_KEY)
        val counter = rawCounter?.value?.toLongOrNull() ?: if (rawCounter == null) 0 else error("Invalid organization clock")
        check(counter in 0 until MAX_ORGANIZATION_NUMBER) { "Organization clock exhausted" }
        val writer = dao.get(WRITER_KEY)?.value ?: UUID.randomUUID().toString().also { dao.set(Kv(WRITER_KEY, it)) }
        UUID.fromString(writer)
        dao.set(Kv(COUNTER_KEY, (counter + 1).toString()))
        return counter + 1 to writer
    }

    private suspend fun write(entry: OrganizationEntry) {
        val value = encodeOrganization(entry).toString()
        val key = ORGANIZATION_PREFIX + entry.notebookId
        if (dao.get(key)?.value != value) dao.set(Kv(key, value))
        val projectionKey = JOURNAL_ENTRY_PREFIX + entry.notebookId
        val old = dao.get(projectionKey)?.let(::decodeJournalEntry)
        val present = entry.topics.filterValues { it.present }
        val order = (old?.topics.orEmpty().map(::asciiLower) + present.keys).distinct()
        val projection = JournalEntry(entry.notebookId, entry.date, entry.createdAt, order.mapNotNull { present[it]?.name })
        val encoded = Json.encodeToString(projection)
        if (dao.get(projectionKey)?.value != encoded) dao.set(Kv(projectionKey, encoded))
    }
}
