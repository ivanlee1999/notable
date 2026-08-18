package com.ethran.notable.sync

import com.ethran.notable.sync.couch.CouchSyncController
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant

/**
 * The stamp-time half of protocol §7.1: a measured skew stops being a sentence in the log and
 * starts moving the timestamps this device writes.
 *
 * The merge functions are deliberately not exercised here. They must stay pure — a value that
 * arrived from a peer is never adjusted — so the correction is only ever observable at the moment a
 * stamp is made, which is what these assert.
 */
class SyncClockTest {

    @Before
    fun clearClock() = SyncClock.reset()

    @After
    fun restoreClock() = SyncClock.reset()

    @Test
    fun `an unmeasured clock stamps the system clock unchanged`() {
        val before = System.currentTimeMillis()
        val stamped = SyncClock.nowMs()
        val after = System.currentTimeMillis()

        assertTrue("expected $stamped within [$before, $after]", stamped in before..after)
    }

    @Test
    fun `a device running ahead stamps the corrected time, not its own`() {
        SyncClock.note(3_600)

        val raw = System.currentTimeMillis()
        val stamped = SyncClock.nowMs()

        // An hour ahead of the server means every stamp must come back an hour, or this device
        // wins every comparison against a peer whose clock is right.
        assertEquals(-3_600_000.0, (stamped - raw).toDouble(), 2_000.0)
    }

    @Test
    fun `a device running behind stamps forward by the same measurement`() {
        SyncClock.note(-1_800)

        val raw = System.currentTimeMillis()
        val stamped = SyncClock.nowMs()

        assertEquals(1_800_000.0, (stamped - raw).toDouble(), 2_000.0)
    }

    @Test
    fun `every stamp shape reads the same corrected instant`() {
        SyncClock.note(7_200)

        val ms = SyncClock.nowMs()
        val instant = SyncClock.now()
        val date = SyncClock.nowDate()
        val iso = SyncClock.nowIso()

        // The deletion path stamps ISO, the Room entities stamp Date, the prune horizon does
        // arithmetic on millis. If they disagreed, correcting one would skew it against another.
        assertEquals(ms.toDouble(), instant.toEpochMilli().toDouble(), 2_000.0)
        assertEquals(ms.toDouble(), date.time.toDouble(), 2_000.0)
        assertEquals(ms.toDouble(), Instant.parse(iso).toEpochMilli().toDouble(), 2_000.0)
    }

    @Test
    fun `a clock that has been put right stops being corrected`() {
        SyncClock.note(3_600)
        SyncClock.note(null)

        val raw = System.currentTimeMillis()
        assertEquals(0.0, (SyncClock.nowMs() - raw).toDouble(), 2_000.0)
        assertFalse(SyncClock.isWorthWarningAbout)
    }

    @Test
    fun `the prune horizon moves with the correction`() {
        // The cutoff is compared against instants stamped by this same corrected clock, here and on
        // the peers. Reading the raw clock for the cutoff while stamping corrected instants would
        // take the skew straight off the 30-day horizon — on a device a week fast, that drops
        // tombstones the peer still needs, and erased strokes come back.
        SyncClock.note(7L * 24 * 60 * 60)

        // The horizon itself is `CouchSyncHost.TOMBSTONE_PRUNE_HORIZON_MS`, whose companion is
        // private; its value is beside the point here. What matters is which clock the subtraction
        // starts from, so any horizon shows it.
        val horizonMs = 30L * 24 * 60 * 60 * 1000
        val corrected = SyncClock.nowMs() - horizonMs
        val uncorrected = System.currentTimeMillis() - horizonMs

        assertEquals(
            "a week of skew must come off the cutoff, not off the horizon",
            (-7L * 24 * 60 * 60 * 1000).toDouble(),
            (corrected - uncorrected).toDouble(),
            2_000.0,
        )
    }

    @Test
    fun `a skew worth mentioning is not always worth a banner`() {
        // 120s is the threshold at which a skew is recorded at all, and it is loose enough to catch
        // a slow link. A banner at that scale is one that gets ignored when it matters.
        SyncClock.note(150)
        assertFalse(SyncClock.isWorthWarningAbout)
        assertFalse(CouchSyncController.UiState(clockSkewSeconds = 150).clockSkewNeedsAttention)

        SyncClock.note(600)
        assertTrue(SyncClock.isWorthWarningAbout)
        assertTrue(CouchSyncController.UiState(clockSkewSeconds = 600).clockSkewNeedsAttention)
    }

    @Test
    fun `the banner fires the same distance behind as ahead`() {
        // A clock that is slow destroys just as much as one that is fast — it loses comparisons it
        // should win, so this device's real edits lose to the peer's older ones.
        assertTrue(CouchSyncController.UiState(clockSkewSeconds = -600).clockSkewNeedsAttention)
        assertFalse(CouchSyncController.UiState(clockSkewSeconds = null).clockSkewNeedsAttention)
    }

    @Test
    fun `the warning names the direction and a magnitude someone can act on`() {
        val ahead = CouchSyncController.UiState(clockSkewSeconds = 600).clockSkewWarning
        val behind = CouchSyncController.UiState(clockSkewSeconds = -600).clockSkewWarning

        // "Your clock is wrong" sends someone to the wrong device half the time.
        assertTrue(ahead!!.contains("10 minutes"))
        assertTrue(ahead.contains("ahead of"))
        assertTrue(behind!!.contains("behind"))
    }
}
