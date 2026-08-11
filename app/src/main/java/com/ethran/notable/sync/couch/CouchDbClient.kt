package com.ethran.notable.sync.couch

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Typed failures from the CouchDB API. The distinctions matter: a 409 is the input to the merge
 * loop, a 401 can never be fixed by waiting, and a timeout must keep the work queued.
 *
 * [detail] is a stable, short rendering used in reports (a `Throwable.toString()` would carry the
 * package name and vary with obfuscation).
 */
sealed class CouchError(val detail: String) : Exception(detail) {
    /** The stored revision was stale. The caller re-reads, merges, and writes again. */
    class Conflict(val documentId: String) : CouchError("conflict($documentId)")

    class NotFound(val path: String) : CouchError("notFound($path)")

    /** Credentials rejected — worth surfacing immediately, since retrying cannot fix it. */
    object Unauthorized : CouchError("unauthorized") {
        private fun readResolve(): Any = Unauthorized
    }

    class Server(val status: Int, val path: String) : CouchError("server($status, $path)")

    /** Offline, DNS failure, timeout: keep the work queued and back off. */
    class Transport(val reason: String) : CouchError("transport($reason)")

    class MalformedResponse(val reason: String) : CouchError("malformedResponse($reason)")

    /** Whether waiting and trying again could plausibly succeed. */
    val isRetriable: Boolean
        get() = this is Transport || this is Server
}

/**
 * The slice of CouchDB's HTTP API the sync engine uses: read a document, write a document, follow
 * the change feed, and move attachment bytes. Deliberately not a general client — see
 * `docs/couch-sync-protocol.md` §7 in the bopa repo for the complete list.
 *
 * Full CouchDB replication (`_revs_diff`, `_bulk_docs` with `new_edits:false`, conflict leaves) is
 * not implemented: with a merge that never needs a common ancestor, pushing with the last known
 * `_rev` and merging on 409 keeps the revision tree linear, which removes the whole class of
 * accumulating conflict branches.
 *
 * bopa's `CouchDBClient.swift` is the twin of this file.
 */
class CouchDbClient(
    private val transport: CouchTransport,
    private val database: String = "notes",
) {

    private fun path(suffix: String): String = "/$database/$suffix"

    // region Documents

    /**
     * A stored document: its revision plus the raw body. [deleted] marks a tombstone, which
     * CouchDB still reports a revision for and which the merge treats as a real fact — not the
     * same thing as the document being absent.
     */
    data class Stored(
        val id: String,
        val rev: String,
        val deleted: Boolean,
        val json: JsonObject,
    )

    /** A [Stored] whose body has been decoded into [T]. */
    data class StoredBody<T>(
        val id: String,
        val rev: String,
        val deleted: Boolean,
        val body: T,
    )

    /**
     * Fetches a document as raw JSON, or null when the server has never held one. A tombstone is
     * *not* null: it comes back with `deleted == true` so the caller can apply delete-vs-edit.
     *
     * The raw form is what the conflict-copy path needs — it must keep the bytes of a document
     * that will not decode.
     */
    suspend fun getRaw(documentId: String): Stored? {
        val response = send(CouchRequest(method = "GET", path = path(documentId)))
        return when (response.status) {
            HTTP_OK -> {
                val json = jsonObject(response.body, "GET $documentId")
                val rev = json["_rev"]?.jsonPrimitive?.contentOrNullSafe
                    ?: throw CouchError.MalformedResponse("GET $documentId carried no _rev")
                Stored(
                    id = documentId,
                    rev = rev,
                    deleted = json["_deleted"]?.jsonPrimitive?.booleanOrNull ?: false,
                    json = json,
                )
            }

            // A plain GET of a deleted document is a 404 (`{"error":"not_found","reason":"deleted"}`),
            // not a 200 carrying `_deleted`. Telling "tombstoned" apart from "never existed" needs a
            // second request — and it matters: a caller that reads a tombstone as absent re-creates
            // the document, which silently undoes the peer's deletion.
            HTTP_NOT_FOUND -> getDeleted(documentId)
            else -> throw errorFor(response, path(documentId))
        }
    }

    /**
     * The winning leaf via `?open_revs=all`, which — unlike a plain GET — returns deleted
     * revisions, body and all. A 404 here means the document genuinely never existed.
     */
    private suspend fun getDeleted(documentId: String): Stored? {
        val response = send(
            CouchRequest(
                method = "GET",
                path = path(documentId),
                query = listOf(CouchQueryItem("open_revs", "all")),
                // Without this CouchDB answers multipart/mixed, which nothing here can parse.
                headers = mapOf("Accept" to "application/json"),
            )
        )
        if (response.status == HTTP_NOT_FOUND) return null
        if (response.status != HTTP_OK) throw errorFor(response, path(documentId))

        // `[{"ok": {…}}, {"missing": "…"}]` — only the readable leaves carry `ok`.
        //
        // Anything else from a 200 is reported, never read as "absent": absent is what sends the
        // pusher back round as a create, and a create over a tombstone is exactly the resurrection
        // this method exists to prevent. A document the server would not describe has to stay dirty
        // and be retried, not be overwritten on a guess.
        val leaves = runCatching {
            couchJson.parseToJsonElement(String(response.body, Charsets.UTF_8)).jsonArray
        }.getOrNull() ?: throw CouchError.MalformedResponse(
            "GET $documentId?open_revs=all did not return a list of revisions"
        )
        val document = leaves.firstNotNullOfOrNull { it.jsonObject["ok"]?.jsonObject }
            ?: throw CouchError.MalformedResponse(
                "GET $documentId?open_revs=all returned no readable revision"
            )
        val rev = document["_rev"]?.jsonPrimitive?.contentOrNullSafe
            ?: throw CouchError.MalformedResponse("GET $documentId carried no _rev")
        return Stored(
            id = documentId,
            rev = rev,
            deleted = document["_deleted"]?.jsonPrimitive?.booleanOrNull ?: false,
            json = document,
        )
    }

    /** Typed fetch. Returns null for an absent document, exactly as [getRaw] does. */
    suspend fun <T> get(documentId: String, deserializer: DeserializationStrategy<T>): StoredBody<T>? {
        val stored = getRaw(documentId) ?: return null
        val body = try {
            couchJson.decodeFromJsonElement(deserializer, stored.json)
        } catch (e: Exception) {
            throw CouchError.MalformedResponse("GET $documentId did not decode: $e")
        }
        return StoredBody(stored.id, stored.rev, stored.deleted, body)
    }

    /**
     * Writes a document, returning the new revision. [rev] must be the revision this device last
     * saw; passing null creates. A 409 surfaces as [CouchError.Conflict] for the caller's merge
     * loop rather than being retried blindly here — the retry needs the merged body.
     */
    suspend fun <T> put(
        documentId: String,
        rev: String?,
        body: T,
        serializer: SerializationStrategy<T>,
        deleted: Boolean = false,
    ): String {
        val encoded = couchJson.encodeToJsonElement(serializer, body)
        val fields = LinkedHashMap(encoded.jsonObject)
        fields["_id"] = JsonPrimitive(documentId)
        if (rev != null) fields["_rev"] = JsonPrimitive(rev)
        if (deleted) fields["_deleted"] = JsonPrimitive(true)

        val payload = couchJson
            .encodeToString(JsonObject.serializer(), JsonObject(fields))
            .toByteArray(Charsets.UTF_8)

        val response = send(
            CouchRequest(
                method = "PUT",
                path = path(documentId),
                headers = mapOf("Content-Type" to "application/json"),
                body = payload,
            )
        )
        return revFromWriteResponse(response, documentId, "PUT $documentId")
    }

    // endregion

    // region Changes feed

    /**
     * One row of `_changes`. [json] is the document body (`include_docs=true`), left raw until the
     * caller's id prefix says which shape to decode it as. Absent for rows the server elided.
     */
    data class ChangeRow(
        val id: String,
        val rev: String,
        val deleted: Boolean,
        val json: JsonObject?,
    )

    data class Changes(val lastSeq: String, val rows: List<ChangeRow>)

    /**
     * Reads the change feed from [since].
     *
     * [longpoll] holds the connection open until something changes or [timeoutMs] elapses — the
     * near-real-time path, and the reason the call needs a read timeout comfortably above the
     * window it asks for. A normal feed returns immediately and is used to catch up before
     * entering the loop.
     */
    suspend fun changes(
        since: String,
        longpoll: Boolean,
        timeoutMs: Long = DEFAULT_LONGPOLL_MS,
        limit: Int? = null,
    ): Changes {
        val query = mutableListOf(
            CouchQueryItem("since", since),
            CouchQueryItem("include_docs", "true"),
            CouchQueryItem("feed", if (longpoll) "longpoll" else "normal"),
        )
        if (longpoll) {
            // No `heartbeat`, deliberately. CouchDB treats it as *overriding* `timeout`: given
            // both, it holds the connection open until something actually changes, however long
            // that takes. Its keep-alive bytes then reset the client's read timeout in turn, so the
            // call never returned on its own — and since the engine holds its lock across the whole
            // pull, one quiet server meant this device could never push again. It was added to stop
            // an idle proxy dropping a long-held connection; losing that costs a retry, where
            // keeping it cost sync entirely.
            query += CouchQueryItem("timeout", timeoutMs.toString())
        }
        if (limit != null) query += CouchQueryItem("limit", limit.toString())

        val response = send(
            CouchRequest(
                method = "GET",
                path = path("_changes"),
                query = query,
                // Outlast the window we just asked the server to hold the connection open for.
                readTimeoutMs = if (longpoll) timeoutMs + LONGPOLL_READ_MARGIN_MS else null,
                // The bound that actually holds, for the reason given above the heartbeat.
                callTimeoutMs = if (longpoll) timeoutMs + LONGPOLL_READ_MARGIN_MS else null,
            )
        )
        if (response.status != HTTP_OK) throw errorFor(response, path("_changes"))
        return parseChanges(response.body)
    }

    internal fun parseChanges(body: ByteArray): Changes {
        val root = jsonObject(body, "_changes")
        // CouchDB 3 reports `last_seq` as an opaque string; older servers used a number. It is
        // only ever echoed back to the server, so keep whatever shape it arrived in.
        val lastSeqElement = root["last_seq"]
        val lastSeq = lastSeqElement?.jsonPrimitive?.let { primitive ->
            primitive.contentOrNullSafe ?: primitive.longOrNull?.toString()
        } ?: throw CouchError.MalformedResponse("_changes carried no last_seq")

        val rows = (root["results"] as? JsonArray)
            ?.mapNotNull { element ->
                val row = element as? JsonObject ?: return@mapNotNull null
                val id = row["id"]?.jsonPrimitive?.contentOrNullSafe ?: return@mapNotNull null
                val rev = row["changes"]?.jsonArray?.firstOrNull()
                    ?.jsonObject?.get("rev")?.jsonPrimitive?.contentOrNullSafe.orEmpty()
                ChangeRow(
                    id = id,
                    rev = rev,
                    deleted = row["deleted"]?.jsonPrimitive?.booleanOrNull ?: false,
                    json = row["doc"] as? JsonObject,
                )
            }
            .orEmpty()
        return Changes(lastSeq = lastSeq, rows = rows)
    }

    // endregion

    // region Attachments

    /** An attachment's bytes plus the type the server served them with. */
    class Attachment(val bytes: ByteArray, val contentType: String)

    /**
     * Assets are *written* by [put], which carries the blob inline in the document — see
     * [CouchAsset]. Only the read needs its own request: the change feed reports an asset document
     * as a stub, so the bytes are fetched when a page turns out to need them.
     *
     * Null when either the document or the attachment is absent — for a content-addressed asset
     * that is a peer which has not uploaded the bytes yet, not an error.
     */
    suspend fun getAttachment(
        documentId: String,
        name: String = CouchAssetId.BLOB_NAME,
    ): Attachment? {
        val response = send(CouchRequest(method = "GET", path = path("$documentId/$name")))
        return when (response.status) {
            HTTP_OK -> Attachment(
                bytes = response.body,
                contentType = response.headers.entries
                    .firstOrNull { it.key.equals("Content-Type", ignoreCase = true) }
                    ?.value ?: CouchAssetId.contentTypeOf(response.body),
            )

            HTTP_NOT_FOUND -> null
            else -> throw errorFor(response, path("$documentId/$name"))
        }
    }

    // endregion

    // region Plumbing

    /**
     * Shared success handling for the two write verbs.
     *
     * 201 for a create, 202 when the write is only accepted — and **200**, which is what a
     * tombstone write actually returns. Rejecting 200 made every notebook deletion fail against a
     * real server while passing against a mock that always answered 201.
     */
    private fun revFromWriteResponse(
        response: CouchResponse,
        documentId: String,
        what: String,
    ): String = when (response.status) {
        HTTP_OK, HTTP_CREATED, HTTP_ACCEPTED -> {
            val rev = jsonObject(response.body, what)["rev"]?.jsonPrimitive?.contentOrNullSafe
            rev ?: throw CouchError.MalformedResponse("$what returned no rev")
        }

        HTTP_CONFLICT -> throw CouchError.Conflict(documentId)
        else -> throw errorFor(response, path(documentId))
    }

    private suspend fun send(request: CouchRequest): CouchResponse = withContext(Dispatchers.IO) {
        try {
            transport.send(request)
        } catch (e: CouchError) {
            throw e
        } catch (e: Exception) {
            // OkHttp surfaces "offline", DNS failures and timeouts as thrown IOExceptions rather
            // than statuses. They are all "try again later", never "the document is gone".
            throw CouchError.Transport(e.toString())
        }
    }

    private fun jsonObject(body: ByteArray, what: String): JsonObject = try {
        couchJson.parseToJsonElement(String(body, Charsets.UTF_8)).jsonObject
    } catch (e: Exception) {
        throw CouchError.MalformedResponse("$what is not a JSON object: $e")
    }

    private fun errorFor(response: CouchResponse, path: String): CouchError = when (response.status) {
        HTTP_UNAUTHORIZED, HTTP_FORBIDDEN -> CouchError.Unauthorized
        HTTP_NOT_FOUND -> CouchError.NotFound(path)
        HTTP_CONFLICT -> CouchError.Conflict(path)
        else -> CouchError.Server(response.status, path)
    }

    // endregion

    companion object {
        const val DEFAULT_LONGPOLL_MS = 55_000L

        /**
         * Slack over the requested longpoll window, so the client never aborts first. Applied as
         * both a read timeout and a whole-call deadline: the call deadline is the one that holds if
         * a server ever streams keep-alive bytes at us anyway.
         */
        private const val LONGPOLL_READ_MARGIN_MS = 15_000L

        private const val HTTP_OK = 200
        private const val HTTP_CREATED = 201
        private const val HTTP_ACCEPTED = 202
        private const val HTTP_UNAUTHORIZED = 401
        private const val HTTP_FORBIDDEN = 403
        private const val HTTP_NOT_FOUND = 404
        private const val HTTP_CONFLICT = 409
    }
}

/**
 * `content` for a JSON string, null for anything else (including `null` and bare numbers).
 *
 * `JsonPrimitive.content` renders a number or boolean as its text, so reading `last_seq` or `_rev`
 * through it would silently accept a value of the wrong type. Everywhere a *string* is required,
 * this is the accessor.
 */
private val JsonPrimitive.contentOrNullSafe: String?
    get() = if (isString) content else null
