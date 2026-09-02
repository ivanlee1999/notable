package com.ethran.notable.sync.couch

import com.ethran.notable.data.AppRepository
import com.ethran.notable.sync.SyncClock
import com.ethran.notable.data.ensureBackgroundsFolder
import com.ethran.notable.data.ensureAudioFolder
import com.ethran.notable.data.ensureImagesFolder
import com.ethran.notable.data.db.Block
import com.ethran.notable.data.db.BlockPayload
import com.ethran.notable.data.db.DeletedBlock
import com.ethran.notable.data.db.DeletedImage
import com.ethran.notable.data.db.DeletedPage
import com.ethran.notable.data.db.DeletedStroke
import com.ethran.notable.data.db.Folder
import com.ethran.notable.data.db.Image
import com.ethran.notable.data.db.Kv
import com.ethran.notable.data.db.KvDao
import com.ethran.notable.data.db.Notebook
import com.ethran.notable.data.db.Page
import com.ethran.notable.data.db.RemoteApply
import com.ethran.notable.data.db.Stroke
import com.ethran.notable.data.db.declaredDefaultPageSize
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
import java.io.File
import java.util.Base64
import java.util.Date
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * [CouchLocalStore] over notable's Room database — the bridge between the sync engine and what the
 * app actually reads and writes. bopa's `FileCouchStore.swift` is the twin of this class, and its
 * test suite is mirrored by `RoomCouchStoreTest`.
 *
 * Two things are worth knowing before reading further.
 *
 * **A recorded deletion outranks what is still in the database.** Deleting a Room row is not by
 * itself a syncable fact — an absent notebook is also what a device that never saw it looks like —
 * so deletions are recorded in `couch_deletion` and [load] consults that table first. That is what
 * makes deleting a notebook while offline work: the intent survives a restart. The record also
 * outlives the push, because a deleted notebook's pages do not go anywhere on the server and this
 * device has to keep recognizing them as leftovers.
 *
 * **Writing a document drops its deletion.** Delete-vs-edit resurrection (protocol §6.4) reaches
 * this class as a plain notebook write; without dropping the record, the next flush would delete
 * the notebook all over again.
 *
 * **Everything this class writes is marked [RemoteApply].** Landing a remote change means calling
 * the very same repository methods a user edit does, and those now queue what they write. Without
 * the marker every pull would queue a push, the peer would receive the echo and queue one back, and
 * two devices would ping-pong for as long as they were both switched on. Suppression lives here,
 * on the one path that receives rather than originates, so that "queue it" stays the default
 * everywhere else — the bugs this outbox exists to remove are all of the form "someone forgot".
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
    /**
     * The pages an applied change rewrote, so anything holding them in memory can re-read them.
     * Never called for reads, and never with an empty set — a change that touched no page (a
     * folder, an asset no page places) leaves nothing cached to correct. An image blob landing
     * for a page that is already applied counts: the page's rows did not move, but what the
     * canvas shows for them did — see [applyAsset].
     */
    private val onPagesApplied: ((Set<String>) -> Unit)? = null,
    /**
     * Where placed images live. A function rather than a `File` because resolving it touches
     * external storage, which a plain JVM test has none of — and this class is otherwise free of
     * `android.*`.
     */
    private val imagesFolder: () -> File = ::ensureImagesFolder,
    private val audioFolder: () -> File = ::ensureAudioFolder,
    /** Where a downloaded PDF or background picture is filed. Deferred for the same reason. */
    private val backgroundsFolder: () -> File = ::ensureBackgroundsFolder,
    /**
     * An asset's bytes have just been written to this path.
     *
     * The page showing that background is holding a decode that failed while the file was still on
     * its way, and nothing on the drawing side is going to try again by itself: a background is
     * decoded once and cached, and the watch that would have reported the file changing was never
     * installed, because there was no file to watch when the page was opened. So the arrival has to
     * be announced. Without it a PDF imported on the other device lands correctly and goes on
     * showing a blank page until the notebook is closed and reopened.
     */
    private val onAssetFileWritten: ((String) -> Unit)? = null,
    /**
     * How a file's bytes are hashed on a cache miss — see [hashOf]. Injectable so a test can
     * count the reads; production always digests the file.
     */
    private val hashFile: (File) -> String? = { CouchAssetId.sha256Hex(it) },
) : CouchLocalStore {

    private val log = ShipBook.getLogger("RoomCouchStore")

    // region CouchLocalStore

    override fun contentClock(documentId: String): String? = runBlocking {
        val (type, id) = CouchDocId.split(documentId) ?: return@runBlocking null
        if (type != CouchDocType.NOTEBOOK) return@runBlocking null
        appRepository.pageRepository.newestUpdatedAtInNotebook(id)?.let { iso(it) }
    }

    override fun load(documentId: String): CouchDocBody? = runBlocking {
        val (type, id) = CouchDocId.split(documentId) ?: return@runBlocking null

        // A recorded deletion outranks whatever rows are still present: the cascade may not have run
        // yet, and the engine needs to push the tombstone regardless.
        //
        // Settled deletions answer here too, not just the ones still owed to the server. "Deleted"
        // is the honest answer to what this device holds, and it is the one the merge needs: a peer
        // that re-offers a stale copy of a notebook we deleted meets §6.4 and is corrected, where a
        // device that had forgotten the deletion would take the copy and quietly resurrect it.
        appRepository.couchDeletionRepository.get(documentId)?.let { deletion ->
            return@runBlocking CouchDocBody.Deleted(
                CouchDeletedDoc(type = type, deletedAt = deletion.deletedAt, updatedBy = deviceId)
            )
        }

        when (type) {
            CouchDocType.NOTEBOOK -> loadNotebook(id)?.let { CouchDocBody.Notebook(it) }
            CouchDocType.PAGE -> loadPage(id)?.let { CouchDocBody.Page(it) }
            CouchDocType.FOLDER -> loadFolder(id)?.let { CouchDocBody.Folder(it) }
            CouchDocType.ASSET -> loadAsset(documentId)?.let { CouchDocBody.Asset(it) }
            else -> null
        }
    }

    // `RemoteApply` covers everything below it, including the placeholder notebook `applyPage`
    // creates for a page whose notebook has not landed yet and the page rows `applyNotebook`
    // removes for tombstoned pages — all of those go through repositories that would otherwise
    // queue a push for content that came from the server in the first place.
    override fun apply(
        documentId: String,
        body: CouchDocBody,
        basedOn: CouchDocBody?,
    ) = runBlocking<Unit>(RemoteApply()) {
        val (type, id) = CouchDocId.split(documentId) ?: return@runBlocking

        // Which pages this change rewrites, collected as it goes. A page still on screen or held in
        // a cache is showing what it held before this call, and only these ids say so.
        val rewrittenPages = mutableSetOf<String>()

        when (body) {
            is CouchDocBody.Notebook -> {
                // Read before the apply: the pages it drops are deleted by it, and afterwards there
                // is nothing left to name them.
                rewrittenPages += body.notebook.deletedPageIds.map { it.id }
                applyNotebook(id, body.notebook)
                // A notebook arriving from the server un-deletes it here — that decision was
                // already made by the merge, which resurrects only when the edit is newer than the
                // deletion.
                appRepository.couchDeletionRepository.clear(documentId)
            }

            is CouchDocBody.Page -> {
                applyPage(id, body.page, basedOn = (basedOn as? CouchDocBody.Page)?.page)
                rewrittenPages += id
            }

            // Where the bytes go was decided when the page that places them was applied: under
            // the hash that names them, which is the path that page's rows already point at.
            // The pages drawing those rows are rewritten pages in every sense that matters: they
            // were applied moments earlier with the file missing, the canvas drew its load-error
            // placeholder, and nothing else will ever repaint them — the file watcher announces
            // backgrounds, but a *picture's* path maps to nobody there.
            is CouchDocBody.Asset -> rewrittenPages += applyAsset(documentId, body.asset)

            is CouchDocBody.Folder -> {
                applyFolder(id, body.folder)
                appRepository.couchDeletionRepository.clear(documentId)
            }

            is CouchDocBody.Deleted -> {
                when (type) {
                    // Room cascades: the notebook's pages, and their strokes and images, go with it.
                    CouchDocType.NOTEBOOK -> {
                        // Again read first: the cascade is what makes these unnameable afterwards.
                        rewrittenPages += appRepository.bookRepository.getById(id)?.pageIds.orEmpty()
                        appRepository.bookRepository.delete(id)
                    }

                    CouchDocType.FOLDER -> appRepository.folderRepository.delete(id)
                    // Page deletions travel inside their notebook's `deletedPageIds`, not as their
                    // own tombstone document; nothing to do here.
                    else -> Unit
                }
                // Recorded as settled rather than forgotten. There is nothing left to push — the
                // deletion came *from* the server — but this device has to go on knowing the
                // document is deleted, because the pages it orphaned stay live on the server for
                // good and every replay of the feed brings them back past [applyPage]'s guard.
                //
                // Dropping the row here made that guard depend on the tombstone being applied
                // *after* those pages, and the change feed does not promise any such order: CouchDB
                // shards the two documents and merges the per-shard streams as they answer, so the
                // notebook's tombstone leads its own orphans often enough to matter. See
                // [com.ethran.notable.data.db.CouchDeletion.pending].
                if (type == CouchDocType.NOTEBOOK || type == CouchDocType.FOLDER) {
                    appRepository.couchDeletionRepository
                        .recordPublished(documentId, deletedAtToKeep(documentId, body.tombstone))
                } else {
                    appRepository.couchDeletionRepository.clear(documentId)
                }
            }
        }
        if (rewrittenPages.isNotEmpty()) onPagesApplied?.invoke(rewrittenPages)
    }

    /**
     * Which instant to keep for a deletion the server has taken — the rule `mergeTombstones` uses
     * for two documents, applied to the stored row.
     *
     * A tombstone can arrive without one: a plain HTTP `DELETE`, or a client that kept no body,
     * leaves `deletedAt` empty, meaning *unknown* (see [CouchDeletedDoc.deletedAt]). Unknown loses
     * every §6.4 comparison, so letting one overwrite an instant this device already recorded would
     * turn a deletion that stands into one that yields — and the orphan pages it left behind would
     * start rebuilding their notebook again. When both are known the earlier wins, because a
     * deletion cannot un-happen.
     */
    private suspend fun deletedAtToKeep(documentId: String, tombstone: CouchDeletedDoc): String {
        val known = appRepository.couchDeletionRepository.get(documentId)?.deletedAt
        return when {
            tombstone.deletedAt.isEmpty() -> known.orEmpty()
            known.isNullOrEmpty() -> tombstone.deletedAt
            else -> CouchMerge.earlier(known, tombstone.deletedAt)
        }
    }

    /**
     * Protocol §6.5. The local copy is left exactly as it is and the remote one is materialized
     * alongside it under a fresh identity, so a document this build cannot understand costs the
     * user a duplicate rather than their work.
     */
    override fun applyConflictCopy(documentId: String, json: JsonObject) = runBlocking<Unit>(RemoteApply()) {
        // Every shape gets a copy, folders included. §6.5's promise is that nothing is discarded,
        // and a folder quietly dropped here is a folder the user never learns went missing.
        CouchDocId.split(documentId) ?: return@runBlocking

        val stamp = DAY_FORMAT.format(SyncClock.now())
        // Derived from the document and its bytes rather than minted fresh, so re-reading the same
        // unreadable document produces the same copy instead of another one. The feed is replayed
        // from the start whenever a checkpoint is lost — which this design treats as safe — and
        // fresh ids turned every replay into a fresh set of duplicates in the library. Content is
        // part of the name because a *new* unreadable revision genuinely is a different thing.
        val identity = CouchAssetId.sha256Hex(
            (documentId + couchJson.encodeToString(JsonObject.serializer(), json)).toByteArray()
        )
        val notebookId = uuidShaped(identity.take(32))
        val pageId = uuidShaped(identity.takeLast(32))
        val now = SyncClock.nowDate()

        if (appRepository.bookRepository.getById(notebookId) != null) return@runBlocking

        appRepository.bookRepository.createEmpty(
            Notebook(
                id = notebookId,
                title = "Unreadable sync copy (conflict $stamp $deviceId)",
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
        // No notification: every row this wrote is new, so nothing in memory can be holding a
        // stale copy of it.
    }

    /**
     * Protocol §3.4. A picture is placed by the page document, and a PDF or background image is
     * named by it, but the bytes of either travel as a separate `asset:` document — so between a
     * page arriving and its blob being downloaded the reference is real and the file is not there
     * yet. This is what is still owed.
     *
     * Read from the rows rather than from a side table: a row whose file is missing and whose name
     * is a hash *is* the record of an outstanding download, and it survives a restart for free —
     * that window can span one, since the page can arrive in one session and the bytes only be
     * fetchable in the next.
     */
    override fun missingAssetIds(): List<String> = runBlocking {
        pendingAssets()
            .map { it.assetId }
            .distinct()
            .sorted()
            .also { if (it.isNotEmpty()) log.i("${it.size} asset(s) still to download") }
    }

    // endregion

    // region Local deletions

    /**
     * Records a locally-initiated deletion so the engine pushes a tombstone. Survives a restart,
     * which is what makes deleting a notebook while offline work.
     */
    fun recordDeletion(documentId: String, deletedAt: String = SyncClock.nowIso()) =
        runBlocking { appRepository.couchDeletionRepository.record(documentId, deletedAt) }

    fun pendingDeletionIds(): List<String> =
        runBlocking { appRepository.couchDeletionRepository.pendingIds() }

    /**
     * Forgets a recorded deletion the user decided not to publish — the store half of
     * [CouchSyncEngine.discardHeldDeletions].
     *
     * The notebook's rows went with the tombstone, in the same transaction, so there is nothing here
     * to restore and nothing here to re-create: dropping the row simply stops this device claiming
     * a deletion it no longer intends. The copy on the server is untouched, which is why the
     * notebook reappears on the next pull — the outcome the user was told about before choosing.
     */
    override fun discardDeletion(documentId: String) {
        runBlocking { appRepository.couchDeletionRepository.clear(documentId) }
    }

    // endregion

    // region The outbox

    /**
     * The `couch_outbox` table, which the repositories write inside the same transaction as the
     * data. It is the durable half of the engine's dirty set: the checkpoint blob that also holds
     * it is persisted asynchronously, so between a repository writing a row and that blob landing
     * the table is the only record that anything needs sending.
     */
    override fun pendingOutboxIds(): List<String> =
        runBlocking { appRepository.couchOutboxRepository.pendingIds() }

    override fun enqueueOutbox(documentIds: List<String>) {
        runBlocking { appRepository.couchOutboxRepository.queue(documentIds) }
    }

    override fun dequeueOutbox(documentId: String) {
        runBlocking { appRepository.couchOutboxRepository.clear(documentId) }
    }

    // endregion

    // region Enumerating what is here

    /**
     * Every document this device holds, for the first push after setup.
     *
     * Pages are taken from their notebook's `pageIds`, as bopa does, rather than from the page
     * table: the manifest is what names a page on the wire, so a page it does not name has nowhere
     * to live on the other app.
     */
    override fun allDocumentIds(): List<String> = runBlocking {
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

    // region Dividing pages that outgrew their sheet

    /**
     * Divides every page of [notebookId] that outgrew its sheet — the Room half of §6.6, the
     * reconciliation the iPad has run on notebook open since pages became sheets
     * (`NotebookStore.splitOversizedPages`). Until now this app only ever *received* a division;
     * a notebook that never left this device kept its tall pages, with everything below the
     * first sheet invisible to the overview, to bookmarks and to reordering.
     *
     * Cheap when there is nothing to do — the usual case, every open after the first: a MAX()
     * over each page's stroke tops and image positions decides candidacy without loading a
     * single point blob. A page that needs dividing is loaded as the document the protocol
     * splits, divided by [PageSplit] — the function the shared vectors pin — and written back
     * through [applyPage], the same path a peer's division arrives by, inside one transaction
     * with the notebook's new page list and the outbox rows that will publish all of it.
     *
     * Children are applied before their parent deliberately: their rows already exist under the
     * parent, so applying the child first *re-homes* each row — a true move that keeps what the
     * wire model does not carry, an image's local file above all — and the parent's tombstones
     * then have nothing left to delete.
     *
     * Returns every page id the division rewrote (parents and children), after telling
     * [onPagesApplied], so anything holding those pages in memory re-reads them.
     */
    suspend fun splitOversizedPages(notebookId: String): Set<String> {
        val book = appRepository.bookRepository.getById(notebookId) ?: return emptySet()
        val notebookDefault = book.declaredDefaultPageSize()
        val candidates = book.pageIds.filter { pageId ->
            val page = appRepository.pageRepository.getById(pageId) ?: return@filter false
            val sheet = PageSplit.sheetFor(page.pageWidth, page.pageHeight, notebookDefault)
            val lowestStart = maxOf(
                appRepository.strokeRepository.maxTop(pageId) ?: 0f,
                (appRepository.imageRepository.maxY(pageId) ?: 0).toFloat(),
            )
            // The rule sheetCount applies: a second sheet exists when content *starts* past the
            // first one's end, never when it merely reaches past it.
            lowestStart >= sheet.height
        }
        if (candidates.isEmpty()) return emptySet()

        val now = SyncClock.nowIso()
        val rewritten = mutableSetOf<String>()
        appRepository.inTransaction {
            val fresh = appRepository.bookRepository.getById(notebookId)
                ?: return@inTransaction
            var pageIds = fresh.pageIds
            for (pageId in candidates) {
                val doc = loadPage(pageId) ?: continue
                val sheet = PageSplit.sheetFor(doc.pageWidth, doc.pageHeight, notebookDefault)
                val pieces = PageSplit.split(doc, pageId, sheet, now, deviceId)
                if (pieces.size <= 1) continue

                for (piece in pieces.drop(1)) {
                    // A child can already exist: the parent re-grew after an earlier division —
                    // a peer that has not learned the split pushing new below-sheet ink is the
                    // usual way. The produced child is built from the parent alone, so applying
                    // it as-is would erase whatever was drawn on the child since; folding it
                    // through the ordinary page merge keeps both.
                    val existing =
                        if (appRepository.pageRepository.getById(piece.id) == null) null
                        else loadPage(piece.id)
                    val landed =
                        if (existing == null) piece.page
                        else CouchMerge.mergePage(existing, piece.page)
                    applyPage(piece.id, landed, basedOn = null)
                }
                applyPage(pieces[0].id, pieces[0].page, basedOn = null)

                val at = pageIds.indexOf(pageId)
                // A re-divided page's children are usually listed already; they keep the place
                // they have rather than being filed twice.
                val children = pieces.drop(1).map { it.id }.filter { it !in pageIds }
                pageIds =
                    if (at < 0) pageIds + children
                    else pageIds.take(at + 1) + children + pageIds.drop(at + 1)

                // The pages have to travel by themselves: the notebook update below queues only
                // the notebook document.
                pieces.forEach {
                    appRepository.couchOutboxRepository.queue(CouchDocId.page(it.id))
                }
                rewritten += pieces.map { it.id }
            }
            if (rewritten.isNotEmpty()) {
                // Stamps the notebook's clock and queues it — the repair has to travel, or a peer
                // holding the old page list would merge the children straight back out of order.
                appRepository.bookRepository.update(fresh.copy(pageIds = pageIds))
            }
        }
        if (rewritten.isNotEmpty()) onPagesApplied?.invoke(rewritten)
        return rewritten
    }

    // endregion

    // region Reading

    private suspend fun loadNotebook(id: String): CouchNotebook? {
        val notebook = appRepository.bookRepository.getById(id) ?: return null
        return CouchNotebook(
            title = notebook.title,
            pageIds = notebook.pageIds,
            deletedPageIds = appRepository.deletedPageRepository.getByNotebook(id)
                .map { CouchTombstone(id = it.pageId, deletedAt = iso(it.deletedAt)) },
            parentFolderId = notebook.parentFolderId,
            bookmarks = notebook.bookmarks,
            outline = notebook.outline,
            defaultBackground = wireBackground(
                notebook.defaultBackground, notebook.defaultBackgroundType
            ),
            defaultBackgroundType = notebook.defaultBackgroundType,
            defaultPageWidth = notebook.defaultPageWidth,
            defaultPageHeight = notebook.defaultPageHeight,
            // The Trash travels: it is a state of the notebook, not of this device (§3.2).
            deletedAt = notebook.deletedAt?.let { iso(it) },
            createdAt = iso(notebook.createdAt),
            updatedAt = iso(notebook.updatedAt),
            // Null means this device wrote it last; see [Notebook.updatedBy].
            updatedBy = notebook.updatedBy ?: deviceId,
        )
    }

    private suspend fun loadPage(id: String): CouchPage? {
        val data = appRepository.pageRepository.getWithDataById(id) ?: return null
        return CouchPage(
            notebookId = data.page.notebookId,
            title = data.page.title,
            background = wireBackground(data.page.background, data.page.backgroundType),
            backgroundType = data.page.backgroundType,
            pageWidth = data.page.pageWidth,
            pageHeight = data.page.pageHeight,
            strokes = data.strokes.mapNotNull(::couchStroke),
            deletedStrokes = appRepository.deletedStrokeRepository.getByPage(id)
                .map { CouchTombstone(id = it.strokeId, deletedAt = iso(it.deletedAt)) },
            images = data.images.map(::couchImage),
            deletedImages = appRepository.deletedImageRepository.getByPage(id)
                .map { CouchTombstone(id = it.imageId, deletedAt = iso(it.deletedAt)) },
            blocks = data.blocks.map(::couchBlock),
            deletedBlocks = appRepository.deletedBlockRepository.getByPage(id)
                .map { CouchTombstone(id = it.blockId, deletedAt = iso(it.deletedAt)) },
            createdAt = iso(data.page.createdAt),
            updatedAt = iso(data.page.updatedAt),
            // Null means this device wrote it last; see [Page.updatedBy].
            updatedBy = data.page.updatedBy ?: deviceId,
        )
    }

    /**
     * The bytes behind an asset this device holds.
     *
     * A blob this device downloaded is named for its own hash, which is the whole lookup. The scans
     * behind it are for a file that arrived by another route — the picker, the WebDAV backend, or a
     * PDF the user imported under its own name — and they are only reached when such a file is
     * pushed for the first time.
     *
     * Both folders are searched because the id says nothing about which it is: a `sha256` is a
     * picture placed on a page or a document the whole notebook is drawn on, and by the time a
     * peer asks for one, all that is known is the hash.
     */
    private suspend fun loadAsset(documentId: String): CouchAsset? {
        val sha = CouchAssetId.sha256HexOfAssetId(documentId) ?: return null
        val file = assetFile(sha)
        if (file == null) {
            // Silence here is what the bug looked like: the pages went up carrying a reference,
            // the engine read "nothing to send" from the null and dropped the id off the queue,
            // and every peer drew blank sheets under the ink. Nothing can be sent without the
            // bytes, but it must not happen quietly.
            log.e("Not pushing asset $sha: no file this library names holds those bytes")
            return null
        }
        val bytes = runCatching { file.readBytes() }.getOrNull() ?: return null
        // An asset carries no history of its own — it is bytes under the name of their hash. The
        // timestamps describe this device's copy and never decide anything: nothing merges an
        // asset, and nothing overwrites one.
        return CouchAsset.of(bytes, at = iso(Date(file.lastModified())), updatedBy = deviceId)
    }

    private suspend fun assetFile(sha: String): File? {
        val images = runCatching { imagesFolder() }.getOrNull()
        val backgrounds = runCatching { backgroundsFolder() }.getOrNull()

        val audio = runCatching { audioFolder() }.getOrNull()

        val named = buildList {
            images?.let { add(File(it, sha)) }
            audio?.let { add(File(it, sha)) }
            backgrounds?.let { folder ->
                CouchBackgroundFiles.fileNamesFor(sha).forEach { add(File(folder, it)) }
            }
        }
        named.firstOrNull { it.exists() }?.let { return it }

        referencedFile(sha)?.let { return it }

        images?.listFiles()?.firstOrNull { hashOf(it) == sha }?.let { return it }
        audio?.listFiles()?.firstOrNull { hashOf(it) == sha }?.let { return it }
        // Backgrounds sit one level down, in `pdfs/` or `images/`, so this walks rather than lists.
        // Hashes are remembered ([hashOf]), so a notebook's own PDF is read once and not once per
        // page that names it.
        return backgrounds?.walkTopDown()?.firstOrNull { it.isFile && hashOf(it) == sha }
    }

    /**
     * A file the library itself is pointing at whose bytes are [sha], wherever it happens to live.
     *
     * The two folders are where this device files what *it* downloaded, and looking only there is
     * what left an imported PDF behind. "Observe" — the other half of the import dialog — leaves
     * the document where the user keeps it and points the pages at that path
     * ([com.ethran.notable.io.handleFileSaving]), so its bytes are in neither folder and no scan of
     * them can ever find it. The pages still publish it, because [wireBackground] hashes the file
     * they name; the bytes then could not be produced, and an asset the store answers null for is
     * dropped from the push without a word.
     *
     * What ties bytes to an asset id is not where they sit but the rows naming them, so the rows
     * are what is asked — the same three queries [pendingAssets] uses for the mirror question.
     * Distinct by construction: a two-hundred-page book names one document, and [hashOf] remembers
     * it, so this reads a file once rather than once per page or per flush.
     */
    private suspend fun referencedFile(sha: String): File? {
        val candidates = LinkedHashSet<File>()
        appRepository.pageRepository.getFileBackgrounds().forEach { row ->
            backgroundFile(row.background, row.backgroundType)?.let { candidates += it }
        }
        appRepository.bookRepository.getFileDefaultBackgrounds().forEach { row ->
            backgroundFile(row.background, row.backgroundType)?.let { candidates += it }
        }
        // Pictures are copied into the images folder as they are placed, so the scan above almost
        // always answers for them; a row pointing somewhere else is the same silent loss, and costs
        // nothing to cover here.
        appRepository.imageRepository.getAllUris().forEach { uri ->
            CouchImageFiles.fileFor(uri)?.let { candidates += it }
        }
        return candidates.firstOrNull { it.isFile && contentHashOf(it) == sha }
    }

    /**
     * What a candidate file's bytes are, by its name where that says so and by reading it where it
     * does not — the rule [CouchBackgroundFiles.assetIdFor] applies, and for the same reason: a
     * file named after a hash was written by the downloader, which had the bytes in hand, and a
     * background is routinely tens of megabytes.
     */
    private fun contentHashOf(file: File): String? =
        CouchBackgroundFiles.sha256HexOfFileName(file.name) ?: hashOf(file)

    /** The file a row is drawn from, or null for a native template and for a row naming no file. */
    private fun backgroundFile(background: String, backgroundType: String): File? {
        if (!CouchBackgroundFiles.isFileBacked(backgroundType) || background.isEmpty()) return null
        return File(background)
    }

    private suspend fun loadFolder(id: String): CouchFolder? {
        val folder = appRepository.folderRepository.get(id) ?: return null
        return CouchFolder(
            title = folder.title,
            parentFolderId = folder.parentFolderId,
            deletedAt = folder.deletedAt?.let { iso(it) },
            createdAt = iso(folder.createdAt),
            updatedAt = iso(folder.updatedAt),
            // Null means this device wrote it last; see [Folder.updatedBy].
            updatedBy = folder.updatedBy ?: deviceId,
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

    /** Lays 32 hex characters out in the 8-4-4-4-12 shape the rest of the app expects of an id. */
    private fun uuidShaped(hex: String): String {
        if (hex.length != 32) return UUID.randomUUID().toString()
        return listOf(0..7, 8..11, 12..15, 16..19, 20..31)
            .joinToString("-") { hex.substring(it.first, it.last + 1) }
    }

    /**
     * The background to publish: the `asset:` document holding its bytes for a PDF or a picture,
     * and the value itself for a native template.
     *
     * A file-backed background that cannot be identified — an external PDF this device can no
     * longer read, on a page it has never been able to draw — travels as whatever it says locally.
     * That is a path naming nothing on the peer, which is exactly how [localBackground] reads it:
     * as a peer with nothing to say about backgrounds, so the peer keeps its own. Sending "blank"
     * instead would be a claim, and would wipe a background the peer can see.
     */
    private fun wireBackground(background: String, backgroundType: String): String =
        CouchBackgroundFiles.assetIdFor(background, backgroundType, ::hashOf) ?: background

    /** Path -> ((size, modified), hash). See [hashOf]. */
    private val hashedFiles = ConcurrentHashMap<String, Pair<Pair<Long, Long>, String>>()

    /**
     * The hash of a file this store keeps re-asking about, remembered for as long as the file does
     * not change.
     *
     * Every page of an imported book names the same PDF, and every push and every incoming page
     * asks what it is. Reading a sixty-megabyte document once per page — two hundred times for a
     * scanned book, on a device with an e-ink processor and a slow card — is the difference between
     * a sync and an ordeal. The same question is asked of every *placed image* too: [couchImage]
     * hashes each picture on every load of every page, and [applyPage]'s held-bytes map hashes
     * every existing image per incoming apply, so those go through here as well rather than
     * digesting unchanged pictures once per sync pass. Size and modification time are what a local
     * edit moves, so keying on them means an externally-edited file (the whole point of a linked
     * notebook) still re-reads.
     */
    private fun hashOf(file: File): String? {
        val stamp = file.length() to file.lastModified()
        hashedFiles[file.path]?.let { (known, hash) -> if (known == stamp) return hash }
        val hash = hashFile(file) ?: return null
        hashedFiles[file.path] = stamp to hash
        return hash
    }

    private fun couchBlock(block: Block): CouchBlock {
        val payload = block.decodedPayload
        return CouchBlock(
            id = block.id,
            kind = block.kind,
            orderKey = block.orderKey,
            text = block.text,
            imageAssetId = payload.imageAssetId,
            segments = payload.segments,
            strokeIds = payload.strokeIds,
            x = block.x,
            y = block.y,
            width = block.width,
            height = block.height,
            startedAt = block.startedAt?.let(::iso),
            createdAt = iso(block.createdAt),
            updatedAt = iso(block.updatedAt),
            deviceId = block.deviceId,
        )
    }

    private fun blockRow(block: CouchBlock, pageId: String): Block {
        val payload = BlockPayload(
            imageAssetId = block.imageAssetId,
            segments = block.segments,
            strokeIds = block.strokeIds,
        )
        return Block(
            id = block.id,
            pageId = pageId,
            kind = block.kind,
            orderKey = block.orderKey,
            text = block.text,
            payload = payload.encode(),
            x = block.x,
            y = block.y,
            width = block.width,
            height = block.height,
            startedAt = block.startedAt?.let(::date),
            createdAt = date(block.createdAt),
            updatedAt = date(block.updatedAt),
            deviceId = block.deviceId,
        )
    }

    private fun couchImage(image: Image): CouchImage = CouchImage(
        id = image.id,
        // The wire names bytes, not a path: where this device keeps the file is its own business,
        // and the peer's copy lives somewhere else entirely.
        assetId = CouchImageFiles.assetIdFor(image.uri, ::hashOf),
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
            // Written from the document, not `?: existing`: unlike a declared sheet, an empty list
            // is a real state — the last bookmark being removed — so falling back to what is
            // already here would make removing the last one impossible.
            bookmarks = notebook.bookmarks,
            outline = notebook.outline,
            defaultBackground = localBackground(
                incoming = notebook.defaultBackground,
                backgroundType = notebook.defaultBackgroundType,
                held = existing?.defaultBackground,
                heldType = existing?.defaultBackgroundType,
            ),
            defaultBackgroundType = notebook.defaultBackgroundType,
            // A declared sheet is kept when the peer names none — the same rule the merge uses, and
            // for the same reason: a build that has not learned the field must not reflow pages.
            defaultPageWidth = notebook.defaultPageWidth ?: existing?.defaultPageWidth,
            defaultPageHeight = notebook.defaultPageHeight ?: existing?.defaultPageHeight,
            linkedExternalUri = existing?.linkedExternalUri,
            // Written from the document, not carried over from `existing`: the Trash is synced
            // now, so this is how a notebook thrown away on the iPad leaves the library here — and
            // how one restored there comes back. Before the field existed this row was rebuilt
            // without `deletedAt` at all, so any incoming edit silently emptied the local Trash.
            deletedAt = notebook.deletedAt?.let { date(it) },
            createdAt = date(notebook.createdAt),
            updatedAt = date(notebook.updatedAt),
            // See the note in [applyPage]: the real author, not this device.
            updatedBy = notebook.updatedBy,
        )
        // updateVerbatim, not update: `update` stamps `updatedAt = now()`, which would overwrite
        // the merged timestamp and make this device look like the newest writer of every document
        // it receives.
        if (existing == null) appRepository.bookRepository.createEmpty(row)
        else appRepository.bookRepository.updateVerbatim(row)

        // The tombstones themselves have to be stored, or the next `load` would forget the removal
        // and the peer's copy of the manifest would append the page back on the following merge.
        appRepository.deletedPageRepository.upsertAll(
            notebook.deletedPageIds.map {
                DeletedPage(pageId = it.id, notebookId = id, deletedAt = date(it.deletedAt))
            }
        )
        // The merge already dropped these from `pageIds`; the rows themselves have to go too, or a
        // page nothing points at keeps its strokes — and its previews — on disk forever.
        notebook.deletedPageIds.forEach { appRepository.pageRepository.delete(it.id) }
    }

    private suspend fun applyPage(id: String, page: CouchPage, basedOn: CouchPage?) {
        val existing = appRepository.pageRepository.getWithDataById(id)
        // A page naming no notebook is an orphan: §6.4 gives pages no lifecycle of their own, so
        // there is no manifest that will ever name this one and nothing here could show it. bopa
        // destroys such files on sight; dropping it is the same answer. Falling back to the local
        // row first, because an incoming edit may simply be omitting a field we already know.
        val notebookId = page.notebookId ?: existing?.page?.notebookId ?: return
        // Room enforces the page -> notebook foreign key, so a page that arrives before its
        // notebook document needs somewhere to live. The placeholder is overwritten in full the
        // moment the real notebook lands (which the engine pushes *after* its pages, so this is the
        // ordering the protocol expects).
        if (appRepository.bookRepository.getById(notebookId) == null) {
            // Unless that notebook is deleted. Its pages keep no tombstones of their own — protocol
            // §6.4, they live and die with the notebook's `pageIds` — so deleting a notebook leaves
            // its `page:<id>` documents live on the server forever. A device replaying the feed
            // from zero meets those orphans with no memory of having sent them, and a placeholder
            // built from one resurrects the notebook as an untitled "New notebook". Worse, the
            // placeholder then outranks the incoming tombstone under §6.4 and is *pushed back*,
            // republishing a notebook the user deleted.
            //
            // The record this reads is kept once the tombstone has been published, precisely so
            // that this question can be answered at any point in any feed: the orphans outlive the
            // deletion on the server, so the answer has to outlive it here.
            //
            // §6.4 is the same rule either way: only an edit strictly newer than the deletion
            // resurrects. A page that is not newer is a leftover, and is dropped along with it.
            val deletedAt = appRepository.couchDeletionRepository
                .get(CouchDocId.notebook(notebookId))?.deletedAt
            if (CouchMerge.resolveDeletion(page.updatedAt, deletedAt) ==
                CouchMerge.DeletionOutcome.APPLY_DELETION
            ) {
                return
            }
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
            background = localBackground(
                incoming = page.background,
                backgroundType = page.backgroundType,
                held = existing?.page?.background,
                heldType = existing?.page?.backgroundType,
            ),
            backgroundType = page.backgroundType,
            pageWidth = page.pageWidth ?: existing?.page?.pageWidth,
            pageHeight = page.pageHeight ?: existing?.page?.pageHeight,
            createdAt = date(page.createdAt),
            updatedAt = date(page.updatedAt),
            // Who actually wrote this, so the next merge can break a scalar tie on the real author
            // instead of on this device's id. Written through unchanged — the repository keeps it
            // only because this runs under `RemoteApply`; a local edit stamps it back to null.
            updatedBy = page.updatedBy,
        )
        if (existing == null) appRepository.pageRepository.create(row)
        else appRepository.pageRepository.update(row)

        val tombstoned = page.deletedStrokes.map { it.id }.toSet()
        val incoming = page.strokes.mapNotNull { strokeRow(it, id) }
        val incomingIds = incoming.map { it.id }.toSet()
        val existingIds = existing?.strokes?.map { it.id }.orEmpty().toSet()

        // What the merge was actually looking at. Computing it took a network round trip, and the
        // editor kept drawing throughout — so a stroke can be on disk now that the merge never saw.
        // Deleting on "present locally but absent from the result" would take those with it, with
        // no tombstone recorded and nothing left to push: ink drawn during a sync would simply
        // disappear. Only ids the merge saw and dropped are eligible.
        val merged = basedOn?.strokes?.map { it.id }?.toSet() ?: existingIds
        appRepository.strokeRepository.deleteAll(
            (((merged - incomingIds) + tombstoned) intersect existingIds).toList()
        )
        // §6.6 moves ink between pages under a stable id, and the change feed promises no
        // order: a divided page's child can land before the parent's truncation releases the
        // row here. The id is the global primary key, so a blind insert would abort the whole
        // pull — and every retry would replay it. The incoming document owns the id; re-homing
        // the row onto this page is the correct application of it.
        val arriving = incoming.filter { it.id !in existingIds }
        val heldElsewhere = appRepository.strokeRepository.existingIds(arriving.map { it.id })
        appRepository.strokeRepository.create(arriving.filter { it.id !in heldElsewhere })
        appRepository.strokeRepository.update(
            incoming.filter { it.id in existingIds } + arriving.filter { it.id in heldElsewhere }
        )

        // The tombstones themselves have to be stored, or the next `load` would forget the erasure
        // and the peer's copy would come back on the following merge.
        appRepository.deletedStrokeRepository.upsertAll(
            page.deletedStrokes.map {
                DeletedStroke(strokeId = it.id, pageId = id, deletedAt = date(it.deletedAt))
            }
        )

        val tombstonedImages = page.deletedImages.map { it.id }.toSet()
        // What this page already draws, indexed by content. An image arriving from the peer names
        // bytes, not a filename, so if those bytes are already here — under whatever name they
        // were imported with — the row points at the file that has them rather than at one nothing
        // will ever write.
        val held = existing?.images.orEmpty()
            .mapNotNull { image ->
                CouchImageFiles.assetIdFor(image.uri, ::hashOf)?.let { it to image.uri }
            }
            .toMap()
        val incomingImages = page.images.map { imageRow(it, id, held[it.assetId]) }
        val incomingImageIds = incomingImages.map { it.id }.toSet()
        val existingImageIds = existing?.images?.map { it.id }.orEmpty().toSet()
        // Same rule as the strokes above: an image placed while the merge was in flight is not
        // something the merge decided against.
        val mergedImages = basedOn?.images?.map { it.id }?.toSet() ?: existingImageIds
        appRepository.imageRepository.deleteAll(
            (((mergedImages - incomingImageIds) + tombstonedImages) intersect existingImageIds)
                .toList()
        )
        // Same re-homing rule as the strokes above, for the same reason — but an image row also
        // carries where its bytes live locally, which the wire model does not, so a re-homed row
        // keeps the uri it had on its old page whenever the incoming document cannot name one.
        val arrivingImages = incomingImages.filter { it.id !in existingImageIds }
        val imagesHeldElsewhere =
            appRepository.imageRepository.existingIds(arrivingImages.map { it.id })
        appRepository.imageRepository.create(
            arrivingImages.filter { it.id !in imagesHeldElsewhere }
        )
        val rehomedImages = arrivingImages.filter { it.id in imagesHeldElsewhere }.map { row ->
            if (row.uri != null) row
            else row.copy(uri = appRepository.imageRepository.getImageWithPointsById(row.id).uri)
        }
        appRepository.imageRepository.update(
            incomingImages.filter { it.id in existingImageIds } + rehomedImages
        )

        appRepository.deletedImageRepository.upsertAll(
            page.deletedImages.map {
                DeletedImage(imageId = it.id, pageId = id, deletedAt = date(it.deletedAt))
            }
        )

        // Blocks, by the same rules as the images above but without their complication: a block
        // names its assets by id on both sides, so there is no local path to preserve and nothing
        // to re-home.
        val tombstonedBlocks = page.deletedBlocks.map { it.id }.toSet()
        val incomingBlocks = page.blocks.map { blockRow(it, id) }
        val incomingBlockIds = incomingBlocks.map { it.id }.toSet()
        val existingBlockIds = existing?.blocks?.map { it.id }.orEmpty().toSet()
        // §6.1a: only content the merge actually saw may be removed. A block typed while the merge
        // was in flight is not something the merge decided against.
        val mergedBlocks = basedOn?.blocks?.map { it.id }?.toSet() ?: existingBlockIds
        appRepository.blockRepository.deleteByIds(
            (((mergedBlocks - incomingBlockIds) + tombstonedBlocks) intersect existingBlockIds)
                .toList()
        )
        appRepository.blockRepository.upsertAll(
            incomingBlocks.filter { it.id !in tombstonedBlocks }
        )
        appRepository.deletedBlockRepository.upsertAll(
            page.deletedBlocks.map {
                DeletedBlock(blockId = it.id, pageId = id, deletedAt = date(it.deletedAt))
            }
        )
    }

    private suspend fun applyFolder(id: String, folder: CouchFolder) {
        val existing = appRepository.folderRepository.get(id)
        val row = Folder(
            id = id,
            title = folder.title,
            parentFolderId = resolveFolder(folder.parentFolderId),
            // See [applyNotebook]: the Trash is part of the document.
            deletedAt = folder.deletedAt?.let { date(it) },
            createdAt = date(folder.createdAt),
            updatedAt = date(folder.updatedAt),
            // See the note in [applyPage]: the real author, not this device.
            updatedBy = folder.updatedBy,
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

    /**
     * The local file an incoming image should be drawn from. [heldAt] is where this device already
     * keeps those exact bytes, if it does.
     *
     * A file already here keeps its name — that is the image the user picked, or one the WebDAV
     * backend downloaded, and renaming it to its hash would orphan it for that backend. Anything
     * else is filed under the hash, which is where the downloader will put the bytes.
     */
    private fun imageRow(image: CouchImage, pageId: String, heldAt: String?): Image = Image(
        id = image.id,
        x = image.x,
        y = image.y,
        width = image.width,
        height = image.height,
        uri = localImageUri(image.assetId, heldAt),
        pageId = pageId,
        createdAt = date(image.createdAt),
        updatedAt = date(image.updatedAt),
    )

    private fun localImageUri(assetId: String?, heldAt: String?): String? {
        if (assetId == null || CouchAssetId.sha256HexOfAssetId(assetId) == null) {
            // The peer named no asset — it has not hashed its copy, or the document predates
            // assets travelling at all. That says nothing about the file this device holds, so
            // whatever is here keeps its place rather than being forgotten.
            //
            // What is *not* kept is the peer's own `assetId` text. Before assets travelled it held
            // the writer's local path, which never named anything here; adopting it now would let
            // a document decide which file this device reads. Every uri a row holds is therefore
            // one this device wrote.
            return heldAt
        }
        if (heldAt != null) return heldAt
        // Storage is unreachable, so there is no path this image could be drawn from and none
        // this device could download it to. Recording a bare hash instead would be worse than
        // recording nothing: it resolves to no file and never will, so every pull would see the
        // download as still outstanding and fetch the picture again, for good.
        //
        // Nothing is lost by leaving it empty. The next push then names no asset for this image,
        // and a reader that is told no asset keeps whatever it already had — so the peer's copy
        // is not disturbed, and the next apply here fills the path in once storage is back.
        val folder = runCatching { imagesFolder() }.getOrNull() ?: return null
        return CouchImageFiles.localUriFor(assetId, folder)
    }

    /**
     * The local file an incoming background should be drawn from. [held] is what this device has
     * been showing for it, and [heldType] what kind of thing that was.
     *
     * The two "keep what is here" branches are the same rule [localImageUri] applies, for the same
     * reason: every path a row holds is one this device wrote, and a document never gets to decide
     * which file this device reads. A peer that names no asset has said nothing about backgrounds
     * — it is an older build, or one that cannot read its own file — so it must not be able to
     * blank out a background that is here and working.
     */
    private fun localBackground(
        incoming: String,
        backgroundType: String,
        held: String?,
        heldType: String?,
    ): String {
        // A native template is its own value on every device; there is nothing to resolve.
        if (!CouchBackgroundFiles.isFileBacked(backgroundType)) return incoming
        if (CouchAssetId.sha256HexOfAssetId(incoming) == null) return held ?: incoming

        // These exact bytes may already be here under the name they were imported with — the file
        // the user picked, or one the WebDAV backend downloaded. That file keeps its name: this is
        // most often a page coming back from the server that this very device sent.
        if (held != null && heldType != null &&
            CouchBackgroundFiles.assetIdFor(held, heldType, ::hashOf) == incoming
        ) {
            return held
        }

        // Storage is unreachable, so there is no path these bytes could be drawn from and none the
        // downloader could put them at. The reference is kept rather than replaced with a guess:
        // it names the right bytes, so it survives being pushed back, and the next apply resolves
        // it once storage is there.
        val folder = runCatching { backgroundsFolder() }.getOrNull() ?: return held ?: incoming
        return CouchBackgroundFiles.localPathFor(incoming, backgroundType, folder)
            ?: held ?: incoming
    }

    /**
     * An asset this device is owed, and the file waiting for it.
     *
     * Read from the rows rather than from a side table, for the reason given on [missingAssetIds]:
     * a row whose file is missing and whose name is a hash *is* the record of an outstanding
     * download, and it survives a restart for free.
     */
    private data class PendingAsset(val assetId: String, val file: File)

    private suspend fun pendingAssets(): List<PendingAsset> {
        val pending = mutableListOf<PendingAsset>()

        val images = runCatching { imagesFolder() }.getOrNull()
        if (images != null) {
            appRepository.imageRepository.getAllUris()
                .mapNotNull { uri -> CouchImageFiles.fileFor(uri) }
                .filter { !it.exists() && CouchAssetId.isSha256Hex(it.name) }
                .forEach { pending += PendingAsset(CouchDocId.asset(it.name), it) }
        }

        // A block names its bytes by id — a picture in the images folder, a recording's segments
        // in the audio folder — so a missing file under a hash-shaped name is the record of a
        // download still owed, exactly as it is for a placed image. Without this a received
        // recording would merge, render as a pill, and never play: nothing would ever ask for it.
        val audio = runCatching { audioFolder() }.getOrNull()
        appRepository.blockRepository.getPayloads().forEach { json ->
            val payload = BlockPayload.decode(json)
            payload.imageAssetId?.let { assetId ->
                pendingBlockAsset(assetId, images)?.let { pending += it }
            }
            payload.segments.forEach { segment ->
                pendingBlockAsset(segment.assetId, audio)?.let { pending += it }
            }
        }

        appRepository.pageRepository.getFileBackgrounds().forEach { row ->
            pendingBackground(row.background, row.backgroundType)?.let { pending += it }
        }
        appRepository.bookRepository.getFileDefaultBackgrounds().forEach { row ->
            pendingBackground(row.background, row.backgroundType)?.let { pending += it }
        }
        return pending
    }

    private fun pendingBlockAsset(assetId: String, folder: File?): PendingAsset? {
        if (folder == null) return null
        val sha = CouchAssetId.sha256HexOfAssetId(assetId) ?: return null
        val file = File(folder, sha)
        return if (file.exists()) null else PendingAsset(assetId, file)
    }

    private fun pendingBackground(background: String, backgroundType: String): PendingAsset? {
        if (!CouchBackgroundFiles.isFileBacked(backgroundType) || background.isEmpty()) return null
        val file = File(background)
        if (file.exists()) return null
        val sha = CouchBackgroundFiles.sha256HexOfFileName(file.name) ?: return null
        return PendingAsset(CouchDocId.asset(sha), file)
    }

    /**
     * Files the bytes of an asset, at every path a row is currently pointing at for them.
     *
     * Returns the ids of the pages whose *image rows* point at a path just written. Those pages
     * were applied while the file was still on its way — the canvas decoded a missing file and
     * cached the failure — and this arrival is the only event that can say so: the file watcher
     * ([onAssetFileWritten]) covers backgrounds, and a picture's path deliberately maps to nobody
     * there. Reporting them lets [apply] fold them into the same `onPagesApplied` notification a
     * page apply produces, which is exactly what they need: a re-read and a repaint.
     */
    private suspend fun applyAsset(documentId: String, asset: CouchAsset): Set<String> {
        val sha = CouchAssetId.sha256HexOfAssetId(documentId) ?: return emptySet()
        val bytes = asset.bytes ?: return emptySet()
        // Nothing waiting means an image whose row already resolves — the fetch that asked for
        // these bytes expects them in the images folder, under the hash that names them.
        val destinations = pendingAssets().filter { it.assetId == documentId }.map { it.file }
            .distinct()
            .ifEmpty {
                listOfNotNull(runCatching { imagesFolder() }.getOrNull()?.let { File(it, sha) })
            }
        val written = mutableSetOf<String>()
        for (file in destinations) {
            runCatching {
                file.parentFile?.mkdirs()
                file.writeBytes(bytes)
            }.onFailure {
                log.e("Could not store asset $sha at ${file.name}: ${it.message}")
            }.onSuccess {
                written += file.absolutePath
                onAssetFileWritten?.invoke(file.absolutePath)
            }
        }
        if (written.isEmpty()) return emptySet()
        return appRepository.imageRepository.getPageUris()
            .filter { row -> CouchImageFiles.fileFor(row.uri)?.absolutePath in written }
            .map { it.pageId }
            .toSet()
    }

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
