package com.ethran.notable.sync.couch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `_changes` parsing is strict because its result is checkpointed.
 *
 * `last_seq` is persisted the moment a batch applies, and CouchDB never offers a sequence twice.
 * So a row this parser drops is a remote change lost *permanently* — the checkpoint moves past it
 * and no later pull asks about it again. The parser used to be permissive in four separate ways (a
 * missing `results` became an empty list, a malformed row was skipped by `mapNotNull`, a missing
 * revision became `""`, and a live row without a body was accepted), and every one of them turns a
 * truncating proxy or an unfamiliar server into silent data loss.
 *
 * Refusing the whole response costs one retry. That is the trade this file pins, case for case with
 * bopa's `CouchChangesParsingTests.swift`.
 */
class CouchChangesParsingTest {

    private val client = CouchDbClient(FakeCouchTransport(), database = "notes")

    private fun parse(json: String) = client.parseChanges(json.toByteArray())

    private fun assertMalformed(json: String, message: String) {
        try {
            parse(json)
            throw AssertionError("expected MalformedResponse: $message")
        } catch (e: CouchError.MalformedResponse) {
            // The point of the test.
        }
    }

    // region Rejected

    @Test
    fun a_response_without_results_is_rejected() = assertMalformed(
        """{"last_seq": "5"}""",
        "no results array is not the same fact as an empty one",
    )

    @Test
    fun a_results_property_of_the_wrong_type_is_rejected() = assertMalformed(
        """{"results": {}, "last_seq": "5"}""",
        "results must be an array",
    )

    @Test
    fun a_non_object_row_is_rejected() = assertMalformed(
        """{"results": ["nope"], "last_seq": "5"}""",
        "a row must be an object",
    )

    @Test
    fun a_row_without_an_id_is_rejected() = assertMalformed(
        """{"results": [{"changes": [{"rev": "1-a"}], "doc": {}}], "last_seq": "5"}""",
        "a row with no id names no document",
    )

    @Test
    fun a_row_with_an_empty_id_is_rejected() = assertMalformed(
        """{"results": [{"id": "", "changes": [{"rev": "1-a"}], "doc": {}}], "last_seq": "5"}""",
        "an empty id names no document either",
    )

    @Test
    fun a_row_without_a_revision_is_rejected() = assertMalformed(
        """{"results": [{"id": "notebook:nb1", "doc": {}}], "last_seq": "5"}""",
        "the revision is the base the next push builds on; \"\" is not a revision",
    )

    @Test
    fun a_row_with_an_empty_revision_is_rejected() = assertMalformed(
        """{"results": [{"id": "notebook:nb1", "changes": [{"rev": ""}], "doc": {}}], "last_seq": "5"}""",
        "an empty revision would be recorded as if it were real",
    )

    @Test
    fun a_live_row_without_a_document_is_rejected() = assertMalformed(
        """{"results": [{"id": "notebook:nb1", "changes": [{"rev": "1-a"}]}], "last_seq": "5"}""",
        "include_docs was asked for; a live row without one is a truncated response",
    )

    @Test
    fun a_live_row_with_a_null_document_is_rejected() = assertMalformed(
        """{"results": [{"id": "notebook:nb1", "changes": [{"rev": "1-a"}], "doc": null}], "last_seq": "5"}""",
        "an explicit null body on a live row is no better than a missing one",
    )

    @Test
    fun a_non_boolean_deleted_is_rejected() = assertMalformed(
        """{"results": [{"id": "notebook:nb1", "changes": [{"rev": "1-a"}], "deleted": "yes", "doc": {}}], "last_seq": "5"}""",
        "a deleted flag that is not a Boolean is a server this client does not understand",
    )

    /**
     * `booleanOrNull` parses the *string* `"true"` as well, so a quoted flag would have been read
     * as a deletion by a parser that only asked for the primitive.
     */
    @Test
    fun a_quoted_boolean_deleted_is_rejected() = assertMalformed(
        """{"results": [{"id": "notebook:nb1", "changes": [{"rev": "1-a"}], "deleted": "true", "doc": {}}], "last_seq": "5"}""",
        "a string is not a Boolean, whatever it spells",
    )

    @Test
    fun a_numeric_deleted_is_rejected() = assertMalformed(
        """{"results": [{"id": "notebook:nb1", "changes": [{"rev": "1-a"}], "deleted": 1, "doc": {}}], "last_seq": "5"}""",
        "1 is not true",
    )

    @Test
    fun a_response_without_a_last_seq_is_rejected() = assertMalformed(
        """{"results": []}""",
        "there is no checkpoint to persist",
    )

    // endregion

    // region Accepted

    @Test
    fun an_empty_results_array_is_a_valid_quiet_feed() {
        val changes = parse("""{"results": [], "last_seq": "9"}""")
        assertEquals("9", changes.lastSeq)
        assertTrue(changes.rows.isEmpty())
    }

    /**
     * The one body CouchDB legitimately omits: `include_docs` found the document already deleted.
     * The engine synthesizes what the merge needs from `deleted` and the revision, so rejecting
     * this would break ordinary deletions rather than catch a fault.
     */
    @Test
    fun a_tombstone_may_arrive_without_a_body() {
        val changes = parse(
            """{"results": [{"id": "notebook:nb1", "changes": [{"rev": "2-b"}], "deleted": true, "doc": null}], "last_seq": "9"}"""
        )
        val row = changes.rows.single()
        assertEquals("notebook:nb1", row.id)
        assertEquals("2-b", row.rev)
        assertTrue(row.deleted)
        assertNull(row.json)
    }

    @Test
    fun a_tombstone_may_also_arrive_with_its_body() {
        val changes = parse(
            """{"results": [{"id": "notebook:nb1", "changes": [{"rev": "2-b"}], "deleted": true, "doc": {"_id": "notebook:nb1", "_deleted": true}}], "last_seq": "9"}"""
        )
        val row = changes.rows.single()
        assertTrue(row.deleted)
        assertNotNull(row.json)
    }

    /** CouchDB 1.x reported a number. It is only ever echoed back, so keep the shape it arrived in. */
    @Test
    fun a_numeric_last_seq_is_kept_as_its_text() {
        assertEquals("42", parse("""{"results": [], "last_seq": 42}""").lastSeq)
    }

    @Test
    fun a_valid_row_survives_intact() {
        val changes = parse(
            """{"results": [{"id": "notebook:nb1", "changes": [{"rev": "1-a"}], "doc": {"type": "notebook", "schema": 1}}], "last_seq": "5"}"""
        )
        val row = changes.rows.single()
        assertEquals("notebook:nb1", row.id)
        assertEquals("1-a", row.rev)
        assertFalse(row.deleted)
        assertEquals("notebook", row.json?.get("type")?.toString()?.trim('"'))
    }

    /**
     * The failure has to arrive as a *throw*, not as a short batch: a parser that returned the good
     * rows and dropped the rest would still have the engine checkpoint past the bad one.
     */
    @Test
    fun a_malformed_second_row_rejects_the_whole_response() = assertMalformed(
        """
        {"results": [
          {"id": "notebook:nb1", "changes": [{"rev": "1-a"}], "doc": {"type": "notebook"}},
          {"id": "notebook:nb2", "changes": [{"rev": "1-b"}]}
        ], "last_seq": "7"}
        """.trimIndent(),
        "one bad row poisons the batch — the good row is returned again on the retry",
    )

    // endregion
}
