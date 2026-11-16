package com.ethran.notable.recognition

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.ethran.notable.data.decodeOrganization
import com.ethran.notable.data.AppRepository
import com.ethran.notable.data.JournalOrganizationStore
import com.ethran.notable.data.db.AppDatabase
import com.ethran.notable.data.db.Kv
import com.ethran.notable.editor.text.MarkdownText
import com.ethran.notable.io.ThumbnailGeneratorEntryPoint
import com.ethran.notable.sync.SyncBackend
import com.ethran.notable.sync.SyncOrchestratorEntryPoint
import com.ethran.notable.sync.SyncSettings
import com.ethran.notable.sync.couch.*
import com.ethran.notable.testing.ComposeUiSupportRule
import com.ethran.notable.ui.components.HandwritingPreviewSettings
import com.ethran.notable.ui.components.PagePreview
import com.ethran.notable.ui.dialogs.NoteRenameEntryPoint
import com.ethran.notable.ui.theme.InkaTheme
import com.ethran.notable.ui.theme.Kaleido
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runners.model.Statement
import java.io.File
import java.security.MessageDigest
import java.util.Locale

/** Actual app sync/settings/preview, opted in only for the owner's isolated synthetic fixture. */
class LayoutTranscriptionEndToEndTest {
    private val arguments get() = InstrumentationRegistry.getArguments()
    private val compose = createComposeRule()
    @get:Rule val gate = TestRule { base, description -> object : Statement() {
        override fun evaluate() {
            assumeTrue("Use -e layoutE2e true with the isolated layout fixture", arguments.getString("layoutE2e") == "true")
            ComposeUiSupportRule(compose).apply(base, description).evaluate()
        }
    } }
    private val context: Context = ApplicationProvider.getApplicationContext()
    private var showSettings by mutableStateOf(true)
    private fun visible(source: String) = MarkdownText.parseInline(source).joinToString("") { it.text }
    private fun words(source: String) = Regex("[\\p{L}\\p{N}]+").findAll(source).map { it.value.lowercase(Locale.ROOT) }.toList()

    @Test fun currentLayoutPhaseUsesActualFreshServerMarkdownAndPreservesCanonicalNotes() = runBlocking {
        check(Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk")) { "Synthetic emulator only" }
        val url = arguments.getString("layoutCouchUrl") ?: "http://10.0.2.2:15985"
        check(url in setOf("http://10.0.2.2:15985", "http://127.0.0.1:15985"))
        val manifestFile = File(context.filesDir, "layout-e2e/manifest.json")
        val manifest = couchJson.parseToJsonElement(manifestFile.readText()).jsonObject
        val phaseName = arguments.getString("layoutPhase") ?: "01-layout"
        val phase = manifest.getValue("phases").jsonArray.map { it.jsonObject }.single { it["name"]?.jsonPrimitive?.content == phaseName }
        val pageId = manifest.getValue("pageId").jsonPrimitive.content
        val notebookId = manifest.getValue("notebookId").jsonPrimitive.content
        val blockId = manifest.getValue("blockId").jsonPrimitive.content
        val database = "llm_e2e_layout_notes"
        check(manifest.getValue("requiredFormat").jsonPrimitive.content == "markdown")
        val transport = OkHttpCouchTransport(url, "llm_e2e", "llm_e2e_test_only", OkHttpClient())
        val readOnly = object : CouchTransport {
            override fun send(request: CouchRequest): CouchResponse {
                check(request.method == "GET" && request.body == null) { "Observer must not write remote data" }
                return transport.send(request)
            }
        }
        val client = CouchDbClient(readOnly, database)
        val metadata = client.getRaw("sync-meta:database")?.json ?: error("Fixture metadata missing")
        assertEquals("sync-database-metadata", metadata["type"]?.jsonPrimitive?.content)
        assertEquals(false, metadata["locked"]?.jsonPrimitive?.booleanOrNull)
        assertEquals(manifest.getValue("metadata").jsonObject.getValue("generation"), metadata["generation"])
        val expectedPage = couchJson.decodeFromJsonElement<CouchPage>(phase.getValue("page"))
        check(expectedPage.notebookId == notebookId)
        val expectedOrganization = decodeOrganization(manifest.getValue("organization").jsonObject)
        val expectedBlock = expectedPage.blocks.single { it.id == blockId }
        val services = EntryPointAccessors.fromApplication(context, NoteRenameEntryPoint::class.java)
        val app = services.appRepository()
        val syncServices = EntryPointAccessors.fromApplication(context, SyncOrchestratorEntryPoint::class.java)
        val config = SyncSettings(syncEnabled = true, backend = SyncBackend.COUCHDB, autoSync = false,
            syncOnAppStart = false, syncOnNoteClose = false, checkOnOpen = false, couchUrl = url,
            couchDatabase = database, couchUsername = "llm_e2e", couchPassword = "llm_e2e_test_only", deviceId = "layout-e2e-android")
        syncServices.kvProxy().setSyncSettings(config)
        assertEquals(database, syncServices.kvProxy().getSyncSettings().couchDatabase)
        services.handwritingPreferences().update(services.handwritingPreferences().options.value.copy(
            automatic = false, cloudTranscripts = true, textPreview = true, transcriptDatabase = database + "_text", language = "en-US"))
        val preferences = HandwritingPreferences(context).options.value
        assertTrue(preferences.cloudTranscripts && preferences.textPreview); assertFalse(preferences.automatic)
        // Reach the existing app Room database without adding a production-only test accessor.
        val db = AppRepository::class.java.getDeclaredField("db").let { field ->
            field.isAccessible = true
            field.get(app) as AppDatabase
        }
        val notebookBefore = client.getRaw("notebook:$notebookId") ?: error("Fixture notebook missing")
        val pageBefore = client.getRaw("page:$pageId") ?: error("Fixture page missing")
        val orgClient = CouchDbClient(readOnly, database + "_organization")
        val orgBefore = orgClient.getRaw("journal:$notebookId") ?: error("Fixture organization missing")
        val files = File(context.filesDir, "layout-e2e/assets").apply { mkdirs() }
        val store = RoomCouchStore(app, db.kvDao(), "layout-e2e-android",
            imagesFolder = { files }, audioFolder = { files }, backgroundsFolder = { files })
        withContext(Dispatchers.IO) {
            store.apply("notebook:$notebookId", CouchDocBody.Notebook(couchJson.decodeFromJsonElement<CouchNotebook>(notebookBefore.json)), null)
            store.apply("page:$pageId", CouchDocBody.Page(couchJson.decodeFromJsonElement<CouchPage>(pageBefore.json)), null)
            JournalOrganizationStore(db).merge(decodeOrganization(orgBefore.json))
            db.kvDao().set(Kv(couchStateKey(url, database), couchJson.encodeToString(CouchSyncState(
                revs = mapOf("notebook:$notebookId" to notebookBefore.rev, "page:$pageId" to pageBefore.rev),
                databaseGeneration = metadata.getValue("generation").jsonPrimitive.content))))
        }
        assertTrue("Remote import must not enqueue a local page write", app.couchOutboxRepository.pendingIds().none { it == "page:$pageId" || it == "notebook:$notebookId" })
        suspend fun organization() = syncServices.kvProxy().get("journal.organization.$notebookId", JsonObject.serializer())
            ?.let(::decodeOrganization) ?: error("Organization missing")
        assertEquals(expectedOrganization, organization())
        val localPage = app.pageRepository.getById(pageId) ?: error("Page missing")
        val localInk = app.strokeRepository.getByPage(pageId)
        val typed = app.blockRepository.getByPage(pageId).single { it.id == blockId }
        assertEquals(expectedBlock.text, typed.text)
        assertEquals(canonicalInkFingerprint(pageId, expectedPage), transcriptInkFingerprint(localPage, localInk))
        val revision = pageBefore.rev
        withTimeout(30_000) {
            while (syncServices.kvProxy().get(couchStateKey(url, database), CouchSyncState.serializer())?.revs?.get("page:$pageId") != revision) delay(100)
        }
        val textClient = CouchDbClient(readOnly, database + "_text")
        val record = withTimeout(150_000) {
            var value: JsonObject?
            do {
                value = textClient.getRaw("pagetext:$pageId")?.json
                if (value?.get("sourceRev")?.jsonPrimitive?.content == revision && value["format"]?.jsonPrimitive?.content == "markdown") break
                delay(1000)
            } while (true)
            value ?: error("Fresh Markdown record missing")
        }
        val raw = record.getValue("text").jsonPrimitive.content
        val cacheName = MessageDigest.getInstance("SHA-256").digest(pageId.toByteArray()).joinToString("") { "%02x".format(it) }
        assertFalse("Target page must have no local OCR cache", File(context.cacheDir, "handwriting-text/$cacheName.json").exists())
        val transcripts = EntryPointAccessors.fromApplication(context, ThumbnailGeneratorEntryPoint::class.java).pageTranscriptionRepository()
        val preview = withTimeout(45_000) { transcripts.observe(pageId).first {
            it.text == raw.takeIf(String::isNotBlank) && it.format == "markdown".takeIf { raw.isNotBlank() } && it.textMode
        } }
        assertEquals(raw.takeIf(String::isNotBlank), preview.text)
        assertEquals(localInk, app.strokeRepository.getByPage(pageId))
        assertEquals(typed, app.blockRepository.getByPage(pageId).single { it.id == blockId })
        assertEquals(expectedOrganization, organization())
        val blocks = TranscriptMarkdown.parse(raw)
        val lines = blocks.filterIsInstance<TranscriptMarkdown.Block.Line>()
        val tables = blocks.filterIsInstance<TranscriptMarkdown.Block.Table>()
        val actualWords = blocks.flatMap { block -> when (block) {
            is TranscriptMarkdown.Block.Line -> words(visible(block.source))
            is TranscriptMarkdown.Block.Table -> (block.header + block.rows.flatten()).flatMap { words(visible(it)) }
            is TranscriptMarkdown.Block.Code -> words(block.source)
        } }
        val wantedWords = phase.getValue("expectedWords").jsonArray.map { it.jsonPrimitive.content.lowercase(Locale.ROOT) }
        val layout = phase["layout"] as? JsonObject ?: buildJsonObject {}
        fun expected(name: String) = layout[name]?.jsonArray?.map { it.jsonPrimitive.content.lowercase(Locale.ROOT) }.orEmpty()
        fun actual(predicate: (TranscriptMarkdown.Block.Line) -> Boolean) = lines.filter(predicate).map { visible(it.source).lowercase(Locale.ROOT) }
        val expectedTasks = layout["tasks"]?.jsonArray.orEmpty().map { it.jsonObject }.map {
            it.getValue("text").jsonPrimitive.content.lowercase(Locale.ROOT) to it.getValue("checked").jsonPrimitive.boolean
        }
        val expectedTable = (layout["table"] as? JsonObject)
        val actualTasks = lines.filter { it.checked != null }.map { visible(it.source).lowercase(Locale.ROOT) to it.checked }
        val structurePassed = expected("headings") == actual { it.heading != null } &&
            expected("bullets") == actual { it.marker == "•" && it.checked == null } &&
            expected("ordered") == actual { it.marker?.firstOrNull()?.isDigit() == true } &&
            expected("orderedNumbers") == lines.mapNotNull { it.marker?.takeIf { marker -> marker.firstOrNull()?.isDigit() == true }?.dropLast(1) } &&
            expectedTasks == actualTasks &&
            if (expectedTable == null) tables.isEmpty() else tables.size == 1 &&
                expectedTable.getValue("header").jsonArray.map { it.jsonPrimitive.content.lowercase(Locale.ROOT) } == tables.single().header.map { visible(it).lowercase(Locale.ROOT) } &&
                expectedTable.getValue("rows").jsonArray.map { row -> row.jsonArray.map { it.jsonPrimitive.content.lowercase(Locale.ROOT) } } ==
                    tables.single().rows.map { row -> row.map { visible(it).lowercase(Locale.ROOT) } }
        val qualityPassed = actualWords == wantedWords && structurePassed
        compose.setContent { InkaTheme {
            Column(Modifier.fillMaxSize().background(Kaleido.Paper).verticalScroll(rememberScrollState()).padding(12.dp)) {
                if (showSettings) HandwritingPreviewSettings()
                else PagePreview(pageId = pageId, modifier = Modifier.fillMaxWidth(), expandToContent = true)
            }
        } }
        compose.onNodeWithContentDescription("Use server transcripts").assertIsOn()
        val evidence = File(context.filesDir, "layout-e2e/evidence").apply { mkdirs() }
        val size = arguments.getString("layoutSize") ?: "phone"
        fun screenshot(name: String) { compose.onRoot().captureToImage().asAndroidBitmap().let { image ->
            File(evidence, "$phaseName-$size-$name.png").outputStream().use { check(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } }
        screenshot("settings")
        compose.runOnIdle { showSettings = false }
        val typedVisible = MarkdownText.parse(typed.text.orEmpty()).map { it.plainText }.first { it.isNotBlank() }
        compose.waitUntil(45_000) { runCatching {
            compose.onNodeWithText(typedVisible, substring = true).assertExists()
            if (raw.isNotBlank()) compose.onNodeWithContentDescription("Recognized handwriting").assertExists()
            true
        }.getOrDefault(false) }
        screenshot("preview")
        fun unchanged(before: CouchDbClient.Stored, after: CouchDbClient.Stored?) {
            assertEquals("Observer changed a canonical revision", before.rev, after?.rev)
            assertEquals("Observer changed canonical source", before.json, after?.json)
        }
        unchanged(pageBefore, client.getRaw("page:$pageId"))
        unchanged(notebookBefore, client.getRaw("notebook:$notebookId"))
        unchanged(orgBefore, orgClient.getRaw("journal:$notebookId"))
        unchanged(CouchDbClient.Stored("sync-meta:database", metadata.getValue("_rev").jsonPrimitive.content, false, metadata), client.getRaw("sync-meta:database"))
        val resultFile = File(evidence, "$phaseName-$size-result.json")
        val result = buildJsonObject {
            put("status", if (qualityPassed) "render-pending" else "quality-failed"); put("qualityPassed", qualityPassed)
            put("nativeVerified", false); put("canonicalUnchanged", true)
            put("format", record["format"] ?: JsonNull); put("sourceRev", revision); put("sourceFingerprint", record["sourceFingerprint"] ?: JsonNull)
            put("pipelineVersion", record["pipelineVersion"] ?: JsonNull); put("text", raw)
            put("actualWords", JsonArray(actualWords.map(::JsonPrimitive))); put("expectedWords", JsonArray(wantedWords.map(::JsonPrimitive)))
            put("inkFingerprint", transcriptInkFingerprint(localPage, localInk)); put("typedSource", typed.text.orEmpty())
            put("organization", com.ethran.notable.data.encodeOrganization(organization()))
        }
        resultFile.writeText(result.toString())
        assertEquals("No extra or corrected words may be hidden", wantedWords, actualWords)
        assertTrue("Heading/list/task/table structure differs from the fixture", structurePassed)
        expected("headings").forEach { heading -> compose.onNode(hasText(heading, ignoreCase = true) and
            SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)).assertExists() }
        expectedTasks.forEach { (text, checked) ->
            compose.onNode(hasText(text, ignoreCase = true)).assertIsNotEnabled().assertHasNoClickAction().also {
                if (checked) it.assertIsOn() else it.assertIsOff()
            }
        }
        if (expectedTable != null) compose.onNodeWithContentDescription("Recognized table").assertExists()
        if (raw.isBlank()) compose.onNodeWithContentDescription("Recognized handwriting").assertDoesNotExist()
        resultFile.writeText(JsonObject(result + mapOf("status" to JsonPrimitive("passed"), "nativeVerified" to JsonPrimitive(true))).toString())
    }
}
