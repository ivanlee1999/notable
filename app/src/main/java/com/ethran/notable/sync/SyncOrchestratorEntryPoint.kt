package com.ethran.notable.sync

import com.ethran.notable.data.db.KvProxy
import com.ethran.notable.sync.couch.CouchSyncController
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Hilt entry point used by [SyncWorker] to reach the orchestrator and its collaborators from a
 * WorkManager-instantiated worker (which cannot use constructor injection).
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface SyncOrchestratorEntryPoint {
    fun syncOrchestrator(): SyncOrchestrator
    fun kvProxy(): KvProxy

    /** The CouchDB backend's equivalent of the orchestrator; used when it is the selected one. */
    fun couchSyncController(): CouchSyncController
}
