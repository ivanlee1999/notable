package com.ethran.notable.sync.couch

import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException

/**
 * In-memory CouchDB implementing the subset the engine speaks, with real revision checking and a
 * real sequence-ordered change feed — so the tests exercise the actual 409-merge loop and
 * checkpoint handling rather than a simplified stand-in. bopa's `MockCouchServer.swift` is the
 * twin of this class.
 *
 * MockWebServer is deliberately not a dependency of this project; [CouchTransport] is the seam
 * that makes it unnecessary.
 */
class FakeCouchTransport : CouchTransport {

    private data class Doc(
        val rev: String,
        val deleted: Boolean,
        val json: JsonObject,
        val seq: Int,
    )

    private val lock = Any()
    private val docs = LinkedHashMap<String, Doc>()
    private var seqCounter = 0
    private var revCounter = 0

    /** When set, every request throws — the offline case. */
    var isOffline = false

    /** Forces a status for documents whose id is listed, for failure injection. */
    val failingDocumentIds = mutableMapOf<String, Int>()

    private val log = mutableListOf<Pair<String, String>>()
    val requestLog: List<Pair<String, String>> get() = synchronized(lock) { log.toList() }

    override fun send(request: CouchRequest): CouchResponse = synchronized(lock) {
        if (isOffline) throw IOException("not connected to the internet")
        log += request.method to request.path

        val components = request.path.split('/').filter { it.isNotEmpty() }
        if (components.size < 2) return CouchResponse(status = 404)
        val tail = components.drop(1).joinToString("/")

        if (tail == "_changes") return changes(request)
        failingDocumentIds[tail]?.let { return CouchResponse(status = it) }

        return when (request.method) {
            "GET" -> get(tail)
            "PUT" -> put(tail, request)
            else -> CouchResponse(status = 405)
        }
    }

    // region Verbs

    private fun get(documentId: String): CouchResponse {
        val doc = docs[documentId] ?: return CouchResponse(status = 404)
        return CouchResponse(status = 200, body = encode(materialize(documentId, doc)))
    }

    private fun put(documentId: String, request: CouchRequest): CouchResponse {
        val body = request.body ?: return CouchResponse(status = 400)
        val json = runCatching {
            couchJson.parseToJsonElement(String(body, Charsets.UTF_8)).jsonObject
        }.getOrNull() ?: return CouchResponse(status = 400)

        val providedRev = json["_rev"]?.jsonPrimitive?.content
        val deleted = json["_deleted"]?.jsonPrimitive?.content == "true"

        val existing = docs[documentId]
        if (existing != null) {
            // A stale revision is the whole point of the conflict path; a tombstone may be
            // overwritten without one, which is how a deleted document gets resurrected.
            if (!existing.deleted || providedRev != null) {
                if (providedRev != existing.rev) return conflict()
            }
        } else if (providedRev != null) {
            return conflict()
        }

        revCounter += 1
        seqCounter += 1
        val generation = existing?.rev?.substringBefore('-')?.toIntOrNull() ?: 0
        val newRev = "${generation + 1}-r$revCounter"
        val stripped = JsonObject(json.filterKeys { it !in RESERVED })
        docs[documentId] = Doc(rev = newRev, deleted = deleted, json = stripped, seq = seqCounter)

        val result = buildJsonObject {
            put("ok", JsonPrimitive(true))
            put("id", JsonPrimitive(documentId))
            put("rev", JsonPrimitive(newRev))
        }
        // Real CouchDB answers 200 for a tombstone write and 201 for a live one. Modelling the
        // asymmetry rather than smoothing it over is what catches a client that accepts only 201
        // and therefore fails every deletion against a real server.
        return CouchResponse(status = if (deleted) 200 else 201, body = encode(result))
    }

    private fun conflict(): CouchResponse = CouchResponse(
        status = 409,
        body = encode(
            buildJsonObject {
                put("error", JsonPrimitive("conflict"))
                put("reason", JsonPrimitive("Document update conflict."))
            }
        ),
    )

    private fun changes(request: CouchRequest): CouchResponse {
        val since = request.query.firstOrNull { it.name == "since" }?.value?.toIntOrNull() ?: 0
        val rows: JsonArray = buildJsonArray {
            docs.entries
                .filter { it.value.seq > since }
                .sortedBy { it.value.seq }
                .forEach { (id, doc) ->
                    add(
                        buildJsonObject {
                            put("id", JsonPrimitive(id))
                            put("seq", JsonPrimitive(doc.seq))
                            put(
                                "changes",
                                buildJsonArray {
                                    add(buildJsonObject { put("rev", JsonPrimitive(doc.rev)) })
                                }
                            )
                            put("doc", materialize(id, doc))
                            if (doc.deleted) put("deleted", JsonPrimitive(true))
                        }
                    )
                }
        }
        val result = buildJsonObject {
            put("results", rows)
            // CouchDB 3 reports an opaque *string* here, not a number.
            put("last_seq", JsonPrimitive(seqCounter.toString()))
        }
        return CouchResponse(status = 200, body = encode(result))
    }

    // endregion

    // region Test helpers

    fun revision(documentId: String): String? = synchronized(lock) { docs[documentId]?.rev }

    fun isDeleted(documentId: String): Boolean =
        synchronized(lock) { docs[documentId]?.deleted ?: false }

    fun documentIds(): List<String> = synchronized(lock) { docs.keys.sorted() }

    /** Writes a document as if another device had pushed it. */
    fun <T> seed(
        documentId: String,
        body: T,
        serializer: SerializationStrategy<T>,
        deleted: Boolean = false,
    ) = synchronized(lock) {
        seedRaw(documentId, couchJson.encodeToJsonElement(serializer, body).jsonObject, deleted)
    }

    /** Writes arbitrary JSON, for documents the engine is meant to fail to understand. */
    fun seedRaw(documentId: String, json: JsonObject, deleted: Boolean = false) =
        synchronized(lock) {
            revCounter += 1
            seqCounter += 1
            val generation = docs[documentId]?.rev?.substringBefore('-')?.toIntOrNull() ?: 0
            docs[documentId] = Doc(
                rev = "${generation + 1}-r$revCounter",
                deleted = deleted,
                json = JsonObject(json.filterKeys { it !in RESERVED }),
                seq = seqCounter,
            )
            Unit
        }

    // endregion

    private fun materialize(documentId: String, doc: Doc): JsonObject = buildJsonObject {
        for ((key, value) in doc.json) put(key, value)
        put("_id", JsonPrimitive(documentId))
        put("_rev", JsonPrimitive(doc.rev))
        if (doc.deleted) put("_deleted", JsonPrimitive(true))
    }

    private fun encode(json: JsonObject): ByteArray =
        couchJson.encodeToString(JsonObject.serializer(), json).toByteArray(Charsets.UTF_8)

    private companion object {
        val RESERVED = setOf("_id", "_rev", "_deleted")
    }
}

/** Map-backed [CouchLocalStore], standing in for a device's own storage. */
class FakeLocalStore : CouchLocalStore {

    private val documents = LinkedHashMap<String, CouchDocBody>()
    private val copies = mutableListOf<String>()

    val conflictCopies: List<String> get() = copies.toList()

    override fun load(documentId: String): CouchDocBody? = documents[documentId]

    override fun apply(documentId: String, body: CouchDocBody) {
        documents[documentId] = body
    }

    override fun applyConflictCopy(documentId: String, json: JsonObject) {
        // The real store materializes the remote document under a fresh identity; recording the
        // id is enough to assert that the local copy was left alone.
        copies += documentId
    }

    // region Test helpers

    fun set(documentId: String, body: CouchDocBody) {
        documents[documentId] = body
    }

    fun page(documentId: String): CouchPage? = (documents[documentId] as? CouchDocBody.Page)?.page

    fun notebook(documentId: String): CouchNotebook? =
        (documents[documentId] as? CouchDocBody.Notebook)?.notebook

    fun body(documentId: String): CouchDocBody? = documents[documentId]

    // endregion
}
