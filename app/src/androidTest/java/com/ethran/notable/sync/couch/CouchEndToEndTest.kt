package com.ethran.notable.sync.couch

import android.content.Context
import android.graphics.Color
import android.graphics.pdf.PdfDocument
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ethran.notable.data.AppRepository
import com.ethran.notable.data.db.AppDatabase
import com.ethran.notable.data.db.BookRepository
import com.ethran.notable.data.db.CouchDeletionRepository
import com.ethran.notable.data.db.CouchOutboxRepository
import com.ethran.notable.data.db.CryptoHelper
import com.ethran.notable.data.db.DeletedImageRepository
import com.ethran.notable.data.db.DeletedPageRepository
import com.ethran.notable.data.db.DeletedStrokeRepository
import com.ethran.notable.data.db.FolderRepository
import com.ethran.notable.data.db.ImageRepository
import com.ethran.notable.data.db.KvProxy
import com.ethran.notable.data.db.KvRepository
import com.ethran.notable.data.db.Notebook
import com.ethran.notable.data.db.NotebookSyncStateRepository
import com.ethran.notable.data.db.PageRepository
import com.ethran.notable.data.db.PageSyncStateRepository
import com.ethran.notable.data.db.StrokeRepository
import com.ethran.notable.data.model.BackgroundType
import com.ethran.notable.io.ImportOptions
import com.ethran.notable.io.importPdf
import com.ethran.notable.testing.TestDatabaseFactory
import com.ethran.notable.testing.trashRepositoryFor
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64
import java.util.Date
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * The whole BOOX stack — repositories, Room, [RoomCouchStore], [CouchSyncEngine] — against a **real**
 * CouchDB. The last open item of the sync review.
 *
 * The 22 shared scenarios are good engine tests, but their scenario stores call `markDirty`
 * themselves, so they converge happily while every one of the app's real mutation paths fails to
 * queue anything. [com.ethran.notable.data.db.CouchOutboxTest] closed half of that gap: it drives
 * the real repositories and asks what landed in `couch_outbox`. This closes the other half — it
 * drives the same repositories and asks **what landed on the server**, which is the question a user
 * asks. A document can be queued correctly and still never arrive.
 *
 * Every defect this branch fixed lived in exactly this gap, which is also why the recovery paths
 * are here: an outbox that survives being offline is only worth having if it drains afterwards.
 *
 * Skipped unless `NOTABLE_COUCH_URL` is set, so a machine with no CouchDB still gets a green suite:
 *
 *     NOTABLE_COUCH_URL=http://10.0.2.2:5984 NOTABLE_COUCH_USER=sync \
 *       NOTABLE_COUCH_PASSWORD=… NOTABLE_COUCH_ADMIN_USER=admin NOTABLE_COUCH_ADMIN_PASSWORD=… \
 *       ./gradlew :app:connectedDebugAndroidTest
 *
 * `app/build.gradle` forwards those as instrumentation runner arguments — the test runs in an app
 * process on a device, which inherits no environment from the shell.
 */
@RunWith(AndroidJUnit4::class)
class CouchEndToEndTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private lateinit var db: AppDatabase
    private lateinit var repository: AppRepository
    private lateinit var images: File

    /** Where a downloaded background is filed, and where a "Copy" import puts the document. */
    private lateinit var backgrounds: File

    /**
     * Where a document the user chose to *observe* stays: outside every folder sync files its own
     * downloads in, which is the whole point of that mode — and was the whole difficulty.
     */
    private lateinit var observed: File
    private lateinit var client: CouchDbClient
    private lateinit var database: String

    @Before
    fun setUp() {
        assumeTrue("set NOTABLE_COUCH_URL to run the end-to-end tests", url != null)
        // A database per *test*, not per class. Every engine here replays the feed from zero, so a
        // shared one would have each test read every other test's documents — and assertions like
        // "this peer had nothing to push back" would be answering for the whole class.
        database = "notable-e2e-${UUID.randomUUID().toString().take(12)}"
        admin("PUT", "$url/$database")
        // The sync account has to be able to read and write it, or every request is a 401.
        admin(
            "PUT", "$url/$database/_security",
            """{"members":{"names":["$user"]},"admins":{"names":[]}}""",
        )
        db = TestDatabaseFactory.createInMemory(context)
        repository = repositoryFor(db)
        images = File(context.cacheDir, "e2e-images-${UUID.randomUUID()}").apply { mkdirs() }
        backgrounds = File(context.cacheDir, "e2e-bg-${UUID.randomUUID()}").apply { mkdirs() }
        observed = File(context.cacheDir, "e2e-observed-${UUID.randomUUID()}").apply { mkdirs() }
        client = clientFor()
    }

    @After
    fun tearDown() {
        if (url == null) return
        db.close()
        images.deleteRecursively()
        backgrounds.deleteRecursively()
        observed.deleteRecursively()
        admin("DELETE", "$url/$database")
    }

    // region Fixtures

    private fun repositoryFor(db: AppDatabase) = AppRepository(
        bookRepository = BookRepository(
            db.notebookDao(), db.pageDao(), CouchOutboxRepository(db.couchOutboxDao()), db
        ),
        pageRepository = PageRepository(
            db.pageDao(), CouchOutboxRepository(db.couchOutboxDao()), db
        ),
        strokeRepository = StrokeRepository(db.strokeDao()),
        imageRepository = ImageRepository(db.ImageDao()),
        folderRepository = FolderRepository(
            db.folderDao(), CouchOutboxRepository(db.couchOutboxDao()), db
        ),
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

    private fun clientFor(baseUrl: String = url!!) = CouchDbClient(
        OkHttpCouchTransport(
            baseUrl = baseUrl,
            username = user,
            password = password,
            client = OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .build(),
        ),
        database = database,
    )

    /** This device. A second one gets its own Room database, its own store and its own engine. */
    private fun storeFor(
        repository: AppRepository,
        db: AppDatabase,
        deviceId: String,
        imagesFolder: File,
        backgroundsFolder: File = backgrounds,
    ) = RoomCouchStore(
        repository, db.kvDao(), deviceId = deviceId,
        imagesFolder = { imagesFolder },
        backgroundsFolder = { backgroundsFolder },
    )

    private fun store(deviceId: String = "boox") = storeFor(repository, db, deviceId, images)

    private fun engine(
        store: CouchLocalStore = store(),
        deviceId: String = "boox",
        client: CouchDbClient = this.client,
        state: CouchSyncState = CouchSyncState(),
    ) = CouchSyncEngine(client, store, deviceId = deviceId, state = state)

    /**
     * A second device: its own Room database and its own store, sharing only the server. Returned
     * as a triple rather than fields because the tests that need one close it themselves — an
     * in-memory Room database left open outlives the test that made it.
     */
    private fun secondDevice(): Triple<AppDatabase, AppRepository, File> {
        val otherDb = TestDatabaseFactory.createInMemory(context)
        val otherImages = File(context.cacheDir, "e2e-images-${UUID.randomUUID()}")
            .apply { mkdirs() }
        return Triple(otherDb, repositoryFor(otherDb), otherImages)
    }

    /** A notebook with one page, made the way `BookRepository.create` makes it. */
    private fun seedNotebook(title: String = "Notes"): Pair<String, String> = runBlocking {
        val notebook = Notebook(title = title)
        repository.bookRepository.create(notebook)
        notebook.id to repository.bookRepository.getById(notebook.id)!!.pageIds.single()
    }

    private fun queued(): Set<String> = runBlocking { db.couchOutboxDao().allIds().toSet() }

    private fun flush(engine: CouchSyncEngine): CouchSyncEngine.FlushReport = runBlocking {
        val report = engine.flush()
        assertTrue("pushing failed: ${report.failures}", report.failures.isEmpty())
        report
    }

    private fun notebookOnServer(notebookId: String) = runBlocking {
        client.get(CouchDocId.notebook(notebookId), CouchNotebook.serializer())?.body
    }

    private fun pageOnServer(pageId: String) = runBlocking {
        client.get(CouchDocId.page(pageId), CouchPage.serializer())?.body
    }

    // endregion

    // region Every mutation hook reaches the server

    /**
     * The defect the review opened with. Creating a notebook queued the manifest and left behind the
     * page it names, so the peer received a notebook pointing at a document the server had never
     * been offered — a notebook that opens on nothing.
     */
    @Test
    fun creating_a_notebook_sends_the_notebook_and_its_first_page() {
        val (notebookId, pageId) = seedNotebook("From the BOOX")
        flush(engine())

        assertEquals("From the BOOX", notebookOnServer(notebookId)?.title)
        assertEquals(listOf(pageId), notebookOnServer(notebookId)?.pageIds)
        assertNotNull("the page the notebook names never arrived", pageOnServer(pageId))
    }

    @Test
    fun adding_a_blank_page_sends_the_page_and_the_new_page_list() = runBlocking {
        val (notebookId, firstPageId) = seedNotebook()
        flush(engine())

        val added = repository.newPageInBook(notebookId, index = 1)!!
        flush(engine())

        assertNotNull("the new page never arrived", pageOnServer(added))
        assertEquals(listOf(firstPageId, added), notebookOnServer(notebookId)?.pageIds)
    }

    @Test
    fun duplicating_a_page_sends_the_duplicate_and_the_new_page_list() = runBlocking {
        val (notebookId, pageId) = seedNotebook()
        flush(engine())

        repository.duplicatePage(pageId)
        flush(engine())

        val duplicate = repository.bookRepository.getById(notebookId)!!.pageIds
            .single { it != pageId }
        assertNotNull("the duplicate never arrived", pageOnServer(duplicate))
        assertTrue(
            "the server still lists only the original",
            notebookOnServer(notebookId)?.pageIds?.contains(duplicate) == true,
        )
    }

    /**
     * Reorder is the case the "never sent" scan can never rescue: the notebook is one the server
     * already holds, so nothing about it looks unsent, and only the order of `pageIds` moved.
     */
    @Test
    fun reordering_pages_sends_the_new_order() = runBlocking {
        val (notebookId, firstPageId) = seedNotebook()
        val second = repository.newPageInBook(notebookId, index = 1)!!
        flush(engine())
        assertEquals(listOf(firstPageId, second), notebookOnServer(notebookId)?.pageIds)

        repository.bookRepository.changePageIndex(notebookId, firstPageId, 1)
        flush(engine())

        assertEquals(listOf(second, firstPageId), notebookOnServer(notebookId)?.pageIds)
    }

    @Test
    fun renaming_a_page_sends_the_title() = runBlocking {
        val (_, pageId) = seedNotebook()
        flush(engine())

        repository.pageRepository.rename(pageId, "Chapter one")
        flush(engine())

        assertEquals("Chapter one", pageOnServer(pageId)?.title)
    }

    /**
     * A background change writes the page row and nothing else — no stroke, so none of the drawing
     * paths that queue anything runs. Before the outbox it stayed on the device it was chosen on.
     */
    @Test
    fun changing_only_a_page_background_sends_the_background() = runBlocking {
        val (_, pageId) = seedNotebook()
        flush(engine())
        assertEquals("blank", pageOnServer(pageId)?.background)

        val page = repository.pageRepository.getById(pageId)!!
        repository.pageRepository.update(
            page.copy(background = "grid", backgroundType = "native", updatedAt = Date())
        )
        flush(engine())

        assertEquals("grid", pageOnServer(pageId)?.background)
    }

    // endregion

    // region The document the pages are drawn on

    /**
     * A real one-page PDF, written by the platform. The importer measures and counts the sheets of
     * whatever it is handed, so a fixture pretending to be a document would not survive the trip.
     */
    private fun writePdf(file: File): ByteArray {
        val document = PdfDocument()
        val page = document.startPage(PdfDocument.PageInfo.Builder(595, 842, 1).create())
        page.canvas.drawColor(Color.WHITE)
        document.finishPage(page)
        file.parentFile?.mkdirs()
        file.outputStream().use { document.writeTo(it) }
        document.close()
        return file.readBytes()
    }

    /**
     * A book imported from [file], as `ImportEngine.importPdfFile` writes one: the notebook carries
     * the document as its default background and one page is created per sheet. The pages come from
     * the importer itself, so what is asserted below is what an import really leaves behind.
     */
    private fun importBook(file: File, observe: Boolean): Pair<String, List<String>> = runBlocking {
        val book = Notebook(
            title = file.nameWithoutExtension,
            defaultBackground = file.absolutePath,
            defaultBackgroundType = BackgroundType.AutoPdf.key,
        )
        repository.bookRepository.createEmpty(book)
        val pageIds = mutableListOf<String>()
        importPdf(file, ImportOptions(linkToExternalFile = observe), book.id) { data ->
            repository.pageRepository.create(data.page)
            repository.bookRepository.addPage(book.id, data.page.id)
            pageIds += data.page.id
        }
        book.id to pageIds
    }

    private fun assetOnServer(assetId: String): ByteArray? =
        runBlocking { client.getAttachment(assetId)?.bytes }

    /**
     * "Copy": the importer files the document under the database and the pages name it there. The
     * pages have always synced; this is about what they are drawn on, without which a peer opens
     * the book on blank sheets with the ink floating over them.
     */
    @Test
    fun importing_a_pdf_sends_the_document_its_pages_are_drawn_on() {
        val file = File(backgrounds, "pdfs/Lecture 3.pdf")
        val bytes = writePdf(file)
        val assetId = CouchAssetId.forBytes(bytes)

        val (bookId, pageIds) = importBook(file, observe = false)
        flush(engine())

        assertArrayEquals(
            "the document the pages are drawn on never reached the server",
            bytes, assetOnServer(assetId),
        )
        assertEquals(assetId, notebookOnServer(bookId)?.defaultBackground)
        assertEquals(assetId, pageOnServer(pageIds.first())?.background)
    }

    /**
     * "Observe": the document stays where the user keeps it — a Download, a cloud folder, a LaTeX
     * build directory — and the pages point at that path. Those are the same bytes and they have to
     * travel the same way; looking for them by folder alone found nothing, and an asset the store
     * cannot produce was dropped from the push without a word, so the ink arrived and the document
     * never did.
     */
    @Test
    fun observing_a_pdf_outside_the_apps_own_folders_sends_it_too() {
        val file = File(observed, "Lecture 3.pdf")
        val bytes = writePdf(file)
        val assetId = CouchAssetId.forBytes(bytes)

        val (bookId, pageIds) = importBook(file, observe = true)
        flush(engine())

        assertArrayEquals(
            "the observed document never reached the server",
            bytes, assetOnServer(assetId),
        )
        assertEquals(assetId, notebookOnServer(bookId)?.defaultBackground)
        assertEquals(assetId, pageOnServer(pageIds.first())?.background)
    }

    /**
     * The far end of the same trip: a second device pulls the book and has to end up with the
     * document on disk, at the path its own page rows point at. This is the assertion a user makes
     * by opening the notebook.
     */
    @Test
    fun a_peer_receives_the_document_and_files_it_where_its_pages_look() {
        val file = File(observed, "Lecture 3.pdf")
        val bytes = writePdf(file)
        val assetId = CouchAssetId.forBytes(bytes)
        val (_, pageIds) = importBook(file, observe = true)
        flush(engine())

        val (otherDb, otherRepository, otherImages) = secondDevice()
        val otherBackgrounds = File(context.cacheDir, "e2e-bg-${UUID.randomUUID()}")
            .apply { mkdirs() }
        try {
            val peer = engine(
                store = storeFor(otherRepository, otherDb, "ipad", otherImages, otherBackgrounds),
                deviceId = "ipad",
            )
            val pull = runBlocking { peer.pull() }

            assertTrue(
                "the peer never downloaded the document: ${pull.assetFailures}",
                assetId in pull.fetchedAssets,
            )
            val background = runBlocking { otherRepository.pageRepository.getById(pageIds.first()) }
                ?.background
            assertNotNull("the peer's page names no background at all", background)
            val landed = File(background!!)
            assertTrue("the peer's page points at a file that is not there", landed.isFile)
            assertArrayEquals(bytes, landed.readBytes())
        } finally {
            otherDb.close()
            otherImages.deleteRecursively()
            otherBackgrounds.deleteRecursively()
        }
    }

    // endregion

    // region The mirror image: applying a remote change must queue nothing

    /**
     * The highest-consequence assumption in the branch, checked the only way that really settles it:
     * two engines, two Room databases, one real server.
     *
     * Landing a change received from the server goes through the same repository methods a user edit
     * does. If those queued, the peer's pull would queue a push, this device would receive the echo
     * and queue one back, and two switched-on devices would ping-pong until one was turned off. The
     * unit test for this asserts on `couch_outbox`; this asserts that the second device, having
     * pulled real documents off a real server, then has nothing whatsoever to say back to it.
     */
    @Test
    fun a_peer_that_pulls_has_nothing_to_push_back() {
        val (notebookId, pageId) = seedNotebook("Written here")
        flush(engine())

        val (otherDb, otherRepository, otherImages) = secondDevice()
        try {
            val peer = engine(
                store = storeFor(otherRepository, otherDb, "ipad", otherImages),
                deviceId = "ipad",
            )
            val pull = runBlocking { peer.pull() }

            // It really did receive them — otherwise "nothing to push back" is trivially true.
            assertTrue(
                "the peer never received the notebook, so this proves nothing",
                CouchDocId.notebook(notebookId) in pull.applied,
            )
            assertTrue(
                "the peer never received the page, so this proves nothing",
                CouchDocId.page(pageId) in pull.applied,
            )
            assertEquals(
                "Written here",
                runBlocking { otherRepository.bookRepository.getById(notebookId) }?.title,
            )

            assertEquals(
                "applying a remote change queued a push — two devices would ping-pong forever",
                emptySet<String>(),
                runBlocking { otherDb.couchOutboxDao().allIds().toSet() },
            )

            // And a flush right after the pull sends nothing, which is the behaviour the user sees.
            val echo = flush(peer)
            assertEquals(emptyList<String>(), echo.pushed)
            assertEquals(emptyList<String>(), echo.merged)
        } finally {
            otherDb.close()
            otherImages.deleteRecursively()
        }
    }

    // endregion

    // region Recovery

    /**
     * The reconnect fix. An edit made with no server reachable stays in `couch_outbox` — the whole
     * point of the table — and the next engine that does reach the server drains it without the user
     * having to touch the document again.
     *
     * The offline half uses a port nothing is listening on rather than a fake transport: "the flush
     * failed" has to mean a real transport failure, because a fake one is a statement about what we
     * believe failure looks like.
     */
    @Test
    fun an_edit_made_offline_is_sent_once_the_server_comes_back() = runBlocking {
        val (notebookId, pageId) = seedNotebook("Made offline")

        val offline = engine(client = clientFor("http://127.0.0.1:1"))
        val failed = offline.flush()
        assertTrue("the offline flush should have failed", failed.failures.isNotEmpty())

        // Nothing was lost: the durable outbox still names both documents.
        assertEquals(
            setOf(CouchDocId.notebook(notebookId), CouchDocId.page(pageId)),
            queued(),
        )
        assertNull("nothing should have reached the server", notebookOnServer(notebookId))

        // A fresh engine, as a restarted app would build — its in-memory dirty set starts empty and
        // is adopted from the table, which is what makes the outbox durable rather than decorative.
        flush(engine())

        assertEquals("Made offline", notebookOnServer(notebookId)?.title)
        assertNotNull(pageOnServer(pageId))
        assertEquals("the outbox should have drained", emptySet<String>(), queued())
    }

    /**
     * Checkpoint loss: a device that forgets `lastSeq` replays the feed from zero. That is slow, and
     * it must also be *harmless* — the second pass has to reach the same content as the first, and
     * anything it decides to send must be what the server already holds.
     *
     * It is now silent too. It was not: a notebook row had no `updatedBy` column, so
     * `RoomCouchStore.loadNotebook` stamped the reading device's own id. On a replay the local copy
     * claimed authorship of a document the peer wrote, the timestamps tied, and `CouchMerge.wins`
     * broke the tie on `updatedBy` — so on the device whose id sorts higher ("ipad" > "boox") the
     * local copy won and was queued straight back. The content was identical, which is why it was
     * recovery rather than corruption, but a device that lost its checkpoint re-uploaded what it
     * held. [Notebook.updatedBy] is that column, so the replay now recognises the peer's document as
     * the peer's and sends nothing at all — which is what the empty outbox below asserts.
     */
    @Test
    fun losing_the_checkpoint_replays_without_changing_anything() {
        val (notebookId, pageId) = seedNotebook("Replayed")
        flush(engine())

        val (otherDb, otherRepository, otherImages) = secondDevice()
        try {
            val store = storeFor(otherRepository, otherDb, "ipad", otherImages)
            val first = engine(store = store, deviceId = "ipad")
            runBlocking { first.pull() }
            val afterFirst = runBlocking { otherRepository.bookRepository.getById(notebookId) }
            assertEquals("Replayed", afterFirst?.title)

            // The same store, a new engine that has never heard of a checkpoint: replay from "0".
            val amnesiac = engine(store = store, deviceId = "ipad", state = CouchSyncState())
            runBlocking { amnesiac.pull() }

            val afterReplay = runBlocking { otherRepository.bookRepository.getById(notebookId) }
            assertEquals("Replayed", afterReplay?.title)
            assertEquals(
                "the replay must not duplicate the notebook's pages",
                listOf(pageId),
                afterReplay?.pageIds,
            )
            // Nothing at all: the replay re-read documents this device knows the peer wrote, so the
            // merge finds nothing of its own to contribute and queues no push. Before
            // [Notebook.updatedBy] existed this held the notebook and its page — identical content,
            // uploaded again for no reason — and the assertion below is what keeps that from
            // quietly coming back.
            val requeued = runBlocking { otherDb.couchOutboxDao().allIds().toSet() }
            assertEquals(
                "a replay of documents the peer wrote must send nothing back",
                emptySet<String>(),
                requeued,
            )
            flush(engine(store = store, deviceId = "ipad"))
            assertEquals("Replayed", notebookOnServer(notebookId)?.title)
            assertEquals(listOf(pageId), notebookOnServer(notebookId)?.pageIds)
        } finally {
            otherDb.close()
            otherImages.deleteRecursively()
        }
    }

    /**
     * A notebook deleted here has to stay deleted after a pull. The tombstone is the only thing
     * standing between the user and the notebook they deleted coming back off the server, so this
     * follows the deletion all the way out and back.
     */
    @Test
    fun a_notebook_deleted_here_stays_deleted_across_a_pull() = runBlocking {
        val (notebookId, _) = seedNotebook("Doomed")
        flush(engine())
        assertNotNull(notebookOnServer(notebookId))

        repository.deleteNotebookLocally(notebookId, deletedAt = Instantish.now())
        flush(engine())

        // The server holds a tombstone, not the notebook.
        assertNull(
            "the notebook is still live on the server",
            runBlocking { client.getRaw(CouchDocId.notebook(notebookId)) }?.takeIf { !it.deleted },
        )

        val pulled = engine()
        runBlocking { pulled.pull() }

        assertNull(
            "the deleted notebook came back on a pull",
            repository.bookRepository.getById(notebookId),
        )
        // The local tombstone is settled, not forgotten: the server has taken the deletion, so
        // there is nothing left to push — but the notebook's pages are still live documents on the
        // server, and this device has to keep recognizing them as leftovers on every later feed.
        assertEquals(
            "nothing should still be queued for a deletion the server has taken",
            emptyList<String>(),
            repository.couchDeletionRepository.pendingIds(),
        )
        assertNotNull(
            "the device forgot that the notebook was deleted",
            repository.couchDeletionRepository.get(CouchDocId.notebook(notebookId)),
        )
        assertEquals(emptySet<String>(), queued())
    }

    /**
     * The same promise on a device that only ever *heard* about the deletion, and in the order that
     * does not heal itself.
     *
     * Deleting a notebook leaves its `page:<id>` documents live on the server for good (protocol
     * §6.4: pages keep no tombstone of their own), so the orphans outlive the tombstone and can be
     * re-announced long after it. Here the peer applies the deletion in one pull, and only in a
     * later one meets the page — re-uploaded verbatim by a third device that lost its checkpoint,
     * which [losing_the_checkpoint_replays_without_changing_anything] pins as a real thing this
     * stack does. Nothing will ever mention `notebook:<id>` again.
     *
     * This is the case that persists. Within a single batch a placeholder built from the page is
     * deleted again by the tombstone that follows it, so the damage is invisible; across two pulls
     * there is no tombstone left to follow, and the placeholder stays in that library for good —
     * and republishes the deleted notebook the next time the device uploads what it holds.
     */
    @Test
    fun a_peer_does_not_rebuild_a_deleted_notebook_around_a_page_announced_later() {
        val (notebookId, pageId) = seedNotebook("Doomed elsewhere")
        flush(engine())

        val (otherDb, otherRepository, otherImages) = secondDevice()
        try {
            val store = storeFor(otherRepository, otherDb, "ipad", otherImages)
            val peer = engine(store = store, deviceId = "ipad")
            runBlocking { peer.pull() }
            assertEquals(
                "Doomed elsewhere",
                runBlocking { otherRepository.bookRepository.getById(notebookId) }?.title,
            )

            runBlocking { repository.deleteNotebookLocally(notebookId, deletedAt = Instantish.now()) }
            flush(engine())
            // The peer takes the deletion. Its page went with the notebook, and from here on the
            // server will never mention `notebook:<id>` again.
            runBlocking { peer.pull() }
            assertNull(runBlocking { otherRepository.bookRepository.getById(notebookId) })

            // A third device that lost its checkpoint re-uploads the page exactly as it holds it:
            // a new revision, a sequence above the peer's checkpoint, and — because nothing was
            // edited — the same `updatedAt` it always had. It is a leftover, not an edit, so §6.4
            // leaves the deletion standing.
            val stored = runBlocking { client.get(CouchDocId.page(pageId), CouchPage.serializer()) }
            assertNotNull("the deletion should have left the page document behind", stored)
            runBlocking {
                client.put(
                    CouchDocId.page(pageId), stored!!.rev, stored.body, CouchPage.serializer()
                )
            }

            runBlocking { peer.pull() }

            assertNull(
                "the deleted notebook was rebuilt around a page announced after its tombstone",
                runBlocking { otherRepository.bookRepository.getById(notebookId) },
            )
            assertNull(
                "the leftover page was applied even though its notebook is deleted",
                runBlocking { otherRepository.pageRepository.getById(pageId) },
            )
            // The real cost of the placeholder was never the row: it is queued like any other local
            // change, so the next flush would publish the notebook the user deleted.
            assertEquals(
                "the peer queued a deleted notebook for re-upload",
                emptySet<String>(),
                runBlocking { otherDb.couchOutboxDao().allIds().toSet() },
            )
        } finally {
            otherDb.close()
            otherImages.deleteRecursively()
        }
    }

    // endregion

    private object Instantish {
        fun now(): String = java.time.Instant.now().toString()
    }

    companion object {
        private val arguments = InstrumentationRegistry.getArguments()

        /**
         * The emulator's `127.0.0.1` is the emulator, not the machine running the tests, so a URL
         * copied from the JVM suites reaches nothing. `10.0.2.2` is the host as seen from inside,
         * and rewriting here means one variable works for both tiers.
         */
        private val url: String? = arguments.getString("NOTABLE_COUCH_URL")
            ?.takeIf { it.isNotBlank() }
            ?.replace("//127.0.0.1:", "//10.0.2.2:")
            ?.replace("//localhost:", "//10.0.2.2:")

        private val user: String = arguments.getString("NOTABLE_COUCH_USER") ?: "sync"
        private val password: String = arguments.getString("NOTABLE_COUCH_PASSWORD") ?: ""

        /**
         * Creating and dropping a database is the one thing here that is not something the app does,
         * and a sync account with rights over one database answers 401 to it. Defaults to the sync
         * account, for a server that does grant it.
         */
        private val adminUser: String = arguments.getString("NOTABLE_COUCH_ADMIN_USER") ?: user
        private val adminPassword: String =
            arguments.getString("NOTABLE_COUCH_ADMIN_PASSWORD") ?: password

        fun admin(method: String, target: String, body: String? = null) {
            val connection = (URL(target).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                val credentials = Base64.getEncoder()
                    .encodeToString("$adminUser:$adminPassword".toByteArray())
                setRequestProperty("Authorization", "Basic $credentials")
                if (body != null) {
                    setRequestProperty("Content-Type", "application/json")
                    doOutput = true
                }
            }
            try {
                body?.let { connection.outputStream.use { out -> out.write(it.toByteArray()) } }
                val status = connection.responseCode
                check(status in 200..299 || status == 412) {
                    "$method $target answered $status — set NOTABLE_COUCH_ADMIN_USER/PASSWORD"
                }
            } finally {
                connection.disconnect()
            }
        }
    }
}
