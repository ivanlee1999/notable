package com.ethran.notable.sync.couch

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * How the change feed is *asked for*, which turned out to decide whether this device can sync at all.
 *
 * CouchDB treats `heartbeat` as overriding `timeout`: given both it holds the connection until
 * something changes, however long that takes, and its keep-alive bytes reset the client's read
 * timeout in turn. The engine holds its lock across the whole pull, so a quiet server meant nothing
 * could ever be pushed again — the device went silent until something happened to change on the
 * server. These are cheap assertions on a request, guarding a failure that is very expensive to
 * notice in the field.
 */
class ChangesFeedRequestTest {

    /** Records the request and returns an empty, well-formed `_changes` body. */
    private class RecordingTransport : CouchTransport {
        var last: CouchRequest? = null

        override fun send(request: CouchRequest): CouchResponse {
            last = request
            return CouchResponse(
                status = 200,
                headers = emptyMap(),
                body = """{"results":[],"last_seq":"7"}""".toByteArray(),
            )
        }
    }

    private fun requestFor(longpoll: Boolean): CouchRequest {
        val transport = RecordingTransport()
        val client = CouchDbClient(transport, database = "notes")
        runBlocking { client.changes(since = "3", longpoll = longpoll) }
        return assertNotNull(transport.last).let { transport.last!! }
    }

    private fun CouchRequest.param(name: String): String? =
        query.firstOrNull { it.name == name }?.value

    @Test
    fun `a long poll never asks for a heartbeat`() {
        val request = requestFor(longpoll = true)

        assertNull(
            "heartbeat makes CouchDB ignore timeout and hold the connection indefinitely",
            request.param("heartbeat")
        )
    }

    @Test
    fun `a long poll asks the server to return after the window`() {
        val request = requestFor(longpoll = true)

        assertEquals("longpoll", request.param("feed"))
        assertEquals(CouchDbClient.DEFAULT_LONGPOLL_MS.toString(), request.param("timeout"))
    }

    /**
     * A read timeout can be reset by any byte arriving; a call deadline cannot. If a server ever
     * streams keep-alive bytes regardless, this is what still ends the call.
     */
    @Test
    fun `a long poll is bounded by a whole-call deadline, not only a read timeout`() {
        val request = requestFor(longpoll = true)

        val readTimeout = assertNotNull(request.readTimeoutMs).let { request.readTimeoutMs!! }
        val callTimeout = assertNotNull(request.callTimeoutMs).let { request.callTimeoutMs!! }
        assertEquals(
            "the call deadline must not be shorter than the read timeout",
            readTimeout,
            callTimeout
        )
        assertEquals(
            "and both must outlast the window we asked the server to hold",
            true,
            callTimeout > CouchDbClient.DEFAULT_LONGPOLL_MS
        )
    }

    /** The catch-up feed is a plain request: no window, and no timeouts of its own to wait out. */
    @Test
    fun `a normal feed carries no timeout at all`() {
        val request = requestFor(longpoll = false)

        assertEquals("normal", request.param("feed"))
        assertNull(request.param("timeout"))
        assertNull(request.param("heartbeat"))
        assertNull(request.readTimeoutMs)
        assertNull(request.callTimeoutMs)
    }
}
