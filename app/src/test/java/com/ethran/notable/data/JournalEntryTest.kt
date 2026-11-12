package com.ethran.notable.data

import com.ethran.notable.data.db.Kv
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

class JournalEntryTest {
    @Test
    fun captureDateUsesCaptureZoneRatherThanUtc() {
        val time = Instant.parse("2026-11-12T01:30:00Z").toEpochMilli()
        assertEquals("2026-11-11", journalDate(time, ZoneId.of("America/Los_Angeles")))
        assertEquals("2026-11-12", journalDate(time, ZoneId.of("Asia/Tokyo")))
    }

    @Test
    fun topicsReuseExistingDisplayNamesAndRemoveCaseInsensitiveDuplicates() {
        assertEquals(listOf("Product Design", "Alex"), normalizeTopics(
            listOf(" #product   design ", "PRODUCT DESIGN", "Alex", " ", "#"),
            listOf("Product Design"),
        ))
    }

    @Test
    fun topicIdentityDoesNotDependOnDeviceLocale() {
        val before = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            assertEquals(listOf("INK"), normalizeTopics(listOf("INK", "ink")))
        } finally {
            Locale.setDefault(before)
        }
    }

    @Test
    fun metadataRetainsCaptureDayWhenReadInAnotherTimezone() {
        val entry = JournalEntry("note", "2026-11-11", 1794447000000, listOf("Product"))
        assertEquals(entry, decodeJournalEntry(Kv("journal.entry.note", Json.encodeToString(entry))))
    }

    @Test(expected = IllegalStateException::class)
    fun mismatchedMetadataIdentityIsNotSilentlyAccepted() {
        val entry = JournalEntry("other", "2026-11-11", 1)
        decodeJournalEntry(Kv("journal.entry.note", Json.encodeToString(entry)))
    }

    @Test(expected = kotlinx.serialization.SerializationException::class)
    fun corruptMetadataIsNotReplacedWithAnEmptyJournal() {
        decodeJournalEntry(Kv("journal.entry.note", "not json"))
    }
}
