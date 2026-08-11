package com.ethran.notable.sync

import com.ethran.notable.sync.couch.CouchDocId
import com.ethran.notable.sync.couch.CouchDocumentState
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The library badge under CouchDB.
 *
 * It used to be read from `notebook_sync_state`, which is the WebDAV engine's record of its own
 * commits and which the CouchDB engine never writes a row to. A missing row means "never synced",
 * so every notebook read **local only** however faithfully CouchDB was syncing it — a notebook
 * visibly present on the other device still claimed to be local. These pin the mapping from the
 * outbox, which is the record CouchDB actually keeps.
 */
class CouchBadgeTest {

    private val nb = CouchDocId.notebook("nb1")

    private fun badgeFor(state: CouchDocumentState): SyncBadge =
        NotebookSyncStatusStore.couchBadgeFor(nb, state)

    @Test
    fun `a notebook the server has is synced`() {
        assertEquals(
            SyncBadge.SYNCED,
            badgeFor(CouchDocumentState(known = setOf(nb), queued = emptySet()))
        )
    }

    /** The case that was wrong: present on the server, and the badge said otherwise. */
    @Test
    fun `a notebook the server has is not reported as local only`() {
        val badge = badgeFor(CouchDocumentState(known = setOf(nb)))

        assertEquals("this is the bug: a synced notebook read as unsynced", SyncBadge.SYNCED, badge)
    }

    @Test
    fun `a notebook the server has never seen is unsynced`() {
        assertEquals(SyncBadge.NOT_SYNCED, badgeFor(CouchDocumentState()))
    }

    /**
     * Queued outranks known — the same precedence the WebDAV branch gives a local edit over a
     * previously committed sync. A document changed again after being accepted is not in sync.
     */
    @Test
    fun `a notebook changed again after the server accepted it is unsynced`() {
        assertEquals(
            SyncBadge.NOT_SYNCED,
            badgeFor(CouchDocumentState(known = setOf(nb), queued = setOf(nb)))
        )
    }

    /** Other documents' state must not leak into this notebook's badge. */
    @Test
    fun `another document being queued does not change this one`() {
        val other = CouchDocId.notebook("nb2")

        assertEquals(
            SyncBadge.SYNCED,
            badgeFor(CouchDocumentState(known = setOf(nb, other), queued = setOf(other)))
        )
    }
}
