package com.ethran.notable.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests the pure verdict [evaluate] — the capability decision — without touching the network.
 * The probe's I/O is exercised by hand against a real server (see `webdav-capability-probe.sh`).
 */
class CapabilityProbeServiceTest {

    private fun tag(v: String): ETag = ETag.parse("\"$v\"")!!

    /** A sequence of distinct ETags at each step: the Nextcloud-class "everything propagates" case. */
    private fun allMoved() = ProbeObservations(
        putEtag = tag("put"),
        baseline = tag("b0"),
        afterOverwrite = tag("b1"),
        afterMove = tag("b2"),
        afterDeepCreate = tag("b3"),
        afterDeepOverwrite = tag("b4"),
        moveOk = true,
        tmpEtag = tag("tmp"),
        destEtagAfterMove = tag("tmp"),
    )

    @Test
    fun propagates_true_when_all_four_steps_moved() {
        val caps = evaluate("srv", 0L, allMoved())
        assertTrue(caps.collectionEtagPropagates)
    }

    @Test
    fun propagates_false_when_overwrite_did_not_move() {
        val caps = evaluate("srv", 0L, allMoved().copy(afterOverwrite = tag("b0")))
        assertFalse(caps.collectionEtagPropagates)
    }

    @Test
    fun propagates_false_when_move_did_not_move() {
        val caps = evaluate("srv", 0L, allMoved().copy(afterMove = tag("b1")))
        assertFalse(caps.collectionEtagPropagates)
    }

    @Test
    fun propagates_false_when_deep_overwrite_did_not_move() {
        val caps = evaluate("srv", 0L, allMoved().copy(afterDeepOverwrite = tag("b3")))
        assertFalse(caps.collectionEtagPropagates)
    }

    @Test
    fun propagates_false_when_server_withheld_a_collection_etag() {
        val caps = evaluate("srv", 0L, allMoved().copy(afterMove = null))
        assertFalse(caps.collectionEtagPropagates)
    }

    @Test
    fun movePreservesEtag_true_when_destination_keeps_tmp_validator() {
        assertTrue(evaluate("srv", 0L, allMoved()).movePreservesEtag)
    }

    @Test
    fun movePreservesEtag_false_when_move_failed() {
        val caps = evaluate("srv", 0L, allMoved().copy(moveOk = false))
        assertFalse(caps.movePreservesEtag)
    }

    @Test
    fun movePreservesEtag_false_when_destination_etag_differs() {
        val caps = evaluate("srv", 0L, allMoved().copy(destEtagAfterMove = tag("other")))
        assertFalse(caps.movePreservesEtag)
    }

    @Test
    fun movePreservesEtag_false_when_destination_etag_absent() {
        val caps = evaluate("srv", 0L, allMoved().copy(destEtagAfterMove = null))
        assertFalse(caps.movePreservesEtag)
    }

    @Test
    fun movePreservesEtag_rejects_strong_tmp_to_weak_destination() {
        // They identify equivalent content for cache validation, but are not interchangeable for
        // If-Match: a strong tmp validator can never strongly match the weak destination validator.
        val obs = allMoved().copy(tmpEtag = tag("v"), destEtagAfterMove = ETag.parse("W/\"v\"")!!)
        assertFalse(evaluate("srv", 0L, obs).movePreservesEtag)
    }

    @Test
    fun moveEtag_reuse_requires_capability_for_the_active_client_server() {
        val caps = evaluate("active", 0L, allMoved())

        assertTrue(canReuseMovedEtag(caps, "active"))
        assertFalse(canReuseMovedEtag(caps, "different"))
        assertFalse(canReuseMovedEtag(caps.copy(movePreservesEtag = false), "active"))
        assertFalse(canReuseMovedEtag(null, "active"))
    }

    @Test
    fun diagnostics_reflect_put_etag() {
        val strong = evaluate("srv", 0L, allMoved())
        assertTrue(strong.issuesEtagOnPut)
        assertTrue(strong.issuesStrongEtags)

        val weak = evaluate("srv", 0L, allMoved().copy(putEtag = ETag.parse("W/\"x\"")!!))
        assertTrue(weak.issuesEtagOnPut)
        assertFalse(weak.issuesStrongEtags)

        val none = evaluate("srv", 0L, allMoved().copy(putEtag = null))
        assertFalse(none.issuesEtagOnPut)
        assertFalse(none.issuesStrongEtags)
    }

    @Test
    fun serverKey_is_stable_and_separates_servers() {
        val a = ServerCapabilities.serverKey("https://a.example", "user")
        assertEquals(a, ServerCapabilities.serverKey("https://a.example", "user"))
        assertNotEquals(a, ServerCapabilities.serverKey("https://b.example", "user"))
        assertNotEquals(a, ServerCapabilities.serverKey("https://a.example", "other"))
    }
}
