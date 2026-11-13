package com.ethran.notable.ui.components

import org.junit.Assert.*
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

class JournalCalendarTest {
    private data class Entry(val id: String, val day: String)
    private val entries = listOf(Entry("a", "2026-11-12"), Entry("b", "2026-11-12"),
        Entry("c", "2026-11-12"), Entry("d", "2026-11-11"))

    @Test fun jumpsUseRenderedGridRowsAndExpandedCards() {
        fun index(columns: Int, expanded: String?) = journalDays(entries, columns, expanded,
            Entry::day, Entry::id).last().index
        assertEquals(4, index(1, null))
        assertEquals(3, index(2, null))
        assertEquals(4, index(2, "b"))
    }

    @Test fun selectingAnEmptyDayAddsOnlyAnAnchorAndKeepsEveryEntry() {
        val days = journalDays(entries, 2, null, Entry::day, Entry::id, "2026-11-10")
        assertEquals(listOf("2026-11-12", "2026-11-11", "2026-11-10"), days.map { it.date })
        assertEquals(entries, days.flatMap { it.rows.flatten() })
        assertTrue(days.last().rows.isEmpty())
        assertEquals(5, days.last().index)
    }

    @Test fun leapMonthAndLocaleWeekStartHaveCompleteAlignedWeeks() {
        val month = YearMonth.of(2024, 2)
        val monday = calendarDays(month, DayOfWeek.MONDAY)
        val sunday = calendarDays(month, DayOfWeek.SUNDAY)
        assertEquals(3, monday.indexOf(LocalDate.of(2024, 2, 1)))
        assertEquals(4, sunday.indexOf(LocalDate.of(2024, 2, 1)))
        assertEquals(29, monday.filterNotNull().size)
        assertEquals(0, monday.size % 7)
        assertEquals(0, sunday.size % 7)
        assertEquals(LocalDate.of(2024, 2, 29), monday.filterNotNull().last())
    }

    @Test fun pageBrowsingStopsAtBoundariesAndPreservesIdentity() {
        val pages = listOf("one", "two", "three")
        assertNull(previewPage(pages, "one", -1))
        assertNull(previewPage(pages, "three", 1))
        assertNull(previewPage(pages, "removed", 1))
        assertEquals("three", previewPage(pages, "two", 1))
        assertEquals("two", previewPage(listOf("three", "one", "two"), "one", 1))
    }
}
