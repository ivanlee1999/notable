package com.ethran.notable.sync.couch

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.time.Instant
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * Which device this invocation is playing. `boox` — notable's own role in the cross-app run — unless
 * the driver says otherwise, so bopa's `scripts/couch-scenarios.sh` is unaffected.
 *
 * The override exists because every one of the shared scenarios has an iPad step, so notable's half
 * cannot be run at all without a peer: with no bopa checkout the whole suite is unrunnable rather
 * than partly runnable. Notable's harness happens to implement a superset of the ops the iPad steps
 * use, so pointing a second invocation at `ipad` lets one checkout drive both sides through a real
 * server (`scripts/couch-scenarios-solo.sh`). That proves notable satisfies the shared script and
 * that two independent engines converge; it does **not** prove the Swift implementation agrees —
 * only bopa's driver does that, and this changes nothing about how it runs.
 */
private val DEVICE_ID: String =
    System.getenv("COUCH_SCENARIO_DEVICE")?.takeIf { it.isNotBlank() } ?: "boox"

/**
 * notable's half of the cross-app scenario suite. The iPad half is bopa's `CouchScenarioTests.swift`;
 * bopa's `scripts/couch-scenarios.sh` interleaves them against one real CouchDB, and the script both
 * halves execute is bopa's `docs/couch-sync-vectors/interop-scenarios.json` (passed by path, so
 * there is one copy and it cannot drift).
 *
 * Where `vectors.json` proves the two merge functions agree on paper, this proves two devices
 * driving their own engines through a real server converge on the same content — including the
 * cases no single-app suite can stage, like an erasure made here having to stay erased after the
 * iPad pushes its own copy back.
 *
 * One invocation runs one step index: for every scenario whose step at that index belongs to this
 * device, it replays the device's persisted state, executes the ops, and saves the state again.
 * Skipped unless the environment names a server, a scenario file and a step.
 */
class CouchScenarioTest {

    @Test
    fun scenarioStep() = runBlocking {
        val url = System.getenv("NOTABLE_COUCH_URL")
        val scriptPath = System.getenv("COUCH_SCENARIO_FILE")
        val stepText = System.getenv("COUCH_SCENARIO_STEP")
        val stateDir = System.getenv("COUCH_SCENARIO_STATE_DIR")
        val runId = System.getenv("COUCH_SCENARIO_RUN")
        assumeTrue(
            "scenario steps run from bopa's scripts/couch-scenarios.sh",
            !url.isNullOrBlank() && !scriptPath.isNullOrBlank() && !stepText.isNullOrBlank() &&
                !stateDir.isNullOrBlank() && !runId.isNullOrBlank(),
        )
        val step = stepText!!.toInt()
        val stateDirectory = File(stateDir!!)

        val client = CouchDbClient(
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

        val script = Json.parseToJsonElement(File(scriptPath!!).readText()).jsonObject
        val scenarios = script["scenarios"]!!.jsonArray.map { it.jsonObject }

        var ran = 0
        val failures = mutableListOf<String>()
        for (scenario in scenarios) {
            val name = scenario["name"]!!.jsonPrimitive.content
            val steps = scenario["steps"]!!.jsonArray
            if (step >= steps.size) continue
            val entry = steps[step].jsonObject
            if (entry["device"]?.jsonPrimitive?.content != DEVICE_ID) continue

            val ops = entry["do"]?.jsonArray?.map { it.jsonObject } ?: emptyList()
            // Per scenario, so one broken scenario does not hide the verdict on the others.
            runCatching { ScenarioDevice(name, runId!!, stateDirectory, client, step).run(ops) }
                .onFailure { failures += "[$name] step $step: ${it.message}" }
            ran += 1
        }

        // A receipt, checked by the driver against the step count it expects. Without one, a run in
        // which this test never executed — a skipped assumption, a stale value from a warm daemon —
        // is indistinguishable from one where every scenario passed.
        File(stateDirectory, "ran.$DEVICE_ID.$step").writeText(ran.toString())
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }
}

/**
 * One device's world between steps: what it holds locally, plus the sync state (checkpoint,
 * revisions, outbox). Each step is a separate process, so both have to survive on disk — which is
 * also what makes "edit now, push two steps later" a genuine offline edit rather than a fiction.
 */
private class ScenarioDevice(
    private val scenario: String,
    private val runId: String,
    stateDirectory: File,
    private val client: CouchDbClient,
    private val step: Int,
) {
    private val stateFile = File(stateDirectory, "$scenario.$DEVICE_ID.json")
    private var persisted: Persisted
    private val store: ScenarioStore

    init {
        stateDirectory.mkdirs()
        persisted = if (stateFile.exists()) {
            stateJson.decodeFromString(Persisted.serializer(), stateFile.readText())
        } else {
            Persisted()
        }
        store = ScenarioStore(
            persisted.docs.mapNotNull { (id, envelope) ->
                decodeBody(envelope.json, envelope.deleted)?.let { id to it }
            }.toMap().toMutableMap(),
            persisted.conflictCopies.toMutableList(),
        )
    }

    suspend fun run(ops: List<JsonObject>) {
        // Saved even when an op throws: the later steps of this scenario still have to run against
        // what actually happened, or one failure reads as five.
        try {
            ops.forEachIndexed { index, op -> apply(op, index) }
        } finally {
            save()
        }
    }

    private suspend fun apply(op: JsonObject, index: Int) {
        val name = op.str("op") ?: error("op without a name")
        val at = Instant.ofEpochSecond(
            1_770_000_000L + (op["at"]?.jsonPrimitive?.int ?: (step * 1000 + index)).toLong()
        ).toString()

        when (name) {
            "newNotebook" -> {
                val pages = op.strings("pages")
                store.set(
                    docId(op),
                    CouchDocBody.Notebook(
                        CouchNotebook(
                            title = op.str("title").orEmpty(),
                            pageIds = pages.map { rawId(it) },
                            createdAt = at, updatedAt = at, updatedBy = DEVICE_ID,
                        )
                    ),
                )
                markDirty(docId(op))
                for (page in pages) {
                    val pageDocId = CouchDocId.page(rawId(page))
                    store.set(
                        pageDocId,
                        CouchDocBody.Page(
                            CouchPage(
                                notebookId = rawId(op.str("doc")!!),
                                createdAt = at, updatedAt = at, updatedBy = DEVICE_ID,
                            )
                        ),
                    )
                    markDirty(pageDocId)
                }
            }

            "newFolder" -> {
                store.set(
                    docId(op),
                    CouchDocBody.Folder(
                        CouchFolder(
                            title = op.str("title").orEmpty(),
                            createdAt = at, updatedAt = at, updatedBy = DEVICE_ID,
                        )
                    ),
                )
                markDirty(docId(op))
            }

            "draw" -> {
                val page = page(op)
                val drawn = op.strings("strokes").map {
                    CouchStroke(
                        id = it, createdAt = at, updatedAt = at, deviceId = DEVICE_ID,
                        pen = "BALLPEN", color = -16_777_216, size = 3.5f,
                        top = 0f, bottom = 10f, left = 0f, right = 10f,
                        pointsData = "U0ICD10AAAAA",
                    )
                }
                store.set(
                    docId(op),
                    CouchDocBody.Page(
                        page.copy(
                            strokes = page.strokes + drawn,
                            updatedAt = at, updatedBy = DEVICE_ID,
                        )
                    ),
                )
                markDirty(docId(op))
            }

            "erase" -> {
                val page = page(op)
                val removed = op.strings("strokes").toSet()
                val before = page.strokes.map { it.id }.toSet()
                // The real save path, not a hand-written tombstone: this is the code that has to be
                // right for an erasure to stay erased.
                val erased = CouchTombstones.recordErasures(
                    page = page.copy(strokes = page.strokes.filterNot { it.id in removed }),
                    previousStrokeIds = before,
                    deletedAt = at,
                )
                store.set(
                    docId(op),
                    CouchDocBody.Page(erased.copy(updatedAt = at, updatedBy = DEVICE_ID)),
                )
                markDirty(docId(op))
            }

            "placeImage" -> {
                val page = page(op)
                val bytes = op.bytes("bytes")
                val assetId = CouchAssetId.forBytes(bytes)
                // The bytes land in this device's own store first, exactly as importing a picture
                // does; sync's job is to notice the page now names an asset and carry it across.
                store.set(
                    assetId,
                    CouchDocBody.Asset(CouchAsset.of(bytes, at = at, updatedBy = DEVICE_ID)),
                )
                store.set(
                    docId(op),
                    CouchDocBody.Page(
                        page.copy(
                            images = page.images + CouchImage(
                                id = op.str("image").orEmpty(),
                                assetId = assetId,
                                x = op.intValue("x"), y = op.intValue("y"),
                                width = op.intValue("width"), height = op.intValue("height"),
                                createdAt = at, updatedAt = at,
                            ),
                            updatedAt = at, updatedBy = DEVICE_ID,
                        )
                    ),
                )
                markDirty(docId(op))
            }

            "setBackground" -> {
                val page = page(op)
                store.set(
                    docId(op),
                    CouchDocBody.Page(
                        page.copy(
                            background = op.str("background") ?: "blank",
                            updatedAt = at, updatedBy = DEVICE_ID,
                        )
                    ),
                )
                markDirty(docId(op))
            }

            "rename" -> {
                when (val body = body(op)) {
                    is CouchDocBody.Notebook -> store.set(
                        docId(op),
                        CouchDocBody.Notebook(
                            body.notebook.copy(
                                title = op.str("title").orEmpty(),
                                updatedAt = at, updatedBy = DEVICE_ID,
                            )
                        ),
                    )

                    is CouchDocBody.Folder -> store.set(
                        docId(op),
                        CouchDocBody.Folder(
                            body.folder.copy(
                                title = op.str("title").orEmpty(),
                                updatedAt = at, updatedBy = DEVICE_ID,
                            )
                        ),
                    )

                    else -> error("$scenario: rename applies to notebooks and folders")
                }
                markDirty(docId(op))
            }

            "move" -> {
                store.set(
                    docId(op),
                    CouchDocBody.Notebook(
                        notebook(op).copy(
                            parentFolderId = op.str("folder")?.let { rawId(it) },
                            updatedAt = at, updatedBy = DEVICE_ID,
                        )
                    ),
                )
                markDirty(docId(op))
            }

            "addPage" -> {
                val notebook = notebook(op)
                val page = op.str("page") ?: error("addPage needs page")
                store.set(
                    docId(op),
                    CouchDocBody.Notebook(
                        notebook.copy(
                            pageIds = notebook.pageIds + rawId(page),
                            updatedAt = at, updatedBy = DEVICE_ID,
                        )
                    ),
                )
                markDirty(docId(op))
                val pageDocId = CouchDocId.page(rawId(page))
                store.set(
                    pageDocId,
                    CouchDocBody.Page(
                        CouchPage(
                            notebookId = rawId(op.str("doc")!!),
                            createdAt = at, updatedAt = at, updatedBy = DEVICE_ID,
                        )
                    ),
                )
                markDirty(pageDocId)
            }

            "deletePage" -> {
                val notebook = notebook(op)
                val removed = rawId(op.str("page") ?: error("deletePage needs page"))
                store.set(
                    docId(op),
                    CouchDocBody.Notebook(
                        notebook.copy(
                            pageIds = notebook.pageIds.filterNot { it == removed },
                            deletedPageIds = CouchTombstones.derive(
                                previousIds = setOf(removed),
                                currentIds = emptySet(),
                                existing = notebook.deletedPageIds,
                                deletedAt = at,
                            ),
                            updatedAt = at, updatedBy = DEVICE_ID,
                        )
                    ),
                )
                markDirty(docId(op))
            }

            "delete" -> {
                val type = CouchDocId.split(docId(op))!!.first
                store.set(
                    docId(op),
                    CouchDocBody.Deleted(
                        CouchDeletedDoc(type = type, deletedAt = at, updatedBy = DEVICE_ID)
                    ),
                )
                markDirty(docId(op))
            }

            "push" -> {
                val engine = engine()
                val report = engine.flush()
                assertTrue("[$scenario] step $step push failed: ${report.failures}",
                    report.failures.isEmpty())
                absorb(engine)
            }

            "pull" -> {
                val engine = engine()
                engine.pull()
                absorb(engine)
            }

            // A reinstall: the device forgets both its content and where it was in the feed.
            "reset" -> {
                persisted = Persisted()
                store.reset()
            }

            "expectStrokes" -> assertEquals(
                "[$scenario] ${describe(op)} strokes",
                op.strings("ids").sorted(),
                page(op).strokes.map { it.id }.sorted(),
            )

            "expectImage" -> {
                val bytes = op.bytes("bytes")
                val assetId = CouchAssetId.forBytes(bytes)
                val placed = page(op).images.firstOrNull { it.id == op.str("image") }
                assertEquals(
                    "[$scenario] ${describe(op)} should place $assetId",
                    assetId,
                    placed?.assetId,
                )
                // The reference is only half of it: without the bytes the peer cannot draw
                // anything, which is the whole failure this scenario exists to catch.
                val held = store.load(assetId) as? CouchDocBody.Asset
                assertNotNull("[$scenario] ${describe(op)} holds no bytes for $assetId", held)
                assertArrayEquals(
                    "[$scenario] ${describe(op)} holds different bytes",
                    bytes,
                    held!!.asset.bytes,
                )
            }

            "expectPageIds" -> assertEquals(
                "[$scenario] ${describe(op)} pageIds",
                op.strings("pages").map { rawId(it) },
                notebook(op).pageIds,
            )

            "expectTitle" -> {
                val expected = op.str("title").orEmpty()
                when (val body = body(op)) {
                    is CouchDocBody.Notebook ->
                        assertEquals("[$scenario] ${describe(op)} title", expected, body.notebook.title)

                    is CouchDocBody.Folder ->
                        assertEquals("[$scenario] ${describe(op)} title", expected, body.folder.title)

                    else -> error("[$scenario] ${describe(op)} is not titled")
                }
            }

            "expectParent" -> assertEquals(
                "[$scenario] ${describe(op)} parent",
                op.str("folder")?.let { rawId(it) },
                notebook(op).parentFolderId,
            )

            "expectBackground" -> assertEquals(
                "[$scenario] ${describe(op)} background",
                op.str("background") ?: "blank",
                page(op).background,
            )

            "expectDeleted" -> assertTrue(
                "[$scenario] ${describe(op)} should be deleted, holds ${store.load(docId(op))}",
                store.load(docId(op))?.isDeleted == true,
            )

            "expectConflictCopy" -> assertTrue(
                "[$scenario] ${describe(op)} should have been set aside, got ${store.conflictCopies}",
                docId(op) in store.conflictCopies,
            )

            "expectClean" -> assertTrue(
                "[$scenario] step $step: nothing should still be queued, got ${persisted.dirty}",
                persisted.dirty.isEmpty(),
            )

            else -> error("unknown op `$name`")
        }
    }

    // region Engine plumbing

    private fun engine() = CouchSyncEngine(
        client = client,
        store = store,
        deviceId = DEVICE_ID,
        state = CouchSyncState(
            lastSeq = persisted.lastSeq,
            revs = persisted.revs,
            dirty = persisted.dirty.toSet(),
        ),
    )

    private fun absorb(engine: CouchSyncEngine) {
        val state = engine.currentState
        persisted = persisted.copy(
            lastSeq = state.lastSeq,
            revs = state.revs,
            dirty = state.dirty.sorted(),
        )
    }

    private fun markDirty(documentId: String) {
        if (documentId !in persisted.dirty) {
            persisted = persisted.copy(dirty = persisted.dirty + documentId)
        }
    }

    // There is deliberately no notebook bump on a draw any more. The app stopped advancing the
    // notebook's envelope on ink (the envelope decides renames and moves, and ink used to clobber
    // them), and the runner must model that faithfully — §6.4's liveness now comes from
    // [ScenarioStore.contentClock], the newest page clock, exactly as the real store answers it.
    // `ink-only-edit-resurrects` passes through that mechanism or not at all.

    private fun save() {
        persisted = persisted.copy(
            docs = store.snapshot().mapValues { (_, body) ->
                Envelope(deleted = body.isDeleted, json = encodeBody(body))
            },
            conflictCopies = store.conflictCopies,
        )
        stateFile.writeText(stateJson.encodeToString(Persisted.serializer(), persisted))
    }

    // endregion

    // region Names and lookups

    /**
     * `"pgA"` → `"ipad-starts-boox-continues-pgA-1770000000"`. Namespacing by run keeps repeated
     * runs from colliding in a database that is never wiped between them.
     */
    private fun rawId(logical: String): String = "$scenario-$logical-$runId"

    private fun docId(op: JsonObject): String {
        val logical = op.str("doc").orEmpty()
        val id = rawId(logical)
        return when {
            logical.startsWith("nb") -> CouchDocId.notebook(id)
            logical.startsWith("fd") -> CouchDocId.folder(id)
            else -> CouchDocId.page(id)
        }
    }

    private fun describe(op: JsonObject): String =
        "step $step ${op.str("op")} ${op.str("doc")}"

    private fun body(op: JsonObject): CouchDocBody =
        store.load(docId(op)) ?: error("${describe(op)}: this device holds no ${docId(op)}")

    private fun page(op: JsonObject): CouchPage =
        (body(op) as? CouchDocBody.Page)?.page ?: error("${describe(op)} is not a page")

    private fun notebook(op: JsonObject): CouchNotebook =
        (body(op) as? CouchDocBody.Notebook)?.notebook ?: error("${describe(op)} is not a notebook")

    // endregion

}

// region Persistence

@kotlinx.serialization.Serializable
private data class Envelope(val deleted: Boolean, val json: String)

@kotlinx.serialization.Serializable
private data class Persisted(
    val lastSeq: String = "0",
    val revs: Map<String, String> = emptyMap(),
    val dirty: List<String> = emptyList(),
    val conflictCopies: List<String> = emptyList(),
    /** documentId → (deleted, body JSON). Stored as text so the file stays diffable. */
    val docs: Map<String, Envelope> = emptyMap(),
)

private val stateJson = Json { encodeDefaults = true; ignoreUnknownKeys = true }

private fun decodeBody(json: String, deleted: Boolean): CouchDocBody? = runCatching {
    if (deleted) {
        return CouchDocBody.Deleted(couchJson.decodeFromString(CouchDeletedDoc.serializer(), json))
    }
    val type = couchJson.parseToJsonElement(json).jsonObject["type"]?.jsonPrimitive?.content
    when (type) {
        CouchDocType.PAGE -> CouchDocBody.Page(couchJson.decodeFromString(CouchPage.serializer(), json))
        CouchDocType.NOTEBOOK ->
            CouchDocBody.Notebook(couchJson.decodeFromString(CouchNotebook.serializer(), json))

        CouchDocType.FOLDER ->
            CouchDocBody.Folder(couchJson.decodeFromString(CouchFolder.serializer(), json))

        CouchDocType.ASSET ->
            CouchDocBody.Asset(couchJson.decodeFromString(CouchAsset.serializer(), json))

        else -> null
    }
}.getOrNull()

private fun encodeBody(body: CouchDocBody): String = when (body) {
    is CouchDocBody.Page -> couchJson.encodeToString(CouchPage.serializer(), body.page)
    is CouchDocBody.Notebook -> couchJson.encodeToString(CouchNotebook.serializer(), body.notebook)
    is CouchDocBody.Folder -> couchJson.encodeToString(CouchFolder.serializer(), body.folder)
    // The blob rides along in `_attachments`, so a device that downloaded a picture in one step
    // still has it in the next — which is what makes the assertion about bytes real.
    is CouchDocBody.Asset -> couchJson.encodeToString(CouchAsset.serializer(), body.asset)
    is CouchDocBody.Deleted -> couchJson.encodeToString(CouchDeletedDoc.serializer(), body.tombstone)
}

// endregion

/** Map-backed [CouchLocalStore] whose contents the device serializes between steps. */
private class ScenarioStore(
    private val documents: MutableMap<String, CouchDocBody>,
    private val copies: MutableList<String>,
) : CouchLocalStore {

    val conflictCopies: List<String> get() = copies.toList()

    override fun load(documentId: String): CouchDocBody? = documents[documentId]

    // The same answer the real store computes from its page rows: the newest page clock of the
    // notebook's pages held here. §6.4's liveness — this is what lets ink drawn after a peer's
    // purge resurrect the notebook now that ink no longer moves the envelope.
    override fun contentClock(documentId: String): String? {
        val (type, id) = CouchDocId.split(documentId) ?: return null
        if (type != CouchDocType.NOTEBOOK) return null
        return documents.values
            .mapNotNull { (it as? CouchDocBody.Page)?.page }
            .filter { it.notebookId == id }
            .maxOfOrNull { it.updatedAt }
    }

    // A scenario step is single-threaded — nothing edits between load and apply — so plain
    // replacement honours the contract: everything on disk is exactly what the merge saw.
    override fun apply(documentId: String, body: CouchDocBody, basedOn: CouchDocBody?) {
        documents[documentId] = body
    }

    /**
     * Protocol §6.5: the local copy is left exactly as it is. Recording the id is enough to assert
     * that — the real store materializes the remote document under a fresh identity.
     */
    override fun applyConflictCopy(documentId: String, json: JsonObject) {
        if (documentId !in copies) copies += documentId
    }

    /**
     * Every asset a held page places whose bytes are not here — the same question the real store
     * answers from the image rows.
     */
    override fun missingAssetIds(): List<String> =
        documents.values.flatMap { it.referencedAssetIds }
            .filter { it !in documents }
            .distinct()
            .sorted()

    fun set(documentId: String, body: CouchDocBody) {
        documents[documentId] = body
    }

    fun snapshot(): Map<String, CouchDocBody> = documents.toMap()

    fun reset() {
        documents.clear()
        copies.clear()
    }
}

// region JSON conveniences

private fun JsonObject.str(key: String): String? = when (val value = this[key]) {
    null, JsonNull -> null
    is JsonPrimitive -> value.content
    else -> null
}

private fun JsonObject.intValue(key: String): Int = (this[key] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0

/**
 * The picture an image op works with, base64 in the script so both apps hash the same bytes and
 * therefore agree on the asset id without ever comparing notes.
 */
private fun JsonObject.bytes(key: String): ByteArray =
    str(key)?.let { Base64.getDecoder().decode(it) } ?: error("op needs `$key` as base64")

private fun JsonObject.strings(key: String): List<String> =
    (this[key] as? JsonArray)?.map { it.jsonPrimitive.content } ?: emptyList()

// endregion
