package com.ethran.notable.sync.couch

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ethran.notable.data.AppRepository
import com.ethran.notable.data.PageDataManager
import com.ethran.notable.data.PageViewportState
import com.ethran.notable.data.BackgroundFileWatcher
import com.ethran.notable.data.db.AppDatabase
import com.ethran.notable.data.db.BookRepository
import com.ethran.notable.data.db.CouchDeletionRepository
import com.ethran.notable.data.db.CryptoHelper
import com.ethran.notable.data.db.DeletedStrokeRepository
import com.ethran.notable.data.db.FolderRepository
import com.ethran.notable.data.db.Image
import com.ethran.notable.data.db.ImageRepository
import com.ethran.notable.data.db.KvProxy
import com.ethran.notable.data.db.KvRepository
import com.ethran.notable.data.db.Notebook
import com.ethran.notable.data.db.NotebookSyncStateRepository
import com.ethran.notable.data.db.Page
import com.ethran.notable.data.db.PageRepository
import com.ethran.notable.data.db.PageSyncStateRepository
import com.ethran.notable.data.db.Stroke
import com.ethran.notable.data.db.StrokePoint
import com.ethran.notable.data.db.StrokeRepository
import com.ethran.notable.data.db.encodeStrokePoints
import com.ethran.notable.data.events.DefaultAppEventBus
import com.ethran.notable.editor.utils.Pen
import com.ethran.notable.testing.TestDatabaseFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Instant
import java.util.Base64
import java.util.Date
import java.util.UUID

/**
 * [RoomCouchStore] against a real (in-memory) Room database, plus a round trip through the engine
 * and a real-shaped server so the storage layer is exercised the way sync will use it.
 *
 * These are **instrumentation** tests, not JVM unit tests: Room's in-memory builder needs an
 * Android `Context` and a real SQLite, and the alternative (Robolectric) is deliberately not a
 * dependency of this project. Everything here is a mirror of bopa's `FileCouchStoreTests.swift`,
 * case for case, so a divergence between the two stores shows up as the same named failure on both
 * sides. [FakeCouchTransport] lives in `src/sharedTest` for exactly that reason — the JVM engine
 * tests and these tests drive the same fake server.
 */
@RunWith(AndroidJUnit4::class)
class RoomCouchStoreTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private lateinit var db: AppDatabase
    private lateinit var repository: AppRepository
    private lateinit var store: RoomCouchStore

    /**
     * Stands in for the app's images folder. A test has no "All files access", so the real one
     * cannot be resolved — and pointing at it would leave pictures in the user's storage anyway.
     */
    private lateinit var images: File

    @Before
    fun setUp() {
        db = TestDatabaseFactory.createInMemory(context)
        repository = repositoryFor(db)
        images = File(context.cacheDir, "couch-images-${UUID.randomUUID()}").apply { mkdirs() }
        store = RoomCouchStore(repository, db.kvDao(), deviceId = "boox", imagesFolder = { images })
    }

    @After
    fun tearDown() {
        db.close()
        images.deleteRecursively()
    }

    // region Fixtures

    private fun repositoryFor(db: AppDatabase) = AppRepository(
        bookRepository = BookRepository(db.notebookDao(), db.pageDao()),
        pageRepository = PageRepository(db.pageDao()),
        strokeRepository = StrokeRepository(db.strokeDao()),
        imageRepository = ImageRepository(db.ImageDao()),
        folderRepository = FolderRepository(db.folderDao()),
        notebookSyncStateRepository = NotebookSyncStateRepository(db.notebookSyncStateDao()),
        pageSyncStateRepository = PageSyncStateRepository(db.pageSyncStateDao()),
        deletedStrokeRepository = DeletedStrokeRepository(db.deletedStrokeDao()),
        couchDeletionRepository = CouchDeletionRepository(db.couchDeletionDao()),
        kvProxy = KvProxy(KvRepository(db.kvDao(), context), CryptoHelper()),
        db = db,
    )

    private fun stamp(second: Int): String =
        Instant.ofEpochSecond(1_770_000_000L + second).toString()

    private fun points() = listOf(
        StrokePoint(x = 1f, y = 2f, pressure = 0.5f),
        StrokePoint(x = 3.25f, y = 4.5f, pressure = 0.75f),
    )

    /** Real SB-encoded points: the store decodes these into Room rows and re-encodes on the way out. */
    private fun pointsData(): String =
        Base64.getEncoder().encodeToString(encodeStrokePoints(points()))

    private fun stroke(id: String, at: Int, device: String = "boox") = CouchStroke(
        id = id, createdAt = stamp(at), updatedAt = stamp(at), deviceId = device,
        pen = "BALLPEN", color = -16_777_216, size = 3f,
        top = 0f, bottom = 1f, left = 0f, right = 1f, pointsData = pointsData(),
    )

    private fun page(
        strokes: List<CouchStroke>,
        notebookId: String,
        updatedAt: Int,
        by: String = "boox",
    ) = CouchPage(
        notebookId = notebookId, strokes = strokes,
        createdAt = stamp(0), updatedAt = stamp(updatedAt), updatedBy = by,
    )

    private val pictureBytes = "PNG-ish bytes, hashed exactly as they are".toByteArray()
    private val pictureAssetId get() = CouchAssetId.forBytes(pictureBytes)

    // endregion

    // region Images

    /**
     * What a placed image looks like on the wire: a reference to the hash of its bytes, not to
     * wherever this device happens to keep the file.
     */
    @Test
    fun aPlacedImageTravelsAsTheHashOfItsBytes() {
        val file = File(images, "holiday.png").apply { writeBytes(pictureBytes) }
        val pageId = CouchDocId.page("p1")
        store.apply(pageId, CouchDocBody.Page(page(emptyList(), notebookId = "nb1", updatedAt = 5)))
        runBlocking {
            repository.imageRepository.create(
                Image(id = "i1", x = 1, y = 2, width = 3, height = 4,
                    uri = file.absolutePath, pageId = "p1")
            )
        }

        val loaded = store.load(pageId) as? CouchDocBody.Page
        assertNotNull("the page did not load", loaded)
        assertEquals(listOf(pictureAssetId), loaded!!.page.images.map { it.assetId })

        val asset = store.load(pictureAssetId) as? CouchDocBody.Asset
        assertNotNull("the bytes behind the reference were not found", asset)
        assertArrayEquals(pictureBytes, asset!!.asset.bytes)
    }

    /** The window this whole mechanism exists for: the page has arrived, the picture has not. */
    @Test
    fun anIncomingImageIsOwedUntilItsBytesArrive() {
        val pageId = CouchDocId.page("p1")
        store.apply(
            pageId,
            CouchDocBody.Page(
                page(emptyList(), notebookId = "nb1", updatedAt = 5).copy(
                    images = listOf(
                        CouchImage(
                            id = "i1", assetId = pictureAssetId, x = 0, y = 0, width = 4,
                            height = 4, createdAt = stamp(1), updatedAt = stamp(1),
                        )
                    )
                )
            ),
        )

        assertEquals(listOf(pictureAssetId), store.missingAssetIds())
        assertNull("nothing should claim to hold the bytes yet", store.load(pictureAssetId))

        store.apply(
            pictureAssetId,
            CouchDocBody.Asset(CouchAsset.of(pictureBytes, at = stamp(2), updatedBy = "boox")),
        )

        assertTrue(store.missingAssetIds().isEmpty())
        // And it landed where the row says to look for it, so the canvas finds it.
        val uri = runBlocking { repository.imageRepository.getUrisForPage("p1") }.first()
        assertArrayEquals(pictureBytes, File(uri!!).readBytes())
    }

    /**
     * An image this device already holds under its own name keeps that name: renaming it to the
     * hash would orphan the copy the WebDAV backend syncs by filename.
     */
    @Test
    fun bytesAlreadyHeldKeepTheNameTheyArrivedUnder() {
        val file = File(images, "holiday.png").apply { writeBytes(pictureBytes) }
        val pageId = CouchDocId.page("p1")
        store.apply(pageId, CouchDocBody.Page(page(emptyList(), notebookId = "nb1", updatedAt = 5)))
        runBlocking {
            repository.imageRepository.create(
                Image(id = "i1", x = 1, y = 2, width = 3, height = 4,
                    uri = file.absolutePath, pageId = "p1")
            )
        }

        val loaded = (store.load(pageId) as CouchDocBody.Page).page
        store.apply(pageId, CouchDocBody.Page(loaded))

        val uri = runBlocking { repository.imageRepository.getUrisForPage("p1") }.first()
        assertEquals(file.absolutePath, uri)
        assertTrue(store.missingAssetIds().isEmpty())
    }

    // endregion

    // region Round trips

    @Test
    fun notebookRoundTripsThroughTheDatabase() {
        val id = CouchDocId.notebook("nb1")
        val notebook = CouchNotebook(
            title = "notes", pageIds = listOf("p1"), createdAt = stamp(0), updatedAt = stamp(5),
            updatedBy = "boox",
        )

        store.apply(id, CouchDocBody.Notebook(notebook))
        assertNotNull(runBlocking { repository.bookRepository.getById("nb1") })

        val loaded = store.load(id) as? CouchDocBody.Notebook
        assertNotNull("notebook did not load back", loaded)
        assertEquals(notebook, loaded!!.notebook)
    }

    /**
     * The document names only the page; the store has to place it, create the row and rebuild the
     * exact binary point payload it was handed.
     */
    @Test
    fun pageRoundTripsIncludingItsInkBytes() {
        val id = CouchDocId.page("p1")
        val written = page(listOf(stroke("s1", at = 1)), notebookId = "nb1", updatedAt = 5)
        store.apply(id, CouchDocBody.Page(written))

        val row = runBlocking { repository.pageRepository.getWithDataById("p1") }
        assertNotNull("the page row was not created", row)
        assertEquals(listOf("s1"), row!!.strokes.map { it.id })
        assertEquals(points().size, row.strokes.first().points.size)

        val loaded = store.load(id) as? CouchDocBody.Page
        assertNotNull("page did not load back", loaded)
        assertEquals(written, loaded!!.page)
    }

    @Test
    fun folderRoundTripsThroughTheFolderTable() {
        val id = CouchDocId.folder("f1")
        val folder = CouchFolder(
            title = "study", createdAt = stamp(0), updatedAt = stamp(1), updatedBy = "boox",
        )
        store.apply(id, CouchDocBody.Folder(folder))

        val loaded = store.load(id) as? CouchDocBody.Folder
        assertNotNull("folder did not load back", loaded)
        assertEquals(folder, loaded!!.folder)
    }

    @Test
    fun absentDocumentsLoadAsNull() {
        assertNull(store.load(CouchDocId.notebook("missing")))
        assertNull(store.load(CouchDocId.page("missing")))
        assertNull(store.load(CouchDocId.folder("missing")))
        assertNull(store.load("nonsense-without-a-prefix"))
    }

    // endregion

    // region Deletion

    /** Deleting offline has to survive a restart, or the deletion silently never syncs. */
    @Test
    fun aLocalDeletionIsRecordedAndOutranksWhatIsStillStored() {
        val id = CouchDocId.notebook("nb1")
        store.apply(
            id,
            CouchDocBody.Notebook(
                CouchNotebook(
                    title = "doomed", createdAt = stamp(0), updatedAt = stamp(1),
                    updatedBy = "boox",
                )
            )
        )

        store.recordDeletion(id, deletedAt = stamp(9))

        // A fresh store over the same database stands in for a restart.
        val reopened = RoomCouchStore(repository, db.kvDao(), deviceId = "boox")
        val tombstone = reopened.load(id) as? CouchDocBody.Deleted
        assertNotNull("the deletion did not survive", tombstone)
        assertEquals(stamp(9), tombstone!!.tombstone.deletedAt)
        assertEquals(listOf(id), reopened.pendingDeletionIds())
        // The notebook row is still there — the tombstone outranks it, it does not erase it.
        assertNotNull(runBlocking { repository.bookRepository.getById("nb1") })
    }

    @Test
    fun applyingARemoteDeletionRemovesTheNotebookAndLeavesNothingToPush() {
        val id = CouchDocId.notebook("nb1")
        store.apply(
            id,
            CouchDocBody.Notebook(
                CouchNotebook(
                    title = "gone", createdAt = stamp(0), updatedAt = stamp(1), updatedBy = "ipad",
                )
            )
        )
        store.apply(
            id,
            CouchDocBody.Deleted(
                CouchDeletedDoc(
                    type = CouchDocType.NOTEBOOK, deletedAt = stamp(9), updatedBy = "ipad",
                )
            )
        )

        assertNull(runBlocking { repository.bookRepository.getById("nb1") })
        // The tombstone came from the server, so re-pushing it would be pointless traffic.
        assertTrue(store.pendingDeletionIds().isEmpty())
        assertNull(store.load(id))
    }

    /**
     * Delete-vs-edit resurrection reaches the store as a plain notebook write; it has to undo the
     * local tombstone or the notebook would be deleted again on the next flush.
     */
    @Test
    fun writingANotebookClearsItsPendingDeletion() {
        val id = CouchDocId.notebook("nb1")
        store.recordDeletion(id, deletedAt = stamp(5))
        store.apply(
            id,
            CouchDocBody.Notebook(
                CouchNotebook(
                    title = "back", createdAt = stamp(0), updatedAt = stamp(9), updatedBy = "boox",
                )
            )
        )

        assertTrue(store.pendingDeletionIds().isEmpty())
        assertTrue(
            "the resurrected notebook should load as a notebook",
            store.load(id) is CouchDocBody.Notebook
        )
    }

    // endregion

    // region Enumeration and conflict copies

    @Test
    fun allDocumentIdsCoversFoldersNotebooksPagesAndDeletions() {
        store.apply(
            CouchDocId.folder("f1"),
            CouchDocBody.Folder(
                CouchFolder(
                    title = "school", createdAt = stamp(0), updatedAt = stamp(1),
                    updatedBy = "boox",
                )
            )
        )
        store.apply(
            CouchDocId.notebook("nb1"),
            CouchDocBody.Notebook(
                CouchNotebook(
                    title = "notes", pageIds = listOf("p1", "p2"), createdAt = stamp(0),
                    updatedAt = stamp(1), updatedBy = "boox",
                )
            )
        )
        store.recordDeletion(CouchDocId.notebook("nb-old"))

        assertEquals(
            listOf("folder:f1", "notebook:nb-old", "notebook:nb1", "page:p1", "page:p2"),
            store.allDocumentIds()
        )
    }

    @Test
    fun aConflictCopyKeepsTheLocalCopyAndPreservesTheRawDocument() {
        val id = CouchDocId.page("p1")
        store.apply(
            id,
            CouchDocBody.Page(page(listOf(stroke("mine", at = 1)), notebookId = "nb1", updatedAt = 5))
        )

        val raw = buildJsonObject {
            put("type", JsonPrimitive("page"))
            put("schema", JsonPrimitive(99))
            put("somethingNew", JsonPrimitive(true))
        }
        store.applyConflictCopy(id, raw)

        // The local page is untouched — that is the whole promise of this path.
        val stillMine = store.load(id) as? CouchDocBody.Page
        assertNotNull("the local page was disturbed", stillMine)
        assertEquals(listOf("mine"), stillMine!!.page.strokes.map { it.id })

        // A copy notebook exists alongside it, under a fresh identity.
        val copies = runBlocking { repository.bookRepository.getAll() }
            .filter { it.title.startsWith("Unreadable sync copy") }
        assertEquals(1, copies.size)

        // And the document we could not read is kept verbatim rather than reinterpreted.
        val preserved = runBlocking {
            db.kvDao().get(RoomCouchStore.conflictCopyKey(id, copies.single().id))
        }
        assertNotNull("the unreadable document was discarded", preserved)
        assertTrue(preserved!!.value.contains("somethingNew"))
    }

    // endregion

    // region Erasure

    /**
     * The reason this table exists: an erased stroke has to leave a fact behind. Without it the
     * merge cannot tell "erased here" from "not synced here yet", and the peer's copy comes back.
     */
    @Test
    fun anErasedStrokeLeavesATombstoneThePageCarries() {
        val pageDataManager = PageDataManager(
            appRepository = repository,
            appEventBus = DefaultAppEventBus(),
            backgroundFileWatcher = BackgroundFileWatcher(DefaultAppEventBus()),
            viewport = PageViewportState(),
            // This test is about what the *store* sees after an erasure. The controller's own
            // behaviour is covered by CouchSyncControllerTest, so it is wired to a dead backend
            // here rather than allowed to reach for a server that is not part of this scenario.
            couchSync = inertCouchSync(),
        )

        runBlocking {
            repository.bookRepository.createEmpty(Notebook(id = "nb1", pageIds = listOf("p1")))
            repository.pageRepository.create(Page(id = "p1", notebookId = "nb1"))
            repository.strokeRepository.create(
                listOf(strokeRow("s1", "p1"), strokeRow("s2", "p1"))
            )
        }

        pageDataManager.removeStrokesFromDb(listOf("s2"), "p1")

        // The write is dispatched onto the manager's own IO scope; wait for it to land.
        val tombstones = awaitTombstones("p1")
        assertEquals(listOf("s2"), tombstones.map { it.strokeId })

        val loaded = store.load(CouchDocId.page("p1")) as? CouchDocBody.Page
        assertNotNull(loaded)
        assertEquals(listOf("s1"), loaded!!.page.strokes.map { it.id })
        assertEquals(listOf("s2"), loaded.page.deletedStrokes.map { it.id })
    
        // The manager's writes are fire-and-forget, and the trailing timestamp bump outlives the
        // test body. Teardown closes the database, so an unawaited write fails against a closed
        // pool — and lands against whatever test is running by then, not this one.
        runBlocking { pageDataManager.awaitPendingDbWrites() }
    }

    private fun strokeRow(id: String, pageId: String) = Stroke(
        id = id, size = 3f, pen = Pen.BALLPEN, maxPressure = 1,
        top = 0f, bottom = 1f, left = 0f, right = 1f,
        points = points(), pageId = pageId,
        createdAt = Date(1_770_000_000_000L), updatedAt = Date(1_770_000_000_000L),
    )

    private fun awaitTombstones(pageId: String) = generateSequence(0) { it + 1 }
        .take(100)
        .map {
            Thread.sleep(50)
            runBlocking { repository.deletedStrokeRepository.getByPage(pageId) }
        }
        .firstOrNull { it.isNotEmpty() }
        .orEmpty()

    // endregion

    // region Through the engine

    /** The scenario the store exists for: two devices, two databases, one server. */
    @Test
    fun twoDatabasesConvergeThroughTheEngine() {
        val server = FakeCouchTransport()
        val client = CouchDbClient(server, database = "notes")

        val otherDb = TestDatabaseFactory.createInMemory(context)
        try {
            val ipadStore = RoomCouchStore(repositoryFor(otherDb), otherDb.kvDao(), "ipad")
            val boox = CouchSyncEngine(client, store, deviceId = "boox")
            val ipad = CouchSyncEngine(client, ipadStore, deviceId = "ipad")

            val pageId = CouchDocId.page("p1")
            store.apply(
                pageId,
                CouchDocBody.Page(
                    page(listOf(stroke("s-boox", at = 1)), notebookId = "nb1", updatedAt = 5)
                )
            )
            runBlocking {
                boox.markDirty(listOf(pageId))
                boox.flush()
                ipad.pull()
            }

            // The iPad now has the page as real rows in its own database.
            val received = ipadStore.load(pageId) as? CouchDocBody.Page
            assertNotNull("the page did not reach the other database", received)
            assertEquals(listOf("s-boox"), received!!.page.strokes.map { it.id })
            assertEquals(
                points().size,
                runBlocking { repositoryFor(otherDb).pageRepository.getWithDataById("p1") }
                    ?.strokes?.first()?.points?.size
            )

            // It draws, pushes, and the BOOX picks the change up.
            val edited = received.page.copy(
                strokes = received.page.strokes + stroke("s-ipad", at = 9, device = "ipad"),
                updatedAt = stamp(9),
                updatedBy = "ipad",
            )
            ipadStore.apply(pageId, CouchDocBody.Page(edited))
            runBlocking {
                ipad.markDirty(listOf(pageId))
                ipad.flush()
                boox.pull()
            }

            val merged = store.load(pageId) as? CouchDocBody.Page
            assertNotNull(merged)
            assertEquals(listOf("s-boox", "s-ipad"), merged!!.page.strokes.map { it.id }.sorted())
        } finally {
            otherDb.close()
        }
    }

    /** An erasure on one device has to remove the stroke on the other, not be undone by it. */
    @Test
    fun anErasureTravelsThroughTheEngineAndRemovesTheStrokeOnThePeer() {
        val server = FakeCouchTransport()
        val client = CouchDbClient(server, database = "notes")

        val otherDb = TestDatabaseFactory.createInMemory(context)
        try {
            val ipadRepository = repositoryFor(otherDb)
            val ipadStore = RoomCouchStore(ipadRepository, otherDb.kvDao(), "ipad")
            val boox = CouchSyncEngine(client, store, deviceId = "boox")
            val ipad = CouchSyncEngine(client, ipadStore, deviceId = "ipad")

            val pageId = CouchDocId.page("p1")
            val both = page(
                listOf(stroke("s1", at = 1), stroke("s2", at = 1)),
                notebookId = "nb1", updatedAt = 5,
            )
            store.apply(pageId, CouchDocBody.Page(both))
            runBlocking {
                boox.markDirty(listOf(pageId))
                boox.flush()
                ipad.pull()
            }
            assertEquals(
                listOf("s1", "s2"),
                (ipadStore.load(pageId) as CouchDocBody.Page).page.strokes.map { it.id }.sorted()
            )

            // The BOOX erases s2 and pushes the resulting tombstone.
            store.apply(
                pageId,
                CouchDocBody.Page(
                    both.copy(
                        strokes = both.strokes.filter { it.id == "s1" },
                        deletedStrokes = listOf(CouchTombstone(id = "s2", deletedAt = stamp(7))),
                        updatedAt = stamp(7),
                    )
                )
            )
            runBlocking {
                boox.markDirty(listOf(pageId))
                boox.flush()
                ipad.pull()
            }

            val afterErasure = ipadStore.load(pageId) as CouchDocBody.Page
            assertEquals(listOf("s1"), afterErasure.page.strokes.map { it.id })
            assertEquals(listOf("s2"), afterErasure.page.deletedStrokes.map { it.id })
            // The row itself is gone, not merely hidden by the tombstone.
            val rows = runBlocking { ipadRepository.pageRepository.getWithDataById("p1") }
            assertEquals(listOf("s1"), rows!!.strokes.map { it.id })
            assertFalse(rows.strokes.any { it.id == "s2" })
        } finally {
            otherDb.close()
        }
    }

    // endregion
}

/**
 * A [CouchSyncController] that can never do anything: its backend reports itself disabled, which
 * is exactly the state notable is in until someone selects CouchDB in settings.
 */
private fun inertCouchSync(): CouchSyncController = CouchSyncController(
    scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    backend = object : CouchSyncBackend {
        override suspend fun isEnabled(): Boolean = false
        override suspend fun flush() = CouchSyncEngine.FlushReport()
        override suspend fun pull(longpoll: Boolean) = CouchSyncEngine.PullReport()
        override suspend fun markPageDirty(pageId: String) = Unit
        override suspend fun markEverythingDirty() = Unit
        override suspend fun recordDeletion(documentId: String) = Unit
    },
    clock = CouchSyncClock(),
)
