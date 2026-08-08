package com.ethran.notable.sync

import com.ethran.notable.data.AppRepository
import com.ethran.notable.data.db.Notebook
import com.ethran.notable.sync.serializers.NotebookSerializer
import com.ethran.notable.utils.AppResult
import com.ethran.notable.utils.DomainError
import com.ethran.notable.utils.ErrorAccumulator
import com.ethran.notable.utils.getOrElse
import com.ethran.notable.utils.getOrNull
import com.ethran.notable.utils.onError
import com.ethran.notable.utils.onFailure
import java.util.Date
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NotebookReconciliationService @Inject constructor(
    private val appRepository: AppRepository,
    private val notebookSyncService: NotebookSyncService,
    private val reporter: SyncProgressReporter
) {
    private val log = SyncLogger

    /**
     * Reconcile every local notebook against the server.
     *
     * [remoteNotebookIds] is the single `PROPFIND` listing of `/notebooks/` fetched once by the
     * orchestrator, so existence is a set lookup instead of a `HEAD` per notebook (5a). Preflight
     * (wifi + clock skew) is done once by the orchestrator, not here (5c).
     */
    suspend fun syncExistingNotebooks(
        client: WebDAVClient,
        remoteNotebookIds: Set<String>,
        uploadOnly: Boolean,
        downloadOnly: Boolean,
        conflictStrategy: SyncConflictStrategy,
    ): AppResult<Set<String>, DomainError> {
        val localNotebooks = appRepository.bookRepository.getAll()
        val preDownloadNotebookIds = localNotebooks.map { it.id }.toSet()
        val total = localNotebooks.size
        val errors = ErrorAccumulator()

        localNotebooks.forEachIndexed { i, notebook ->
            reporter.beginItem(index = i + 1, total = total, name = notebook.title, id = notebook.id)
            // Individual notebook sync failures are non-fatal for the whole process.
            reconcileNotebook(
                notebook.id, client, remoteNotebookIds.contains(notebook.id),
                uploadOnly, downloadOnly, conflictStrategy
            ).onError { errors.add(it) }
        }
        reporter.endItem()

        return errors.asResult(preDownloadNotebookIds)
    }

    /**
     * Reconcile a single notebook (sync-on-close). Existence is probed with one `HEAD`; preflight is
     * done by the caller (orchestrator).
     */
    suspend fun syncNotebook(
        notebookId: String,
        client: WebDAVClient,
        uploadOnly: Boolean,
        downloadOnly: Boolean,
        conflictStrategy: SyncConflictStrategy,
    ): AppResult<Unit, DomainError> {
        log.i(TAG, "Syncing notebook: $notebookId")
        // If we cannot determine whether the remote manifest exists (e.g. transient network error),
        // abort this notebook rather than fall through to an unguarded upload (P2).
        val remotePresent = client.exists(SyncPaths.manifestFile(notebookId))
            .onFailure { return AppResult.Error(it) }
        return reconcileNotebook(
            notebookId, client, remotePresent, uploadOnly, downloadOnly, conflictStrategy
        )
    }

    private suspend fun reconcileNotebook(
        notebookId: String,
        client: WebDAVClient,
        remotePresent: Boolean,
        uploadOnly: Boolean,
        downloadOnly: Boolean,
        conflictStrategy: SyncConflictStrategy,
    ): AppResult<Unit, DomainError> {
        val localNotebook = appRepository.bookRepository.getById(notebookId)
            ?: return AppResult.Error(DomainError.NotFound("Notebook $notebookId"))

        // Remote absent -> straight upload (new to the server), no If-Match -- unless download-only.
        if (!remotePresent) {
            return if (downloadOnly) AppResult.Success(Unit)
            else notebookSyncService.uploadNotebook(localNotebook, client)
        }

        val syncState = appRepository.notebookSyncStateRepository.get(notebookId)
        val storedEtag = ETag.parse(syncState?.remoteEtag)
        val manifestPath = SyncPaths.manifestFile(notebookId)

        // Fetch the remote manifest -- conditionally when we have a stored ETag, so an unchanged
        // notebook comes back as a cheap, bodyless 304 (5a).
        //
        // The directory `/notebooks/<id>/` was listed (remotePresent==true) but its manifest.json
        // can still be missing: an interrupted upload (pages-first, manifest-last) or a partial
        // delete leaves a dir-without-manifest shell. A 404 here (RemoteMissing) must NOT fail the
        // run — treat the remote as absent and self-heal by re-uploading the local copy (P6/3a).
        val remoteChanged: Boolean
        val remote: RemoteManifestInfo?
        // The parsed remote manifest, kept for reconciliation so a within-tolerance tie can compare
        // structure without a second fetch. Null when the remote is unchanged (304).
        val remoteNotebook: Notebook?
        if (storedEtag != null) {
            when (val fetched = client.getFileIfNoneMatch(manifestPath, storedEtag)) {
                is AppResult.Error -> {
                    if (fetched.error is DomainError.RemoteMissing)
                        return healMissingRemoteManifest(localNotebook, client, downloadOnly)
                    return AppResult.Error(fetched.error)
                }

                is AppResult.Success -> {
                    if (fetched.data == null) {
                        remoteChanged = false
                        remote = null
                        remoteNotebook = null
                    } else {
                        remoteChanged = true
                        remote = fetched.data.toManifestInfo()
                        remoteNotebook = fetched.data.toNotebook()
                    }
                }
            }
        } else {
            when (val fetched = client.getFileWithMetadata(manifestPath)) {
                is AppResult.Error -> {
                    if (fetched.error is DomainError.RemoteMissing)
                        return healMissingRemoteManifest(localNotebook, client, downloadOnly)
                    return AppResult.Error(fetched.error)
                }

                is AppResult.Success -> {
                    remoteChanged = true
                    remote = fetched.data.toManifestInfo()
                    remoteNotebook = fetched.data.toNotebook()
                }
            }
        }

        val action = NotebookSyncPlanner.decide(
            localUpdatedAt = localNotebook.updatedAt.time,
            syncedLocalUpdatedAt = syncState?.syncedLocalUpdatedAt?.time,
            storedEtag = storedEtag,
            remoteChanged = remoteChanged,
            remote = remote,
            uploadOnly = uploadOnly,
            downloadOnly = downloadOnly,
        )

        return when (action) {
            is NotebookAction.Upload -> {
                reportWriteGuard(localNotebook.title, action.ifMatch)
                notebookSyncService.uploadNotebook(localNotebook, client, action.ifMatch)
            }

            NotebookAction.Download -> notebookSyncService.downloadNotebook(notebookId, client)

            NotebookAction.Reconcile ->
                reconcileConcurrentEdits(notebookId, localNotebook, remoteNotebook, client, conflictStrategy)

            NotebookAction.SkipUploadOnly -> {
                // Upload-only mode: remote is newer but we never pull. This is a planned no-op, not
                // an error (6a/6b). Record REMOTE_AHEAD so the library shows a "newer on server"
                // badge instead of a misleading SYNCED.
                log.i(TAG, "↑ Upload-only: leaving newer server copy of ${localNotebook.title}")
                appRepository.notebookSyncStateRepository.markRemoteAhead(
                    notebookId = notebookId,
                    localUpdatedAt = localNotebook.updatedAt,
                    remoteUpdatedAt = remote?.updatedAt?.let { Date(it) } ?: syncState?.remoteUpdatedAt,
                    remoteEtag = (if (remoteChanged) remote?.etag else storedEtag)?.raw,
                )
                AppResult.Success(Unit)
            }

            NotebookAction.SkipDownloadOnly -> {
                // Download-only mode: local is newer but we never push. Planned no-op; deliberately
                // do NOT markSynced, so the notebook keeps its NOT_SYNCED badge (local changes are
                // genuinely not on the server) (8g-2).
                log.i(TAG, "↓ Download-only: not pushing local changes of ${localNotebook.title}")
                AppResult.Success(Unit)
            }

            NotebookAction.Skip -> {
                log.i(TAG, "= No changes, skipping ${localNotebook.title}")
                // Refresh the sync-state row so an unchanged notebook stays SYNCED and (re)stores the
                // current ETag/timestamp for the next If-None-Match check.
                val etagToStore = if (remoteChanged) remote?.etag else storedEtag
                val remoteAtToStore =
                    if (remoteChanged) remote?.updatedAt?.let { Date(it) } else syncState?.remoteUpdatedAt
                appRepository.notebookSyncStateRepository.markSynced(
                    notebookId = notebookId,
                    localUpdatedAt = localNotebook.updatedAt,
                    remoteUpdatedAt = remoteAtToStore,
                    remoteEtag = etagToStore?.raw,
                )
                AppResult.Success(Unit)
            }
        }
    }

    /**
     * Concurrent edits within the timestamp tolerance. Separate a genuine conflict from independent
     * edits before touching anything:
     *
     * - **Same-page conflict** (a page edited on both sides) or **structural conflict** (the manifests
     *   disagree on page set, order, title, or open page) → non-mergeable. Dispatch on
     *   [conflictStrategy]; only [SyncConflictStrategy.ASK] is implemented: flag the notebook
     *   (CONFLICT badge) and leave both copies untouched for the user to resolve. Crucially the merge
     *   below is skipped, so a locally-added/reordered/renamed manifest is never overwritten by the
     *   download. Unimplemented automatic strategies fall back to ASK rather than crash a background
     *   sync.
     * - **No conflict** (identical structure, at most different-page content edits) → merge
     *   losslessly: pull every remotely-changed page, then push any page that is only locally changed.
     *   The download is safe here precisely because the manifests match, so it drops no local page.
     *   The republish is skipped when nothing is locally dirty, so two already-converged devices don't
     *   ping-pong the manifest ETag and re-trigger each other.
     *
     * [remoteNotebook] is the manifest already fetched by [reconcileNotebook]; a null one (unreadable
     * remote structure) is treated as a structural conflict, the safe reading.
     */
    private suspend fun reconcileConcurrentEdits(
        notebookId: String,
        localNotebook: Notebook,
        remoteNotebook: Notebook?,
        client: WebDAVClient,
        conflictStrategy: SyncConflictStrategy,
    ): AppResult<Unit, DomainError> {
        val pageConflicts = notebookSyncService.detectPageConflicts(localNotebook, client)
            .getOrElse { return AppResult.Error(it) }
        val structural =
            remoteNotebook == null || notebookSyncService.structurallyDiverges(localNotebook, remoteNotebook)

        if (pageConflicts.isNotEmpty() || structural) {
            when (conflictStrategy) {
                SyncConflictStrategy.ASK -> Unit
                SyncConflictStrategy.SERVER_WINS, SyncConflictStrategy.LOCAL_WINS ->
                    log.w(TAG, "Conflict strategy $conflictStrategy not implemented yet; asking instead")
            }
            log.i(
                TAG,
                "⚠ Conflict in ${localNotebook.title} (${pageConflicts.size} page(s)" +
                    "${if (structural) ", structural" else ""}); flagging for the user"
            )
            appRepository.notebookSyncStateRepository.markConflict(notebookId)
            return AppResult.Success(Unit)
        }

        log.i(TAG, "⇄ Independent concurrent edits for ${localNotebook.title}; merging per page")
        notebookSyncService.downloadNotebook(notebookId, client)
            .onFailure { return AppResult.Error(it) }

        val refreshed = appRepository.bookRepository.getById(notebookId) ?: localNotebook
        return if (notebookSyncService.hasLocallyDirtyPages(refreshed)) {
            val storedAfter =
                ETag.parse(appRepository.notebookSyncStateRepository.get(notebookId)?.remoteEtag)
            notebookSyncService.uploadNotebook(refreshed, client, storedAfter)
        } else {
            AppResult.Success(Unit)
        }
    }

    /**
     * The remote notebook directory exists but its manifest.json is gone — a broken shell from an
     * interrupted upload or partial delete. Don't fail the run and don't route it to download
     * (there is nothing coherent to download). Since we hold the notebook locally, self-heal by
     * re-uploading our copy, which rewrites the manifest and completes the commit. In download-only
     * mode we can't push, so skip it (the owning device heals it on its next full sync). (P6/3a)
     */
    private suspend fun healMissingRemoteManifest(
        localNotebook: Notebook,
        client: WebDAVClient,
        downloadOnly: Boolean
    ): AppResult<Unit, DomainError> {
        if (downloadOnly) {
            log.i(TAG, "⚠ Remote manifest missing for ${localNotebook.title}; skipping (download-only)")
            return AppResult.Success(Unit)
        }
        log.i(TAG, "⚠ Remote manifest missing for ${localNotebook.title}; re-uploading local copy to self-heal")
        return notebookSyncService.uploadNotebook(localNotebook, client)
    }

    /**
     * Say, once per notebook, whether this upload's commit is actually protected.
     *
     * An unguarded commit is last-writer-wins: a concurrent edit from another device is overwritten
     * silently rather than rejected with a 412. That is the declared conflict policy, so it is not
     * an error — but it is a different outcome from a guarded commit, and reporting the two alike
     * hides it. It goes to the in-app sync log rather than a badge, since it is a property of the
     * server that the user cannot act on. Notebook-level, because the notebook is the unit that
     * either commits or does not.
     *
     * Not called for the uploads that are unguarded *by design* — a notebook new to the server, and
     * [healMissingRemoteManifest] — where there is no remote version to guard against, so the
     * missing guard says nothing about the server.
     */
    private fun reportWriteGuard(title: String, ifMatch: ETag?) {
        val guard = ifMatch.writeGuard()
        if (guard is WriteGuard.Unguarded) {
            log.w(TAG, "Publishing '$title' without a concurrency guard (${guard.explain()}); " +
                "a concurrent edit from another device would be overwritten")
        }
    }

    private fun DownloadedFile.toManifestInfo(): RemoteManifestInfo {
        val updatedAt = NotebookSerializer.getManifestUpdatedAt(content.decodeToString())?.time
        return RemoteManifestInfo(updatedAt = updatedAt, etag = etag)
    }

    /** The fully parsed remote manifest, or null if it could not be deserialized. */
    private fun DownloadedFile.toNotebook(): Notebook? =
        NotebookSerializer.deserializeManifest(content.decodeToString()).getOrNull()

    companion object {
        private const val TAG = "NotebookReconciliationService"
    }
}
