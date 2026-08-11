package com.ethran.notable.sync

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncLoggerTest {

    @After
    fun resetVerbosity() {
        SyncLogger.verbose = false
    }

    @Test
    fun clear_resets_logs() {
        SyncLogger.clear()
        SyncLogger.i("sync", "first")
        assertTrue(SyncLogger.logs.value.isNotEmpty())

        SyncLogger.clear()

        assertTrue(SyncLogger.logs.value.isEmpty())
    }

    @Test
    fun log_capped_to_max_entries_and_preserves_tail() {
        SyncLogger.clear()

        val overflow = 5
        for (i in 0 until MAX_LOGS + overflow) {
            SyncLogger.i("sync", "message-$i")
        }

        val logs = SyncLogger.logs.value
        assertEquals(MAX_LOGS, logs.size)
        assertEquals("message-$overflow", logs.first().message)
        assertEquals("message-${MAX_LOGS + overflow - 1}", logs.last().message)
    }

    @Test
    fun log_levels_are_recorded_in_order() {
        SyncLogger.clear()

        SyncLogger.i("sync", "info")
        SyncLogger.w("sync", "warn")
        SyncLogger.e("sync", "error")

        val logs = SyncLogger.logs.value
        assertEquals(3, logs.size)
        assertEquals(SyncLogger.LogLevel.INFO, logs[0].level)
        assertEquals(SyncLogger.LogLevel.WARNING, logs[1].level)
        assertEquals(SyncLogger.LogLevel.ERROR, logs[2].level)
    }

    @Test
    fun debug_entries_are_dropped_unless_verbose() {
        SyncLogger.clear()
        SyncLogger.verbose = false

        SyncLogger.d("sync", "detail")

        assertTrue(SyncLogger.logs.value.isEmpty())
    }

    @Test
    fun debug_entries_are_recorded_when_verbose() {
        SyncLogger.clear()
        SyncLogger.verbose = true

        SyncLogger.d("sync", "detail")

        val logs = SyncLogger.logs.value
        assertEquals(1, logs.size)
        assertEquals(SyncLogger.LogLevel.DEBUG, logs[0].level)
        assertEquals("detail", logs[0].message)
    }

    @Test
    fun run_marker_is_logged_with_its_label() {
        SyncLogger.clear()

        SyncLogger.beginRun("periodic sync")

        val entry = SyncLogger.logs.value.single()
        assertTrue(entry.message.contains("periodic sync"))
    }

    /**
     * Without persistence installed (unit tests, and any code path before `install`) the dump still
     * has to produce the buffer rather than an empty string — "Copy Log" must never hand back nothing
     * while lines are visibly on screen.
     */
    @Test
    fun dump_falls_back_to_the_in_memory_buffer() {
        SyncLogger.clear()
        SyncLogger.i("sync", "uploaded Diary")

        val dumped = SyncLogger.dump()

        assertTrue(dumped, dumped.contains("uploaded Diary"))
        assertTrue(dumped, dumped.contains("I/sync"))
    }

    /** Entries logged by this process are live, not restored history, so they render undimmed. */
    @Test
    fun entries_from_this_session_are_not_marked_restored() {
        SyncLogger.clear()
        SyncLogger.i("sync", "live")

        assertFalse(SyncLogger.logs.value.single().isRestored)
    }

    /**
     * A restored line whose own text is unparsable still came from disk. Deriving that from its
     * timestamp got it wrong — a parse failure leaves no timestamp, so the line rendered as if this
     * session had just produced it.
     */
    @Test
    fun a_restored_line_with_an_unreadable_timestamp_is_still_history() {
        val garbled = SyncLogger.parseFileLineForTest("not a log line at all")

        assertTrue(garbled.isRestored)
        assertEquals(0L, garbled.atMillis)
        assertEquals("not a log line at all", garbled.message)
    }

    /** A well-formed line round-trips through the file format, and is marked as history. */
    @Test
    fun a_persisted_line_parses_back_to_its_entry() {
        val parsed =
            SyncLogger.parseFileLineForTest("2026-08-11 14:30:02 W/SyncWorker: no network")

        assertEquals(SyncLogger.LogLevel.WARNING, parsed.level)
        assertEquals("SyncWorker", parsed.tag)
        assertEquals("no network", parsed.message)
        assertTrue(parsed.isRestored)
        assertTrue(parsed.atMillis > 0L)
    }

    private companion object {
        /** Mirrors SyncLogger.MAX_LOGS, which is private. */
        const val MAX_LOGS = 400
    }
}
