package com.ethran.notable.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **Weak comparison for reads, no guard at all for writes** — the two properties [ETag] exists to
 * hold in place, both silent when broken.
 *
 * Comparing spellings makes an unchanged page look changed on every sync; re-quoting a `W/`-stripped
 * tag as `If-Match` 412s every write to a weak-ETag server and the notebook never commits.
 */
class ETagTest {

    private fun tag(raw: String) = requireNotNull(ETag.parse(raw))

    // ---- stored × received -> matches? / ifMatchHeader ----

    @Test
    fun strong_matches_strong_and_guards() {
        assertTrue(tag("\"abc\"").matches(tag("\"abc\"")))
        assertEquals("\"abc\"", tag("\"abc\"").ifMatchHeader())
    }

    @Test
    fun strong_stored_matches_weak_received() {
        assertTrue(tag("\"abc\"").matches(tag("W/\"abc\"")))
    }

    @Test
    fun weak_stored_matches_strong_received_but_may_not_guard() {
        assertTrue(tag("W/\"abc\"").matches(tag("\"abc\"")))
        // A weak validator cannot legally guard a state-changing request, so send no If-Match
        // rather than lie about its strength.
        assertNull(tag("W/\"abc\"").ifMatchHeader())
        assertNull(tag("w/\"abc\"").ifMatchHeader())
    }

    @Test
    fun bare_stored_value_matches_and_is_requoted() {
        // A stored row may hold an unquoted value. It must compare equal to the quoted and weak
        // spellings of the same validator, or the next sync re-downloads every page.
        assertTrue(tag("abc").matches(tag("\"abc\"")))
        assertTrue(tag("abc").matches(tag("W/\"abc\"")))
        assertEquals("\"abc\"", tag("abc").ifMatchHeader())
    }

    @Test
    fun different_values_do_not_match() {
        assertFalse(tag("\"abc\"").matches(tag("\"xyz\"")))
        assertFalse(tag("W/\"abc\"").matches(tag("\"xyz\"")))
    }

    // ---- spelling is preserved, not canonicalized ----

    @Test
    fun raw_keeps_the_servers_own_spelling() {
        assertEquals("W/\"abc\"", tag("W/\"abc\"").raw)
        assertEquals("\"abc\"", tag("\"abc\"").raw)
        // Surrounding whitespace is transport noise, not part of the validator.
        assertEquals("W/\"abc\"", tag("  W/\"abc\"  ").raw)
    }

    @Test
    fun isWeak_reads_the_prefix_case_insensitively() {
        assertTrue(tag("W/\"abc\"").isWeak)
        assertTrue(tag("w/\"abc\"").isWeak)
        assertFalse(tag("\"abc\"").isWeak)
        assertFalse(tag("abc").isWeak)
    }

    // ---- conditional reads keep working where writes stop ----

    @Test
    fun ifNoneMatch_is_emitted_for_a_weak_tag() {
        // If-None-Match uses weak comparison, so the weak spelling goes through unchanged. This is
        // the asymmetry the type exists to express: the same tag is usable for a read and not for
        // a write.
        assertEquals("W/\"abc\"", tag("W/\"abc\"").ifNoneMatchHeader())
        assertEquals("\"abc\"", tag("\"abc\"").ifNoneMatchHeader())
        assertEquals("\"abc\"", tag("abc").ifNoneMatchHeader())
    }

    // ---- parse rejects what is not a validator ----

    @Test
    fun parse_returns_null_for_missing_blank_or_empty_valued() {
        assertNull(ETag.parse(null))
        assertNull(ETag.parse(""))
        assertNull(ETag.parse("   "))
        assertNull(ETag.parse("\"\""))
        assertNull(ETag.parse("W/\"\""))
    }

    @Test
    fun parse_returns_null_for_an_unbalanced_quote() {
        // An opaque value may not contain a DQUOTE, so these are garbage rather than validators.
        // Accepted, they would render as `""abc"` in If-Match and fail for an unrecognizable reason.
        assertNull(ETag.parse("\""))
        assertNull(ETag.parse("\"abc"))
        assertNull(ETag.parse("abc\""))
        assertNull(ETag.parse("W/\"abc"))
    }

    @Test
    fun weak_prefix_is_stripped_once_whatever_its_case() {
        // The prefix is read in exactly one place (isWeak), so `opaque` cannot disagree with it —
        // notably, a value that merely *starts* like a second prefix keeps it.
        assertTrue(tag("w/\"abc\"").matches(tag("W/\"abc\"")))
        assertTrue(tag("W/\"w/abc\"").matches(tag("\"w/abc\"")))
    }

    // ---- matches is null-hostile on purpose ----

    @Test
    fun null_never_matches() {
        val known: ETag? = tag("\"abc\"")
        val unknown: ETag? = null
        assertFalse(known.matches(null))
        assertFalse(unknown.matches(known))
        assertFalse(unknown.matches(null))
    }

    // ---- the guard decision, and its reason ----

    @Test
    fun writeGuard_guards_a_strong_tag() {
        val guard = tag("\"abc\"").writeGuard()
        assertEquals(WriteGuard.Guarded("\"abc\""), guard)
    }

    @Test
    fun writeGuard_reports_why_it_cannot_guard() {
        assertEquals(
            WriteGuard.Unguarded(WriteGuard.Reason.WeakTag),
            tag("W/\"abc\"").writeGuard()
        )
        // A server that issues no ETags at all reaches the same place by a different route: there
        // is nothing to guard with, so the manifest MOVE goes out unguarded.
        assertEquals(
            WriteGuard.Unguarded(WriteGuard.Reason.NoStoredTag),
            (null as ETag?).writeGuard()
        )
    }
}
