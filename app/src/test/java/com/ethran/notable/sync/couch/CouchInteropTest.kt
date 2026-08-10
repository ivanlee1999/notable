package com.ethran.notable.sync.couch

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * Steps 1 and 3 of the three-step cross-app proof. Step 2 lives in bopa's `CouchInteropTests.swift`;
 * all three run in order against one real CouchDB:
 *
 * 1. **notable** (here) pushes `page:interop-<id>` carrying one stroke, `boox-stroke`.
 * 2. **bopa** pulls that page, adds `ipad-stroke`, and pushes the result back.
 * 3. **notable** (here) pulls again and must see *both* strokes.
 *
 * What this checks that the per-app suites cannot: that the two independent implementations of the
 * document format and the merge actually agree in the same database — that Kotlin's serializer
 * writes what Swift's decoder expects, and that the merge is the same function on both sides.
 *
 * The two steps are separate `@Test` methods so a driver script can run them either side of the
 * Swift step; nothing here waits for bopa.
 *
 * Skipped unless `NOTABLE_COUCH_URL` and `COUCH_INTEROP_ID` are both set.
 */
class CouchInteropTest {

    private lateinit var client: CouchDbClient
    private lateinit var documentId: String

    @Before
    fun setUp() {
        val url = System.getenv("NOTABLE_COUCH_URL")
        val interopId = System.getenv("COUCH_INTEROP_ID")
        assumeTrue(
            "set NOTABLE_COUCH_URL and COUCH_INTEROP_ID to run the interop steps",
            !url.isNullOrBlank() && !interopId.isNullOrBlank(),
        )
        client = CouchDbClient(
            OkHttpCouchTransport(
                baseUrl = url!!,
                username = System.getenv("NOTABLE_COUCH_USER") ?: "sync",
                password = System.getenv("NOTABLE_COUCH_PASSWORD") ?: "",
                client = OkHttpClient.Builder()
                    .connectTimeout(30, TimeUnit.SECONDS)
                    .readTimeout(60, TimeUnit.SECONDS)
                    .writeTimeout(60, TimeUnit.SECONDS)
                    .build(),
            ),
            database = System.getenv("NOTABLE_COUCH_DATABASE") ?: "notes",
        )
        documentId = CouchDocId.page("interop-$interopId")
    }

    private fun stamp(second: Int): String =
        Instant.ofEpochSecond(1_770_000_000L + second).toString()

    @Test
    fun interop_step1_boox_pushes_a_page() = runBlocking {
        val store = FakeLocalStore()
        val engine = CouchSyncEngine(client, store, deviceId = "boox")

        store.set(
            documentId,
            CouchDocBody.Page(
                CouchPage(
                    notebookId = "interop-nb",
                    strokes = listOf(
                        CouchStroke(
                            id = "boox-stroke",
                            createdAt = stamp(0), updatedAt = stamp(0), deviceId = "boox",
                            pen = "BALLPEN", color = -16_777_216, size = 3.5f,
                            top = 0f, bottom = 10f, left = 0f, right = 10f,
                            pointsData = "U0ICD10AAAAA",
                        )
                    ),
                    createdAt = stamp(0), updatedAt = stamp(1), updatedBy = "boox",
                )
            )
        )
        engine.markDirty(listOf(documentId))

        val flush = engine.flush()
        assertTrue("pushing failed: ${flush.failures}", flush.failures.isEmpty())
        assertTrue(
            "the page was not pushed",
            documentId in flush.pushed || documentId in flush.merged,
        )

        // Confirm on the server, so step 2 is diagnosing bopa rather than this step.
        val onServer = client.get(documentId, CouchPage.serializer())
        assertEquals(listOf("boox-stroke"), onServer?.body?.strokes?.map { it.id })
        assertEquals("interop-nb", onServer?.body?.notebookId)
        assertEquals("boox", onServer?.body?.updatedBy)
    }

    @Test
    fun interop_step3_boox_sees_the_union() = runBlocking {
        val store = FakeLocalStore()
        val engine = CouchSyncEngine(client, store, deviceId = "boox")

        // A fresh engine replays the feed from "0", so the document is guaranteed to be in range.
        val pull = engine.pull()
        assertTrue(
            "notable did not receive the page bopa pushed back — got ${pull.applied}",
            documentId in pull.applied,
        )

        val page = store.page(documentId)
        assertEquals(
            "the iPad's stroke should have merged in without losing the BOOX's",
            listOf("boox-stroke", "ipad-stroke"),
            page?.strokes?.map { it.id }?.sorted(),
        )
    }
}
