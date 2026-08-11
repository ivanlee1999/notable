package com.ethran.notable.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.ethran.notable.data.db.KvProxy
import com.ethran.notable.sync.SyncBackend
import com.ethran.notable.sync.SyncOrchestratorEntryPoint
import com.ethran.notable.sync.SyncRequest
import com.ethran.notable.sync.SyncScheduler
import com.ethran.notable.sync.SyncSettings
import dagger.hilt.android.EntryPointAccessors

/**
 * Reaches the persisted sync configuration from a composable, mirroring [rememberCouchSyncController]
 * — the dialogs that act on one notebook sit below any view model, and the settings are a singleton
 * with no screen state of their own.
 */
@Composable
fun rememberKvProxy(): KvProxy {
    val context = LocalContext.current.applicationContext
    return remember(context) {
        EntryPointAccessors
            .fromApplication(context, SyncOrchestratorEntryPoint::class.java)
            .kvProxy()
    }
}

/**
 * What a manual "Sync now" on a single notebook did, so the caller can say something true rather
 * than an unconditional "queued". A request that no backend will act on is the whole reason this
 * exists: queuing work into a disabled engine and reporting success is how sync came to look
 * broken-but-silent.
 */
sealed interface ManualSyncOutcome {
    /** Queued against WebDAV, which really does transfer just this notebook. */
    data object QueuedForNotebook : ManualSyncOutcome

    /**
     * Queued against CouchDB. Its background job is a whole change-feed catch-up with no per-document
     * entry point, so this syncs everything — worth saying, since the user asked for one notebook.
     */
    data object QueuedForEverything : ManualSyncOutcome

    /** Nothing was queued. [reason] names the switch that has to change for it to work. */
    data class NotConfigured(val reason: Reason) : ManualSyncOutcome {
        enum class Reason { BACKEND_OFF, WEBDAV_DISABLED, COUCH_UNCONFIGURED }
    }
}

/**
 * Ask for [notebookId] to be synced now, through whichever backend is live.
 *
 * Routed through [SyncScheduler] rather than calling the orchestrator inline so a manual sync gets
 * the same WorkManager treatment as every other run — it survives the dialog closing, it retries,
 * and it shows up in the activity log under its own run marker.
 */
suspend fun requestNotebookSync(
    kvProxy: KvProxy,
    syncScheduler: SyncScheduler,
    notebookId: String,
): ManualSyncOutcome {
    val settings: SyncSettings = kvProxy.getSyncSettings()
    return when {
        settings.webdavActive -> {
            syncScheduler.triggerImmediateSync(SyncRequest.SyncNotebook(notebookId))
            ManualSyncOutcome.QueuedForNotebook
        }

        settings.couchActive -> {
            syncScheduler.triggerImmediateSync(SyncRequest.SyncAll)
            ManualSyncOutcome.QueuedForEverything
        }

        settings.backend == SyncBackend.OFF ->
            ManualSyncOutcome.NotConfigured(ManualSyncOutcome.NotConfigured.Reason.BACKEND_OFF)

        settings.backend == SyncBackend.WEBDAV ->
            ManualSyncOutcome.NotConfigured(ManualSyncOutcome.NotConfigured.Reason.WEBDAV_DISABLED)

        else ->
            ManualSyncOutcome.NotConfigured(ManualSyncOutcome.NotConfigured.Reason.COUCH_UNCONFIGURED)
    }
}
