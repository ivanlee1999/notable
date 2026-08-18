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
import com.ethran.notable.data.db.CouchOutboxRepository
import com.ethran.notable.data.db.CryptoHelper
import com.ethran.notable.data.db.DeletedImageRepository
import com.ethran.notable.data.db.DeletedPageRepository
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
import com.ethran.notable.editor.canvas.CanvasEventBus
import com.ethran.notable.editor.utils.Pen
import com.ethran.notable.testing.TestDatabaseFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
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
import javax.inject.Provider
import java.time.Instant
import java.util.Base64
import java.util.Date
import java.util.UUID
import com.ethran.notable.testing.trashRepositoryFor

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

    /** The same stand-in for the backgrounds folder, where imported PDFs are kept. */
    private lateinit var backgrounds: File

    /** Asset files the store announced writing — how a page learns its PDF has landed. */
    private val assetsWritten = mutableListOf<String>()

    @Before
    fun setUp() {
        db = TestDatabaseFactory.createInMemory(context)
        repository = repositoryFor(db)
        images = File(context.cacheDir, "couch-images-${UUID.randomUUID()}").apply { mkdirs() }
        backgrounds = File(context.cacheDir, "couch-bg-${UUID.randomUUID()}").apply { mkdirs() }
        store = RoomCouchStore(
            repository, db.kvDao(), deviceId = "boox",
            imagesFolder = { images },
            backgroundsFolder = { backgrounds },
            onAssetFileWritten = { assetsWritten += it },
        )
    }

    @After
    fun tearDown() {
        db.close()
        images.deleteRecursively()
        backgrounds.deleteRecursively()
    }

    // region Fixtures

    private fun repositoryFor(db: AppDatabase) = AppRepository(
        bookRepository = BookRepository(db.notebookDao(), db.pageDao(), CouchOutboxRepository(db.couchOutboxDao()), db),
        pageRepository = PageRepository(db.pageDao(), CouchOutboxRepository(db.couchOutboxDao()), db),
        strokeRepository = StrokeRepository(db.strokeDao()),
        imageRepository = ImageRepository(db.ImageDao()),
        folderRepository = FolderRepository(db.folderDao(), CouchOutboxRepository(db.couchOutboxDao()), db),
        notebookSyncStateRepository = NotebookSyncStateRepository(db.notebookSyncStateDao()),
        pageSyncStateRepository = PageSyncStateRepository(db.pageSyncStateDao()),
        deletedStrokeRepository = DeletedStrokeRepository(db.deletedStrokeDao()),
        deletedPageRepository = DeletedPageRepository(db.deletedPageDao()),
        deletedImageRepository = DeletedImageRepository(db.deletedImageDao()),
        couchDeletionRepository = CouchDeletionRepository(db.couchDeletionDao()),
        couchOutboxRepository = CouchOutboxRepository(db.couchOutboxDao()),
        trashRepository = trashRepositoryFor(db),
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

    /**
     * The hash of a placed image is asked for on every load of every page — per push and per
     * apply — and used to be recomputed from the bytes each time. The mtime+size-keyed cache that
     * already spared backgrounds covers images now: an unchanged file is digested once, ever.
     */
    @Test
    fun anUnchangedImageIsHashedOnceAcrossLoads() {
        var hashes = 0
        val counting = RoomCouchStore(
            repository, db.kvDao(), deviceId = "boox",
            imagesFolder = { images },
            backgroundsFolder = { backgrounds },
            hashFile = { file ->
                hashes += 1
                CouchAssetId.sha256Hex(file)
            },
        )
        val file = File(images, "holiday.png").apply { writeBytes(pictureBytes) }
        val pageId = CouchDocId.page("p1")
        counting.apply(
            pageId,
            CouchDocBody.Page(page(emptyList(), notebookId = "nb1", updatedAt = 5)),
        )
        runBlocking {
            repository.imageRepository.create(
                Image(
                    id = "i1", x = 1, y = 2, width = 3, height = 4,
                    uri = file.absolutePath, pageId = "p1",
                )
            )
        }

        val first = counting.load(pageId) as? CouchDocBody.Page
        assertEquals(listOf(pictureAssetId), first!!.page.images.map { it.assetId })
        assertTrue("the first look has to read the file", hashes >= 1)

        val afterFirst = hashes
        val second = counting.load(pageId) as? CouchDocBody.Page
        assertEquals(listOf(pictureAssetId), second!!.page.images.map { it.assetId })
        assertEquals(
            "the second load of an unchanged image must not re-read its bytes",
            afterFirst,
            hashes,
        )

        // An incoming apply consults the held-bytes map over the same cache: still no re-read.
        counting.apply(
            pageId,
            CouchDocBody.Page(
                page(emptyList(), notebookId = "nb1", updatedAt = 9).copy(
                    images = listOf(
                        CouchImage(
                            id = "i1", assetId = pictureAssetId, x = 1, y = 2, width = 3,
                            height = 4, createdAt = stamp(1), updatedAt = stamp(9),
                        )
                    )
                )
            ),
            basedOn = first,
        )
        assertEquals("an apply must not re-hash unchanged pictures", afterFirst, hashes)

        // A changed file is a different picture and must be re-read.
        file.writeBytes("different bytes entirely".toByteArray())
        counting.load(pageId)
        assertTrue("an edited file has to be re-read", hashes > afterFirst)
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
     * With nowhere to put a picture, the row is left without a path rather than given one that
     * resolves to no file — such a row would read as an outstanding download on every pull and be
     * fetched again for good.
     */
    @Test
    fun anImageWithNowhereToLandIsNotGivenAPathThatGoesNowhere() {
        val stranded = RoomCouchStore(
            repository, db.kvDao(), deviceId = "boox",
            imagesFolder = { error("storage is unreachable") },
        )
        stranded.apply(
            CouchDocId.page("p1"),
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

        assertNull(runBlocking { repository.imageRepository.getUrisForPage("p1") }.first())
        assertTrue(stranded.missingAssetIds().isEmpty())
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

    // region Imported PDFs

    private val pdfBytes = "%PDF-1.7\nlecture notes\n".toByteArray()
    private val pdfAssetId get() = CouchAssetId.forBytes(pdfBytes)

    /**
     * The page an imported book produces: drawn on one page of a document kept next to the
     * database, and named by the path the importer wrote.
     */
    private fun pdfPage(file: File) = page(emptyList(), notebookId = "nb1", updatedAt = 5)
        .copy(background = file.absolutePath, backgroundType = "pdf0")

    private suspend fun backgroundOf(pageId: String): String? =
        repository.pageRepository.getById(pageId)?.background

    /**
     * The whole feature in one test. What the importer wrote is a path that exists on this device
     * and nowhere else; what leaves the device is the hash of the document's bytes.
     */
    @Test
    fun anImportedPdfTravelsAsItsBytesRatherThanAsItsPath() {
        val file = File(backgrounds, "Lecture 3.pdf").apply { writeBytes(pdfBytes) }
        val pageId = CouchDocId.page("p1")
        // A path is what a device holds; nothing resolves it away on the way in, because a peer
        // naming one has said nothing about assets.
        store.apply(pageId, CouchDocBody.Page(pdfPage(file)))
        assertEquals(file.absolutePath, runBlocking { backgroundOf("p1") })

        val loaded = (store.load(pageId) as CouchDocBody.Page).page
        assertEquals(pdfAssetId, loaded.background)
        assertEquals("pdf0", loaded.backgroundType)

        val asset = store.load(pdfAssetId) as? CouchDocBody.Asset
        assertNotNull("the document behind the reference was not found", asset)
        assertArrayEquals(pdfBytes, asset!!.asset.bytes)
        assertEquals("application/pdf", asset.asset.contentType)
    }

    /** A notebook that follows a PDF's page numbers publishes that PDF the same way. */
    @Test
    fun aNotebooksDefaultPdfTravelsAsItsBytesToo() {
        val file = File(backgrounds, "Lecture 3.pdf").apply { writeBytes(pdfBytes) }
        val id = CouchDocId.notebook("nb1")
        store.apply(
            id,
            CouchDocBody.Notebook(
                CouchNotebook(
                    title = "Lecture 3", pageIds = emptyList(), createdAt = stamp(0),
                    updatedAt = stamp(5), updatedBy = "boox",
                    defaultBackground = file.absolutePath, defaultBackgroundType = "autoPdf",
                )
            ),
        )

        val loaded = (store.load(id) as CouchDocBody.Notebook).notebook
        assertEquals(pdfAssetId, loaded.defaultBackground)
    }

    /**
     * The receiving half: the page lands first and is drawn blank, the document follows, and it has
     * to land exactly where the page was told to look — including the `.pdf`, which is how the
     * renderer knows to page through it rather than decode it as a picture.
     */
    @Test
    fun anIncomingPdfIsOwedUntilItsBytesArriveAndThenLandsWhereThePageLooks() {
        val pageId = CouchDocId.page("p1")
        store.apply(
            pageId,
            CouchDocBody.Page(
                page(emptyList(), notebookId = "nb1", updatedAt = 5)
                    .copy(background = pdfAssetId, backgroundType = "pdf0")
            ),
        )

        val expected = File(backgrounds, "${CouchAssetId.sha256Hex(pdfBytes)}.pdf")
        assertEquals(expected.absolutePath, runBlocking { backgroundOf("p1") })
        assertEquals(listOf(pdfAssetId), store.missingAssetIds())

        store.apply(
            pdfAssetId,
            CouchDocBody.Asset(CouchAsset.of(pdfBytes, at = stamp(2), updatedBy = "ipad")),
        )

        assertTrue(store.missingAssetIds().isEmpty())
        assertArrayEquals(pdfBytes, expected.readBytes())
        // And the page showing it is told, because it decoded a blank while the file was on its way.
        assertEquals(listOf(expected.absolutePath), assetsWritten)
    }

    /** A notebook's default is owed on its own, so a book whose pages are all gone still resolves. */
    @Test
    fun anIncomingDefaultBackgroundIsOwedEvenWithNoPagesToNameIt() {
        store.apply(
            CouchDocId.notebook("nb1"),
            CouchDocBody.Notebook(
                CouchNotebook(
                    title = "Lecture 3", pageIds = emptyList(), createdAt = stamp(0),
                    updatedAt = stamp(5), updatedBy = "ipad",
                    defaultBackground = pdfAssetId, defaultBackgroundType = "autoPdf",
                )
            ),
        )

        assertEquals(listOf(pdfAssetId), store.missingAssetIds())
    }

    /**
     * The document is already here, under the name the user imported it with. It keeps that name:
     * this is most often the page coming back from the server that this very device sent, and
     * re-filing the file under its hash would strand the copy the WebDAV backend syncs by filename.
     */
    @Test
    fun aPdfAlreadyHereKeepsTheNameItWasImportedWith() {
        val file = File(backgrounds, "Lecture 3.pdf").apply { writeBytes(pdfBytes) }
        val pageId = CouchDocId.page("p1")
        store.apply(pageId, CouchDocBody.Page(pdfPage(file)))

        store.apply(
            pageId,
            CouchDocBody.Page(
                page(emptyList(), notebookId = "nb1", updatedAt = 9, by = "ipad")
                    .copy(background = pdfAssetId, backgroundType = "pdf0")
            ),
        )

        assertEquals(file.absolutePath, runBlocking { backgroundOf("p1") })
        assertTrue("nothing is owed: the document is here", store.missingAssetIds().isEmpty())
    }

    /**
     * A peer that names a path rather than an asset is one running a build from before backgrounds
     * travelled. It has said nothing about backgrounds, so it must not be able to replace a working
     * one with a path that names nothing here.
     */
    @Test
    fun aPeerThatNamesNoAssetDoesNotBlankTheBackgroundThatIsHere() {
        val file = File(backgrounds, "Lecture 3.pdf").apply { writeBytes(pdfBytes) }
        val pageId = CouchDocId.page("p1")
        store.apply(pageId, CouchDocBody.Page(pdfPage(file)))

        store.apply(
            pageId,
            CouchDocBody.Page(
                page(emptyList(), notebookId = "nb1", updatedAt = 9, by = "ipad")
                    .copy(background = "/var/mobile/Containers/Data/somebody-else.pdf",
                        backgroundType = "pdf0")
            ),
        )

        assertEquals(file.absolutePath, runBlocking { backgroundOf("p1") })
    }

    /** A native template is its own value everywhere, and travels untouched. */
    @Test
    fun aNativeTemplateIsNotTreatedAsAFile() {
        val pageId = CouchDocId.page("p1")
        store.apply(
            pageId,
            CouchDocBody.Page(
                page(emptyList(), notebookId = "nb1", updatedAt = 5)
                    .copy(background = "lined", backgroundType = "native")
            ),
        )

        assertEquals("lined", runBlocking { backgroundOf("p1") })
        assertEquals("lined", (store.load(pageId) as CouchDocBody.Page).page.background)
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

    // endregion

    // region The Trash rides on the document

    /**
     * A notebook thrown away on the iPad has to land in this device's Trash — not stay in the
     * library with a `deletedAt` nothing reads — and it has to still be *there*: trashed is not
     * deleted, so the row, its pages and its ink all survive and go on syncing.
     */
    @Test
    fun anIncomingTrashingLandsInTheTrashWithoutDeletingAnything() {
        val id = CouchDocId.notebook("nb1")
        store.apply(id, CouchDocBody.Notebook(CouchNotebook(
            title = "notes", pageIds = listOf("p1"), deletedAt = stamp(9),
            createdAt = stamp(0), updatedAt = stamp(9), updatedBy = "ipad",
        )))

        val row = runBlocking { repository.bookRepository.getById("nb1") }
        assertNotNull("trashed is not deleted: the row stays", row)
        assertEquals(Instant.parse(stamp(9)).toEpochMilli(), row!!.deletedAt!!.time)

        val loaded = store.load(id) as? CouchDocBody.Notebook
        assertEquals(stamp(9), loaded!!.notebook.deletedAt)
    }

    /**
     * The other direction, and the reason `applyNotebook` writes `deletedAt` from the document
     * rather than carrying the local value over: before this field existed the row was rebuilt
     * without it, so any incoming edit quietly emptied the Trash.
     */
    @Test
    fun anIncomingRestoreTakesTheNotebookBackOutOfTheTrash() {
        val id = CouchDocId.notebook("nb1")
        store.apply(id, CouchDocBody.Notebook(CouchNotebook(
            title = "notes", deletedAt = stamp(9),
            createdAt = stamp(0), updatedAt = stamp(9), updatedBy = "ipad",
        )))

        store.apply(id, CouchDocBody.Notebook(CouchNotebook(
            title = "notes", createdAt = stamp(0), updatedAt = stamp(12), updatedBy = "ipad",
        )))

        assertNull(runBlocking { repository.bookRepository.getById("nb1") }!!.deletedAt)
        assertNull((store.load(id) as CouchDocBody.Notebook).notebook.deletedAt)
    }

    @Test
    fun aFolderTrashingTravelsTheSameWay() {
        val id = CouchDocId.folder("f1")
        store.apply(id, CouchDocBody.Folder(CouchFolder(
            title = "study", deletedAt = stamp(4),
            createdAt = stamp(0), updatedAt = stamp(4), updatedBy = "ipad",
        )))

        assertNotNull(runBlocking { repository.folderRepository.get("f1") }!!.deletedAt)
        assertEquals(stamp(4), (store.load(id) as CouchDocBody.Folder).folder.deletedAt)
    }

    /**
     * Trashing here is published, or the iPad would go on showing a notebook this device has
     * binned. The stamp matters as much as the queueing: `deletedAt` merges as an ordinary scalar,
     * so a trashing that did not move `updatedAt` would tie with the peer's live copy.
     */
    @Test
    fun trashingANotebookQueuesItAndStampsIt() {
        val trash = trashRepositoryFor(db, Provider { repository })
        store.apply(CouchDocId.notebook("nb1"), CouchDocBody.Notebook(CouchNotebook(
            title = "notes", createdAt = stamp(0), updatedAt = stamp(5), updatedBy = "ipad",
        )))
        runBlocking { CouchOutboxRepository(db.couchOutboxDao()).clear(CouchDocId.notebook("nb1")) }

        runBlocking { trash.trashNotebook("nb1") }

        val row = runBlocking { repository.bookRepository.getById("nb1") }!!
        assertNotNull("it is in the Trash", row.deletedAt)
        assertTrue(
            "the trashing has to outrank the peer's live copy",
            row.updatedAt.time > CouchMerge.millis(stamp(5)),
        )
        assertTrue(
            "and it has to be queued, or the peer never hears about it",
            runBlocking { CouchOutboxRepository(db.couchOutboxDao()).pendingIds() }
                .contains(CouchDocId.notebook("nb1")),
        )
    }

    // endregion

    // region Round trips

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
        // It is still remembered, though — "deleted" is what this device holds, and answering
        // "nothing" would let a peer's stale copy of the notebook land as a fresh one.
        val remembered = store.load(id) as? CouchDocBody.Deleted
        assertNotNull("the applied deletion was forgotten", remembered)
        assertEquals(stamp(9), remembered!!.tombstone.deletedAt)
    }

    /**
     * The orphan-page guard has to hold **whichever order** the feed reports the two documents in.
     *
     * Deleting a notebook leaves its `page:<id>` documents live on the server for good (protocol
     * §6.4: pages keep no tombstone of their own), so every replay meets the notebook's tombstone
     * and a leftover page that names it. A clustered CouchDB spreads the two across shards and
     * merges the per-shard change streams as they arrive, so the tombstone is reported *before* the
     * page often enough to matter — even though the page has the lower sequence and was written
     * first. Neither order may resurrect the notebook.
     *
     * This is the tombstone-first order, which is the one that used to fail: applying the tombstone
     * dropped the local deletion record, and the page that followed found nothing to consult and
     * built a placeholder "New notebook" holding the deleted notebook's page.
     */
    @Test
    fun anOrphanPageArrivingAfterTheTombstoneDoesNotResurrectTheNotebook() {
        val id = CouchDocId.notebook("nb1")
        store.recordDeletion(id, deletedAt = stamp(9))
        store.apply(
            id,
            CouchDocBody.Deleted(
                CouchDeletedDoc(
                    type = CouchDocType.NOTEBOOK, deletedAt = stamp(9), updatedBy = "boox",
                )
            )
        )

        // The page the deletion left behind on the server, applied after its notebook's tombstone.
        store.apply(
            CouchDocId.page("p1"),
            CouchDocBody.Page(
                CouchPage(
                    notebookId = "nb1", createdAt = stamp(0), updatedAt = stamp(1),
                    updatedBy = "boox",
                )
            )
        )

        assertNull(
            "the deleted notebook came back as a placeholder",
            runBlocking { repository.bookRepository.getById("nb1") },
        )
        assertNull(
            "the leftover page was applied even though its notebook is deleted",
            runBlocking { repository.pageRepository.getById("p1") },
        )
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

    /** The picture's version of the same bargain, and the same consequence for getting it wrong. */
    @Test
    fun anErasedImageLeavesATombstoneThePageCarries() {
        val pageDataManager = PageDataManager(
            appRepository = repository,
            appEventBus = DefaultAppEventBus(),
            backgroundFileWatcher = BackgroundFileWatcher(DefaultAppEventBus()),
            viewport = PageViewportState(),
            couchSync = inertCouchSync(),
        )

        runBlocking {
            repository.bookRepository.createEmpty(Notebook(id = "nb1", pageIds = listOf("p1")))
            repository.pageRepository.create(Page(id = "p1", notebookId = "nb1"))
            repository.imageRepository.create(listOf(imageRow("i1", "p1"), imageRow("i2", "p1")))
        }

        pageDataManager.removeImagesFromDb(listOf("i2"), "p1")

        val tombstones = await { repository.deletedImageRepository.getByPage("p1") }
        assertEquals(listOf("i2"), tombstones.map { it.imageId })

        val loaded = store.load(CouchDocId.page("p1")) as? CouchDocBody.Page
        assertNotNull(loaded)
        assertEquals(listOf("i1"), loaded!!.page.images.map { it.id })
        assertEquals(listOf("i2"), loaded.page.deletedImages.map { it.id })

        runBlocking { pageDataManager.awaitPendingDbWrites() }
    }

    /**
     * The notebook's version. `mergeNotebook` is an add-wins union over `pageIds`, so a manifest
     * that merely stopped naming the page has the peer's copy appended straight back onto it.
     */
    @Test
    fun aDeletedPageLeavesATombstoneTheNotebookCarries() {
        runBlocking {
            repository.bookRepository.createEmpty(
                Notebook(id = "nb1", pageIds = listOf("p1", "p2"))
            )
            repository.pageRepository.create(Page(id = "p1", notebookId = "nb1"))
            repository.pageRepository.create(Page(id = "p2", notebookId = "nb1"))
            repository.bookRepository.removePage("nb1", "p2")
            repository.deletedPageRepository.record("nb1", listOf("p2"), Date(1_770_000_007_000L))
            repository.pageRepository.delete("p2")
        }

        val loaded = store.load(CouchDocId.notebook("nb1")) as? CouchDocBody.Notebook
        assertNotNull(loaded)
        assertEquals(listOf("p1"), loaded!!.notebook.pageIds)
        assertEquals(listOf("p2"), loaded.notebook.deletedPageIds.map { it.id })
        assertEquals(stamp(7), loaded.notebook.deletedPageIds.single().deletedAt)
    }

    /**
     * Protocol §6.6. Re-stamping a tombstone on every save would let an arbitrarily later
     * timestamp win a delete-vs-edit comparison the deletion should lose.
     */
    @Test
    fun aTombstonedPageKeepsTheInstantItWasFirstRecordedAt() {
        runBlocking {
            repository.deletedPageRepository.record("nb1", listOf("p2"), Date(1_770_000_007_000L))
            repository.deletedPageRepository.record("nb1", listOf("p2"), Date(1_770_000_099_000L))
            assertEquals(
                Date(1_770_000_007_000L),
                repository.deletedPageRepository.getByNotebook("nb1").single().deletedAt,
            )
        }
    }

    /**
     * A tombstone that arrived from a merge has to be stored, or the next load forgets the removal
     * and offers the peer its own copy of the page back.
     */
    @Test
    fun aMergedInPageTombstoneIsRememberedAndTakesTheRowWithIt() {
        val id = CouchDocId.notebook("nb1")
        runBlocking {
            repository.bookRepository.createEmpty(
                Notebook(id = "nb1", pageIds = listOf("p1", "p2"))
            )
            repository.pageRepository.create(Page(id = "p1", notebookId = "nb1"))
            repository.pageRepository.create(Page(id = "p2", notebookId = "nb1"))
        }

        store.apply(
            id,
            CouchDocBody.Notebook(
                CouchNotebook(
                    title = "notes", pageIds = listOf("p1"),
                    deletedPageIds = listOf(CouchTombstone(id = "p2", deletedAt = stamp(7))),
                    createdAt = stamp(0), updatedAt = stamp(7), updatedBy = "ipad",
                )
            )
        )

        assertNull("the deleted page's row survived", runBlocking {
            repository.pageRepository.getById("p2")
        })
        val loaded = store.load(id) as? CouchDocBody.Notebook
        assertNotNull(loaded)
        assertEquals(listOf("p2"), loaded!!.notebook.deletedPageIds.map { it.id })
    }

    private fun strokeRow(id: String, pageId: String) = Stroke(
        id = id, size = 3f, pen = Pen.BALLPEN, maxPressure = 1,
        top = 0f, bottom = 1f, left = 0f, right = 1f,
        points = points(), pageId = pageId,
        createdAt = Date(1_770_000_000_000L), updatedAt = Date(1_770_000_000_000L),
    )

    private fun imageRow(id: String, pageId: String) = Image(
        id = id, x = 0, y = 0, width = 10, height = 10, uri = null, pageId = pageId,
        createdAt = Date(1_770_000_000_000L), updatedAt = Date(1_770_000_000_000L),
    )

    // endregion

    // region Reaching the screen

    /**
     * A [PageDataManager] wired the way the app wires it, and a store wired the way
     * [CouchSyncHost] wires it — the two halves this scenario needs joined.
     */
    private fun pageCache() = PageDataManager(
        appRepository = repository,
        appEventBus = DefaultAppEventBus(),
        backgroundFileWatcher = BackgroundFileWatcher(DefaultAppEventBus()),
        viewport = PageViewportState(),
        couchSync = inertCouchSync(),
    )

    private fun reportingStore() = RoomCouchStore(
        repository, db.kvDao(), deviceId = "boox", imagesFolder = { images },
        // The host launches this onto the application scope; here it is awaited instead, so the
        // notification is demonstrably out before `apply` returns and the test is left waiting only
        // on the cache, not on a race with its own producer.
        onPagesApplied = { runBlocking { CanvasEventBus.pagesChangedInDb.emit(it) } },
    )

    /** Opens [pageId] in [cache] and waits for its strokes to be resident, as the editor does. */
    private fun openAndLoad(cache: PageDataManager, pageId: String) = runBlocking {
        cache.setPage(pageId)
        cache.requestCurrentPageLoadJoin()
    }

    /**
     * The cache is corrected off the caller's thread; give it a bounded chance to catch up.
     * Sorted, because a page's strokes come back through a Room `@Relation`, which promises no
     * order — this is about which strokes are on the page, not the order they are drawn in.
     */
    private fun awaitStrokeIds(cache: PageDataManager, pageId: String, expected: List<String>) =
        generateSequence(0) { it + 1 }
            .take(100)
            .map {
                Thread.sleep(50)
                cache.getStrokes(pageId).map { stroke -> stroke.id }.sorted()
            }
            .firstOrNull { it == expected.sorted() }
            ?: cache.getStrokes(pageId).map { it.id }.sorted()

    private fun seedPage(vararg strokeIds: String) = runBlocking {
        repository.bookRepository.createEmpty(
            Notebook(id = "nb1", pageIds = listOf("p1", "p2"))
        )
        repository.pageRepository.create(Page(id = "p1", notebookId = "nb1"))
        repository.pageRepository.create(Page(id = "p2", notebookId = "nb1"))
        repository.strokeRepository.create(strokeIds.map { strokeRow(it, "p1") })
    }

    /**
     * The bug this closes. A pulled stroke reached Room but never the screen: [PageDataManager] is
     * the editor's only source of strokes and never re-reads a page it has already loaded, so the
     * open page went on showing its pre-pull ink — while the Library thumbnail, rendered straight
     * from Room, showed the new stroke. Applying a page is only half the job; naming the pages that
     * moved is the other half.
     */
    @Test
    fun aPulledStrokeReachesThePageAlreadyOpen() {
        val cache = pageCache()
        seedPage("s1")
        openAndLoad(cache, "p1")
        assertEquals(listOf("s1"), cache.getStrokes("p1").map { it.id })

        reportingStore().apply(
            CouchDocId.page("p1"),
            CouchDocBody.Page(
                page(
                    listOf(stroke("s1", at = 1), stroke("s2", at = 5, device = "ipad")),
                    notebookId = "nb1", updatedAt = 5, by = "ipad",
                )
            ),
            basedOn = null,
        )

        assertEquals(listOf("s1", "s2"), awaitStrokeIds(cache, "p1", listOf("s1", "s2")))
    }

    /**
     * The other direction: a stroke the incoming page dropped has to leave the screen too, or the
     * cache goes on drawing ink that Room has no copy of.
     */
    @Test
    fun aStrokeTheChangeRemovedLeavesTheOpenPage() {
        val cache = pageCache()
        seedPage("s1", "s2")
        openAndLoad(cache, "p1")
        assertEquals(2, cache.getStrokes("p1").size)

        // basedOn names what the merge was looking at, so dropping s2 from the result is a decision
        // about s2 rather than a stroke the merge never saw.
        reportingStore().apply(
            CouchDocId.page("p1"),
            CouchDocBody.Page(
                page(listOf(stroke("s1", at = 1)), notebookId = "nb1", updatedAt = 5, by = "ipad")
            ),
            basedOn = CouchDocBody.Page(
                page(
                    listOf(stroke("s1", at = 1), stroke("s2", at = 1)),
                    notebookId = "nb1", updatedAt = 1,
                )
            ),
        )

        assertEquals(listOf("s1"), awaitStrokeIds(cache, "p1", listOf("s1")))
    }

    /**
     * A page nobody is drawing is forgotten rather than corrected, so its next open reads Room.
     * Left resident it would be served from memory forever: a loaded page is never read again.
     */
    @Test
    fun aPulledStrokeReachesAPageThatIsMerelyCached() {
        val cache = pageCache()
        seedPage("s1")
        openAndLoad(cache, "p1")
        // Turn the page: p1 stays resident, but it is no longer the current one.
        runBlocking { cache.setPage("p2") }

        reportingStore().apply(
            CouchDocId.page("p1"),
            CouchDocBody.Page(
                page(
                    listOf(stroke("s1", at = 1), stroke("s2", at = 5, device = "ipad")),
                    notebookId = "nb1", updatedAt = 5, by = "ipad",
                )
            ),
            basedOn = null,
        )

        // Dropped, so nothing is left to serve the stale copy from...
        assertEquals(emptyList<String>(), awaitStrokeIds(cache, "p1", emptyList()))
        // ...and opening it again reads Room.
        openAndLoad(cache, "p1")
        assertEquals(listOf("s1", "s2"), cache.getStrokes("p1").map { it.id }.sorted())
    }

    // endregion

    // region Helpers (continued)

    private fun <T> await(read: suspend () -> List<T>): List<T> = generateSequence(0) { it + 1 }
        .take(100)
        .map {
            Thread.sleep(50)
            runBlocking { read() }
        }
        .firstOrNull { it.isNotEmpty() }
        .orEmpty()

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

    /**
     * The whole point of the page tombstone, end to end: deleting a page on the BOOX has to stay
     * deleted on the iPad, including after the iPad — which still listed it a moment ago — pushes
     * its own copy of the notebook back.
     */
    @Test
    fun aPageDeletionTravelsThroughTheEngineAndStaysDeletedOnThePeer() {
        val server = FakeCouchTransport()
        val client = CouchDbClient(server, database = "notes")

        val otherDb = TestDatabaseFactory.createInMemory(context)
        try {
            val ipadRepository = repositoryFor(otherDb)
            val ipadStore = RoomCouchStore(ipadRepository, otherDb.kvDao(), "ipad")
            val boox = CouchSyncEngine(client, store, deviceId = "boox")
            val ipad = CouchSyncEngine(client, ipadStore, deviceId = "ipad")

            val notebookId = CouchDocId.notebook("nb1")
            runBlocking {
                repository.bookRepository.createEmpty(
                    Notebook(id = "nb1", title = "notes", pageIds = listOf("p1", "p2"))
                )
                repository.pageRepository.create(Page(id = "p1", notebookId = "nb1"))
                repository.pageRepository.create(Page(id = "p2", notebookId = "nb1"))
                boox.markDirty(listOf(notebookId))
                boox.flush()
                ipad.pull()
            }
            assertEquals(
                listOf("p1", "p2"),
                (ipadStore.load(notebookId) as CouchDocBody.Notebook).notebook.pageIds,
            )

            // The BOOX deletes p2 and pushes the manifest the removal left behind.
            runBlocking {
                repository.bookRepository.removePage("nb1", "p2")
                repository.deletedPageRepository.record("nb1", listOf("p2"))
                repository.pageRepository.delete("p2")
                boox.markDirty(listOf(notebookId))
                boox.flush()
                ipad.pull()
            }

            val afterDeletion = ipadStore.load(notebookId) as CouchDocBody.Notebook
            assertEquals(listOf("p1"), afterDeletion.notebook.pageIds)
            assertEquals(listOf("p2"), afterDeletion.notebook.deletedPageIds.map { it.id })
            assertNull(runBlocking { ipadRepository.pageRepository.getById("p2") })

            // And the iPad re-pushing what it holds must not put the page back.
            runBlocking {
                ipad.markDirty(listOf(notebookId))
                ipad.flush()
                boox.pull()
            }
            assertEquals(
                listOf("p1"),
                (store.load(notebookId) as CouchDocBody.Notebook).notebook.pageIds,
            )
        } finally {
            otherDb.close()
        }
    }

    // endregion
}

/**
 * Seeds a document as plain local state. There is no snapshot a merge consumed, because no merge
 * preceded it — which is what `basedOn = null` means, and what every arrival at a device that has
 * never held the document looks like.
 */
private fun CouchLocalStore.apply(documentId: String, body: CouchDocBody) =
    apply(documentId, body, basedOn = null)

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
        override suspend fun markDocumentDirty(documentId: String) = Unit
        override suspend fun markUnsentDirty(): Int = 0
        override suspend fun markEverythingDirty() = Unit
        override suspend fun recordDeletion(documentId: String) = Unit
        override suspend fun approveHeldDeletions(ids: List<String>) = Unit
        override suspend fun discardHeldDeletions(ids: List<String>) = Unit
        override suspend fun pendingCount(): Int = 0

        // Null is what the interface reserves for "CouchDB is not the live backend", which is
        // exactly what this stand-in is.
        override val documentState = MutableStateFlow<CouchDocumentState?>(null)
    },
    clock = CouchSyncClock(),
)
