package com.ethran.notable.sync

import android.content.Context
import android.net.Uri
import com.ethran.notable.data.AppRepository
import com.ethran.notable.data.db.Notebook
import com.ethran.notable.data.db.Page
import com.ethran.notable.data.db.PageSyncState
import com.ethran.notable.data.ensureBackgroundsFolder
import com.ethran.notable.data.ensureImagesFolder
import com.ethran.notable.sync.PageSyncSelector.selectDirtyPages
import com.ethran.notable.sync.serializers.NotebookSerializer
import com.ethran.notable.utils.AppResult
import com.ethran.notable.utils.DomainError
import com.ethran.notable.utils.ErrorAccumulator
import com.ethran.notable.utils.flatMap
import com.ethran.notable.utils.getOrElse
import com.ethran.notable.utils.map
import com.ethran.notable.utils.onError
import com.ethran.notable.utils.onFailure
import com.ethran.notable.utils.onSuccess
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.Date
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NotebookSyncService @Inject constructor(
    private val appRepository: AppRepository,
    private val reporter: SyncProgressReporter,
    @param:ApplicationContext private val context: Context
) {
    private val log = SyncLogger

    suspend fun applyRemoteDeletions(
        client: WebDAVClient,
        maxAgeDays: Long
    ): AppResult<Set<String>, DomainError> {
        log.i(TAG, "Applying remote deletions...")
        val tombstonesPath = SyncPaths.tombstonesDir()

        val tombstonesExist = client.exists(tombstonesPath).onFailure { return AppResult.Error(it) }
        if (!tombstonesExist) return AppResult.Success(emptySet())

        return client.listCollectionWithMetadata(tombstonesPath).flatMap { tombstones ->
            val tombstonedIds = tombstones.map { it.name }.toSet()
            val errors = ErrorAccumulator()

            if (tombstones.isNotEmpty()) {
                log.i(TAG, "Server has ${tombstones.size} tombstone(s)")
                for (tombstone in tombstones) {
                    val notebookId = tombstone.name
                    val deletedAt = tombstone.lastModified
                    val localNotebook = appRepository.bookRepository.getById(notebookId) ?: continue

                    if (deletedAt != null && localNotebook.updatedAt.after(deletedAt)) {
                        log.i(
                            TAG,
                            "↻ Resurrecting '${localNotebook.title}' (modified after server deletion)"
                        )
                        continue
                    }

                    log.i(
                        TAG,
                        "Deleting locally (tombstone on server): ${localNotebook.title}"
                    )
                    try {
                        appRepository.bookRepository.delete(notebookId)
                        // Gone on both sides now -- drop the sync-state row so it is not later
                        // mis-read as a local deletion to re-tombstone, and the per-page rows with
                        // it: a resurrected notebook must re-download every page, not skip pages
                        // whose stale ETag still matches while no local page row exists.
                        appRepository.notebookSyncStateRepository.delete(notebookId)
                        appRepository.pageSyncStateRepository.deleteByNotebook(notebookId)
                    } catch (e: Exception) {
                        log.e(TAG, "Failed to delete ${localNotebook.title}: ${e.message}")
                        errors.add(DomainError.DatabaseError("Failed to delete ${localNotebook.title}"))
                    }
                }
            }

            val cutoff = Date(System.currentTimeMillis() - maxAgeDays * 86_400_000L)
            val stale =
                tombstones.filter { it.lastModified != null && it.lastModified.before(cutoff) }
            if (stale.isNotEmpty()) {
                log.i(TAG, "Pruning ${stale.size} stale tombstone(s) older than $maxAgeDays days")
                for (entry in stale) {
                    client.delete(SyncPaths.tombstone(entry.name)).onError {
                        log.w(TAG, "Failed to prune tombstone ${entry.name}: ${it.userMessage}")
                    }
                }
            }

            errors.asResult(tombstonedIds)
        }
    }

    /**
     * Remove orphaned remote notebook directories: a `/notebooks/<id>/` that has no manifest.json
     * and that we do NOT hold locally. This is the leftover of an interrupted upload (pages-first,
     * manifest-last) or a partial delete from *another* device, which 3a can't self-heal (3a re-uploads
     * only the ones we own locally). Strictly time-gated by the directory's own last-modified: only
     * dirs older than [maxAgeDays] are touched, so an in-flight upload — whose dir is recent — is
     * never raced. Best-effort: failures are logged, never fatal to the run. (P6 cleanup / 3c)
     */
    suspend fun garbageCollectOrphanedRemotes(
        client: WebDAVClient,
        localNotebookIds: Set<String>,
        maxAgeDays: Long
    ) {
        val cutoff = Date(System.currentTimeMillis() - maxAgeDays * 86_400_000L)
        val entries = client.listCollectionWithMetadata(SyncPaths.notebooksDir()).getOrElse {
            log.w(TAG, "Orphan GC: listing notebooks failed: ${it.userMessage}")
            return
        }
        for (entry in entries) {
            val id = entry.name
            // Owned locally -> 3a re-uploads it to self-heal; never GC a notebook we still have.
            if (id in localNotebookIds) continue
            // Unknown age -> don't risk deleting what might be an in-flight upload.
            val lastModified = entry.lastModified ?: continue
            if (!lastModified.before(cutoff)) continue
            // A manifest means a real, healthy notebook this device just doesn't have yet (it will be
            // downloaded as new) -> leave it. On an ambiguous error, assume present and skip.
            val hasManifest = client.exists(SyncPaths.manifestFile(id)).getOrElse { true }
            if (hasManifest) continue
            log.i(TAG, "Orphan GC: removing dead remote notebook $id (no manifest, older than $maxAgeDays days)")
            client.delete(SyncPaths.notebookDir(id)).onError {
                log.w(TAG, "Orphan GC: failed to remove $id: ${it.userMessage}")
            }
        }
    }

    suspend fun detectAndUploadLocalDeletions(
        client: WebDAVClient, preDownloadNotebookIds: Set<String>
    ): AppResult<Int, DomainError> {
        log.i(TAG, "Detecting local deletions...")
        // A notebook we recorded as synced but that is no longer local was deleted here.
        val syncedIds = appRepository.notebookSyncStateRepository.getAllIds()
        val deletedLocally = syncedIds - preDownloadNotebookIds
        val errors = ErrorAccumulator()

        if (deletedLocally.isNotEmpty()) {
            log.i(TAG, "Detected ${deletedLocally.size} local deletion(s)")
            for (notebookId in deletedLocally) {
                val notebookPath = SyncPaths.notebookDir(notebookId)
                // Unknown existence is recorded but does not stop the tombstone PUT below.
                val onServer = client.exists(notebookPath).onError { errors.add(it) }.getOrElse { false }
                if (onServer) {
                    log.i(TAG, "Deleting from server: $notebookId")
                    client.delete(notebookPath).onError { errors.add(it) }
                }
                client.putFile(
                    SyncPaths.tombstone(notebookId), ByteArray(0), "application/octet-stream"
                ).onSuccess {
                    log.i(TAG, "Tombstone uploaded for: $notebookId")
                    // Deletion propagated -- drop the sync-state rows so it is not detected again.
                    appRepository.notebookSyncStateRepository.delete(notebookId)
                    appRepository.pageSyncStateRepository.deleteByNotebook(notebookId)
                }.onError { error ->
                    log.e(TAG, "Failed to upload tombstone for $notebookId: ${error.userMessage}")
                    errors.add(error)
                }
            }
        } else {
            log.i(TAG, "No local deletions detected")
        }

        return errors.asResult(deletedLocally.size)
    }

    suspend fun downloadNewNotebooks(
        client: WebDAVClient,
        tombstonedIds: Set<String>,
        preDownloadNotebookIds: Set<String>,
        remoteNotebookIds: Set<String>
    ): AppResult<Int, DomainError> {
        log.i(TAG, "Checking server for new notebooks...")
        // Notebooks we previously synced but are no longer local were deleted here; don't
        // re-download them (they get tombstoned by detectAndUploadLocalDeletions instead).
        val syncedIds = appRepository.notebookSyncStateRepository.getAllIds()
        // remoteNotebookIds is the single PROPFIND listing shared with reconciliation (5a).
        val newNotebookIds = remoteNotebookIds
            .filter { it !in preDownloadNotebookIds }
            .filter { it !in tombstonedIds }
            .filter { it !in syncedIds }

        val errors = ErrorAccumulator()
        if (newNotebookIds.isNotEmpty()) {
            log.i(TAG, "Found ${newNotebookIds.size} new notebook(s) on server")
            val total = newNotebookIds.size
            newNotebookIds.forEachIndexed { i, notebookId ->
                reporter.beginItem(index = i + 1, total = total, name = notebookId, id = notebookId)
                downloadNotebook(notebookId, client).onError { errors.add(it) }
            }
            reporter.endItem()
        } else {
            log.i(TAG, "No new notebooks on server")
        }

        return errors.asResult(newNotebookIds.size)
    }

    /**
     * Upload a notebook, recording an ERROR sync-state row on any failure so the library shows the
     * ERROR badge (P25). A successful upload writes the SYNCED row inside [uploadNotebookInternal].
     */
    suspend fun uploadNotebook(
        notebook: Notebook,
        client: WebDAVClient,
        manifestIfMatch: String? = null
    ): AppResult<Unit, DomainError> {
        val result = uploadNotebookInternal(notebook, client, manifestIfMatch)
        if (result is AppResult.Error) {
            appRepository.notebookSyncStateRepository.markError(notebook.id, result.error.userMessage)
        }
        return result
    }

    private suspend fun uploadNotebookInternal(
        notebook: Notebook,
        client: WebDAVClient,
        manifestIfMatch: String? = null
    ): AppResult<Unit, DomainError> {
        val notebookId = notebook.id
        log.i(TAG, "Uploading: ${notebook.title} (${notebook.pageIds.size} pages)")

        return client.ensureParentDirectories(SyncPaths.pagesDir(notebookId) + "/").flatMap {
            client.createCollection(SyncPaths.imagesDir(notebookId))
        }.flatMap {
            client.createCollection(SyncPaths.backgroundsDir(notebookId))
        }.flatMap {
            // 1. Upload only the DIRTY pages FIRST. Pages unchanged since their last
            //    committed sync are skipped -- editing one page of an 800-page notebook now PUTs one
            //    page, not 800. Skipped pages keep their existing page_sync_state row.
            val pages = appRepository.pageRepository.getByIds(notebook.pageIds)
            val rowsByPageId =
                appRepository.pageSyncStateRepository.getByNotebook(notebookId).associateBy { it.pageId }
            val dirtyPages = selectDirtyPages(pages, rowsByPageId)
            log.i(TAG, "${dirtyPages.size}/${pages.size} page(s) dirty, uploading")

            val errors = ErrorAccumulator()
            val committedPageRows = mutableListOf<PageSyncState>()
            for (page in dirtyPages) {
                // Guard the dirty-page PUT with the stored ETag. A concurrent remote
                // change to this page 412s, aborting before manifest publish (nothing committed);
                // new pages (no row) send no If-Match.
                val ifMatch = rowsByPageId[page.id]?.remoteEtag
                uploadPage(page, notebookId, client, ifMatch).onSuccess { pageEtag ->
                    committedPageRows.add(
                        PageSyncState(
                            pageId = page.id,
                            notebookId = notebookId,
                            remoteEtag = pageEtag,
                            syncedLocalUpdatedAt = page.updatedAt,
                            lastSyncedAt = Date(),
                        )
                    )
                }.onError { errors.add(it) }
            }
            // Rows for pages that left the notebook are dropped in the commit transaction.
            val departedPageIds = rowsByPageId.keys - notebook.pageIds.toSet()

            // 2. If any page failed, do NOT publish the manifest. Leaving the old commit marker in
            //    place keeps the notebook "not yet updated" for other devices and makes this device
            //    re-upload on the next sync -- never a manifest pointing at missing/stale pages (P1).
            if (errors.hasErrors) {
                errors.asResult(Unit)
            } else {
                // 3. All dirty pages are up: publish the manifest last. This is the atomic commit;
                //    the If-Match guard rejects the publish if the remote changed since we read it.
                //    Capture the new ETag so next sync can do a cheap If-None-Match check (P26).
                val manifestJson = NotebookSerializer.serializeManifest(notebook)
                publishManifest(notebookId, manifestJson.toByteArray(), manifestIfMatch, client)
                    .onSuccess { newEtag ->
                    // If the server returned no ETag on any page PUT, one PROPFIND on the pages dir
                    // backfills them so the stored rows can drive next sync's skip.
                    backfillMissingPageEtags(notebookId, committedPageRows, client)
                    // Commit point: mark the notebook synced,
                    // write the uploaded pages' rows, and drop departed rows -- all in one
                    // transaction. After upload the remote's updatedAt equals the manifest we just
                    // wrote (notebook.updatedAt).
                    appRepository.commitNotebookSync(
                        notebook = notebook,
                        localUpdatedAt = notebook.updatedAt,
                        remoteUpdatedAt = notebook.updatedAt,
                        manifestEtag = newEtag,
                        committedPageRows = committedPageRows,
                        departedPageIds = departedPageIds.toList(),
                    )
                    // Only after a committed upload: drop any stale tombstone for a resurrected
                    // notebook. Best-effort -- a leftover tombstone is re-checked next sync.
                    val tombstonePath = SyncPaths.tombstone(notebookId)
                    if (client.exists(tombstonePath).getOrElse { false }) {
                        client.delete(tombstonePath).onSuccess {
                            log.i(TAG, "Removed stale tombstone for resurrected notebook: $notebookId")
                        }.onError {
                            log.w(TAG, "Failed to remove stale tombstone $notebookId: ${it.userMessage}")
                        }
                    }
                    log.i(TAG, "Uploaded: ${notebook.title}")
                    // Best-effort GC: remove remote page/image/background files no longer
                    // referenced by the manifest we just committed (P11). Never fails the upload.
                    garbageCollectRemote(notebook, client)
                }.map { }
            }
        }
    }

    /**
     * Fill in ETags for uploaded pages whose PUT response carried none, via one Depth-1 PROPFIND on
     * the pages directory. A page row with a null ETag would force a re-download next sync (its
     * stored ETag never matches the server's), so this keeps the skip path effective on servers that
     * don't echo an ETag on PUT. Best-effort: on PROPFIND failure the rows keep their null ETags.
     */
    private suspend fun backfillMissingPageEtags(
        notebookId: String,
        rows: MutableList<PageSyncState>,
        client: WebDAVClient
    ) {
        if (rows.none { it.remoteEtag == null }) return
        val etagsByName = client.listEtags(SyncPaths.pagesDir(notebookId)).getOrElse {
            log.w(TAG, "Page ETag backfill PROPFIND failed: ${it.userMessage}")
            return
        }
        for (i in rows.indices) {
            val row = rows[i]
            if (row.remoteEtag == null) {
                etagsByName["${row.pageId}.json"]?.let { rows[i] = row.copy(remoteEtag = it) }
            }
        }
    }

    /**
     * Publish the manifest atomically: PUT to a `.tmp` sibling (a full write that never touches the
     * live commit marker), then MOVE it over `manifest.json`. This closes the only interruption
     * window a plain PUT leaves open — a torn PUT of the manifest itself would otherwise leave a corrupt
     * commit marker. Falls back to a direct guarded PUT when the server doesn't support MOVE.
     *
     * A 412 during MOVE is a genuine concurrency conflict and is propagated (not retried). The tmp
     * file is cleaned up best-effort; if left behind (interrupted before MOVE) it is simply
     * overwritten by the next upload.
     *
     * Returns the published manifest's ETag when known. The MOVE path can't reliably report the
     * destination's post-move ETag, so it returns `null` there — self-correcting, since the next
     * sync does one full GET and the skip path backfills the ETag.
     */
    private suspend fun publishManifest(
        notebookId: String,
        manifestBytes: ByteArray,
        ifMatch: String?,
        client: WebDAVClient
    ): AppResult<String?, DomainError> {
        val finalPath = SyncPaths.manifestFile(notebookId)
        val tmpPath = "$finalPath.tmp"

        client.putFile(tmpPath, manifestBytes, "application/json")
            .onFailure { return AppResult.Error(it) }

        return when (val moved = client.move(tmpPath, finalPath, ifMatchDestination = ifMatch)) {
            is AppResult.Success -> AppResult.Success(null)
            is AppResult.Error -> {
                client.delete(tmpPath) // best-effort cleanup either way
                if (moved.error is DomainError.SyncConflict) {
                    // Destination changed under us -- a real conflict, do not fall back.
                    moved
                } else {
                    // MOVE unsupported or transient: fall back to a direct guarded PUT (the manifest
                    // is still written last, so ordering is preserved; only atomicity is lost).
                    log.w(TAG, "MOVE publish failed (${moved.error.userMessage}); direct PUT fallback")
                    client.putFileReturningEtag(finalPath, manifestBytes, "application/json", ifMatch)
                }
            }
        }
    }

    /**
     * Upload one page's JSON (streamed from a temp file) plus its images/backgrounds, returning the
     * page file's new server ETag (or `null` if the server sent none). [ifMatch] guards the page PUT
     * against a concurrent remote change; pass `null` for a page with no prior sync row.
     */
    private suspend fun uploadPage(
        page: Page,
        notebookId: String,
        client: WebDAVClient,
        ifMatch: String? = null
    ): AppResult<String?, DomainError> {
        val pageWithData =
            appRepository.pageRepository.getWithDataById(page.id) ?: return AppResult.Error(
                DomainError.DatabaseError("Page data not found for page ID: ${page.id}")
            )
        // Serialize the page to a temp file (streaming, one stroke at a time) and stream that file
        // to the PUT, rather than building a whole-page JSON string + toByteArray() in memory. This
        // bounds upload memory to ~one stroke regardless of page size — a 12k-stroke page otherwise
        // materialised its point data several times over and OOM'd.
        val tempFile = File.createTempFile("notable-page-", ".json", context.cacheDir)
        val pagePath = SyncPaths.pageFile(notebookId, page.id)
        val pageUploaded = try {
            tempFile.outputStream().buffered().use { out ->
                NotebookSerializer.serializePage(page, pageWithData.strokes, pageWithData.images, out)
            }
            val guarded = client.putFileReturningEtag(pagePath, tempFile, "application/json", ifMatch)
            // A page-level 412 is NOT a terminal conflict in 10-I. The manifest publish's own If-Match
            // is the real whole-notebook last-writer-wins guard; a page's stored ETag can be stale for
            // benign reasons that must self-heal, not wedge the notebook in a permanent false conflict:
            //  - our own prior sync PUT this page then failed on a later media upload, so the row was
            //    never committed and still holds the pre-PUT ETag (review Risk 1);
            //  - the server regenerated the ETag with no content change (review Risk 2 / the "harmless
            //    re-transfer" design note).
            // Overwrite unconditionally; a genuine concurrent *notebook* change is still caught when
            // the manifest publish 412s, keeping page-file clobbering invisible to other devices (they
            // stay gated on the manifest commit marker). True page-level conflict handling is 10f.
            if (guarded is AppResult.Error && guarded.error is DomainError.SyncConflict && ifMatch != null) {
                log.w(TAG, "Page ${page.id} If-Match 412; overwriting (manifest guard still applies)")
                client.putFileReturningEtag(pagePath, tempFile, "application/json", null)
            } else {
                guarded
            }
        } finally {
            tempFile.delete()
        }
        return pageUploaded.flatMap { pageEtag ->
            val errors = ErrorAccumulator()
            for (image in pageWithData.images) {
                if (!image.uri.isNullOrEmpty()) {
                    // Normalize the URI: some rows store a `file://` scheme that File() can't resolve,
                    // which silently skipped the upload and left a dangling reference.
                    val localFile = resolveLocalFile(image.uri)
                    if (localFile.exists()) {
                        val remotePath = SyncPaths.imageFile(notebookId, localFile.name)
                        // Unknown existence -> upload anyway; PUT is idempotent.
                        if (!client.exists(remotePath).getOrElse { false }) {
                            client.putFile(remotePath, localFile, detectMimeType(localFile))
                                .onSuccess {
                                    log.i(TAG, "Uploaded image: ${localFile.name}")
                                }.onError { errors.add(it) }
                        }
                    } else {
                        // Dangling reference: the page references an image we can't find locally, so
                        // it will 404 on every other device. Surface it.
                        log.w(TAG, "Image referenced by page but missing locally: ${image.uri}")
                    }
                }
            }

            // Linked external PDFs (absolute path outside managed storage) can't be synced -- skip
            // them; the reference is left as-is and is non-fatal on download.
            if (page.backgroundType != "native" && page.background != "blank" &&
                !File(page.background).isAbsolute
            ) {
                val bgFile = File(ensureBackgroundsFolder(), page.background)
                if (bgFile.exists()) {
                    val remotePath = SyncPaths.backgroundFile(notebookId, bgFile.name)
                    if (!client.exists(remotePath).getOrElse { false }) {
                        client.putFile(remotePath, bgFile, detectMimeType(bgFile)).onSuccess {
                            log.i(TAG, "Uploaded background: ${bgFile.name}")
                        }.onError { errors.add(it) }
                    }
                } else {
                    log.w(TAG, "Background referenced by page but missing locally: ${page.background}")
                }
            }

            errors.asResult(pageEtag)
        }
    }

    /**
     * Download a notebook, recording an ERROR sync-state row on any failure so the library shows
     * the ERROR badge (P25). A successful download writes the SYNCED row inside
     * [downloadNotebookInternal].
     */
    suspend fun downloadNotebook(
        notebookId: String,
        client: WebDAVClient
    ): AppResult<Unit, DomainError> {
        val result = downloadNotebookInternal(notebookId, client)
        if (result is AppResult.Error) {
            appRepository.notebookSyncStateRepository.markError(notebookId, result.error.userMessage)
        }
        return result
    }

    private suspend fun downloadNotebookInternal(
        notebookId: String,
        client: WebDAVClient
    ): AppResult<Unit, DomainError> {
        log.i(TAG, "Downloading notebook ID: $notebookId")

        // 1. Fetch manifest file with its ETag (Early Return on error). The ETag is stored at the
        //    commit point so next sync can do a cheap If-None-Match check (P26).
        val manifestFile = client.getFileWithMetadata(SyncPaths.manifestFile(notebookId))
            .onFailure { return AppResult.Error(it) }
        val remoteEtag = manifestFile.etag

        // 2. Deserialize manifest (Early Return on corrupted JSON)
        val manifestJson = manifestFile.content.decodeToString()
        val notebook = NotebookSerializer.deserializeManifest(manifestJson)
            .onFailure { return AppResult.Error(it) }

        log.i(TAG, "Found notebook: ${notebook.title} (${notebook.pageIds.size} pages)")

        // 3. Ensure a notebook row exists so page foreign keys resolve, but do NOT advance its
        //    commit timestamp yet. A brand-new notebook is inserted with an epoch-0 timestamp so a
        //    partial download reads as "older than remote" and re-downloads next sync instead of
        //    being skipped as in-sync (P1 download half). An existing notebook keeps its current
        //    (older) timestamp untouched.
        val isNew = appRepository.bookRepository.getById(notebookId) == null
        if (isNew) {
            try {
                appRepository.bookRepository.createEmpty(notebook.copy(updatedAt = Date(0)))
            } catch (e: Exception) {
                return AppResult.Error(DomainError.DatabaseError("Failed to create notebook locally: ${e.message}"))
            }
        }

        // 4. One Depth-1 PROPFIND on the pages dir gives every page's current remote ETag, so we can
        //    fetch only the pages that changed since our last committed sync. On PROPFIND
        //    failure the map is empty -> every page's current ETag reads null -> all pages fetched
        //    which is safe, just not economical.
        val currentEtagByPageId = client.listEtags(SyncPaths.pagesDir(notebookId)).getOrElse {
            log.w(TAG, "Page listing PROPFIND failed for ${notebook.title}; fetching all pages: ${it.userMessage}")
            emptyMap<String, String?>()
        }.mapNotNull { (name, etag) ->
            if (name.endsWith(".json")) name.removeSuffix(".json") to etag else null
        }.toMap()
        // A server that lists pages but omits <getetag> makes download-skip a silent no-op (every page
        // re-fetched every sync). Correct, just not economical -- surface it once so it's diagnosable.
        if (currentEtagByPageId.isNotEmpty() && currentEtagByPageId.values.all { it == null }) {
            log.w(TAG, "Server returned no page ETags for ${notebook.title}; download-skip disabled (fetching all)")
        }
        val rowsByPageId =
            appRepository.pageSyncStateRepository.getByNotebook(notebookId).associateBy { it.pageId }
        // Which of the manifest's pages we actually hold locally. A `page_sync_state` row is not
        // proof the page row still exists (it may have been deleted locally while offline, or the
        // notebook restored from a backup that predates it), and skipping a page we don't have would
        // leave the notebook permanently referencing a page with no content.
        val localPageIds =
            appRepository.pageRepository.getByIds(notebook.pageIds).map { it.id }.toSet()

        // 5. Download and persist the changed pages; skipped pages keep their local content and row.
        val errors = ErrorAccumulator()
        val committedPageRows = mutableListOf<PageSyncState>()
        // Backgrounds are often shared across pages (e.g. a PDF); fetch each distinct one once.
        val attemptedBackgrounds = mutableSetOf<String>()
        var fetched = 0
        for (pageId in notebook.pageIds) {
            val currentEtag = currentEtagByPageId[pageId]
            val storedEtag = rowsByPageId[pageId]?.remoteEtag
            // Fetch when we have no committed row, when the page is missing locally, when we
            // couldn't read the remote ETag (fetch to be safe), or when the remote ETag differs
            // from what we last committed.
            val needsFetch = rowsByPageId[pageId] == null ||
                pageId !in localPageIds ||
                currentEtag == null ||
                storedEtag != currentEtag
            if (!needsFetch) continue
            fetched++
            downloadPage(pageId, notebookId, client, attemptedBackgrounds).onSuccess { pageUpdatedAt ->
                committedPageRows.add(
                    PageSyncState(
                        pageId = pageId,
                        notebookId = notebookId,
                        remoteEtag = currentEtag,
                        syncedLocalUpdatedAt = pageUpdatedAt,
                        lastSyncedAt = Date(),
                    )
                )
            }.onError { errors.add(it) }
        }
        log.i(TAG, "Downloaded $fetched/${notebook.pageIds.size} changed page(s) for ${notebook.title}")

        // 6. Commit: only when every fetched page landed, write the notebook row with the real remote
        //    timestamp. On any failure the notebook keeps its old/sentinel timestamp, so the next
        //    sync retries the download rather than treating the hole as "in sync".
        if (errors.hasErrors) {
            log.w(TAG, "Download incomplete for ${notebook.title}; leaving timestamp stale to retry")
            return errors.asResult(Unit)
        }
        val departedPageIds =
            (rowsByPageId.keys - notebook.pageIds.toSet()).toList()
        return try {
            appRepository.bookRepository.updatePreservingTimestamp(notebook)
            // Commit point: mark the notebook synced at the remote
            // timestamp, write the fetched pages' rows, and drop rows for departed pages -- one
            // transaction. Skipped pages keep their prior rows untouched.
            appRepository.commitNotebookSync(
                notebook = notebook,
                localUpdatedAt = notebook.updatedAt,
                remoteUpdatedAt = notebook.updatedAt,
                manifestEtag = remoteEtag,
                committedPageRows = committedPageRows,
                departedPageIds = departedPageIds,
            )
            // Best-effort GC: delete local pages that are no longer in the downloaded manifest (P11).
            pruneLocalOrphanPages(notebook)
            log.i(TAG, "Downloaded: ${notebook.title}")
            AppResult.Success(Unit)
        } catch (e: Exception) {
            AppResult.Error(DomainError.DatabaseError("Failed to commit notebook $notebookId: ${e.message}"))
        }
    }

    /**
     * Fetch, persist, and return one page's `updatedAt` (used as its `page_sync_state` anchor). A
     * permanently-missing media file (404) is non-fatal; a transient media error is aggregated so
     * the page is retried next sync.
     */
    private suspend fun downloadPage(
        pageId: String,
        notebookId: String,
        client: WebDAVClient,
        attemptedBackgrounds: MutableSet<String>
    ): AppResult<Date, DomainError> {

        // 1. Fetch JSON file (Early Return on error)
        val pageBytes = client.getFile(SyncPaths.pageFile(notebookId, pageId))
            .onFailure { return AppResult.Error(it) }

        // 2. Deserialize (Early Return on corrupted JSON)
        val pageJson = pageBytes.decodeToString()
        val (page, strokes, images) = NotebookSerializer.deserializePage(pageJson)
            .onFailure { return AppResult.Error(it) }

        val errors = ErrorAccumulator()

        // 3. Download embedded images
        val updatedImages = images.map { image ->
            if (!image.uri.isNullOrEmpty()) {
                val filename = extractFilename(image.uri)
                val localFile = File(ensureImagesFolder(), filename)

                if (!localFile.exists()) {
                    client.getFile(
                        SyncPaths.imageFile(notebookId, filename),
                        localFile
                    ).onSuccess {
                        log.i(TAG, "Downloaded image: $filename")
                    }.onError { error -> addMediaError(errors, "image $filename", error) }
                }
                image.copy(uri = localFile.absolutePath)
            } else {
                image
            }
        }

        // 4. Download page background.
        if (page.backgroundType != "native" && page.background != "blank") {
            val filename = page.background
            // Linked external PDFs live outside managed storage (absolute path); they can't be synced
            // -- skip them entirely, and don't treat their absence as a failure.
            if (File(filename).isAbsolute) {
                log.i(TAG, "Skipping external background (not in managed storage): $filename")
            } else if (filename in attemptedBackgrounds) {
                // A background shared by many pages is only fetched once per notebook.
            } else {
                attemptedBackgrounds.add(filename)
                val localFile = File(ensureBackgroundsFolder(), filename)
                if (!localFile.exists()) {
                    localFile.parentFile?.mkdirs()
                    // Remote path uses the basename (flat), matching how upload stores it.
                    val remoteName = File(filename).name
                    client.getFile(
                        SyncPaths.backgroundFile(notebookId, remoteName),
                        localFile
                    ).onSuccess {
                        log.i(TAG, "Downloaded background: $filename")
                    }.onError { error -> addMediaError(errors, "background $filename", error) }
                }
            }
        }

        // 5. Persist the page atomically: delete-old + update + insert-new run in one transaction,
        //    so a crash can't leave the page with old strokes gone and new ones not yet written (P5).
        try {
            appRepository.replaceDownloadedPage(page, strokes, updatedImages)
        } catch (e: Exception) {
            errors.add(DomainError.DatabaseError("Failed to save page $pageId: ${e.message}"))
        }

        // 6. Return aggregated result, carrying the page's updatedAt as its sync anchor.
        return errors.asResult(page.updatedAt)
    }

    /**
     * Delete remote page/image/background files that the just-committed manifest no longer
     * references. The manifest is authoritative (we just wrote it), so anything not referenced is a
     * true orphan from a deleted or replaced page. Best-effort: every failure is logged, never fatal.
     */
    private suspend fun garbageCollectRemote(notebook: Notebook, client: WebDAVClient) {
        val notebookId = notebook.id
        val referencedPageFiles = notebook.pageIds.map { "$it.json" }.toSet()
        val referencedImages = mutableSetOf<String>()
        val referencedBackgrounds = mutableSetOf<String>()
        for (pageId in notebook.pageIds) {
            // GC only needs the page's media filenames — load the page metadata + image URIs, NOT
            // getWithDataById (which loads and normalizes every stroke on the page). On a large
            // notebook the old call re-materialised the whole notebook right after upload and
            // re-triggered the OOM.
            val page = appRepository.pageRepository.getById(pageId) ?: continue
            appRepository.imageRepository.getUrisForPage(pageId).forEach { uri ->
                if (!uri.isNullOrEmpty()) referencedImages.add(File(uri).name)
            }
            if (page.backgroundType != "native" && page.background != "blank") {
                referencedBackgrounds.add(File(page.background).name)
            }
        }
        pruneRemoteDir(client, SyncPaths.pagesDir(notebookId), referencedPageFiles)
        pruneRemoteDir(client, SyncPaths.imagesDir(notebookId), referencedImages)
        pruneRemoteDir(client, SyncPaths.backgroundsDir(notebookId), referencedBackgrounds)
    }

    /** Delete entries of [dirPath] whose name is not in [keep]. Best-effort. */
    private suspend fun pruneRemoteDir(
        client: WebDAVClient,
        dirPath: String,
        keep: Set<String>
    ) {
        client.listNames(dirPath).onSuccess { names ->
            for (name in names.filter { it !in keep }) {
                client.delete("$dirPath/$name").onSuccess {
                    log.i(TAG, "GC: removed orphan $dirPath/$name")
                }.onError { log.w(TAG, "GC: failed to remove $name: ${it.userMessage}") }
            }
        }.onError { log.w(TAG, "GC: listing $dirPath failed: ${it.userMessage}") }
    }

    /** Delete local pages of [notebook] whose ids left the downloaded manifest. Best-effort. */
    private suspend fun pruneLocalOrphanPages(notebook: Notebook) {
        val keep = notebook.pageIds.toSet()
        val localPageIds = appRepository.pageRepository.getPageIdsForNotebook(notebook.id)
        for (orphan in localPageIds.filter { it !in keep }) {
            try {
                appRepository.pageRepository.delete(orphan)
                log.i(TAG, "GC: removed local orphan page $orphan")
            } catch (e: Exception) {
                log.w(TAG, "GC: failed to delete local page $orphan: ${e.message}")
            }
        }
    }

    /**
     * Aggregate a media (image/background) download failure. A [DomainError.RemoteMissing] (404) is
     * a *permanent* absence — log and skip it so one missing media file can't wedge the whole
     * notebook download into an endless retry. Transient errors are still aggregated so
     * the notebook keeps its stale timestamp and retries next sync.
     */
    private fun addMediaError(errors: ErrorAccumulator, what: String, error: DomainError) {
        if (error is DomainError.RemoteMissing) {
            log.w(TAG, "Media missing on server, skipping $what")
        } else {
            log.e(TAG, "Failed to download $what: ${error.userMessage}")
            errors.add(error)
        }
    }

    private fun extractFilename(uri: String): String = uri.substringAfterLast('/')

    /** Resolve a stored media URI to a [File], tolerating a `file://` scheme. */
    private fun resolveLocalFile(uri: String): File =
        if (uri.startsWith("file:")) File(Uri.parse(uri).path ?: uri) else File(uri)

    private fun detectMimeType(file: File): String = when (file.extension.lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "pdf" -> "application/pdf"
        else -> "application/octet-stream"
    }

    companion object {
        private const val TAG = "NotebookSyncService"
    }
}
