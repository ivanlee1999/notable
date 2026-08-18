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
        /**
         * Attachment bytes, held apart from the body exactly as CouchDB holds them: a document
         * read never carries them, only a stub saying they exist.
         */
        val attachments: Map<String, Attachment> = emptyMap(),
    )

    private class Attachment(val contentType: String, val bytes: ByteArray)

    private val lock = Any()
    private val docs = LinkedHashMap<String, Doc>()
    private var seqCounter = 0
    private var revCounter = 0

    /** When set, every request throws — the offline case. */
    var isOffline = false

    /**
     * The `Date` header every response carries, verbatim.
     *
     * Null by default, which is a server behind a proxy that strips it — and, more importantly, is
     * what every test that does not care about clocks should see, since a header saying "now" would
     * quietly make them all assert the absence of skew as a side effect.
     *
     * Set to a raw string rather than an instant so a test can serve a malformed one, which the
     * client has to treat as no information rather than as an error.
     */
    var dateHeader: String? = null

    /** Forces a status for documents whose id is listed, for failure injection. */
    val failingDocumentIds = mutableMapOf<String, Int>()

    /**
     * The body served with a forced status, for documents that need one.
     *
     * A 413's body is not decoration: it is how a client tells CouchDB refusing a document apart
     * from a proxy refusing the request (protocol §7.2), and the two demand opposite handling. A
     * forced status with no entry here keeps its empty body, which is exactly what an intermediary
     * that answers with nothing looks like.
     */
    val failingDocumentBodies = mutableMapOf<String, ByteArray>()

    private val changeBatchSizes = mutableListOf<Int>()

    /**
     * The most rows any single `_changes` response carried. Request *count* cannot tell paging from
     * its absence — an unpaged read is one big response followed by an empty one, which is two
     * requests either way — but the size of the largest response can.
     */
    val largestChangeBatch: Int get() = synchronized(lock) { changeBatchSizes.maxOrNull() ?: 0 }

    private val log = mutableListOf<Pair<String, String>>()
    val requestLog: List<Pair<String, String>> get() = synchronized(lock) { log.toList() }

    /**
     * Stamps [dateHeader] onto whatever the handler produced, the way a real origin server does:
     * on every response, whatever its status. A header already set by the handler wins, because
     * only the attachment path sets one and it is not a `Date`.
     */
    override fun send(request: CouchRequest): CouchResponse {
        val response = handle(request)
        val date = dateHeader ?: return response
        return CouchResponse(
            status = response.status,
            headers = response.headers + ("Date" to date),
            body = response.body,
        )
    }

    private fun handle(request: CouchRequest): CouchResponse = synchronized(lock) {
        if (isOffline) throw IOException("not connected to the internet")
        log += request.method to request.path

        val components = request.path.split('/').filter { it.isNotEmpty() }
        if (components.size < 2) return CouchResponse(status = 404)
        val tail = components.drop(1).joinToString("/")

        if (tail == "_changes") return changes(request)
        failingDocumentIds[tail]?.let {
            return CouchResponse(
                status = it,
                body = failingDocumentBodies[tail] ?: ByteArray(0),
            )
        }

        return when {
            request.method == "GET" && components.size > 2 &&
                components.last() == CouchAssetId.BLOB_NAME ->
                attachment(components.drop(1).dropLast(1).joinToString("/"))

            request.method == "GET" ->
                if (request.query.any { it.name == "open_revs" }) openRevsAll(tail) else get(tail)

            request.method == "PUT" -> put(tail, request)
            else -> CouchResponse(status = 405)
        }
    }

    // region Verbs

    /**
     * A plain GET, which for a *deleted* document is a 404 — CouchDB does not hand back a
     * tombstone's body here. Modelling that rather than smoothing it over is what catches a client
     * that reads "deleted" as "never existed" and re-creates the document.
     */
    private fun get(documentId: String): CouchResponse {
        val doc = docs[documentId] ?: return CouchResponse(status = 404)
        if (doc.deleted) {
            return CouchResponse(
                status = 404,
                body = """{"error":"not_found","reason":"deleted"}""".toByteArray(Charsets.UTF_8),
            )
        }
        return CouchResponse(status = 200, body = encode(materialize(documentId, doc)))
    }

    /**
     * `?open_revs=all` with `Accept: application/json`: the leaf revisions, including deleted ones,
     * wrapped one per element. This is the only way to read a tombstone's body back.
     */
    private fun openRevsAll(documentId: String): CouchResponse {
        val doc = docs[documentId] ?: return CouchResponse(status = 404)
        val leaves = buildJsonArray {
            add(buildJsonObject { put("ok", materialize(documentId, doc)) })
        }
        return CouchResponse(
            status = 200,
            body = couchJson.encodeToString(JsonArray.serializer(), leaves)
                .toByteArray(Charsets.UTF_8),
        )
    }

    /**
     * `GET /{db}/{docid}/blob` — the only way to get an attachment's bytes back, since every
     * document read renders them as a stub.
     */
    private fun attachment(documentId: String): CouchResponse {
        val blob = docs[documentId]?.attachments?.get(CouchAssetId.BLOB_NAME)
            ?: return CouchResponse(status = 404)
        return CouchResponse(
            status = 200,
            headers = mapOf("Content-Type" to blob.contentType),
            body = blob.bytes,
        )
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
            // Deleting what is already deleted is a 409 even when the revision is current — a
            // client that answers a peer's tombstone by writing the same tombstone back therefore
            // never converges, it just burns its retries.
            if (existing.deleted && deleted) return conflict()
            // Resurrecting is a *create*, never an update. CouchDB refuses a PUT that carries a
            // revision at a deleted leaf — verified against 3.5.2: `_rev` at the tombstone's own
            // current revision answers 409 just as a stale one does, and only a body with no
            // revision at all brings the document back. This fake used to accept the revisioned
            // form, which is why an engine that retried its resurrection at `remote.rev` passed
            // every unit test here and then span forever against a real server.
            if (existing.deleted) {
                if (providedRev != null) return conflict()
            } else if (providedRev != existing.rev) {
                return conflict()
            }
        } else if (providedRev != null) {
            return conflict()
        }

        revCounter += 1
        seqCounter += 1
        val generation = existing?.rev?.substringBefore('-')?.toIntOrNull() ?: 0
        val newRev = "${generation + 1}-r$revCounter"
        val stripped = JsonObject(json.filterKeys { it !in RESERVED })
        val attachments = extractAttachments(stripped)
        docs[documentId] = Doc(
            rev = newRev,
            deleted = deleted,
            json = attachments.second,
            seq = seqCounter,
            attachments = attachments.first,
        )

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

    /**
     * Takes inlined attachment bytes out of the body and leaves the stub CouchDB would leave.
     * Modelled rather than smoothed over: a client that expected to read a blob straight out of
     * the change feed would pass against a fake that kept the data and fail against a server.
     */
    private fun extractAttachments(
        json: JsonObject,
    ): Pair<Map<String, Attachment>, JsonObject> {
        val inlined = json["_attachments"] as? JsonObject ?: return emptyMap<String, Attachment>() to json
        val stored = mutableMapOf<String, Attachment>()
        val stubs = buildJsonObject {
            for ((name, element) in inlined) {
                val blob = element as? JsonObject ?: continue
                val contentType = blob["content_type"]?.jsonPrimitive?.content
                    ?: "application/octet-stream"
                val bytes = blob["data"]?.jsonPrimitive?.content?.fromAttachmentData() ?: ByteArray(0)
                stored[name] = Attachment(contentType, bytes)
                put(
                    name,
                    buildJsonObject {
                        put("content_type", JsonPrimitive(contentType))
                        put("stub", JsonPrimitive(true))
                        put("length", JsonPrimitive(bytes.size))
                    }
                )
            }
        }
        val body = buildJsonObject {
            for ((key, value) in json) if (key != "_attachments") put(key, value)
            put("_attachments", stubs)
        }
        return stored to body
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
        val limit = request.query.firstOrNull { it.name == "limit" }?.value?.toIntOrNull()
        val selected = docs.entries
            .filter { it.value.seq > since }
            .sortedBy { it.value.seq }
            // `limit` is honoured, and `last_seq` is then the sequence of the last row actually
            // returned — not the server's newest. A mock that ignored the limit and reported the
            // end of the feed would let a client "page" by asking once and being handed
            // everything, which is exactly what paging exists to avoid.
            .let { if (limit != null) it.take(limit) else it }
        val rows: JsonArray = buildJsonArray {
            selected
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
        val lastSeq = selected.lastOrNull()?.value?.seq ?: seqCounter
        changeBatchSizes += rows.size
        val result = buildJsonObject {
            put("results", rows)
            // CouchDB 3 reports an opaque *string* here, not a number.
            put("last_seq", JsonPrimitive(lastSeq.toString()))
        }
        return CouchResponse(status = 200, body = encode(result))
    }

    // endregion

    // region Test helpers

    fun revision(documentId: String): String? = synchronized(lock) { docs[documentId]?.rev }

    /**
     * Forgets what has been asked for so far, for tests that assert about the requests a *later*
     * step makes.
     */
    fun forgetRequests() = synchronized(lock) { log.clear() }

    fun isDeleted(documentId: String): Boolean =
        synchronized(lock) { docs[documentId]?.deleted ?: false }

    /**
     * The *library* documents the server holds.
     *
     * Reserved ids are excluded by default because the protocol says they are not library items
     * (§1.1): `sync-meta:database` appears the moment any engine touches a fresh database, and a
     * test asking "did this notebook reach the server" is not asking about bookkeeping. The
     * identity tests pass [includeReserved] to see it.
     */
    fun documentIds(includeReserved: Boolean = false): List<String> = synchronized(lock) {
        docs.keys.filter { includeReserved || !CouchMetaDocId.isReserved(it) }.sorted()
    }

    /** Writes a document as if another device had pushed it. */
    fun <T> seed(
        documentId: String,
        body: T,
        serializer: SerializationStrategy<T>,
        deleted: Boolean = false,
    ) = synchronized(lock) {
        seedRaw(documentId, couchJson.encodeToJsonElement(serializer, body).jsonObject, deleted)
    }

    /**
     * Serves different bytes under a content-addressed id than the ones that named it — a
     * truncating proxy, a mis-served range, a corrupted store on the far side. Nothing a
     * well-behaved CouchDB does, and exactly what the digest check exists to catch.
     */
    fun replaceAttachment(documentId: String, bytes: ByteArray) = synchronized(lock) {
        val doc = docs[documentId] ?: return@synchronized
        val existing = doc.attachments[CouchAssetId.BLOB_NAME]
        docs[documentId] = doc.copy(
            attachments = doc.attachments + (
                CouchAssetId.BLOB_NAME to Attachment(
                    contentType = existing?.contentType ?: "application/octet-stream",
                    bytes = bytes,
                )
                ),
        )
        Unit
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

    companion object {
        private val RESERVED = setOf("_id", "_rev", "_deleted")

        /** What CouchDB itself answers when a document's ordinary fields exceed the limit. */
        val COUCHDB_TOO_LARGE: ByteArray =
            """{"error":"document_too_large","reason":""}""".toByteArray()

        /** What nginx answers when the request body exceeds `client_max_body_size`. */
        val PROXY_TOO_LARGE: ByteArray =
            "<html>\r\n<head><title>413 Request Entity Too Large</title></head>\r\n</html>"
                .toByteArray()
    }
}

/** Map-backed [CouchLocalStore], standing in for a device's own storage. */
class FakeLocalStore : CouchLocalStore {

    private val documents = LinkedHashMap<String, CouchDocBody>()
    private val copies = mutableListOf<String>()
    private val copyBodies = mutableListOf<JsonObject>()

    val conflictCopies: List<String> get() = copies.toList()

    /**
     * Page tombstones, per notebook document id, kept *outside* the notebook body.
     *
     * A real store has no choice about this — `RoomCouchStore` reads them out of their own table on
     * every load, because the notebook row has nowhere to put them. Modelling them as part of the
     * body instead would make this fake immune to the defect these tests exist to catch: a store
     * that removes a page but never says so, leaving the peer's add-wins union to put it back.
     */
    private val pageTombstones = LinkedHashMap<String, LinkedHashMap<String, String>>()

    /** Runs on the next [load], to model the editor writing while a merge is in flight. */
    var onLoad: (() -> Unit)? = null

    /** Ids whose writes throw — a device out of space, a row the database will not open. */
    private val unwritable = mutableSetOf<String>()

    fun failWrites(documentId: String) {
        unwritable += documentId
    }

    class WriteRefused : Exception("the store refused to write")

    override fun allDocumentIds(): List<String> = documents.keys.sorted()


    override fun load(documentId: String): CouchDocBody? {
        // The snapshot is taken first, *then* the hook runs: the engine is modelled as having read
        // the document and gone off to the network, with the editor writing while it is away.
        val snapshot = documents[documentId]
        onLoad?.invoke()
        if (snapshot is CouchDocBody.Notebook) {
            return CouchDocBody.Notebook(
                snapshot.notebook.copy(deletedPageIds = deletedPages(documentId))
            )
        }
        return snapshot
    }

    /**
     * Honours [CouchLocalStore.apply]'s contract the way a real store must: content that arrived
     * after the merge took its snapshot is left alone, because the merge never saw it and so
     * cannot have decided against it. A store that simply overwrote with the merged body would
     * silently drop ink drawn during the round trip — and would hide that bug from these tests.
     */
    override fun apply(documentId: String, body: CouchDocBody, basedOn: CouchDocBody?) {
        if (documentId in unwritable) throw WriteRefused()
        val current = documents[documentId]
        if (body is CouchDocBody.Notebook) {
            // Merged-in tombstones are recorded, not just applied: a store that dropped them would
            // forget the removal on the next load and offer the peer its own copy of the page back.
            body.notebook.deletedPageIds.forEach { tombstones(documentId)[it.id] = it.deletedAt }
        }
        documents[documentId] =
            if (body is CouchDocBody.Page && current is CouchDocBody.Page) {
                CouchDocBody.Page(
                    reconcile(current.page, body.page, (basedOn as? CouchDocBody.Page)?.page)
                )
            } else {
                body
            }
    }

    private fun reconcile(current: CouchPage, merged: CouchPage, basedOn: CouchPage?): CouchPage {
        if (basedOn == null) return merged
        val seen = basedOn.strokes.map { it.id }.toSet()
        val kept = merged.strokes.map { it.id }.toSet()
        val tombstoned = merged.deletedStrokes.map { it.id }.toSet()
        val survivors =
            current.strokes.filter { it.id !in seen && it.id !in kept && it.id !in tombstoned }
        return merged.copy(strokes = merged.strokes + survivors)
    }

    /**
     * The identities a real store would have minted for its copies — the same derivation
     * `RoomCouchStore` uses, so a test can tell "copied twice" from "copied into the same place".
     */
    val conflictCopyIdentities: Set<String>
        get() = copies.indices.map { index ->
            CouchAssetId.sha256Hex(
                (copies[index] + couchJson.encodeToString(JsonObject.serializer(), copyBodies[index]))
                    .toByteArray()
            )
        }.toSet()

    override fun applyConflictCopy(documentId: String, json: JsonObject) {
        // The real store materializes the remote document under a fresh identity; recording the
        // id is enough to assert that the local copy was left alone.
        copies += documentId
        copyBodies += json
    }

    /**
     * Forgets a recorded deletion, the way a real store does: the tombstone goes and nothing takes
     * its place. The document is *not* restored here — the rows it described are already gone — so
     * what this leaves behind is a device that holds nothing and claims nothing, which is exactly
     * the state that lets the server's copy return on the next pull.
     */
    override fun discardDeletion(documentId: String) {
        if (documents[documentId]?.isDeleted == true) documents.remove(documentId)
    }

    /**
     * Every asset a held page places whose bytes are not here — the same question the real store
     * answers from the image rows.
     */
    override fun missingAssetIds(): List<String> =
        documents.values.flatMap { it.referencedAssetIds }
            .filter { it !in documents }
            .distinct()
            .sorted()

    // region Test helpers

    fun set(documentId: String, body: CouchDocBody) {
        documents[documentId] = body
    }

    /**
     * Drops a document without tombstoning it — a notebook this device no longer holds, the way one
     * looks after its deletion was applied and reaped.
     */
    fun remove(documentId: String) {
        documents.remove(documentId)
    }

    fun page(documentId: String): CouchPage? = (documents[documentId] as? CouchDocBody.Page)?.page

    fun notebook(documentId: String): CouchNotebook? =
        (documents[documentId] as? CouchDocBody.Notebook)?.notebook

    fun body(documentId: String): CouchDocBody? = documents[documentId]

    /**
     * Deletes a page here, the way the app does: it leaves the notebook's manifest *and* leaves a
     * tombstone behind. An id already tombstoned keeps its original `deletedAt` (protocol §6.6).
     */
    fun deletePage(documentId: String, pageId: String, deletedAt: String) {
        val notebook = notebook(documentId) ?: return
        tombstones(documentId).putIfAbsent(pageId, deletedAt)
        documents[documentId] = CouchDocBody.Notebook(
            notebook.copy(pageIds = notebook.pageIds - pageId, updatedAt = deletedAt)
        )
    }

    /** What this device would tell a peer about pages it removed, sorted by id as the wire is. */
    fun deletedPages(documentId: String): List<CouchTombstone> =
        tombstones(documentId).map { CouchTombstone(id = it.key, deletedAt = it.value) }
            .sortedBy { it.id }

    private fun tombstones(documentId: String): LinkedHashMap<String, String> =
        pageTombstones.getOrPut(documentId) { LinkedHashMap() }

    // endregion
}
