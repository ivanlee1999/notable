package com.ethran.notable.sync.couch

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which failures are worth trying again, and when.
 *
 * Every [CouchError.Server] status used to answer "retriable", so a request the server had already
 * refused on its merits — a document over the configured maximum, a malformed request, a database
 * this account may not write to — was retried on the same escalating schedule as a dropped
 * connection, forever. The user saw a sync that never settled and never explained itself.
 *
 * The line is whether *waiting* is plausibly part of the answer. Mirrors bopa's
 * `CouchRetryClassificationTests.swift`.
 */
class CouchRetryClassificationTest {

    private fun server(status: Int, retryAfterMs: Long? = null) =
        CouchError.Server(status, "/notes/doc", retryAfterMs)

    // region Classification

    @Test
    fun transient_statuses_are_worth_retrying() {
        for (status in listOf(408, 425, 429, 500, 502, 503, 504, 599)) {
            assertTrue(
                "$status says come back later, or is the server's own fault",
                server(status).isRetriable,
            )
        }
    }

    @Test
    fun terminal_statuses_are_not() {
        // 400 malformed, 405 wrong method, 412 precondition, 413 too large, 415 wrong type,
        // 422 unprocessable, 431 headers too large. None improves by asking again.
        for (status in listOf(400, 402, 405, 406, 410, 412, 413, 415, 422, 431)) {
            assertFalse(
                "$status will be refused again on the same terms",
                server(status).isRetriable,
            )
        }
    }

    @Test
    fun a_transport_failure_is_always_worth_retrying() {
        assertTrue(CouchError.Transport("offline").isRetriable)
    }

    @Test
    fun the_errors_with_their_own_handling_are_not_blindly_retried() {
        assertFalse("a wrong password does not age out", CouchError.Unauthorized.isRetriable)
        assertFalse("the merge loop owns this", CouchError.Conflict("d").isRetriable)
        assertFalse(CouchError.NotFound("/notes/d").isRetriable)
        assertFalse(CouchError.MalformedResponse("bad").isRetriable)
        assertFalse(
            "no socket was ever opened; only a different URL fixes it",
            CouchError.Blocked("cleartext").isRetriable,
        )
    }

    // endregion

    // region Retry-After

    @Test
    fun a_delay_in_seconds_is_read() {
        assertEquals(30_000L, parseRetryAfter("30"))
    }

    @Test
    fun an_http_date_is_read_as_the_interval_until_it() {
        val now = 1_770_000_000_000L
        // RFC 1123, 20 seconds after `now`.
        val header = java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME.format(
            java.time.Instant.ofEpochMilli(now + 20_000).atZone(java.time.ZoneOffset.UTC)
        )
        assertEquals(20_000L, parseRetryAfter(header, nowMs = now))
    }

    /**
     * The server gets to slow this device down, not to switch it off: a misconfigured proxy
     * answering with a day would otherwise park sync until the app was relaunched.
     */
    @Test
    fun an_absurd_delay_is_clamped_to_the_ceiling() {
        assertEquals(MAX_RETRY_AFTER_MS, parseRetryAfter("86400"))
    }

    /** A date that has already passed means "now", not a negative sleep. */
    @Test
    fun a_past_date_is_clamped_to_zero() {
        val now = 1_770_000_000_000L
        val header = java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME.format(
            java.time.Instant.ofEpochMilli(now - 3_600_000).atZone(java.time.ZoneOffset.UTC)
        )
        assertEquals(0L, parseRetryAfter(header, nowMs = now))
    }

    @Test
    fun an_unparsable_or_absent_header_is_simply_no_answer() {
        assertNull(parseRetryAfter(null))
        assertNull(parseRetryAfter(""))
        assertNull(parseRetryAfter("   "))
        assertNull(parseRetryAfter("soon please"))
    }

    @Test
    fun the_header_reaches_the_error_the_caller_sees(): Unit = runBlocking {
        val transport = object : CouchTransport {
            override fun send(request: CouchRequest) = CouchResponse(
                status = 429,
                headers = mapOf("Retry-After" to "12"),
                body = ByteArray(0),
            )
        }
        val client = CouchDbClient(transport, database = "notes")

        try {
            client.changes(since = "0", longpoll = false)
            throw AssertionError("a 429 should have thrown")
        } catch (e: CouchError.Server) {
            assertTrue(e.isRetriable)
            assertEquals(12_000L, e.retryAfterMs)
        }
    }

    @Test
    fun an_absent_header_leaves_no_retry_after() {
        assertNull("absent header, not a defaulted one", server(503).retryAfterMs)
    }

    // endregion
}
