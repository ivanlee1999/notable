package com.ethran.notable.recognition

import com.ethran.notable.data.db.*
import com.ethran.notable.editor.utils.Pen
import com.ethran.notable.sync.SyncSettings
import com.ethran.notable.sync.couch.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64
import java.util.Date

class CloudTranscriptClientTest {
    private val stroke = Stroke(id = "ink", pageId = "p", size = 3f, pen = Pen.BALLPEN, maxPressure = 1,
        top = 0f, bottom = 10f, left = 0f, right = 10f, points = listOf(StrokePoint(0f, 0f), StrokePoint(10f, 10f)),
        createdAt = Date(1000), updatedAt = Date(1000))
    private val wire = CouchStroke(id = "ink", createdAt = "1970-01-01T00:00:01Z", top = 0f, bottom = 10f,
        left = 0f, right = 10f, pointsData = Base64.getEncoder().encodeToString(encodeStrokePoints(stroke.points)))
    private val page = CouchPage(notebookId = "b", pageWidth = 600, pageHeight = 800, strokes = listOf(wire), createdAt = wire.createdAt)
    private val record = CloudPageText("p", "b", "#Work server text", "2-page", "sha256:" + "a".repeat(64), "scribe-v2:" + "b".repeat(64))
    private val requests = mutableListOf<CouchRequest>()
    private fun client(text: CloudPageText = record, canonical: CouchPage = page, rev: String = "2-page", status: Int = 200, formatOverride: JsonElement? = null) =
        CloudTranscriptClient { _ -> object : CouchTransport {
            override fun send(request: CouchRequest): CouchResponse {
                requests += request
                val body = if (request.path == "/notes_text/pagetext:p") {
                    val fields = couchJson.encodeToJsonElement(text).jsonObject.toMutableMap()
                    formatOverride?.let { fields["format"] = it }
                    JsonObject(fields).toString()
                }
                else JsonObject(couchJson.encodeToJsonElement(canonical).jsonObject + ("_rev" to JsonPrimitive(rev))).toString()
                return CouchResponse(status, body = body.toByteArray())
            }
        } }
    private fun fetch(client: CloudTranscriptClient, database: String = "notes_text") = runBlocking { client.fetch(SyncSettings(couchDatabase = "notes"), database, "p", "b", "2-page") }

    @Test fun exactEnhancedRecordUsesOnlyGetAndBindsDecodedCanonicalInk() {
        val result = fetch(client())!!
        assertEquals(record.text, result.text)
        assertEquals(transcriptInkFingerprint(Page(id = "p", notebookId = "b", pageWidth = 600, pageHeight = 800), listOf(stroke)), result.inkFingerprint)
        assertEquals(listOf("/notes_text/pagetext:p", "/notes/page:p"), requests.map { it.path })
        assertTrue(requests.all { it.method == "GET" && it.body == null })
    }
    @Test fun optionalFormatSurvivesFreshProvenanceButUnknownValuesRemainLiteralCandidates() {
        assertNull(fetch(client())!!.format)
        assertEquals("markdown", fetch(client(record.copy(format = "markdown")))!!.format)
        assertEquals("future-format", fetch(client(record.copy(format = "future-format")))!!.format)
        assertNull(fetch(client(formatOverride = buildJsonObject { put("unknown", true) }))!!.format)
        assertNull(fetch(client(formatOverride = JsonPrimitive(4)))!!.format)
        assertNull(fetch(client(record.copy(format = "markdown"), rev = "3-new")))
    }
    @Test fun rejectsLegacyWrongIdentityRevisionAndPipelineWithoutReadingCanonicalPage() {
        for (invalid in listOf(record.copy(sourceRev = ""), record.copy(sourceRev = "1-old"), record.copy(pageId = "other"),
            record.copy(notebookId = "other"), record.copy(sourceFingerprint = ""), record.copy(pipelineVersion = ""))) {
            requests.clear(); assertNull(fetch(client(invalid))); assertEquals(1, requests.size)
        }
    }
    @Test fun canonicalRevisionMismatchMissingRecordAndUnsafeDatabaseFallBack() {
        assertNull(fetch(client(rev = "3-new")))
        assertNull(fetch(client(status = 404)))
        requests.clear(); assertNull(fetch(client(), "https://other/notes")); assertTrue(requests.isEmpty())
    }
    @Test fun geometryDimensionsMovesRemovalAndMalformedInkCannotShareTheLocalFence() {
        val baseline = canonicalInkFingerprint("p", page)
        assertNotEquals(baseline, canonicalInkFingerprint("p", page.copy(pageWidth = 601)))
        assertNotEquals(baseline, canonicalInkFingerprint("other", page))
        assertNotEquals(baseline, canonicalInkFingerprint("p", page.copy(strokes = listOf(wire.copy(left = 1f)))))
        assertNotEquals(baseline, canonicalInkFingerprint("p", page.copy(strokes = emptyList())))
        assertNull(canonicalInkFingerprint("p", page.copy(strokes = listOf(wire.copy(pointsData = "bad!")))))
        assertEquals(canonicalInkFingerprint("p", page.copy(strokes = emptyList())),
            canonicalInkFingerprint("p", page.copy(deletedStrokes = listOf(CouchTombstone("ink", wire.createdAt)))))
    }
    @Test fun penAndDurableWriteFenceRejectsOlderCompletionsAndActiveContact() {
        val fence = CloudTranscriptFence(); val owner = Any()
        assertTrue(fence.state("p").settled)
        fence.contact(owner, "p", true); assertFalse(fence.state("p").settled)
        val first = fence.changed(listOf("p")); fence.saved(first)
        assertFalse(fence.state("p").settled)
        fence.contact(owner, null, false); assertTrue(fence.state("p").settled)
        val old = fence.changed(listOf("p")); val latest = fence.changed(listOf("p"))
        fence.saved(old); assertFalse(fence.state("p").settled)
        fence.saved(latest); assertTrue(fence.state("p").settled)
        fence.contact(owner, "p", true); fence.contact(owner, null, false)
        assertTrue(fence.state("p").settled) // No staged content or write: canceled touch releases.
    }
    @Test fun canceledContactReleasesOnlyItsOwnCleanEpochWithoutReusingOldResponses() {
        val fence = CloudTranscriptFence(); val owner = Any()
        val before = fence.state("p").generation
        fence.contact(owner, "p", true); fence.contact(owner, null, false)
        assertTrue(fence.state("p").settled); assertTrue(fence.state("p").generation > before)
        fence.contact(owner, "p", true)
        val pending = fence.changed(listOf("p"))
        fence.contact(owner, null, false)
        assertFalse(fence.state("p").settled)
        fence.saved(pending); assertTrue(fence.state("p").settled)
        val older = fence.changed(listOf("p"))
        fence.contact(owner, "p", true); fence.contact(owner, null, false)
        assertFalse(fence.state("p").settled)
        fence.saved(older); assertTrue(fence.state("p").settled)
    }
    @Test fun stagedHandoffAndEveryPendingWriteMustFinishBeforeRemoteTextCanReturn() {
        val fence = CloudTranscriptFence(); val owner = Any()
        fence.contact(owner, "p", true)
        val stage = fence.stage(owner)
        fence.contact(owner, null, false)
        assertFalse(fence.state("p").settled)
        val first = fence.changed(listOf("p")); val second = fence.changed(listOf("p"))
        fence.unstaged(stage); fence.saved(second)
        assertFalse(fence.state("p").settled)
        fence.saved(first); assertTrue(fence.state("p").settled)
    }
}
