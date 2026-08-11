package com.ethran.notable.sync.couch

import com.ethran.notable.data.AppRepository
import com.ethran.notable.data.db.DeletedStroke
import com.ethran.notable.data.db.Folder
import com.ethran.notable.data.db.Image
import com.ethran.notable.data.db.Kv
import com.ethran.notable.data.db.KvDao
import com.ethran.notable.data.db.Notebook
import com.ethran.notable.data.db.Page
import com.ethran.notable.data.db.Stroke
import com.ethran.notable.data.db.decodeStrokePoints
import com.ethran.notable.data.db.encodeStrokePoints
import com.ethran.notable.data.db.withNormalizedPressure
import com.ethran.notable.editor.utils.Pen
import io.shipbook.shipbooksdk.ShipBook
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.Date
import java.util.UUID

/**
 * [CouchLocalStore] over notable's Room database — the bridge between the sync engine and what the
 * app actually reads and writes. bopa's `FileCouchStore.swift` is the twin of this class, and its
 * test suite is mirrored by `RoomCouchStoreTest`.
 *
 * Two things are worth knowing before reading further.
 *
 * **A locally recorded deletion outranks what is still in the database.** Deleting a Room row is
 * not by itself a syncable fact — an absent notebook is also what a device that never saw it looks
 * like — so deletions are recorded in `couch_deletion` and [load] consults that table first. That
 * is what makes deleting a notebook while offline work: the intent survives a restart.
 *
 * **Writing a document clears its pending deletion.** Delete-vs-edit resurrection (protocol §6.4)
 * reaches this class as a plain notebook write; without clearing, the next flush would delete the
 * notebook all over again.
 *
 * The interface is synchronous while every repository is `suspend`, so each entry point wraps its
 * work in [runBlocking]. The engine already calls this off the main thread, and the alternative —
 * making the store's interface suspend — would put a suspension point inside Swift's `actor`-shaped
 * twin for no gain.
 */
class RoomCouchStore(
    private val appRepository: AppRepository,
    private val kvDao: KvDao,
    private val deviceId: String,
    /** Called after any change the engine applied, so the UI can reload. Never called for reads. */
    private val onApplied: (() -> Unit)? = null,
) : CouchLocalStore {

    private val log = ShipBook.getLogger("RoomCouchStore")

    // region CouchLocalStore

    override fun load(documentId: String): CouchDocBody? = runBlocking {
        val (type, id) = CouchDocId.split(documentId) ?: return@runBlocking null

        // A local deletion outranks whatever rows are still present: the cascade may not have run
        // yet, and the engine needs to push the tombstone regardless.
        appRepository.couchDeletionRepository.get(documentId)?.let { pending ->
            return@runBlocking CouchDocBody.Deleted(
                CouchDeletedDoc(type = type, deletedAt = pending.deletedAt, updatedBy = deviceId)
            )
        }

        when (type) {
            CouchDocType.NOTEBOOK -> loadNotebook(id)?.let { CouchDocBody.Notebook(it) }
            CouchDocType.PAGE -> loadPage(id)?.let { CouchDocBody.Page(it) }
            CouchDocType.FOLDER -> loadFolder(id)?.let { CouchDocBody.Folder(it) }
            else -> null
        }
    }

    override fun apply(documentId: String, body: CouchDocBody) = runBlocking<Unit> {
        val (type, id) = CouchDocId.split(documentId) ?: return@runBlocking

        when (body) {
            is CouchDocBody.Notebook -> {
                applyNotebook(id, body.notebook)
                // A notebook arriving from the server un-deletes it here — that decision was
                // already made by the merge, which resurrects only when the edit is newer than the
                // deletion.
                appRepository.couchDeletionRepository.clear(documentId)
            }

            is CouchDocBody.Page -> applyPage(id, body.page)

            is CouchDocBody.Folder -> {
                applyFolder(id, body.folder)
                appRepository.couchDeletionRepository.clear(documentId)
            }

            is CouchDocBody.Deleted -> {
                when (type) {
                    // Room cascades: the notebook's pages, and their strokes and images, go with it.
                    CouchDocType.NOTEBOOK -> appRepository.bookRepository.delete(id)
                    CouchDocType.FOLDER -> appRepository.folderRepository.delete(id)
                    // Page deletions travel inside their notebook's `deletedPageIds`, not as their
                    // own tombstone document; nothing to do here.
                    else -> Unit
                }
                // The tombstone is dropped, not kept: it came *from* the server, so there is
                // nothing left to push. Locally-initiated deletions go through [recordDeletion].
                appRepository.couchDeletionRepository.clear(documentId)
            }
        }
        onApplied?.invoke()
    }

    /**
     * Protocol §6.5. The local copy is left exactly as it is and the remote one is materialized
     * alongside it under a fresh identity, so a document this build cannot understand costs the
     * user a duplicate rather than their work.
     */
    override fun applyConflictCopy(documentId: String, json: JsonObject) = runBlocking<Unit> {
        val type = CouchDocId.split(documentId)?.first ?: return@runBlocking
        if (type != CouchDocType.PAGE && type != CouchDocType.NOTEBOOK) return@runBlocking

        val stamp = DAY_FORMAT.format(Instant.now())
        val notebookId = UUID.randomUUID().toString()
        val pageId = UUID.randomUUID().toString()
        val now = Date()

        appRepository.bookRepository.createEmpty(
            Notebook(
                id = notebookId,
                title = "Unreadable sync copy ($stamp)",
                pageIds = listOf(pageId),
                createdAt = now,
                updatedAt = now,
            )
        )
        appRepository.pageRepository.create(
            Page(id = pageId, notebookId = notebookId, createdAt = now, updatedAt = now)
        )
        // Whatever could not be decoded is kept verbatim rather than reinterpreted — the point of
        // this path is that we do not understand it well enough to rewrite it.
        kvDao.set(
            Kv(
                key = conflictCopyKey(documentId, notebookId),
                value = couchJson.encodeToString(JsonObject.serializer(), json),
            )
        )
        onApplied?.invoke()
    }

    // endregion

    // region Local deletions

    /**
     * Records a locally-initiated deletion so the engine pushes a tombstone. Survives a restart,
     * which is what makes deleting a notebook while offline work.
     */
    fun recordDeletion(documentId: String, deletedAt: String = Instant.now().toString()) =
        runBlocking { appRepository.couchDeletionRepository.record(documentId, deletedAt) }

    fun pendingDeletionIds(): List<String> =
        runBlocking { appRepository.couchDeletionRepository.pendingIds() }

    // endregion

    // region Enumerating what is here

    /**
     * Every document this device holds, for the first push after setup.
     *
     * Pages are taken from their notebook's `pageIds`, as bopa does — a standalone "quick page"
     * (`notebookId == null`) belongs to no notebook and has nowhere to live on the other app, so it
     * is not offered for sync.
     */
    fun allDocumentIds(): List<String> = runBlocking {
        val ids = mutableListOf<String>()
        appRepository.folderRepository.getAll().forEach { ids += CouchDocId.folder(it.id) }
        appRepository.bookRepository.getAll().forEach { notebook ->
            ids += CouchDocId.notebook(notebook.id)
            notebook.pageIds.forEach { ids += CouchDocId.page(it) }
        }
        ids += appRepository.couchDeletionRepository.pendingIds()
        ids.distinct().sorted()
    }

    // endregion

    // region Reading

    private suspend fun loadNotebook(id: String): CouchNotebook? {
        val notebook = appRepository.bookRepository.getById(id) ?: return null
        return CouchNotebook(
            title = notebook.title,
            pageIds = notebook.pageIds,
            // Nothing records notebook-level page removals yet, so this device never asserts one.
            // Union-merge means an empty list is inert: a peer's tombstones are kept as they are.
            deletedPageIds = emptyList(),
            parentFolderId = notebook.parentFolderId,
            defaultBackground = notebook.defaultBackground,
            defaultBackgroundType = notebook.defaultBackgroundType,
            createdAt = iso(notebook.createdAt),
            updatedAt = iso(notebook.updatedAt),
            updatedBy = deviceId,
        )
    }

    private suspend fun loadPage(id: String): CouchPage? {
        val data = appRepository.pageRepository.getWithDataById(id) ?: return null
        return CouchPage(
            notebookId = data.page.notebookId,
            title = data.page.title,
            background = data.page.background,
            backgroundType = data.page.backgroundType,
            strokes = data.strokes.mapNotNull(::couchStroke),
            deletedStrokes = appRepository.deletedStrokeRepository.getByPage(id)
                .map { CouchTombstone(id = it.strokeId, deletedAt = iso(it.deletedAt)) },
            images = data.images.map(::couchImage),
            // No local record of erased images yet; see `deletedPageIds` above.
            deletedImages = emptyList(),
            createdAt = iso(data.page.createdAt),
            updatedAt = iso(data.page.updatedAt),
            updatedBy = deviceId,
        )
    }

    private suspend fun loadFolder(id: String): CouchFolder? {
        val folder = appRepository.folderRepository.get(id) ?: return null
        return CouchFolder(
            title = folder.title,
            parentFolderId = folder.parentFolderId,
            createdAt = iso(folder.createdAt),
            updatedAt = iso(folder.updatedAt),
            updatedBy = deviceId,
        )
    }

    /**
     * A stroke whose points cannot be re-encoded is skipped rather than failing the whole page —
     * the same tolerance [com.ethran.notable.sync.serializers.NotebookSerializer] applies, and for
     * the same reason: one unreadable stroke must not cost the user the other thousand.
     */
    private fun couchStroke(stroke: Stroke): CouchStroke? = try {
        CouchStroke(
            id = stroke.id,
            createdAt = iso(stroke.createdAt),
            updatedAt = iso(stroke.updatedAt),
            deviceId = deviceId,
            pen = stroke.pen.penName,
            color = stroke.color,
            size = stroke.size,
            maxPressure = stroke.maxPressure,
            top = stroke.top,
            bottom = stroke.bottom,
            left = stroke.left,
            right = stroke.right,
            pointsData = Base64.getEncoder().encodeToString(encodeStrokePoints(stroke.points)),
        )
    } catch (e: Exception) {
        log.e("Skipping stroke ${stroke.id}, its points will not encode: ${e.message}")
        null
    }

    private fun couchImage(image: Image): CouchImage = CouchImage(
        id = image.id,
        assetId = image.uri,
        x = image.x,
        y = image.y,
        width = image.width,
        height = image.height,
        createdAt = iso(image.createdAt),
        updatedAt = iso(image.updatedAt),
    )

    // endregion

    // region Writing

    private suspend fun applyNotebook(id: String, notebook: CouchNotebook) {
        val existing = appRepository.bookRepository.getById(id)
        val row = Notebook(
            id = id,
            title = notebook.title,
            // `openPageId` and `linkedExternalUri` stay device-local: which page you had open is
            // not a fact about the notebook, and the BOOX's linked path does not exist elsewhere.
            openPageId = existing?.openPageId,
            pageIds = notebook.pageIds,
            parentFolderId = resolveFolder(notebook.parentFolderId),
            defaultBackground = notebook.defaultBackground,
            defaultBackgroundType = notebook.defaultBackgroundType,
            linkedExternalUri = existing?.linkedExternalUri,
            createdAt = date(notebook.createdAt),
            updatedAt = date(notebook.updatedAt),
        )
        // updateVerbatim, not update: `update` stamps `updatedAt = now()`, which would overwrite
        // the merged timestamp and make this device look like the newest writer of every document
        // it receives.
        if (existing == null) appRepository.bookRepository.createEmpty(row)
        else appRepository.bookRepository.updateVerbatim(row)
    }

    private suspend fun applyPage(id: String, page: CouchPage) {
        val existing = appRepository.pageRepository.getWithDataById(id)
        val notebookId = page.notebookId ?: existing?.page?.notebookId
        // Room enforces the page -> notebook foreign key, so a page that arrives before its
        // notebook document needs somewhere to live. The placeholder is overwritten in full the
        // moment the real notebook lands (which the engine pushes *after* its pages, so this is the
        // ordering the protocol expects).
        if (notebookId != null && appRepository.bookRepository.getById(notebookId) == null) {
            appRepository.bookRepository.createEmpty(
                Notebook(
                    id = notebookId,
                    pageIds = listOf(id),
                    createdAt = date(page.createdAt),
                    updatedAt = date(page.updatedAt),
                )
            )
        }

        val row = Page(
            id = id,
            // `scroll` is device-local and does not travel: carried over rather than reset, or
            // every incoming change would scroll the reader back to the top.
            scroll = existing?.page?.scroll ?: 0,
            notebookId = notebookId,
            title = page.title,
            background = page.background,
            backgroundType = page.backgroundType,
            parentFolderId = existing?.page?.parentFolderId,
            createdAt = date(page.createdAt),
            updatedAt = date(page.updatedAt),
        )
        if (existing == null) appRepository.pageRepository.create(row)
        else appRepository.pageRepository.update(row)

        val tombstoned = page.deletedStrokes.map { it.id }.toSet()
        val incoming = page.strokes.mapNotNull { strokeRow(it, id) }
        val incomingIds = incoming.map { it.id }.toSet()
        val existingIds = existing?.strokes?.map { it.id }.orEmpty().toSet()

        // Anything the merged document no longer carries is gone here too — including the strokes
        // it explicitly tombstoned, which is the erasure actually taking effect on this device.
        appRepository.strokeRepository.deleteAll(
            ((existingIds - incomingIds) + (existingIds intersect tombstoned)).toList()
        )
        appRepository.strokeRepository.create(incoming.filter { it.id !in existingIds })
        appRepository.strokeRepository.update(incoming.filter { it.id in existingIds })

        // The tombstones themselves have to be stored, or the next `load` would forget the erasure
        // and the peer's copy would come back on the following merge.
        appRepository.deletedStrokeRepository.upsertAll(
            page.deletedStrokes.map {
                DeletedStroke(strokeId = it.id, pageId = id, deletedAt = date(it.deletedAt))
            }
        )

        val incomingImages = page.images.map { imageRow(it, id) }
        val incomingImageIds = incomingImages.map { it.id }.toSet()
        val existingImageIds = existing?.images?.map { it.id }.orEmpty().toSet()
        appRepository.imageRepository.deleteAll((existingImageIds - incomingImageIds).toList())
        appRepository.imageRepository.create(incomingImages.filter { it.id !in existingImageIds })
        appRepository.imageRepository.update(incomingImages.filter { it.id in existingImageIds })
    }

    private suspend fun applyFolder(id: String, folder: CouchFolder) {
        val existing = appRepository.folderRepository.get(id)
        val row = Folder(
            id = id,
            title = folder.title,
            parentFolderId = resolveFolder(folder.parentFolderId),
            createdAt = date(folder.createdAt),
            updatedAt = date(folder.updatedAt),
        )
        if (existing == null) appRepository.folderRepository.create(row)
        else appRepository.folderRepository.updateVerbatim(row)
    }

    /**
     * A parent folder that has not arrived yet would fail the foreign key and lose the whole
     * document; filing it at the root until its folder shows up loses only its placement, and the
     * folder document's own arrival does not fix it — but the next edit to this document does.
     */
    private suspend fun resolveFolder(folderId: String?): String? {
        if (folderId == null) return null
        return if (appRepository.folderRepository.get(folderId) != null) folderId else null
    }

    /** Null when the wire stroke cannot be turned into a row (bad base64, truncated points, ...). */
    private fun strokeRow(stroke: CouchStroke, pageId: String): Stroke? = try {
        Stroke(
            id = stroke.id,
            size = stroke.size,
            // An unrecognized pen name is a peer running a newer build, not a corrupt document.
            // Room stores the enum, so the name cannot be preserved verbatim as bopa does; falling
            // back to BALLPEN keeps the ink rather than dropping the stroke.
            pen = Pen.fromString(stroke.pen),
            color = stroke.color,
            maxPressure = stroke.maxPressure,
            top = stroke.top,
            bottom = stroke.bottom,
            left = stroke.left,
            right = stroke.right,
            points = decodeStrokePoints(Base64.getDecoder().decode(stroke.pointsData)),
            pageId = pageId,
            createdAt = date(stroke.createdAt),
            updatedAt = date(stroke.updatedAt),
            // Remote data may carry raw-scale pressure; in-memory and stored strokes are [0, 1].
        ).withNormalizedPressure()
    } catch (e: Exception) {
        log.e("Skipping corrupted stroke ${stroke.id}: ${e.message}")
        null
    }

    private fun imageRow(image: CouchImage, pageId: String): Image = Image(
        id = image.id,
        x = image.x,
        y = image.y,
        width = image.width,
        height = image.height,
        uri = image.assetId,
        pageId = pageId,
        createdAt = date(image.createdAt),
        updatedAt = date(image.updatedAt),
    )

    // endregion

    companion object {
        private val DAY_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC)

        /** Where [applyConflictCopy] parks the document it could not read. */
        fun conflictCopyKey(documentId: String, notebookId: String): String =
            "couch-conflict:$documentId:$notebookId"

        fun iso(date: Date): String = Instant.ofEpochMilli(date.time).toString()

        /**
         * An unparseable instant becomes the epoch rather than throwing: it loses every merge
         * comparison, which is the right outcome for a timestamp we cannot read, and keeps a single
         * malformed field from costing the user the document.
         */
        fun date(timestamp: String): Date = try {
            Date.from(Instant.parse(timestamp))
        } catch (_: Exception) {
            Date(0)
        }
    }
}
