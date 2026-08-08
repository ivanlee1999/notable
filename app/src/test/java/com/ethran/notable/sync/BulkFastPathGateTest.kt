package com.ethran.notable.sync

import com.ethran.notable.data.db.SyncStateValue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [knownRemoteUnchanged], the pure bulk fast-path gate. The fast path may skip a
 * notebook's manifest GET only when the directory listing ETag matches the baseline recorded for the
 * current server at a `SYNCED` commit; every missing or mismatched input must route to the ordinary
 * fetch instead.
 */
class BulkFastPathGateTest {

    private fun tag(v: String) = ETag.parse(v)

    private fun gate(
        bulkEnabled: Boolean = true,
        currentServerKey: String? = "srv",
        listingEtag: ETag? = tag("\"v1\""),
        state: String? = SyncStateValue.SYNCED,
        remoteDirServerKey: String? = "srv",
        remoteDirEtag: String? = "\"v1\"",
    ) = knownRemoteUnchanged(
        bulkEnabled, currentServerKey, listingEtag, state, remoteDirServerKey, remoteDirEtag
    )

    @Test
    fun skips_when_baseline_matches_listing_on_current_server() {
        assertTrue(gate())
    }

    @Test
    fun skips_on_weak_strong_spelling_difference_of_same_opaque_value() {
        // The listing may spell the validator weak while the baseline was stored strong (or vice
        // versa); If-None-Match semantics treat them as the same content, so the fast path applies.
        assertTrue(gate(listingEtag = tag("W/\"v1\""), remoteDirEtag = "\"v1\""))
    }

    @Test
    fun falls_back_when_bulk_disabled() {
        assertFalse(gate(bulkEnabled = false))
    }

    @Test
    fun falls_back_when_listing_etag_absent() {
        assertFalse(gate(listingEtag = null))
    }

    @Test
    fun falls_back_when_no_baseline_stored() {
        assertFalse(gate(remoteDirEtag = null))
    }

    @Test
    fun falls_back_when_baseline_from_a_different_server() {
        assertFalse(gate(remoteDirServerKey = "other"))
    }

    @Test
    fun falls_back_when_state_is_not_synced() {
        assertFalse(gate(state = SyncStateValue.REMOTE_AHEAD))
        assertFalse(gate(state = SyncStateValue.ERROR))
        assertFalse(gate(state = SyncStateValue.CONFLICT))
        assertFalse(gate(state = null))
    }

    @Test
    fun falls_back_when_listing_etag_differs_from_baseline() {
        assertFalse(gate(listingEtag = tag("\"v2\""), remoteDirEtag = "\"v1\""))
    }

    @Test
    fun falls_back_when_server_key_is_null_even_if_baseline_key_also_null() {
        // A null current key must never match a null stored key — an unidentifiable server is not a
        // trusted one.
        assertFalse(gate(currentServerKey = null, remoteDirServerKey = null))
    }
}
