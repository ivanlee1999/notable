package com.ethran.notable.sync

import android.net.Uri
import com.ethran.notable.utils.AppResult
import com.ethran.notable.utils.DomainError
import com.ethran.notable.utils.getOrNull
import com.ethran.notable.utils.map
import com.ethran.notable.utils.onError
import com.ethran.notable.utils.onFailure
import io.shipbook.shipbooksdk.Log
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Date
import javax.net.ssl.SSLException

/**
 * A remote WebDAV collection entry with its name, last-modified timestamp, and — for a child
 * collection in a `Depth: 1` listing — that subdirectory's own ETag. The ETag defaults to null so
 * existing metadata/tombstone call sites are unaffected; a null on a notebook directory simply means
 * the bulk fast path falls back to a manifest check for it, never an error.
 */
data class RemoteEntry(val name: String, val lastModified: Date?, val etag: ETag? = null)

/**
 * Result of the collapsed preflight PROPFIND on the sync root — one request that answers "which of
 * the expected directories exist?" and, opportunistically, "what is the server's clock?".
 *
 * @property rootExists false when the root collection itself returned 404 (both children are then
 *   known-absent without any further request).
 * @property childNames decoded names of the root's immediate children, with the root's own entry
 *   removed. Membership answers each directory-existence question.
 * @property serverTimeMs the server clock parsed from the response `Date` header, or null when the
 *   server omitted or corrupted it — the caller then falls back to the dedicated clock-skew HEAD.
 * @property localMidpointMs the local time midway between issuing the request and reading the
 *   response, the best single-sample estimate of "now" to compare against [serverTimeMs].
 */
data class RootProbe(
    val rootExists: Boolean,
    val childNames: Set<String>,
    val serverTimeMs: Long?,
    val localMidpointMs: Long,
)

/**
 * Validate a decoded Depth-1 root listing and remove its mandatory self-entry. A successful HTTP
 * response without the requested collection is not trustworthy WebDAV data (for example, an HTML
 * login page reached through a redirect), so callers must fail closed instead of treating it as an
 * empty root and attempting writes.
 */
internal fun rootChildNames(path: String, decodedNames: List<String>): Set<String>? {
    val selfName = path.trimEnd('/').substringAfterLast('/')
    if (selfName !in decodedNames) return null
    return decodedNames.filter { it.isNotEmpty() && it != selfName }.toSet()
}

/**
 * A fetched body with the validator the server sent for it.
 *
 * [equals]/[hashCode] are hand-written because a `ByteArray` field otherwise compares by reference.
 * The [etag] half compares **raw spelling** — the one place `==` on an [ETag] is right, since this
 * is structural identity of two fetch results rather than the content question [matches] answers.
 * Weak-comparing here would also split `equals` from `hashCode`, which hashes `raw`.
 */
data class DownloadedFile(
    val content: ByteArray, val etag: ETag?
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as DownloadedFile

        if (!content.contentEquals(other.content)) return false
        if (etag != other.etag) return false

        return true
    }

    override fun hashCode(): Int {
        var result = content.contentHashCode()
        result = 31 * result + (etag?.hashCode() ?: 0)
        return result
    }
}

/**
 * Result of a connection test, including optional clock skew information.
 */
data class ConnectionTestResult(val clockSkewMs: Long? = null)

/**
 * WebDAV client built on OkHttp for Notable sync operations.
 */
class WebDAVClient(
    private val serverUrl: String,
    username: String,
    password: String,
    private val client: OkHttpClient
) {
    private val credentials = Credentials.basic(username, password)

    /** Identity of the endpoint this client actually targets; capability gates must match this. */
    internal val serverKey: String = ServerCapabilities.serverKey(serverUrl, username)

    /**
     * Test connection to WebDAV server.
     * Checks server connectivity and detects clock skew.
     * @return AppResult.Success with ConnectionTestResult (includes clock skew info if available),
     *         or AppResult.Error with details.
     */
    fun testConnection(): AppResult<ConnectionTestResult, DomainError> =
        execute("Connection test", {
            Request.Builder().url(serverUrl).head().header("Authorization", credentials).build()
        }) { response ->
            when {
                response.isSuccessful -> {
                    val clockSkewMs =
                        getServerTime().getOrNull()?.let { System.currentTimeMillis() - it }
                    AppResult.Success(ConnectionTestResult(clockSkewMs = clockSkewMs))
                }

                response.code == 401 -> AppResult.Error(DomainError.SyncAuthError)
                else -> AppResult.Error(DomainError.SyncError("Server rejected connection: ${response.code}"))
            }
        }

    /**
     * Get the server's current time from the Date response header (RFC 1123).
     * @return Server time as epoch millis on success, or an Error when the request failed or the
     *         Date header was missing/unparseable.
     */
    fun getServerTime(): AppResult<Long, DomainError> =
        execute("Server time", {
            Request.Builder().url(serverUrl).head().header("Authorization", credentials).build()
        }) { response ->
            if (!response.isSuccessful) {
                AppResult.Error(DomainError.SyncError("Server time HEAD failed: ${response.code}"))
            } else {
                response.header("Date")?.let { parseHttpDate(it) }
                    ?.let { AppResult.Success(it) }
                    ?: AppResult.Error(DomainError.NetworkError("Missing or unparseable Date header"))
            }
        }

    /**
     * Check whether a resource exists on the server.
     *
     * Tri-state: `Success(true)` when present, `Success(false)` on a 404, and `Error` when the
     * check could not be completed (network failure or an unexpected status). Callers must NOT
     * treat "could not determine" as "absent" -- doing so previously let a transient network error
     * trigger an unguarded upload over a possibly-newer remote.
     */
    fun exists(path: String): AppResult<Boolean, DomainError> =
        execute("HEAD", {
            Request.Builder().url(buildUrl(path)).head().header("Authorization", credentials).build()
        }) { response ->
            when {
                response.isSuccessful -> AppResult.Success(true)
                response.code == HttpURLConnection.HTTP_NOT_FOUND -> AppResult.Success(false)
                else -> AppResult.Error(DomainError.SyncError("HEAD failed: ${response.code}"))
            }
        }

    /**
     * Create a WebDAV collection (directory).
     * A 405 is treated as success (collection already exists, per RFC 4918).
     */
    fun createCollection(path: String): AppResult<Unit, DomainError> =
        execute("MKCOL", {
            Request.Builder().url(buildUrl(path)).method("MKCOL", null)
                .header("Authorization", credentials).build()
        }) { response ->
            if (response.isSuccessful || response.code == 405) {
                AppResult.Success(Unit)
            } else {
                AppResult.Error(DomainError.SyncError("MKCOL failed: ${response.code}"))
            }
        }

    /**
     * Upload a file to the WebDAV server.
     * @param path Remote path relative to server URL
     * @param content File content as ByteArray
     * @param contentType MIME type of the content
     * @param ifMatch optional ETag for optimistic concurrency (returns SyncConflict on 412). A
     *   weak tag guards nothing and is silently dropped — see [WriteGuard].
     */
    fun putFile(
        path: String,
        content: ByteArray,
        contentType: String = "application/octet-stream",
        ifMatch: ETag? = null
    ): AppResult<Unit, DomainError> =
        execute("PUT", {
            val requestBody = content.toRequestBody(contentType.toMediaType())
            Request.Builder().url(buildUrl(path)).put(requestBody)
                .header("Authorization", credentials)
                .applyWriteGuard(ifMatch)
                .build()
        }) { response ->
            when {
                response.code == HttpURLConnection.HTTP_PRECON_FAILED ->
                    AppResult.Error(DomainError.SyncConflict)

                response.isSuccessful -> AppResult.Success(Unit)
                else -> AppResult.Error(DomainError.SyncError("PUT failed: ${response.code}"))
            }
        }

    /**
     * Upload a file from local filesystem.
     */
    fun putFile(
        path: String,
        localFile: File,
        contentType: String = "application/octet-stream",
        ifMatch: ETag? = null
    ): AppResult<Unit, DomainError> {
        if (!localFile.exists()) return AppResult.Error(DomainError.SyncError("Local file missing"))
        // Stream the file straight to the socket (okhttp reads it in chunks with a known
        // Content-Length) instead of readBytes() loading the whole file into memory — matters for
        // large serialized pages and images.
        return execute("PUT", {
            val requestBody = localFile.asRequestBody(contentType.toMediaType())
            Request.Builder().url(buildUrl(path)).put(requestBody)
                .header("Authorization", credentials)
                .applyWriteGuard(ifMatch)
                .build()
        }) { response ->
            when {
                response.code == HttpURLConnection.HTTP_PRECON_FAILED ->
                    AppResult.Error(DomainError.SyncConflict)

                response.isSuccessful -> AppResult.Success(Unit)
                else -> AppResult.Error(DomainError.SyncError("PUT failed: ${response.code}"))
            }
        }
    }

    /**
     * Upload a file and return the server's new ETag for it (from the PUT response `ETag` header),
     * or `null` if the server did not send one. Used for the manifest so the notebook's stored ETag
     * matches the just-published version, enabling cheap `If-None-Match` change detection next sync.
     * Returns [DomainError.SyncConflict] on a 412 like [putFile].
     */
    fun putFileReturningEtag(
        path: String,
        content: ByteArray,
        contentType: String = "application/octet-stream",
        ifMatch: ETag? = null
    ): AppResult<ETag?, DomainError> =
        execute("PUT", {
            val requestBody = content.toRequestBody(contentType.toMediaType())
            Request.Builder().url(buildUrl(path)).put(requestBody)
                .header("Authorization", credentials)
                .applyWriteGuard(ifMatch)
                .build()
        }) { response ->
            when {
                response.code == HttpURLConnection.HTTP_PRECON_FAILED ->
                    AppResult.Error(DomainError.SyncConflict)

                response.isSuccessful -> AppResult.Success(response.etag())
                else -> AppResult.Error(DomainError.SyncError("PUT failed: ${response.code}"))
            }
        }

    /**
     * Stream a local file to the server and return its new ETag (or `null` if the server sent none),
     * the streaming counterpart of the `ByteArray` overload above. Used for per-page uploads: the
     * page JSON is streamed from a temp file to bound memory, and the returned ETag is stored in
     * `page_sync_state` so the next sync can skip the page when unchanged. Returns
     * [DomainError.SyncConflict] on a 412 — either the [ifMatch] update guard failing, or, when
     * [createOnly] is set, `If-None-Match: *` rejecting a resource that already exists.
     */
    fun putFileReturningEtag(
        path: String,
        localFile: File,
        contentType: String = "application/octet-stream",
        ifMatch: ETag? = null,
        createOnly: Boolean = false
    ): AppResult<ETag?, DomainError> {
        if (!localFile.exists()) return AppResult.Error(DomainError.SyncError("Local file missing"))
        return execute("PUT", {
            val requestBody = localFile.asRequestBody(contentType.toMediaType())
            Request.Builder().url(buildUrl(path)).put(requestBody)
                .header("Authorization", credentials)
                .applyWritePrecondition(ifMatch, createOnly)
                .build()
        }) { response ->
            when {
                response.code == HttpURLConnection.HTTP_PRECON_FAILED ->
                    AppResult.Error(DomainError.SyncConflict)

                response.isSuccessful -> AppResult.Success(response.etag())
                else -> AppResult.Error(DomainError.SyncError("PUT failed: ${response.code}"))
            }
        }
    }

    fun getFile(path: String): AppResult<ByteArray, DomainError> {
        return getFileWithMetadata(path).map { it.content }
    }

    fun getFileWithMetadata(path: String): AppResult<DownloadedFile, DomainError> =
        execute("GET", {
            Request.Builder().url(buildUrl(path)).get().header("Authorization", credentials).build()
        }) { response ->
            when {
                response.isSuccessful ->
                    AppResult.Success(
                        DownloadedFile(response.body.bytes(), response.etag())
                    )

                response.code == HttpURLConnection.HTTP_NOT_FOUND ->
                    AppResult.Error(DomainError.RemoteMissing(path))

                else -> AppResult.Error(DomainError.SyncError("GET failed: ${response.code}"))
            }
        }

    /**
     * Conditional GET: fetch a file only if its ETag differs from [etag].
     * Returns `Success(null)` when the server replies `304 Not Modified` (the resource is unchanged
     * since we stored [etag]) — a cheap, bodyless "no change" answer that avoids clock math.
     * Otherwise returns the fetched file with its current ETag.
     *
     * Takes a non-null [ETag] because a conditional read with no validator is just a read — the
     * caller should call [getFileWithMetadata] instead of passing null. Unlike the write guards,
     * this accepts a **weak** tag: `If-None-Match` uses weak comparison, which is exactly the
     * "has the content changed" question being asked.
     */
    fun getFileIfNoneMatch(path: String, etag: ETag): AppResult<DownloadedFile?, DomainError> =
        execute("GET", {
            Request.Builder().url(buildUrl(path)).get()
                .header("Authorization", credentials)
                .header("If-None-Match", etag.ifNoneMatchHeader())
                .build()
        }) { response ->
            when {
                response.code == HttpURLConnection.HTTP_NOT_MODIFIED -> AppResult.Success(null)
                response.isSuccessful ->
                    AppResult.Success(
                        DownloadedFile(response.body.bytes(), response.etag())
                    )

                // Same typed signal as getFileWithMetadata so a vanished manifest is handled
                // identically on both the conditional and unconditional fetch paths.
                response.code == HttpURLConnection.HTTP_NOT_FOUND ->
                    AppResult.Error(DomainError.RemoteMissing(path))

                else -> AppResult.Error(DomainError.SyncError("GET failed: ${response.code}"))
            }
        }

    /**
     * Download [path] straight to [localFile], returning the file's ETag (or `null` if the server
     * sent none).
     *
     * Streams the body to disk rather than materialising it in a `ByteArray` — a synced page can
     * hold thousands of strokes, and buffering one whole is the download-side twin of the upload
     * OOM this client already streams around. Writes to a sibling `.part` file and renames on
     * success, so a failed or truncated transfer never leaves a partial file behind: callers treat
     * "the file exists locally" as "already downloaded" and would otherwise cache the fragment
     * forever.
     */
    fun getFile(path: String, localFile: File): AppResult<ETag?, DomainError> =
        execute("GET", {
            Request.Builder().url(buildUrl(path)).get().header("Authorization", credentials).build()
        }) { response ->
            when {
                response.isSuccessful -> {
                    localFile.parentFile?.mkdirs()
                    val partFile = File(localFile.parentFile, "${localFile.name}.part")
                    try {
                        response.body.byteStream().use { input ->
                            partFile.outputStream().buffered().use { output -> input.copyTo(output) }
                        }
                        // Some filesystems refuse a rename onto an existing file; retry via a backup.
                        val moved = partFile.renameTo(localFile) ||
                            replaceViaBackup(partFile, localFile)
                        if (moved) AppResult.Success(response.etag())
                        else AppResult.Error(DomainError.SyncError("Could not store download: $path"))
                    } catch (e: IOException) {
                        AppResult.Error(DomainError.SyncError("Download of $path failed: ${e.message}"))
                    } finally {
                        partFile.delete()
                    }
                }

                response.code == HttpURLConnection.HTTP_NOT_FOUND ->
                    AppResult.Error(DomainError.RemoteMissing(path))

                else -> AppResult.Error(DomainError.SyncError("GET failed: ${response.code}"))
            }
        }

    /**
     * Second attempt at replacing [localFile] with [partFile], for filesystems that refuse a rename
     * onto an existing file.
     *
     * Moves the original aside rather than deleting it, and puts it back if the retry also fails, so
     * a double failure leaves the caller with the file it already had rather than with nothing.
     * Deleting first would strand a caller holding neither copy — and since "the file exists
     * locally" is how callers decide something is already downloaded, that loss stays silent until
     * the next read.
     *
     * @return whether [partFile] now *is* [localFile].
     */
    private fun replaceViaBackup(partFile: File, localFile: File): Boolean {
        val backup = File(localFile.parentFile, "${localFile.name}.bak")
        backup.delete()
        // If the original can't even be moved aside, leave it alone and fail with it intact.
        if (localFile.exists() && !localFile.renameTo(backup)) return false
        if (partFile.renameTo(localFile)) {
            backup.delete()
            return true
        }
        backup.renameTo(localFile) // no-op when there was nothing to move aside
        return false
    }

    /**
     * Move (rename) [from] to [to]. On most servers this is atomic, which is what makes it safe as
     * the final "publish" step for the manifest commit marker.
     *
     * [overwrite] maps to the WebDAV `Overwrite` header. The default `true` replaces any existing
     * destination. `false` sends `Overwrite: F`, so the server rejects the move (412) when the
     * destination already exists — a create-only publish that lets a concurrent create surface as a
     * conflict instead of being clobbered.
     *
     * When [ifMatchDestination] is given and can guard a write, an `If` header makes the server
     * reject the move with 412 if the destination's current ETag differs. A weak tag guards
     * nothing and is dropped, so the caller must not read a 412 as its own conflict without first
     * checking [writeGuard] — as `NotebookSyncService.publishManifest` does.
     *
     * Distinguishes three failures so the caller can react: [DomainError.SyncConflict] on 412 (a
     * real concurrent change, or an existing destination under `Overwrite: F` — do not retry
     * blindly), a `recoverable` [DomainError.SyncError] on 405/501 (server doesn't support MOVE —
     * caller may fall back to a direct PUT), and a plain error otherwise.
     */
    fun move(
        from: String,
        to: String,
        ifMatchDestination: ETag? = null,
        overwrite: Boolean = true
    ): AppResult<Unit, DomainError> =
        execute("MOVE", {
            val destUrl = buildUrl(to)
            Request.Builder().url(buildUrl(from))
                .method("MOVE", null)
                .header("Authorization", credentials)
                .header("Destination", destUrl)
                .header("Overwrite", if (overwrite) "T" else "F")
                .apply {
                    val guard = ifMatchDestination.writeGuard()
                    if (guard is WriteGuard.Guarded) header("If", "<$destUrl> ([${guard.header}])")
                }
                .build()
        }) { response ->
            when {
                response.code == HttpURLConnection.HTTP_PRECON_FAILED ->
                    AppResult.Error(DomainError.SyncConflict)

                response.code == HttpURLConnection.HTTP_BAD_METHOD ||
                        response.code == HttpURLConnection.HTTP_NOT_IMPLEMENTED ->
                    AppResult.Error(
                        DomainError.SyncError("MOVE unsupported: ${response.code}", recoverable = true)
                    )

                response.isSuccessful -> AppResult.Success(Unit)
                else -> AppResult.Error(DomainError.SyncError("MOVE failed: ${response.code}"))
            }
        }

    /**
     * Delete a resource from the WebDAV server.
     * A 404 is treated as success (the resource is already gone).
     */
    fun delete(path: String): AppResult<Unit, DomainError> =
        execute("DELETE", {
            Request.Builder().url(buildUrl(path)).delete().header("Authorization", credentials)
                .build()
        }) { response ->
            if (response.isSuccessful || response.code == HttpURLConnection.HTTP_NOT_FOUND) {
                AppResult.Success(Unit)
            } else {
                AppResult.Error(DomainError.SyncError("DELETE failed: ${response.code}"))
            }
        }

    /**
     * List resources in a collection using PROPFIND.
     * @return List of UUID resource names in the collection
     */
    fun listCollection(path: String): AppResult<List<String>, DomainError> =
        execute("PROPFIND", { propfindRequest(path, PROPFIND_PROPS) }) { response ->
            if (response.isSuccessful) {
                val hrefs = WebDavXml.parseHrefs(response.body.string())
                val kept = hrefs.filter { it != path && !it.endsWith("/$path") }
                    .map { Uri.decode(it.trimEnd('/').substringAfterLast('/')) }
                    .filter { WebDavXml.isValidUuid(it) }
                SyncLogger.i(
                    TAG,
                    "PROPFIND $path: ${hrefs.size} raw href(s) -> ${kept.size} notebook dir(s)"
                )
                AppResult.Success(kept)
            } else {
                AppResult.Error(DomainError.SyncError("PROPFIND failed: ${response.code}"))
            }
        }

    /**
     * List the raw child names of a collection (file names *with* extensions, decoded), excluding
     * the collection's own entry. Unlike [listCollection] this does NOT filter to bare UUIDs, so it
     * can see `{pageId}.json`, image, and background files — used for garbage collection.
     * Returns an empty list when the collection does not exist (404).
     */
    fun listNames(path: String): AppResult<List<String>, DomainError> =
        execute("PROPFIND", { propfindRequest(path, PROPFIND_PROPS) }) { response ->
            when {
                response.code == HttpURLConnection.HTTP_NOT_FOUND -> AppResult.Success(emptyList())
                response.isSuccessful -> {
                    val selfName = path.trimEnd('/').substringAfterLast('/')
                    val names = WebDavXml.parseHrefs(response.body.string())
                        .map { Uri.decode(it.trimEnd('/').substringAfterLast('/')) }
                        .filter { it.isNotEmpty() && it != selfName }
                    AppResult.Success(names)
                }

                else -> AppResult.Error(DomainError.SyncError("PROPFIND failed: ${response.code}"))
            }
        }

    /**
     * List a collection's child files with their ETags, keyed by full (decoded) filename —
     * `{pageId}.json`, image, and background names, *not* bare UUIDs (unlike [listCollection] /
     * [listCollectionWithMetadata], whose `isValidUuid` filter would drop every `.json`). Used by
     * per-page sync to compare each page's remote ETag against the stored one. Returns an
     * empty map when the collection does not exist (404). Entries whose ETag the server omitted map
     * to `null`.
     */
    fun listEtags(path: String): AppResult<Map<String, ETag?>, DomainError> =
        execute("PROPFIND", { propfindRequest(path, PROPFIND_PROPS) }) { response ->
            when {
                response.code == HttpURLConnection.HTTP_NOT_FOUND -> AppResult.Success(emptyMap())
                response.isSuccessful -> {
                    val selfName = path.trimEnd('/').substringAfterLast('/')
                    val map = WebDavXml.parseEntries(response.body.string())
                        .map { Uri.decode(it.href.trimEnd('/').substringAfterLast('/')) to ETag.parse(it.etag) }
                        .filter { (name, _) -> name.isNotEmpty() && name != selfName }
                        .toMap()
                    AppResult.Success(map)
                }

                else -> AppResult.Error(DomainError.SyncError("PROPFIND failed: ${response.code}"))
            }
        }

    /**
     * List resources in a collection with their last-modified timestamps.
     * Used for tombstone-based deletion tracking where we need the server's
     * own timestamp for conflict resolution.
     * @return List of RemoteEntry objects; empty if collection doesn't exist
     */
    fun listCollectionWithMetadata(path: String): AppResult<List<RemoteEntry>, DomainError> =
        execute("PROPFIND", { propfindRequest(path, PROPFIND_PROPS) }) { response ->
            when {
                response.code == HttpURLConnection.HTTP_NOT_FOUND -> AppResult.Success(emptyList())
                response.isSuccessful -> {
                    val entries = WebDavXml.parseEntries(response.body.string())
                    val kept = entries.filter { it.href != path && !it.href.endsWith("/$path") }
                        .mapNotNull { entry ->
                            val name = Uri.decode(entry.href.trimEnd('/').substringAfterLast('/'))
                            if (WebDavXml.isValidUuid(name))
                                RemoteEntry(name, entry.lastModified, ETag.parse(entry.etag))
                            else null
                        }
                    // If these two counts diverge sharply, the server sent plenty but the UUID
                    // filter dropped them (href shape); if both are tiny, the server itself listed
                    // little. That distinction is otherwise invisible from the logs.
                    SyncLogger.i(
                        TAG,
                        "PROPFIND $path: ${entries.size} raw entr(ies) -> ${kept.size} notebook dir(s)"
                    )
                    AppResult.Success(kept)
                }

                else -> AppResult.Error(DomainError.SyncError("PROPFIND failed: ${response.code}"))
            }
        }

    /**
     * Collapsed preflight probe: one `PROPFIND Depth: 1` on the sync root that reports which expected
     * child directories exist and carries the server's `Date` header for a piggybacked clock-skew
     * check. Replaces three sequential existence HEADs (and, when the server sends a usable `Date`,
     * the dedicated clock-skew HEAD) with a single request.
     *
     * A 404 is reported as `rootExists = false` — an expected, non-error outcome that means the whole
     * tree must be created. Every other non-success status stays an [AppResult.Error] (401 as
     * [DomainError.SyncAuthError]); a caller must never read such a failure as "directory missing" and
     * upload over a possibly-populated remote.
     */
    fun probeRoot(path: String): AppResult<RootProbe, DomainError> {
        val startMs = System.currentTimeMillis()
        return execute("PROPFIND", { propfindRequest(path, PROPFIND_PROPS) }) { response ->
            val serverTimeMs = response.header("Date")?.let { parseHttpDate(it) }
            val endMs = System.currentTimeMillis()
            val localMidpointMs = startMs + (endMs - startMs) / 2
            when {
                response.code == HttpURLConnection.HTTP_NOT_FOUND ->
                    AppResult.Success(RootProbe(false, emptySet(), serverTimeMs, localMidpointMs))

                response.isSuccessful -> {
                    val decodedNames = WebDavXml.parseHrefs(response.body.string())
                        .map { Uri.decode(it.trimEnd('/').substringAfterLast('/')) }
                    val childNames = rootChildNames(path, decodedNames)
                        ?: return@execute AppResult.Error(
                            DomainError.SyncError(
                                "Invalid PROPFIND response: sync root entry is missing"
                            )
                        )
                    AppResult.Success(RootProbe(true, childNames, serverTimeMs, localMidpointMs))
                }

                response.code == HttpURLConnection.HTTP_UNAUTHORIZED ->
                    AppResult.Error(DomainError.SyncAuthError)

                else -> AppResult.Error(DomainError.SyncError("PROPFIND failed: ${response.code}"))
            }
        }
    }

    /**
     * The ETag of a single resource or collection, via `PROPFIND Depth: 0`. `Success(null)` when the
     * server omits `getetag` for it — collection ETags are optional in RFC 4918. Used by
     * [CapabilityProbeService] to watch a collection's own ETag move (or not) as its descendants
     * change.
     */
    fun resourceEtag(path: String): AppResult<ETag?, DomainError> =
        execute("PROPFIND", { propfindRequest(path, PROPFIND_PROPS, depth = "0") }) { response ->
            if (response.isSuccessful) {
                val self = WebDavXml.parseEntries(response.body.string()).firstOrNull()
                AppResult.Success(ETag.parse(self?.etag))
            } else {
                AppResult.Error(DomainError.SyncError("PROPFIND failed: ${response.code}"))
            }
        }

    /**
     * Ensure parent directories exist, creating them if necessary.
     * @param path File path (will create parent directories)
     */
    fun ensureParentDirectories(path: String): AppResult<Unit, DomainError> {
        val segments = path.trimStart('/').split('/')
        if (segments.size <= 1) return AppResult.Success(Unit)

        var currentPath = ""
        for (i in 0 until segments.size - 1) {
            currentPath += "/" + segments[i]
            val present = exists(currentPath).onFailure { return AppResult.Error(it) }
            if (!present) {
                createCollection(currentPath).onError { return AppResult.Error(it) }
            }
        }
        return AppResult.Success(Unit)
    }

    /**
     * Issue an authenticated request and map the response, translating any thrown exception into a
     * [DomainError.NetworkError]. The request is built inside the try so that malformed-URL failures
     * are reported as network errors rather than propagating.
     */
    private inline fun <T> execute(
        errorLabel: String,
        buildRequest: () -> Request,
        map: (Response) -> AppResult<T, DomainError>
    ): AppResult<T, DomainError> {
        return try {
            client.newCall(buildRequest()).execute().use { response -> map(response) }
        } catch (e: Exception) {
            // Keep the raw exception in the log for diagnosis; show the user a message that says
            // what went wrong instead of OkHttp's terse "timeout".
            SyncLogger.w(TAG, "$errorLabel failed: ${e.javaClass.simpleName}: ${e.message}")
            AppResult.Error(DomainError.NetworkError(humanizeNetworkError(errorLabel, e)))
        }
    }

    /**
     * Turn a low-level networking exception into a message a user can act on. OkHttp surfaces a bare
     * "timeout" / "Read timed out" and similarly cryptic strings; this names the actual failure and
     * suggests the next step. The technical detail is still logged at the call site above.
     */
    private fun humanizeNetworkError(errorLabel: String, e: Exception): String = when (e) {
        // SocketTimeoutException is a subclass of InterruptedIOException, so it is matched first;
        // OkHttp's overall call timeout throws a bare InterruptedIOException("timeout").
        is SocketTimeoutException, is InterruptedIOException ->
            "The server took too long to respond (timed out). Check your connection and try again."
        is UnknownHostException ->
            "Can't reach the server. Check the server address and your internet connection."
        is ConnectException ->
            "Couldn't connect to the server. It may be offline or blocked by the network."
        is SSLException ->
            "Secure connection to the server failed (TLS/certificate error)."
        is IOException ->
            "Network error while ${errorLabel.lowercase()}: ${e.message ?: "connection interrupted"}."
        else -> e.message ?: "$errorLabel failed"
    }

    /**
     * Apply the `If-Match` precondition for a write, or none at all.
     *
     * The decision — and the reason when there is no guard — belongs to [writeGuard] rather than to
     * each call site. Nothing is logged here; one line per request would be noise. Reporting is the
     * caller's job, and only the manifest commit does it (once per notebook, in
     * [NotebookReconciliationService]), because that is the write where losing the guard costs
     * something. `folders.json` stays quiet: its merge is a union, so an unguarded PUT can only
     * miss a folder created between our GET and our PUT, never destroy one.
     */
    private fun Request.Builder.applyWriteGuard(ifMatch: ETag?): Request.Builder = apply {
        val guard = ifMatch.writeGuard()
        if (guard is WriteGuard.Guarded) header("If-Match", guard.header)
    }

    /**
     * Choose a PUT's precondition. [createOnly] sends `If-None-Match: *` — the write succeeds only if
     * the resource does not yet exist, so a page another device created between our listing and this
     * PUT makes the server return 412 instead of us overwriting it. Otherwise fall back to the
     * [ifMatch] update guard (which is itself dropped for weak/absent tags — see [applyWriteGuard]).
     */
    private fun Request.Builder.applyWritePrecondition(
        ifMatch: ETag?,
        createOnly: Boolean
    ): Request.Builder = apply {
        if (createOnly) header("If-None-Match", "*") else applyWriteGuard(ifMatch)
    }

    private fun propfindRequest(path: String, body: String, depth: String = "1"): Request {
        val requestBody = body.toRequestBody("application/xml".toMediaType())
        return Request.Builder().url(buildUrl(path)).method("PROPFIND", requestBody)
            .header("Authorization", credentials).header("Depth", depth).build()
    }

    /**
     * The validator a response advertises, falling back to Nextcloud/ownCloud's `OC-ETag` when the
     * standard `ETag` header is absent -- some setups send only the OC- variant (e.g. behind a proxy
     * that strips `ETag`, or on chunked uploads). Both carry the same opaque value there, so either
     * is a valid anchor for a later If-Match/If-None-Match.
     */
    private fun Response.etag(): ETag? = ETag.parse(header("ETag") ?: header("OC-ETag"))

    /**
     * Build full URL from server URL and path.
     */
    private fun buildUrl(path: String): String {
        val normalizedServer = serverUrl.trimEnd('/')
        // Percent-encode each path segment individually (keeping the '/' separators) so that
        // image/background filenames with spaces or reserved characters produce valid URLs.
        val encodedPath = path.trim('/').split('/')
            .filter { it.isNotEmpty() }
            .joinToString("/") { Uri.encode(it) }
        return "$normalizedServer/$encodedPath"
    }

    companion object {
        private const val TAG = "WebDAVClient"

        // Enumerate the props we actually read rather than asking for <allprop/>. Nextcloud/ownCloud
        // do NOT return <getetag> (among others) for an <allprop/> request -- the ETag is only sent
        // when it is named explicitly in a <prop>. Relying on allprop left resourceEtag/listEtags
        // reading a null validator on those servers, so every commit degraded to unguarded
        // last-writer-wins ("server issued no ETag") even though the server does issue ETags.
        private val PROPFIND_PROPS = """
            <?xml version="1.0" encoding="utf-8"?>
            <D:propfind xmlns:D="DAV:">
                <D:prop>
                    <D:getetag/>
                    <D:getlastmodified/>
                    <D:resourcetype/>
                </D:prop>
            </D:propfind>
        """.trimIndent()

        /**
         * Parse an HTTP Date header (RFC 1123 format) to epoch millis.
         * Uses the thread-safe [DateTimeFormatter.RFC_1123_DATE_TIME] singleton (safe to reuse
         * across concurrent parses, unlike [java.text.SimpleDateFormat]).
         * @return Epoch millis or null if unparseable
         */
        fun parseHttpDate(dateHeader: String): Long? {
            return try {
                ZonedDateTime.parse(dateHeader, DateTimeFormatter.RFC_1123_DATE_TIME)
                    .toInstant().toEpochMilli()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse HTTP date: ${e.message}", e)
                null
            }
        }
    }
}
