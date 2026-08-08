package com.ethran.notable.sync

import com.ethran.notable.data.AppRepository
import com.ethran.notable.data.db.Folder
import com.ethran.notable.data.db.KvProxy
import com.ethran.notable.data.db.Notebook
import com.ethran.notable.data.db.SyncStateValue
import com.ethran.notable.sync.serializers.FolderSerializer
import com.ethran.notable.utils.AppResult
import com.ethran.notable.utils.DomainError
import com.ethran.notable.utils.ErrorAccumulator
import com.ethran.notable.utils.getOrElse
import com.ethran.notable.utils.onError
import com.ethran.notable.utils.onFailure
import com.ethran.notable.utils.onSuccess
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SyncForceService @Inject constructor(
    private val appRepository: AppRepository,
    private val kvProxy: KvProxy,
    private val syncPreflightService: SyncPreflightService,
    private val notebookSyncService: NotebookSyncService,
    private val webDavClientFactory: WebDavClientFactoryPort,
    private val reporter: SyncProgressReporter
) {
    private val folderSerializer = FolderSerializer
    private val log = SyncLogger

    suspend fun forceUploadAll(): AppResult<Unit, DomainError> {
        log.i(TAG, "FORCE UPLOAD: Replacing server with local data")
        val settings = kvProxy.getSyncSettings()
        if (settings.username.isBlank() || settings.password.isBlank()) {
            return AppResult.Error(DomainError.SyncAuthError)
        }

        val client = webDavClientFactory.create(
            settings.serverUrl,
            settings.username,
            settings.password
        )

        val errors = ErrorAccumulator()

        // 1. Ensure directories exist.
        syncPreflightService.ensureServerDirectories(client)
            .onError { return AppResult.Error(it) }

        // 2. Upload folders (folders.json is a single file, replaced wholesale).
        val folders = appRepository.folderRepository.getAll()
        if (folders.isNotEmpty()) {
            val foldersJson = folderSerializer.serializeFolders(folders)
            client.putFile(
                SyncPaths.foldersFile(),
                foldersJson.toByteArray(),
                "application/json"
            ).onError { errors.add(it) }
        }

        // 3. Upload all local notebooks first. Uploads are upserts, so there is no need to wipe the
        //    server beforehand -- doing so risked losing server data before the local copy is
        //    safely up.
        val notebooks = appRepository.bookRepository.getAll()
        val localIds = notebooks.map { it.id }.toSet()
        log.i(TAG, "Uploading ${notebooks.size} local notebooks...")
        notebooks.forEachIndexed { index, notebook ->
            reporter.beginItem(index + 1, notebooks.size, notebook.title, notebook.id)
            notebookSyncService.uploadNotebook(notebook, client).onSuccess {
                log.i(TAG, "Uploaded: ${notebook.title}")
            }.onError { error ->
                log.e(TAG, "Failed to upload ${notebook.title}: ${error.userMessage}")
                errors.add(error)
            }
            reporter.endItem()
        }

        // 4. Delete server notebooks that no longer exist locally, so the server ends up == local.
        //    Each removal also drops a tombstone so the deletion propagates to other devices
        //    (notably download-only mirrors) instead of being a silent server-side hard delete they
        //    never learn about -- which otherwise left them showing a stale SYNCED badge for a
        //    notebook whose server copy was gone.
        client.listCollection(SyncPaths.notebooksDir()).onSuccess { serverDirs ->
            val extras = serverDirs.map { it.trimEnd('/') }.filter { it !in localIds }
            // Symmetric to detectAndUploadLocalDeletions' guard and forceDownloadAll's "refuse to
            // wipe": never let a small/incomplete local set delete a much larger server. The upload
            // above already ran, so the server keeps both this device's notebooks and the ones it
            // simply does not have, instead of being wiped down to a stale local snapshot.
            if (looksLikeStaleStateWipe(extras.size, serverDirs.size)) {
                val message = "Force upload kept ${extras.size} server notebook(s) not present " +
                    "locally: the local set (${localIds.size}) looks incomplete, so they were not " +
                    "deleted from the server."
                log.e(TAG, message)
                errors.add(DomainError.SyncError(message, recoverable = false))
                return@onSuccess
            }
            extras.forEach { extra ->
                log.i(TAG, "Deleting server notebook not present locally: $extra")
                val deleted = client.delete(SyncPaths.notebookDir(extra))
                    .onError { errors.add(it) } is AppResult.Success
                client.putFile(
                    SyncPaths.tombstone(extra), ByteArray(0), "application/octet-stream"
                ).onSuccess {
                    if (deleted) {
                        // Gone on both sides -- drop any leftover sync-state rows so a later regular
                        // sync doesn't re-detect and re-tombstone it. If DELETE failed, retain them
                        // so normal deletion detection retries instead of eventually resurrecting
                        // the stale server notebook after tombstone pruning.
                        appRepository.notebookSyncStateRepository.delete(extra)
                        appRepository.pageSyncStateRepository.deleteByNotebook(extra)
                    }
                }.onError { error ->
                    log.e(TAG, "Failed to upload tombstone for $extra: ${error.userMessage}")
                    errors.add(error)
                }
            }
        }.onError { errors.add(it) }

        // Sync-state rows are written per notebook by uploadNotebook on each committed upload.
        return errors.asResult(Unit).onSuccess {
            log.i(TAG, "FORCE UPLOAD complete: ${notebooks.size} notebooks")
        }
    }

    suspend fun forceDownloadAll(): AppResult<Unit, DomainError> {
        log.i(TAG, "FORCE DOWNLOAD: Replacing local with server data (incremental)")
        val settings = kvProxy.getSyncSettings()
        if (settings.username.isBlank() || settings.password.isBlank()) {
            return AppResult.Error(DomainError.SyncAuthError)
        }

        val client = webDavClientFactory.create(
            settings.serverUrl,
            settings.username,
            settings.password
        )

        val errors = ErrorAccumulator()

        // 1. Verify the server is reachable and actually has notebooks BEFORE touching local data.
        //    Deleting local first and only then discovering the server is unreachable or empty was
        //    a total-loss path. We refuse to wipe unless the server has content to restore.
        val notebooksDirExists =
            client.exists(SyncPaths.notebooksDir()).onFailure { return AppResult.Error(it) }
        if (!notebooksDirExists) {
            return AppResult.Error(
                DomainError.SyncError("Server has no notebooks directory; refusing to wipe local data")
            )
        }
        val serverNotebookDirs =
            client.listCollection(SyncPaths.notebooksDir()).onFailure { return AppResult.Error(it) }
        if (serverNotebookDirs.isEmpty()) {
            return AppResult.Error(
                DomainError.SyncError("Server has no notebooks; refusing to wipe local data")
            )
        }
        val serverNotebookIds = serverNotebookDirs.map { it.trimEnd('/') }

        // 2. Reconcile folders in place (create missing, update changed) BEFORE notebooks, so their
        //    parentFolderId foreign keys resolve on insert. Folders are NOT wiped-and-recreated:
        //    Notebook->Folder is ON DELETE CASCADE, so deleting every folder would cascade-delete
        //    every foldered notebook and force it to re-download -- exactly the from-scratch cost we
        //    are removing. Extra local folders are pruned in step 4, after their notebooks have been
        //    re-parented under a server folder, so that CASCADE only ever reaches non-server data.
        var serverFolderIds: Set<String>? = null
        if (client.exists(SyncPaths.foldersFile()).onError { errors.add(it) }.getOrElse { false }) {
            client.getFile(SyncPaths.foldersFile()).onSuccess { foldersBytes ->
                try {
                    val folders = folderSerializer.deserializeFolders(foldersBytes.decodeToString())
                    reconcileFolders(folders)
                    serverFolderIds = folders.map { it.id }.toSet()
                    log.i(TAG, "Reconciled ${folders.size} folder(s) from server")
                } catch (e: Exception) {
                    errors.add(DomainError.SyncError("Failed to process folders: ${e.message}"))
                }
            }.onError { errors.add(it) }
        }

        // 3. Download each server notebook, skipping ones already committed as an exact mirror of the
        //    server's current manifest -- this is what makes an interrupted run resume instead of
        //    re-fetching everything (downloads commit per notebook and those rows survive a restart).
        //    A notebook with local edits has its page sync rows dropped so downloadNotebook re-fetches
        //    every page and overwrites them ("replace local with server"); a clean-but-stale notebook
        //    keeps its rows so only the changed pages are fetched.
        log.i(TAG, "Found ${serverNotebookIds.size} notebook(s) on server")
        serverNotebookIds.forEachIndexed { index, notebookId ->
            reporter.beginItem(index + 1, serverNotebookIds.size, notebookId, notebookId)
            val book = appRepository.bookRepository.getById(notebookId)
            val hasDirtyPages = book != null && notebookSyncService.hasLocallyDirtyPages(book)
            val alreadyMirror = book != null && !hasDirtyPages &&
                isCommittedMirror(book, client)
            if (alreadyMirror) {
                log.i(TAG, "Skipping already-mirrored notebook: $notebookId")
            } else {
                if (hasDirtyPages) {
                    appRepository.pageSyncStateRepository.deleteByNotebook(notebookId)
                }
                notebookSyncService.downloadNotebook(notebookId, client)
                    .onError { error ->
                        log.e(TAG, "Failed to download $notebookId: ${error.userMessage}")
                        errors.add(error)
                    }
            }
            reporter.endItem()
        }

        // 4. Now that every server notebook is present and re-parented, remove local data the server
        //    no longer has so local ends up == server: notebooks first (with their sync rows), then
        //    extra folders. Folder pruning is skipped when the server had no folders.json -- refusing
        //    to destroy the local folder tree on missing data, consistent with the guards above.
        val serverIdSet = serverNotebookIds.toSet()
        appRepository.bookRepository.getAll().filter { it.id !in serverIdSet }.forEach { extra ->
            log.i(TAG, "Deleting local notebook not on server: ${extra.title}")
            try {
                appRepository.bookRepository.delete(extra.id)
                appRepository.notebookSyncStateRepository.delete(extra.id)
                appRepository.pageSyncStateRepository.deleteByNotebook(extra.id)
            } catch (e: Exception) {
                errors.add(DomainError.DatabaseError("Failed to delete local notebook ${extra.id}: ${e.message}"))
            }
        }
        serverFolderIds?.let { keep ->
            appRepository.folderRepository.getAll().filter { it.id !in keep }.forEach { extra ->
                log.i(TAG, "Deleting local folder not on server: ${extra.id}")
                try {
                    appRepository.folderRepository.delete(extra.id)
                } catch (e: Exception) {
                    errors.add(DomainError.DatabaseError("Failed to delete local folder ${extra.id}: ${e.message}"))
                }
            }
        }

        return errors.asResult(Unit).onSuccess {
            log.i(TAG, "FORCE DOWNLOAD complete")
        }
    }

    /**
     * Apply the server's folder set to the local DB in place. The serialized list has no ordering
     * contract, so validate and order it parent-first before writing; this also handles hierarchy
     * rearrangements without transient foreign-key failures. Deletion of local-only folders is left
     * to the caller, after notebooks have been re-parented, so a folder's ON DELETE CASCADE never
     * takes a notebook that still exists on the server.
     */
    private suspend fun reconcileFolders(serverFolders: List<Folder>) {
        val serverById = serverFolders.associateBy { it.id }
        require(serverById.size == serverFolders.size) { "Duplicate folder IDs in folders.json" }

        val ordered = mutableListOf<Folder>()
        val visiting = mutableSetOf<String>()
        val visited = mutableSetOf<String>()
        fun visit(folder: Folder) {
            if (folder.id in visited) return
            require(visiting.add(folder.id)) { "Folder cycle in folders.json at ${folder.id}" }
            folder.parentFolderId?.let { parentId ->
                val parent = serverById[parentId]
                    ?: error("Folder ${folder.id} references missing parent $parentId")
                visit(parent)
            }
            visiting.remove(folder.id)
            visited.add(folder.id)
            ordered.add(folder)
        }
        serverFolders.forEach(::visit)

        val localById = appRepository.folderRepository.getAll().associateBy { it.id }
        for (folder in ordered) {
            val local = localById[folder.id]
            when {
                local == null -> appRepository.folderRepository.create(folder)
                local != folder -> appRepository.folderRepository.update(folder)
            }
        }
    }

    /**
     * Whether [notebook] is committed SYNCED against the server's *current* manifest ETag. Used by
     * the force download to skip a notebook that is already an exact mirror, so a resumed run does not
     * re-fetch it. False when the row is missing/ERROR, has no stored ETag, or the server ETag can't
     * be read or differs -- all of which route to a (re-)download, which is safe if not economical.
     * The caller pairs this with a dirty-pages check so a local edit still overwrites the server copy.
     */
    private suspend fun isCommittedMirror(
        notebook: Notebook,
        client: WebDAVClient
    ): Boolean {
        val state = appRepository.notebookSyncStateRepository.get(notebook.id) ?: return false
        if (state.state != SyncStateValue.SYNCED) return false
        // A matching server ETag proves only that the remote is unchanged. Require the local
        // manifest anchor too, otherwise metadata-only edits (title, folder, page order, defaults)
        // would be skipped instead of replaced by the forced download.
        if (notebook.updatedAt != state.syncedLocalUpdatedAt) return false
        // A sync row is not proof that the page row still exists (for example after restoring an
        // inconsistent backup). Let downloadNotebook repair any hole instead of skipping forever.
        val localPageIds = appRepository.pageRepository.getByIds(notebook.pageIds)
            .mapTo(mutableSetOf()) { it.id }
        if (!localPageIds.containsAll(notebook.pageIds)) return false
        val storedEtag = ETag.parse(state.remoteEtag) ?: return false
        val serverEtag = client.resourceEtag(SyncPaths.manifestFile(notebook.id))
            .getOrElse { return false }
        return storedEtag.matches(serverEtag)
    }

    companion object {
        private const val TAG = "SyncForceService"
    }
}
