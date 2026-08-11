package com.ethran.notable.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.ethran.notable.sync.couch.CouchSyncController
import com.ethran.notable.utils.AppResult
import com.ethran.notable.utils.DomainError
import dagger.hilt.android.EntryPointAccessors
import io.shipbook.shipbooksdk.Log

/**
 * Background worker for WebDAV synchronization.
 * Runs via WorkManager. Emits success/error data via WorkManager Results.
 */
class SyncWorker(
    context: Context, params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        Log.i(TAG, "SyncWorker started")

        // 1. Dynamic Checks
        val connectivityChecker = ConnectivityChecker(applicationContext)
        if (!connectivityChecker.isNetworkAvailable()) {
            Log.i(TAG, "No network available, will retry later")
            return Result.retry()
        }

        val entryPoint = EntryPointAccessors.fromApplication(
            applicationContext, SyncOrchestratorEntryPoint::class.java
        )

        val kvProxy = entryPoint.kvProxy()
        val syncSettings = kvProxy.getSyncSettings()

        // The CouchDB backend replaces the WebDAV run entirely, and none of the checks below apply
        // to it: it has its own credentials, and its background job is one catch-up pass rather
        // than a whole-tree reconcile. Everything else in this worker is the WebDAV path.
        if (syncSettings.backend == SyncBackend.COUCHDB) {
            return runCouchCatchUp(entryPoint, syncSettings, connectivityChecker)
        }

        // OFF is caught by `webdavActive` too, but naming it separately keeps the log honest: a
        // run skipped because sync is off is not the same complaint as one skipped because WebDAV
        // was never switched on.
        if (syncSettings.backend == SyncBackend.OFF) {
            Log.i(TAG, "Sync turned off in settings, skipping")
            return Result.success(workDataOf(OUTPUT_KEY_SKIPPED to true))
        }

        if (!syncSettings.webdavActive) {
            Log.i(TAG, "Sync disabled in settings, skipping")
            return Result.success(workDataOf(OUTPUT_KEY_SKIPPED to true))
        }

        if (syncSettings.wifiOnly && !connectivityChecker.isUnmeteredConnected()) {
            Log.i(TAG, "WiFi-only sync enabled but not on unmetered network, skipping")
            return Result.success(workDataOf(OUTPUT_KEY_SKIPPED to true))
        }

        if (syncSettings.username.isBlank() || syncSettings.password.isBlank()) {
            Log.w(TAG, "No credentials stored, skipping sync")
            return Result.success(workDataOf(OUTPUT_KEY_SKIPPED to true))
        }

        // 2. Parse Input
        val syncRequest = SyncRequest.fromData(inputData)
            ?: return Result.failure(workDataOf(OUTPUT_KEY_SUCCESS to false, OUTPUT_KEY_ERROR to "INVALID_INPUT"))

        val syncTrigger = inputData.getString(KEY_SYNC_TRIGGER) ?: SYNC_TRIGGER_PERIODIC
        val isPeriodicSync = syncTrigger == SYNC_TRIGGER_PERIODIC

        // 3. Execute Sync
        return try {
            val result = when (syncRequest) {
                SyncRequest.SyncAll -> entryPoint.syncOrchestrator().syncAllNotebooks()
                SyncRequest.ForceUpload -> entryPoint.syncOrchestrator().forceUploadAll()
                SyncRequest.ForceDownload -> entryPoint.syncOrchestrator().forceDownloadAll()
                is SyncRequest.UploadDeletion -> entryPoint.syncOrchestrator().uploadDeletion(syncRequest.notebookId)
                is SyncRequest.SyncNotebook -> entryPoint.syncOrchestrator().syncNotebook(syncRequest.notebookId)
                is SyncRequest.SyncFromPageId -> {
                    entryPoint.syncOrchestrator().syncFromPageId(syncRequest.pageId)
                    AppResult.Success(Unit)
                }
            }

            // 4. Handle Results
            when (result) {
                is AppResult.Success -> {
                    Log.i(TAG, "Sync $syncRequest completed successfully")
                    Result.success(
                        workDataOf(
                            OUTPUT_KEY_SUCCESS to true,
                            OUTPUT_KEY_IS_PERIODIC to isPeriodicSync
                        )
                    )
                }

                is AppResult.Error -> {
                    val error = result.error
                    val errorStr = error.javaClass.simpleName
                    val failureMessage = error.userMessage

                    when (error) {
                        is DomainError.SyncInProgress -> {
                            Log.i(TAG, "Sync already in progress, skipping this run")
                            // Returning success so it doesn't log as a strict failure, but marking success as false
                            Result.success(workDataOf(OUTPUT_KEY_SUCCESS to false, OUTPUT_KEY_ERROR to errorStr))
                        }

                        is DomainError.NetworkError -> {
                            Log.e(TAG, "Network error during sync: $failureMessage")
                            if (runAttemptCount < MAX_RETRY_ATTEMPTS) {
                                Result.retry()
                            } else {
                                Result.failure(workDataOf(OUTPUT_KEY_SUCCESS to false, OUTPUT_KEY_ERROR to failureMessage))
                            }
                        }

                        is DomainError.SyncAuthError,
                        is DomainError.SyncConfigError,
                        is DomainError.SyncClockSkew,
                        is DomainError.SyncWifiRequired,
                        is DomainError.SyncConflict -> {
                            Log.w(TAG, "Sync failed (non-retryable): $failureMessage")
                            // These are hard failures, mark them as such
                            Result.failure(workDataOf(OUTPUT_KEY_SUCCESS to false, OUTPUT_KEY_ERROR to failureMessage))
                        }

                        else -> {
                            Log.e(TAG, "Sync failed: $failureMessage")
                            if (runAttemptCount < MAX_RETRY_ATTEMPTS && error.recoverable) {
                                Result.retry()
                            } else {
                                Result.failure(workDataOf(OUTPUT_KEY_SUCCESS to false, OUTPUT_KEY_ERROR to failureMessage))
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error in SyncWorker: ${e.message}")
            if (runAttemptCount < MAX_RETRY_ATTEMPTS) {
                Result.retry()
            } else {
                Result.failure(
                    workDataOf(
                        OUTPUT_KEY_SUCCESS to false,
                        OUTPUT_KEY_ERROR to (e.localizedMessage ?: "UNKNOWN_EXCEPTION")
                    )
                )
            }
        }
    }

    /**
     * The CouchDB background job: catch up on the change feed, then send whatever is queued.
     *
     * Deliberately not a long poll — a periodic worker has a budget and must return, and the
     * near-real-time path is the foreground loop's job ([CouchSyncController.start]). What this run
     * exists for is the gap: everything that changed on the other device while notable was closed,
     * and everything drawn here that the app was killed before it could send.
     */
    private suspend fun runCouchCatchUp(
        entryPoint: SyncOrchestratorEntryPoint,
        settings: SyncSettings,
        connectivityChecker: ConnectivityChecker,
    ): Result {
        if (!settings.couchConfigured) {
            Log.i(TAG, "CouchDB selected but not configured, skipping")
            return Result.success(workDataOf(OUTPUT_KEY_SKIPPED to true))
        }
        if (settings.wifiOnly && !connectivityChecker.isUnmeteredConnected()) {
            Log.i(TAG, "WiFi-only sync enabled but not on unmetered network, skipping")
            return Result.success(workDataOf(OUTPUT_KEY_SKIPPED to true))
        }

        val controller = entryPoint.couchSyncController()
        controller.syncNow()

        val status = controller.status
        return if (status is CouchSyncController.Status.Failed) {
            // Queued work is not lost work: the outbox survives, so a failure here is worth a
            // retry rather than a hard failure that would need the user to notice it.
            Log.w(TAG, "CouchDB catch-up failed: ${status.message}")
            if (runAttemptCount < MAX_RETRY_ATTEMPTS) {
                Result.retry()
            } else {
                Result.failure(
                    workDataOf(OUTPUT_KEY_SUCCESS to false, OUTPUT_KEY_ERROR to status.message)
                )
            }
        } else {
            Log.i(TAG, "CouchDB catch-up completed, ${controller.pendingCount} still queued")
            Result.success(workDataOf(OUTPUT_KEY_SUCCESS to true))
        }
    }

    companion object {
        private const val TAG = "SyncWorker"
        private const val MAX_RETRY_ATTEMPTS = 3

        const val KEY_SYNC_TRIGGER = "sync_trigger"
        const val SYNC_TRIGGER_PERIODIC = "periodic"
        const val SYNC_TRIGGER_IMMEDIATE = "immediate"

        // Output keys for the UI to observe
        const val OUTPUT_KEY_SUCCESS = "success"
        const val OUTPUT_KEY_ERROR = "error"
        const val OUTPUT_KEY_IS_PERIODIC = "is_periodic"
        const val OUTPUT_KEY_SKIPPED = "skipped"

        const val SYNC_WORK_TAG = "sync-work"

        /**
         * Unique work name for periodic sync.
         */
        const val WORK_NAME = "notable-periodic-sync"
    }
}