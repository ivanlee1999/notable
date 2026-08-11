package com.ethran.notable.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.ethran.notable.sync.couch.CouchSyncController
import com.ethran.notable.utils.AppResult
import com.ethran.notable.utils.DomainError
import dagger.hilt.android.EntryPointAccessors

/**
 * Background worker for WebDAV synchronization.
 * Runs via WorkManager. Emits success/error data via WorkManager Results.
 *
 * Everything here logs through [SyncLogger], not the ShipBook logger directly. Every early return
 * below is a reason a sync the user expected did not happen, and those are exactly the lines they
 * come to the in-app activity log to find — a remote/logcat-only record answers the question for a
 * developer with a cable, not for the person holding the device.
 */
class SyncWorker(
    context: Context, params: WorkerParameters
) : CoroutineWorker(context, params) {

    private val log = SyncLogger

    override suspend fun doWork(): Result {
        // A worker can start in a fresh process; make sure the log file is wired up before the
        // first decision is recorded, rather than relying on an Activity having run.
        SyncLogger.install(applicationContext)
        val trigger = inputData.getString(KEY_SYNC_TRIGGER) ?: SYNC_TRIGGER_PERIODIC
        SyncLogger.beginRun(
            "$trigger sync" + if (runAttemptCount > 0) " (retry #$runAttemptCount)" else ""
        )

        // 1. Dynamic Checks
        val connectivityChecker = ConnectivityChecker(applicationContext)
        if (!connectivityChecker.isNetworkAvailable()) {
            log.i(TAG, "No network available, will retry later")
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
            log.i(TAG, "Sync turned off in settings, skipping")
            return Result.success(workDataOf(OUTPUT_KEY_SKIPPED to true))
        }

        if (!syncSettings.webdavActive) {
            log.i(
                TAG,
                "WebDAV selected but its Sync switch is off, skipping " +
                    "(Settings ▸ Sync ▸ Enable sync)"
            )
            return Result.success(workDataOf(OUTPUT_KEY_SKIPPED to true))
        }

        if (syncSettings.wifiOnly && !connectivityChecker.isUnmeteredConnected()) {
            log.i(TAG, "WiFi-only sync enabled but not on unmetered network, skipping")
            return Result.success(workDataOf(OUTPUT_KEY_SKIPPED to true))
        }

        if (syncSettings.username.isBlank() || syncSettings.password.isBlank()) {
            val missing = listOfNotNull(
                "username".takeIf { syncSettings.username.isBlank() },
                "password".takeIf { syncSettings.password.isBlank() },
            ).joinToString(" and ")
            log.w(TAG, "No $missing stored, skipping sync — re-save your credentials")
            return Result.success(workDataOf(OUTPUT_KEY_SKIPPED to true))
        }

        // 2. Parse Input
        val syncRequest = SyncRequest.fromData(inputData)
            ?: run {
                log.e(TAG, "Sync request could not be read from the work input; nothing ran")
                return Result.failure(
                    workDataOf(OUTPUT_KEY_SUCCESS to false, OUTPUT_KEY_ERROR to "INVALID_INPUT")
                )
            }

        val isPeriodicSync = trigger == SYNC_TRIGGER_PERIODIC
        log.d(TAG, "Running $syncRequest (attempt ${runAttemptCount + 1})")

        // 3. Execute Sync
        return try {
            val result = when (syncRequest) {
                SyncRequest.SyncAll -> entryPoint.syncOrchestrator().syncAllNotebooks()
                SyncRequest.ForceUpload -> entryPoint.syncOrchestrator().forceUploadAll()
                SyncRequest.ForceDownload -> entryPoint.syncOrchestrator().forceDownloadAll()
                is SyncRequest.UploadDeletion -> entryPoint.syncOrchestrator().uploadDeletion(syncRequest.notebookId)
                // Only the per-notebook "Sync now" action enqueues this, so it waits for the lock
                // rather than skipping — the user is looking at a message saying it is syncing.
                is SyncRequest.SyncNotebook ->
                    entryPoint.syncOrchestrator()
                        .syncNotebook(syncRequest.notebookId, userInitiated = true)
                is SyncRequest.SyncFromPageId -> {
                    entryPoint.syncOrchestrator().syncFromPageId(syncRequest.pageId)
                    AppResult.Success(Unit)
                }
            }

            // 4. Handle Results
            when (result) {
                is AppResult.Success -> {
                    log.i(TAG, "Sync $syncRequest completed successfully")
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
                            log.i(TAG, "Sync already in progress, skipping this run")
                            // Returning success so it doesn't log as a strict failure, but marking success as false
                            Result.success(workDataOf(OUTPUT_KEY_SUCCESS to false, OUTPUT_KEY_ERROR to errorStr))
                        }

                        is DomainError.NetworkError -> {
                            log.e(TAG, "Network error during sync: $failureMessage")
                            if (runAttemptCount < MAX_RETRY_ATTEMPTS) {
                                log.i(TAG, "Will retry (attempt ${runAttemptCount + 1} of $MAX_RETRY_ATTEMPTS)")
                                Result.retry()
                            } else {
                                log.w(TAG, "Giving up after $MAX_RETRY_ATTEMPTS attempts")
                                Result.failure(workDataOf(OUTPUT_KEY_SUCCESS to false, OUTPUT_KEY_ERROR to failureMessage))
                            }
                        }

                        is DomainError.SyncAuthError,
                        is DomainError.SyncConfigError,
                        is DomainError.SyncClockSkew,
                        is DomainError.SyncWifiRequired,
                        is DomainError.SyncConflict -> {
                            log.w(TAG, "Sync failed (non-retryable): $failureMessage")
                            // These are hard failures, mark them as such
                            Result.failure(workDataOf(OUTPUT_KEY_SUCCESS to false, OUTPUT_KEY_ERROR to failureMessage))
                        }

                        else -> {
                            log.e(TAG, "Sync failed: $failureMessage")
                            if (runAttemptCount < MAX_RETRY_ATTEMPTS && error.recoverable) {
                                log.i(TAG, "Will retry (attempt ${runAttemptCount + 1} of $MAX_RETRY_ATTEMPTS)")
                                Result.retry()
                            } else {
                                log.w(
                                    TAG,
                                    if (error.recoverable) "Giving up after $MAX_RETRY_ATTEMPTS attempts"
                                    else "Not retryable; this run is over"
                                )
                                Result.failure(workDataOf(OUTPUT_KEY_SUCCESS to false, OUTPUT_KEY_ERROR to failureMessage))
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            log.e(TAG, "Unexpected error in SyncWorker: ${e.message}")
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
            log.i(
                TAG,
                "CouchDB selected but not configured, skipping " +
                    "(needs an http(s) URL and a database name)"
            )
            return Result.success(workDataOf(OUTPUT_KEY_SKIPPED to true))
        }
        if (settings.wifiOnly && !connectivityChecker.isUnmeteredConnected()) {
            log.i(TAG, "WiFi-only sync enabled but not on unmetered network, skipping")
            return Result.success(workDataOf(OUTPUT_KEY_SKIPPED to true))
        }

        val controller = entryPoint.couchSyncController()
        log.i(TAG, "CouchDB catch-up starting (${controller.pendingCount} queued)")
        controller.syncNow()

        val status = controller.status
        return if (status is CouchSyncController.Status.Failed) {
            // Queued work is not lost work: the outbox survives, so a failure here is worth a
            // retry rather than a hard failure that would need the user to notice it.
            log.w(TAG, "CouchDB catch-up failed: ${status.message}")
            if (runAttemptCount < MAX_RETRY_ATTEMPTS) {
                log.i(TAG, "Will retry (attempt ${runAttemptCount + 1} of $MAX_RETRY_ATTEMPTS)")
                Result.retry()
            } else {
                Result.failure(
                    workDataOf(OUTPUT_KEY_SUCCESS to false, OUTPUT_KEY_ERROR to status.message)
                )
            }
        } else {
            log.i(TAG, "CouchDB catch-up completed, ${controller.pendingCount} still queued")
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