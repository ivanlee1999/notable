package com.ethran.notable.sync.couch

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * Every merge decision in the protocol is made on client wall-clock timestamps — §6.2's scalar
 * winners, §6.4's delete-vs-edit — and neither app has ever checked whether those clocks agree. A
 * device running fast stamps its edits into the future, so its older work beats the peer's newer
 * work, and a deletion it makes destroys edits the peer had not finished making.
 *
 * Hybrid logical clocks would remove the assumption and are out of scope. Noticing that the
 * assumption is broken is not, and it costs nothing: RFC 9110 makes `Date` mandatory on every
 * response an origin server sends. bopa implements the identical rule with the identical threshold,
 * because two apps that disagreed about what counts as a broken clock would warn on one device and
 * not the other, and the user would rightly trust neither.
 */
class ClockSkewTest {

    private lateinit var server: FakeCouchTransport
    private lateinit var store: FakeLocalStore

    /** A fixed "local now", so the test is about the header rather than about the machine. */
    private val localNow = Instant.parse("2026-08-12T10:00:00Z")

    @Before
    fun setUp() {
        server = FakeCouchTransport()
        store = FakeLocalStore()
    }

    private fun client() =
        CouchDbClient(server, database = "notes", nowMs = { localNow.toEpochMilli() })

    private fun engine(client: CouchDbClient) =
        CouchSyncEngine(client, store, deviceId = "boox")

    /** What a server puts in `Date`: RFC 1123 / IMF-fixdate, in GMT. */
    private fun httpDate(at: Instant): String = DateTimeFormatter.RFC_1123_DATE_TIME
        .format(ZonedDateTime.ofInstant(at, ZoneOffset.UTC))

    /** Serves [serverTime] as the server's clock and runs one pull, returning the skew reported. */
    private fun skewAfterPull(dateHeader: String?): Long? {
        server.dateHeader = dateHeader
        val client = client()
        val report = runBlocking { engine(client).pull() }
        assertEquals(
            "the reported skew must be what the client recorded",
            client.clockSkewSeconds,
            report.clockSkewSeconds,
        )
        return report.clockSkewSeconds
    }

    private fun skewWithServerAt(offsetSeconds: Long): Long? =
        skewAfterPull(httpDate(localNow.plusSeconds(offsetSeconds)))

    /**
     * The dangerous direction. A device ahead of the server stamps every edit into the future, so
     * its stale copy wins comparisons it should lose — and a deletion it makes outranks work the
     * peer did afterwards.
     */
    @Test
    fun a_clock_running_ahead_is_reported_as_ahead() {
        // The server's clock reads ten minutes earlier than ours, so we are ten minutes ahead.
        assertEquals(600L, skewWithServerAt(-600))
    }

    @Test
    fun a_clock_running_behind_is_reported_as_behind() {
        assertEquals(-600L, skewWithServerAt(600))
    }

    /**
     * The header has one-second granularity and the round trip is real time that has already
     * elapsed by the moment the bytes arrive. A threshold tight enough to catch a few seconds would
     * fire on a perfectly healthy pair of clocks, and a warning nobody believes is worse than none.
     */
    @Test
    fun a_small_difference_is_not_worth_saying() {
        assertNull(skewWithServerAt(-30))
        assertNull(skewWithServerAt(30))
        // Right up to the line: 119 seconds is still silence, 120 is not.
        assertNull(skewWithServerAt(-119))
        assertEquals(120L, skewWithServerAt(-120))
    }

    /**
     * A proxy that strips the header, or a server that writes it in some other format, is not a
     * broken clock — it is no information. Reporting it as skew would be inventing a fault.
     */
    @Test
    fun a_missing_or_unreadable_date_header_says_nothing() {
        assertNull("no header at all", skewAfterPull(null))
        assertNull("not a date", skewAfterPull("some time on Thursday"))
        assertNull("the wrong format", skewAfterPull("2026-08-12T10:00:00Z"))
        assertNull("empty", skewAfterPull(""))
    }

    /** A clock that has been corrected must stop being complained about. */
    @Test
    fun a_corrected_clock_clears_the_warning() {
        val client = client()
        val engine = engine(client)

        server.dateHeader = httpDate(localNow.minusSeconds(600))
        runBlocking { engine.pull() }
        assertNotNull(client.clockSkewSeconds)

        server.dateHeader = httpDate(localNow)
        runBlocking { engine.pull() }
        assertNull("the reading should not stick around after it stops being true", client.clockSkewSeconds)
    }

    /**
     * The whole point of making it a warning. Skew is detected on the same responses that carry the
     * work, so if it could fail a sync it would fail the sync that was about to succeed.
     */
    @Test
    fun significant_skew_never_stops_a_sync() = runBlocking {
        server.dateHeader = httpDate(localNow.minusSeconds(3_600))
        val pageId = CouchDocId.page("p1")
        store.set(
            pageId,
            CouchDocBody.Page(
                CouchPage(
                    notebookId = "nb1",
                    createdAt = localNow.toString(),
                    updatedAt = localNow.toString(),
                    updatedBy = "boox",
                )
            )
        )
        val engine = engine(client())
        engine.markDirty(listOf(pageId))

        val flush = engine.flush()

        assertEquals(listOf(pageId), flush.pushed)
        assertTrue(flush.failures.isEmpty())
        assertEquals(3_600L, flush.clockSkewSeconds)
    }

    // region What the user is told

    private fun warningFor(skewSeconds: Long?): String? =
        CouchSyncController.UiState(clockSkewSeconds = skewSeconds).clockSkewWarning

    /**
     * "Your clock is wrong" sends half the people who read it to the wrong device. The direction
     * and the rough size are what make it something to act on.
     */
    @Test
    fun the_warning_says_which_way_and_roughly_how_far() {
        val ahead = warningFor(600)
        assertNotNull(ahead)
        assertTrue("should name the direction: $ahead", ahead!!.contains("ahead of"))
        assertTrue("should give a magnitude: $ahead", ahead.contains("10 minute"))

        val behind = warningFor(-600)!!
        assertTrue("should name the direction: $behind", behind.contains("behind"))

        assertNull("nothing worth saying means nothing said", warningFor(null))
    }

    /** It rides alongside the ordinary caption rather than replacing it: sync still worked. */
    @Test
    fun the_warning_does_not_displace_the_status_line() {
        val state = CouchSyncController.UiState(pendingCount = 3, clockSkewSeconds = 600)
        val detail = state.detail!!
        assertTrue("the queue is still the news: $detail", detail.contains("3 waiting to sync"))
        assertTrue("and the clock is said too: $detail", detail.contains("ahead of"))
        assertTrue(
            "it is not a failure, so nothing may report it as one",
            state.status is CouchSyncController.Status.Idle && state.lastError == null,
        )
    }

    // endregion
}
