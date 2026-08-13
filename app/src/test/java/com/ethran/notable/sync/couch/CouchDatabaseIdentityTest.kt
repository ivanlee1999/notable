package com.ethran.notable.sync.couch

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Which database this is (protocol §1.2).
 *
 * Local sync state is scoped by endpoint and database name, and that pair cannot tell the original
 * database from a new one created later under the same name. A checkpoint from the old one either
 * fails against the new one or — worse — succeeds while describing history that server never had,
 * with stale revision entries suppressing genuine changes as though they were this device's own
 * echoes.
 *
 * The identity document makes the difference observable. What it must *never* do is decide on its
 * own that a library should be rebuilt: every branch here either proceeds, or stops and leaves the
 * choice to a human. Mirrors bopa's `CouchDatabaseIdentityTests.swift`.
 */
class CouchDatabaseIdentityTest {

    private lateinit var server: FakeCouchTransport
    private lateinit var store: FakeLocalStore

    @Before
    fun setUp() {
        server = FakeCouchTransport()
        store = FakeLocalStore()
    }

    private fun engine(
        state: CouchSyncState = CouchSyncState(),
        enforce: Boolean = false,
        generation: String = "gen-a",
        localStore: FakeLocalStore = store,
        deviceId: String = "boox",
    ) = CouchSyncEngine(
        CouchDbClient(server, database = "notes"),
        localStore,
        deviceId = deviceId,
        state = state,
        enforceDatabaseIdentity = enforce,
        nowIso = { "2026-08-13T00:00:00Z" },
        newGeneration = { generation },
    )

    private fun seedMetadata(
        generation: String,
        minimumClientProtocol: Int = COUCH_PROTOCOL_VERSION,
        locked: Boolean = false,
        lockReason: String? = null,
    ) = server.seed(
        CouchMetaDocId.DATABASE,
        CouchDatabaseMetadata(
            minimumClientProtocol = minimumClientProtocol,
            generation = generation,
            locked = locked,
            lockReason = lockReason,
            updatedAt = "2026-08-13T00:00:00Z",
        ),
        CouchDatabaseMetadata.serializer(),
    )

    private fun notebookJson() = buildJsonObject {
        put("type", JsonPrimitive(CouchDocType.NOTEBOOK))
        put("schema", JsonPrimitive(COUCH_SCHEMA_VERSION))
        put("title", JsonPrimitive("from the iPad"))
        put("createdAt", JsonPrimitive("2026-01-01T00:00:00Z"))
        put("updatedAt", JsonPrimitive("2026-01-01T00:00:00Z"))
        put("updatedBy", JsonPrimitive("ipad"))
    }

    // region Naming a database

    @Test
    fun `an empty database is named on first use`() = runBlocking {
        val engine = engine(generation = "gen-new")

        val identity = engine.verifyDatabaseIdentity()

        assertEquals(CouchSyncEngine.DatabaseIdentity.Matched("gen-new"), identity)
        assertEquals("gen-new", engine.currentState.databaseGeneration)
        assertTrue(
            "the identity document should have been written",
            server.documentIds(includeReserved = true).contains(CouchMetaDocId.DATABASE),
        )
    }

    /**
     * A database that already holds a library predates this document. Minting one here would be
     * this device deciding it is a *new* database — the decision that leads to rebuilding a library
     * it should have been syncing with.
     */
    @Test
    fun `a populated database without metadata is left alone`() = runBlocking {
        server.seedRaw(CouchDocId.notebook("nb1"), notebookJson())
        val engine = engine()

        val identity = engine.verifyDatabaseIdentity()

        assertEquals(CouchSyncEngine.DatabaseIdentity.Unknown, identity)
        assertNull(engine.currentState.databaseGeneration)
        assertFalse(
            "an existing library must not be renamed by whichever client arrives first",
            server.documentIds(includeReserved = true).contains(CouchMetaDocId.DATABASE),
        )
    }

    /**
     * And it must still sync: the whole point of the staged rollout is that a peer which has never
     * written the document keeps working.
     */
    @Test
    fun `a database without metadata still syncs under enforcement`() = runBlocking {
        server.seedRaw(CouchDocId.notebook("nb1"), notebookJson())
        val engine = engine(enforce = true)

        val report = engine.pull()

        assertEquals(CouchSyncEngine.DatabaseIdentity.Unknown, report.databaseIdentity)
        assertEquals(listOf(CouchDocId.notebook("nb1")), report.applied)
    }

    @Test
    fun `an existing identity is adopted on first sight`() = runBlocking {
        seedMetadata(generation = "gen-server")
        val engine = engine()

        val identity = engine.verifyDatabaseIdentity()

        assertEquals(CouchSyncEngine.DatabaseIdentity.Matched("gen-server"), identity)
        assertEquals(
            "a device with no prior claim adopts what it finds",
            "gen-server",
            engine.currentState.databaseGeneration,
        )
    }

    /**
     * Two devices reaching a fresh database together both try to create the document. CouchDB gives
     * exactly one of them the write; the loser adopts rather than retrying, because the question has
     * already been answered.
     */
    @Test
    fun `two devices racing to name an empty database converge on one generation`() = runBlocking {
        val first = engine(generation = "gen-first")
        val second = engine(
            generation = "gen-second",
            localStore = FakeLocalStore(),
            deviceId = "ipad",
        )

        val a = first.verifyDatabaseIdentity()
        val b = second.verifyDatabaseIdentity()

        assertEquals("both devices must end up believing the same thing", a, b)
        assertEquals(CouchSyncEngine.DatabaseIdentity.Matched("gen-first"), a)
        assertEquals("gen-first", second.currentState.databaseGeneration)
    }

    // endregion

    // region Refusing

    @Test
    fun `a different generation under the same name is refused`() = runBlocking {
        seedMetadata(generation = "gen-new")
        val engine = engine(
            state = CouchSyncState(lastSeq = "42", databaseGeneration = "gen-old"),
            enforce = true,
        )

        val identity = engine.verifyDatabaseIdentity()
        assertEquals(
            CouchSyncEngine.DatabaseIdentity.GenerationChanged("gen-old", "gen-new"), identity)
        assertFalse(identity.isUsable)

        try {
            engine.pull()
            throw AssertionError("a pull against a different database should refuse")
        } catch (e: CouchError.DatabaseIdentity) {
            // The point of the test.
        }

        assertEquals(
            "the checkpoint must not be reset on this device's own",
            "42",
            engine.currentState.lastSeq,
        )
        assertEquals(
            "nor the stored identity overwritten",
            "gen-old",
            engine.currentState.databaseGeneration,
        )
    }

    /** The one that cannot be undone from the other side. */
    @Test
    fun `a flush into a different database uploads nothing`() = runBlocking {
        seedMetadata(generation = "gen-new")
        val engine = engine(
            state = CouchSyncState(lastSeq = "42", databaseGeneration = "gen-old"),
            enforce = true,
        )
        store.set(
            CouchDocId.page("p1"),
            CouchDocBody.Page(
                CouchPage(
                    notebookId = "nb1",
                    createdAt = "2026-01-01T00:00:00Z",
                    updatedAt = "2026-01-01T00:00:00Z",
                    updatedBy = "boox",
                )
            ),
        )
        engine.markDirty(listOf(CouchDocId.page("p1")))

        val report = engine.flush()

        assertTrue("nothing may be uploaded into an unknown library", report.pushed.isEmpty())
        assertEquals(
            "and nothing is dropped either",
            listOf(CouchDocId.page("p1")),
            report.stillDirty,
        )
        assertFalse(
            "waiting does not resolve whose library this is; the user's answer does",
            report.hasRetriableFailure,
        )
        assertFalse(server.documentIds().contains(CouchDocId.page("p1")))
    }

    @Test
    fun `a server requiring a newer client is refused before anything is applied`() = runBlocking {
        seedMetadata(generation = "gen-a", minimumClientProtocol = COUCH_PROTOCOL_VERSION + 1)
        server.seedRaw(CouchDocId.notebook("nb1"), notebookJson())
        val engine = engine(enforce = true)

        try {
            engine.pull()
            throw AssertionError("a client below the floor should refuse")
        } catch (e: CouchError.DatabaseIdentity) {
            assertEquals(
                CouchSyncEngine.DatabaseIdentity.ClientTooOld(COUCH_PROTOCOL_VERSION + 1),
                e.identity,
            )
        }
        assertNull("nothing should have been applied", store.body(CouchDocId.notebook("nb1")))
    }

    @Test
    fun `a locked database is refused with its reason`() = runBlocking {
        seedMetadata(generation = "gen-a", locked = true, lockReason = "rebuilding from the iPad")
        val engine = engine(enforce = true)

        try {
            engine.pull()
            throw AssertionError("a locked database should refuse")
        } catch (e: CouchError.DatabaseIdentity) {
            assertEquals(
                CouchSyncEngine.DatabaseIdentity.Locked("rebuilding from the iPad"), e.identity)
        }
    }

    // endregion

    // region The staged rollout

    /**
     * Stage 3 is gated. A client that required the metadata would refuse to sync with a peer of the
     * previous release, which has never written it — so until both apps have shipped stages 1 and 2,
     * a mismatch is reported and nothing more.
     */
    @Test
    fun `a mismatch is reported but not enforced by default`() = runBlocking {
        seedMetadata(generation = "gen-new")
        server.seedRaw(CouchDocId.notebook("nb1"), notebookJson())
        val engine = engine(state = CouchSyncState(databaseGeneration = "gen-old"))

        val report = engine.pull()

        assertEquals(
            CouchSyncEngine.DatabaseIdentity.GenerationChanged("gen-old", "gen-new"),
            report.databaseIdentity,
        )
        assertEquals(
            "observed, not enforced",
            listOf(CouchDocId.notebook("nb1")),
            report.applied,
        )
    }

    // endregion

    // region The reserved namespace

    /**
     * §1.1: bookkeeping is not a library item. It is not merged, not conflict-copied, and does not
     * turn up in a report about what a pull brought down.
     */
    @Test
    fun `the identity document is never treated as a library document`() = runBlocking {
        seedMetadata(generation = "gen-a")
        val engine = engine()

        val report = engine.pull()

        assertTrue(report.applied.isEmpty())
        assertTrue("it is ours, not an unreadable schema", report.conflictCopies.isEmpty())
        assertTrue("and not noise in the report either", report.skippedEchoes.isEmpty())
        assertNull("nothing to store locally", store.body(CouchMetaDocId.DATABASE))
    }

    /**
     * An id from the reserved namespace this build does not know must be stepped over rather than
     * filed as a document from the future — that is what reserving a *prefix* buys.
     */
    @Test
    fun `an unknown reserved document is stepped over rather than conflict copied`() = runBlocking {
        seedMetadata(generation = "gen-a")
        server.seedRaw(
            "sync-meta:something-later",
            buildJsonObject {
                put("type", JsonPrimitive("sync-something"))
                put("schema", JsonPrimitive(99))
            },
        )
        val engine = engine()

        val report = engine.pull()

        assertTrue(report.conflictCopies.isEmpty())
        assertTrue(report.applied.isEmpty())
        assertFalse(
            "and the checkpoint moves past it",
            engine.currentState.lastSeq == "0",
        )
    }

    @Test
    fun `the checkpoint is preserved across runs against the same database`() = runBlocking {
        seedMetadata(generation = "gen-a")
        server.seedRaw(CouchDocId.notebook("nb1"), notebookJson())

        val first = engine(enforce = true)
        first.pull()
        val carried = first.currentState

        // A relaunch: same endpoint, same database, same generation.
        val second = engine(state = carried, enforce = true)
        val report = second.pull()

        assertEquals(CouchSyncEngine.DatabaseIdentity.Matched("gen-a"), report.databaseIdentity)
        assertEquals("nothing replayed", carried.lastSeq, report.lastSeq)
        assertTrue(report.applied.isEmpty())
    }

    // endregion
}
