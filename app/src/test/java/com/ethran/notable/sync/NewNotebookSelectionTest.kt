package com.ethran.notable.sync

import org.junit.Assert.assertEquals
import org.junit.Test

class NewNotebookSelectionTest {

    @Test
    fun download_only_restores_remote_notebook_when_stale_sync_state_survived() {
        val selected = selectNewRemoteNotebookIds(
            remoteNotebookIds = setOf("missing-locally"),
            localNotebookIds = emptySet(),
            tombstonedIds = emptySet(),
            syncedNotebookIds = setOf("missing-locally"),
            downloadOnly = true,
        )

        assertEquals(listOf("missing-locally"), selected)
    }

    @Test
    fun two_way_sync_does_not_restore_an_intentional_local_deletion() {
        val selected = selectNewRemoteNotebookIds(
            remoteNotebookIds = setOf("deleted-locally"),
            localNotebookIds = emptySet(),
            tombstonedIds = emptySet(),
            syncedNotebookIds = setOf("deleted-locally"),
            downloadOnly = false,
        )

        assertEquals(emptyList<String>(), selected)
    }

    @Test
    fun tombstone_suppresses_download_in_download_only_mode() {
        val selected = selectNewRemoteNotebookIds(
            remoteNotebookIds = setOf("deleted-remotely"),
            localNotebookIds = emptySet(),
            tombstonedIds = setOf("deleted-remotely"),
            syncedNotebookIds = setOf("deleted-remotely"),
            downloadOnly = true,
        )

        assertEquals(emptyList<String>(), selected)
    }

    @Test
    fun never_downloads_a_notebook_that_is_already_local() {
        val selected = selectNewRemoteNotebookIds(
            remoteNotebookIds = setOf("present"),
            localNotebookIds = setOf("present"),
            tombstonedIds = emptySet(),
            syncedNotebookIds = emptySet(),
            downloadOnly = true,
        )

        assertEquals(emptyList<String>(), selected)
    }
}
