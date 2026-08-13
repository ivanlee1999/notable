package com.ethran.notable.sync.couch

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.net.UnknownServiceException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

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

    /**
     * Any other status the client does not handle specially. [retryAfterMs] carries the server's
     * own answer to "when should I come back", when it sent one.
     */
    class Server(
        val status: Int,
        val path: String,
        val retryAfterMs: Long? = null,
    ) : CouchError("server($status, $path)")

    /** Offline, DNS failure, timeout: keep the work queued and back off. */
    class Transport(val reason: String) : CouchError("transport($reason)")

    /**
     * The platform refused to make the call at all — a cleartext URL under a policy that forbids
     * one, most often. Distinct from [Transport] because no packet was ever sent: the connection is
     * not down, the address is unusable, and retrying it every few minutes forever only buries the
     * one message that would tell the user to change the URL.
     */
    class Blocked(val reason: String) : CouchError("blocked($reason)")

    class MalformedResponse(val reason: String) : CouchError("malformedResponse($reason)")

    /**
     * The database is not the one this device has been syncing with, requires a newer client, or is
     * locked for a rebuild (§1.2). Never retried: the answer is a human's, not a delay's.
     */
    class DatabaseIdentity(
        val identity: CouchSyncEngine.DatabaseIdentity,
    ) : CouchError("databaseIdentity($identity)")

    /**
     * Whether waiting and trying again could plausibly succeed.
     *
     * Every [Server] status used to answer yes, which meant a request the server had already
     * refused on its merits — a document larger than the configured maximum, a malformed request, a
     * database name this account may not touch — was retried on the same escalating schedule as a
     * dropped connection, forever, and the one message that would tell the user what to fix
     * scrolled past between attempts.
     *
     * The line is whether *waiting* is plausibly part of the answer. 5xx says the server failed at
     * something it agreed to do; 408, 425 and 429 say "not now, ask again". Every other 4xx is this
     * device having asked something the server will keep declining.
     *
     * [Blocked] stays terminal for the reason its own doc comment gives, and [Conflict], [NotFound],
     * [Unauthorized] and [MalformedResponse] are inputs to the caller's logic rather than failures
     * to retry blindly.
     */
    val isRetriable: Boolean
        get() = when (this) {
            is Transport -> true
            is Server -> when (status) {
                408, 425, 429 -> true          // timeout, too early, rate limited
                in 500..599 -> true            // the server's own fault, possibly transient
                else -> false                  // 400, 403, 405, 413, … will not improve
            }
            else -> false
        }
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
    /**
     * This device's wall clock, injectable so the skew check can be tested without waiting for one.
     * Read at the moment a response comes back, which is as close to "when the server said that" as
     * a client can get.
     */
    private val nowMs: () -> Long = System::currentTimeMillis,
) {

    private fun path(suffix: String): String = "/$database/$suffix"

    /**
     * How far this device's clock is from the server's, signed, in seconds — positive when this
     * device is ahead — or null when there is nothing worth saying.
     *
     * This matters because every merge decision in the protocol is made on client wall-clock
     * timestamps: §6.2's scalar winners, and §6.4's delete-vs-edit. A device running fast stamps
     * every edit into the future, so its older work defeats the peer's newer work, and a deletion it
     * makes destroys edits the peer had not finished making. Hybrid logical clocks would remove the
     * assumption; they are out of scope. Noticing that the assumption is broken, and saying so, is
     * not.
     *
     * Strictly a warning. Sync carries on: refusing to sync over a wrong clock would turn a
     * recoverable mess into an outage, and the wrong clock is usually the *other* device's anyway.
     *
     * `@Volatile` because a flush and the change-feed loop write it from different threads and the
     * reporting reads it from a third. Only the freshest value matters, so there is nothing to
     * serialize beyond visibility.
     */
    @Volatile
    var clockSkewSeconds: Long? = null
        private set

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
     * caller's id prefix says which shape to decode it as.
     *
     * Only ever null for a tombstone. CouchDB answers `"doc": null` for a row whose document was
     * deleted between the feed reading the sequence and `include_docs` fetching the body, and the
     * engine already synthesizes what a stripped tombstone needs. A *live* row without a body is
     * malformed transport data and is rejected in [parseChanges] instead.
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

        // Strict, because `last_seq` is checkpointed the moment this batch applies. A row dropped
        // here — a proxy that truncated the response, a server answering a shape this client does
        // not know — is a remote change lost *permanently*: the checkpoint moves past it and
        // CouchDB never offers that sequence again. Refusing the whole response costs one retry;
        // accepting a partial one costs the change. This used to be a `mapNotNull`.
        //
        // A document this build cannot *merge* is a different thing and is not rejected here: it is
        // valid transport carrying an unknown schema, and the engine preserves it as a conflict
        // copy and checkpoints it.
        val results = root["results"] as? JsonArray
            ?: throw CouchError.MalformedResponse("_changes carried no results array")
        val rows = results.mapIndexed { index, element ->
            val row = element as? JsonObject
                ?: throw CouchError.MalformedResponse("_changes result $index is not an object")
            val id = row["id"]?.jsonPrimitive?.contentOrNullSafe?.takeIf { it.isNotEmpty() }
                ?: throw CouchError.MalformedResponse("_changes result $index carried no id")
            val rev = row["changes"]?.jsonArray?.firstOrNull()
                ?.jsonObject?.get("rev")?.jsonPrimitive?.contentOrNullSafe
                ?.takeIf { it.isNotEmpty() }
                ?: throw CouchError.MalformedResponse("_changes result $index carried no revision")

            val rawDeleted = row["deleted"]
            val deleted = when {
                rawDeleted == null || rawDeleted is JsonNull -> false
                // `booleanOrNull` also parses the *string* `"true"`, and a numeric 1 is not a
                // Boolean at all. Only an unquoted true/false counts.
                else -> (rawDeleted as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
                    ?: throw CouchError.MalformedResponse(
                        "_changes result $index carried a non-Boolean deleted"
                    )
            }

            val rawDocument = row["doc"]
            val json = when {
                rawDocument is JsonObject -> rawDocument
                // The one body CouchDB legitimately omits: `include_docs` found the document
                // already deleted. The engine synthesizes the tombstone the merge needs from
                // `deleted` and the revision alone, so there is nothing to fetch and nothing to
                // lose.
                deleted && (rawDocument == null || rawDocument is JsonNull) -> null
                else -> throw CouchError.MalformedResponse(
                    "_changes result $index ($id) carried no document"
                )
            }
            ChangeRow(id = id, rev = rev, deleted = deleted, json = json)
        }
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
        val response = try {
            transport.send(request)
        } catch (e: CouchError) {
            throw e
        } catch (e: UnknownServiceException) {
            // Android's network security policy rejects a forbidden URL here, before the socket is
            // opened, and it throws an IOException like any other failure — so without this branch
            // a permanently unusable address is reported as a passing outage.
            throw CouchError.Blocked(e.message ?: e.toString())
        } catch (e: Exception) {
            // OkHttp surfaces "offline", DNS failures and timeouts as thrown IOExceptions rather
            // than statuses. They are all "try again later", never "the document is gone".
            throw CouchError.Transport(e.toString())
        }
        // Before the status is examined, so a 404 or a 409 still tells us what time the server
        // thinks it is — every response carries the header, and the interesting ones are often the
        // failures.
        noteClockSkew(response)
        response
    }

    /**
     * Records the disagreement between this device's clock and the server's, if it is large enough
     * to be worth a word.
     *
     * RFC 9110 makes `Date` mandatory for an origin server with a clock, so this is information that
     * arrives free with every request — no probe, no extra round trip. A header that is missing or
     * will not parse is neither an error nor a warning: it is simply no information, and a proxy
     * that strips it must not make sync look broken.
     *
     * The threshold is deliberately generous. The header has one-second granularity, the round trip
     * is real time that has already passed by the moment the bytes arrive, and a device that is a
     * few seconds out changes no merge outcome that a few seconds of network latency would not have
     * changed anyway. Warning at that scale would be crying wolf, and a warning nobody believes is
     * worse than none.
     *
     * Below the threshold the previous reading is *cleared*, not left standing: a clock that has
     * been corrected should stop being complained about without needing the app restarted.
     */
    private fun noteClockSkew(response: CouchResponse) {
        val header = response.headers.entries
            .firstOrNull { it.key.equals("Date", ignoreCase = true) }
            ?.value ?: return
        val serverMs = parseHttpDate(header) ?: return
        val skewSeconds = (nowMs() - serverMs) / 1_000
        clockSkewSeconds =
            if (kotlin.math.abs(skewSeconds) >= CLOCK_SKEW_THRESHOLD_SECONDS) skewSeconds else null
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
        else -> CouchError.Server(
            response.status,
            path,
            retryAfterMs = parseRetryAfter(
                response.headers.entries
                    .firstOrNull { it.key.equals("Retry-After", ignoreCase = true) }
                    ?.value
            ),
        )
    }

    // endregion

    companion object {
        const val DEFAULT_LONGPOLL_MS = 55_000L

        /**
         * Two minutes. Normative — bopa uses the same number, because two apps that disagree about
         * what counts as a broken clock would warn about the same server on one device and not the
         * other, and the user would rightly trust neither.
         */
        const val CLOCK_SKEW_THRESHOLD_SECONDS = 120L

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
 * An HTTP `Date` header — RFC 1123 / IMF-fixdate — as epoch millis, or null when it will not parse.
 *
 * [DateTimeFormatter.RFC_1123_DATE_TIME] is a thread-safe singleton, unlike `SimpleDateFormat`, and
 * this is called from every response on whichever thread made the request. `WebDAVClient` has the
 * same parser for the same header; it is not shared because it logs through `android.util.Log`, and
 * nothing in the couch package may touch `android.*` — the whole package is exercised by plain JVM
 * tests.
 */
internal fun parseHttpDate(header: String): Long? = try {
    ZonedDateTime.parse(header, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
} catch (_: Exception) {
    null
}

/** The longest a server may push this device out — the same ceiling the controller backs off to. */
const val MAX_RETRY_AFTER_MS = 60_000L

/**
 * `Retry-After` in either shape RFC 9110 §10.2.3 allows: a delay in seconds, or an HTTP date.
 *
 * Clamped rather than trusted. A misconfigured proxy answering `Retry-After: 86400` would otherwise
 * park sync for a day — the server gets to slow this device down, not to switch it off — and a date
 * already in the past means "now", not a negative sleep.
 *
 * [nowMs] is injectable only so the date branch is testable; production always passes the clock.
 */
internal fun parseRetryAfter(header: String?, nowMs: Long = System.currentTimeMillis()): Long? {
    val trimmed = header?.trim().orEmpty()
    if (trimmed.isEmpty()) return null
    val millis = trimmed.toLongOrNull()?.times(1_000)
        ?: parseHttpDate(trimmed)?.minus(nowMs)
        ?: return null
    return millis.coerceIn(0, MAX_RETRY_AFTER_MS)
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
