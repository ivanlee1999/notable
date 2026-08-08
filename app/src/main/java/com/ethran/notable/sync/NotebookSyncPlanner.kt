package com.ethran.notable.sync

/**
 * Remote manifest facts needed to reconcile one notebook: its `updatedAt` (epoch millis, or null if
 * the manifest carried none) and its current ETag.
 */
data class RemoteManifestInfo(val updatedAt: Long?, val etag: ETag?)

/**
 * The decision for a single notebook. Pure data — the executor turns it into WebDAV calls.
 */
sealed interface NotebookAction {
    /** Push local up. [ifMatch] guards the manifest PUT against a concurrent remote change. */
    data class Upload(val ifMatch: ETag?) : NotebookAction

    /** Pull remote down. */
    data object Download : NotebookAction

    /** Both sides already agree — nothing to transfer (the sync-state row is just refreshed). */
    data object Skip : NotebookAction

    /**
     * Concurrent edits: the manifest changed remotely while neither clock is clearly newer. The
     * executor separates independent edits from genuine conflicts — independent different-page edits
     * merge losslessly (pull remotely-changed pages, push locally-changed ones), while a same-page or
     * structural conflict is dispatched to the [conflict strategy][SyncConflictStrategy] instead of
     * either side being overwritten.
     */
    data object Reconcile : NotebookAction

    /** Remote is newer but we are in upload-only mode, so the download is intentionally skipped. */
    data object SkipUploadOnly : NotebookAction

    /** Local is newer but we are in download-only mode, so the upload is intentionally skipped. */
    data object SkipDownloadOnly : NotebookAction
}

/**
 * The pure reconciliation decision for one *remote-present* notebook. No I/O: given the local
 * timestamp, what we last committed for it, and the remote facts (already fetched by the executor,
 * conditionally via `If-None-Match`), decide upload / download / skip.
 *
 * The "remote absent" case (a notebook that exists locally but not on the server) is handled by the
 * executor directly as a plain upload — it needs no timestamp reasoning.
 *
 * Conflict handling: when *both* sides changed, this returns the last-writer-wins outcome (upload if
 * local is newer, download if remote is newer); it does not surface a conflict badge. When neither
 * clock is clearly newer (within tolerance) but the manifest ETag differs, it returns [Reconcile]:
 * the tie can't prove page equality, so the executor merges per page rather than mark the notebook
 * synced over possibly-stale pages. The executor reconciles independent (different-page) edits
 * losslessly and hands a genuine same-page or structural conflict to the
 * [SyncConflictStrategy] instead of overwriting either side.
 */
object NotebookSyncPlanner {
    const val TOLERANCE_MS = 1000L

    fun decide(
        /** Local `Notebook.updatedAt`, epoch millis. */
        localUpdatedAt: Long,
        /** `syncedLocalUpdatedAt` from the sync-state row, or null if never synced. */
        syncedLocalUpdatedAt: Long?,
        /** ETag we stored for the remote manifest at the last sync (used as `If-Match` on upload). */
        storedEtag: ETag?,
        /**
         * Whether the remote manifest changed since [storedEtag]. `false` means the conditional GET
         * returned 304 (remote is exactly what we last synced), so [remote] is null.
         */
        remoteChanged: Boolean,
        /** The freshly fetched remote manifest facts; non-null iff [remoteChanged] is true. */
        remote: RemoteManifestInfo?,
        uploadOnly: Boolean,
        /** Download-only: never push local changes (mirror of [uploadOnly]). */
        downloadOnly: Boolean = false,
        toleranceMs: Long = TOLERANCE_MS,
    ): NotebookAction {
        val raw = rawDecide(
            localUpdatedAt, syncedLocalUpdatedAt, storedEtag, remoteChanged, remote, toleranceMs
        )
        // Apply the one-directional mode: an UPLOAD is suppressed in download-only, a DOWNLOAD in
        // upload-only. Skips stay skips. A Reconcile needs both directions, so it degrades to the
        // half its mode permits: download-only keeps the pull, upload-only surfaces REMOTE_AHEAD.
        return when {
            uploadOnly && raw is NotebookAction.Download -> NotebookAction.SkipUploadOnly
            uploadOnly && raw is NotebookAction.Reconcile -> NotebookAction.SkipUploadOnly
            downloadOnly && raw is NotebookAction.Upload -> NotebookAction.SkipDownloadOnly
            downloadOnly && raw is NotebookAction.Reconcile -> NotebookAction.Download
            else -> raw
        }
    }

    /** The direction-agnostic decision: UPLOAD / DOWNLOAD / SKIP. */
    private fun rawDecide(
        localUpdatedAt: Long,
        syncedLocalUpdatedAt: Long?,
        storedEtag: ETag?,
        remoteChanged: Boolean,
        remote: RemoteManifestInfo?,
        toleranceMs: Long,
    ): NotebookAction {
        if (!remoteChanged) {
            // Remote == our last committed sync. Decide purely on whether local moved since then.
            val changedLocally =
                syncedLocalUpdatedAt == null || localUpdatedAt - syncedLocalUpdatedAt > toleranceMs
            return if (changedLocally) NotebookAction.Upload(storedEtag) else NotebookAction.Skip
        }

        // Remote changed since our last sync (or we had no stored ETag and did a full GET).
        val r = remote ?: return NotebookAction.Upload(storedEtag)
        val remoteUpdatedAt = r.updatedAt ?: return NotebookAction.Upload(r.etag)
        val diff = localUpdatedAt - remoteUpdatedAt
        return when {
            diff > toleranceMs -> NotebookAction.Upload(r.etag)      // local newer -> local wins
            diff < -toleranceMs -> NotebookAction.Download           // remote newer -> remote wins
            // Timestamps tie while the manifest ETag differs: concurrent edits. A tie is NOT proof of
            // page equality, so we must not Skip it into a metadata-only markSynced (the next
            // If-None-Match would 304 forever over stale pages). Reconcile merges per page instead.
            else -> NotebookAction.Reconcile
        }
    }
}
