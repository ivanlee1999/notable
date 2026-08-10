package com.ethran.notable.sync.couch

import okhttp3.Credentials
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * The one HTTP call [CouchDbClient] needs, behind an interface.
 *
 * The indirection exists so the engine's tests can drive an in-memory CouchDB
 * ([com.ethran.notable.sync.couch.FakeCouchTransport] in the test sources) without pulling
 * MockWebServer into the build. bopa's `HTTPTransport` protocol is the twin of this type.
 *
 * Nothing in this file may touch `android.*`: the couch package is exercised by plain JVM unit
 * tests, and the integration suite drives [OkHttpCouchTransport] against a real server from the
 * same JVM.
 */
interface CouchTransport {
    /** Blocking. Callers dispatch it off the main thread; [CouchDbClient] does that for them. */
    fun send(request: CouchRequest): CouchResponse
}

/** An ordered query item. A list rather than a map so requests are reproducible in tests. */
data class CouchQueryItem(val name: String, val value: String)

/**
 * A request against the CouchDB HTTP API.
 *
 * Not a `data class`: [body] is a `ByteArray`, whose generated `equals` would compare by
 * reference and quietly lie. Nothing needs to compare requests.
 *
 * @property path server-absolute, resolved against the transport's base URL (`/notes/page:abc`).
 * @property readTimeoutMs per-call read timeout, or null for the shared client's. Set only by the
 *   longpoll path, which must outlast the window it asked the server to hold the connection open.
 */
class CouchRequest(
    val method: String,
    val path: String,
    val query: List<CouchQueryItem> = emptyList(),
    val headers: Map<String, String> = emptyMap(),
    val body: ByteArray? = null,
    val readTimeoutMs: Long? = null,
)

/** A response. Same `ByteArray` caveat as [CouchRequest]. */
class CouchResponse(
    val status: Int,
    val headers: Map<String, String> = emptyMap(),
    val body: ByteArray = ByteArray(0),
)

/**
 * OkHttp-backed transport with HTTP Basic auth, sharing the app's connection pool.
 *
 * A longpoll asks CouchDB to hold the connection for up to `timeout` milliseconds, so a client
 * whose read timeout is *below* that window aborts the request the server is still legitimately
 * holding — the near-real-time path then looks like a flapping network. The fix is a per-call
 * client built with [OkHttpClient.newBuilder], not a raised timeout on the shared one: every other
 * sync request should still fail fast.
 */
class OkHttpCouchTransport(
    baseUrl: String,
    username: String?,
    password: String?,
    private val client: OkHttpClient,
) : CouchTransport {

    private val base = baseUrl.trimEnd('/').toHttpUrl()

    private val authorization: String? =
        if (username != null && password != null) Credentials.basic(username, password) else null

    override fun send(request: CouchRequest): CouchResponse {
        val url = base.newBuilder()
            .apply {
                for (segment in request.path.trim('/').split('/')) {
                    if (segment.isNotEmpty()) addPathSegment(segment)
                }
                for (item in request.query) addQueryParameter(item.name, item.value)
            }
            .build()

        val contentType = request.headers.entries
            .firstOrNull { it.key.equals("Content-Type", ignoreCase = true) }
            ?.value?.toMediaTypeOrNull()
        val requestBody = request.body?.toRequestBody(contentType)
            // OkHttp requires a body for PUT/POST even when there is nothing to send.
            ?: if (request.method == "PUT" || request.method == "POST") {
                ByteArray(0).toRequestBody(contentType)
            } else {
                null
            }

        val builder = Request.Builder().url(url).method(request.method, requestBody)
        for ((name, value) in request.headers) builder.header(name, value)
        authorization?.let { builder.header("Authorization", it) }

        val callClient = request.readTimeoutMs
            ?.let { client.newBuilder().readTimeout(it, TimeUnit.MILLISECONDS).build() }
            ?: client

        return callClient.newCall(builder.build()).execute().use { response ->
            CouchResponse(
                status = response.code,
                headers = response.headers.toMultimap()
                    .mapValues { (_, values) -> values.firstOrNull() ?: "" },
                body = response.body.bytes(),
            )
        }
    }
}
