package com.ethran.notable.recognition

import com.ethran.notable.data.db.Page
import com.ethran.notable.data.db.Stroke
import com.ethran.notable.data.db.decodeStrokePoints
import com.ethran.notable.data.db.withNormalizedPressure
import com.ethran.notable.editor.utils.Pen
import com.ethran.notable.sync.SyncSettings
import com.ethran.notable.sync.couch.*
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import java.time.Instant
import java.util.Base64
import java.util.Date
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
internal data class CloudPageText(
    val pageId: String, val notebookId: String, val text: String,
    val sourceRev: String = "", val sourceFingerprint: String = "", val pipelineVersion: String = "",
    val format: String? = null,
)

/** The server hash is opaque. This second identity compares Android's decoded ink, not hash formats. */
internal fun transcriptInkFingerprint(page: Page, strokes: List<Stroke>): String =
    handwritingFingerprint(strokes.map { it.withNormalizedPressure() },
        "cloud-ink-v1:${page.pageWidth}:${page.pageHeight}:${page.layout}")

internal fun canonicalInkFingerprint(pageId: String, page: CouchPage): String? = runCatching {
    val removed = page.deletedStrokes.mapTo(mutableSetOf()) { it.id }
    val live = page.strokes.filter { it.id !in removed }
    require(live.map { it.id }.distinct().size == live.size)
    val source = live.map { stroke ->
        Stroke(id = stroke.id, size = stroke.size, pen = Pen.fromString(stroke.pen), color = stroke.color,
            maxPressure = stroke.maxPressure, top = stroke.top, bottom = stroke.bottom, left = stroke.left,
            right = stroke.right, points = decodeStrokePoints(Base64.getDecoder().decode(stroke.pointsData)),
            pageId = pageId, createdAt = Date.from(Instant.parse(stroke.createdAt)),
            updatedAt = Date.from(Instant.parse(stroke.updatedAt))).withNormalizedPressure()
    }
    transcriptInkFingerprint(Page(id = pageId, notebookId = page.notebookId.orEmpty(), pageWidth = page.pageWidth,
        pageHeight = page.pageHeight, layout = page.layout), source)
}.getOrNull()

data class TranscriptionContent(val text: String, val format: String? = null)

internal data class CloudTranscript(val text: String, val inkFingerprint: String, val format: String? = null)

/** GET-only companion-database reader. Never creates databases or submits recognition work. */
@Singleton
class CloudTranscriptClient internal constructor(private val transport: (SyncSettings) -> CouchTransport) {
    @Inject constructor() : this({ settings -> OkHttpCouchTransport(settings.couchUrl,
        settings.couchUsername, settings.couchPassword, http) })

    internal suspend fun fetch(settings: SyncSettings, database: String, pageId: String, notebookId: String,
        revision: String): CloudTranscript? {
        if (!database.matches(Regex("[a-z][a-z0-9_$()+-]*"))) return null
        val connection = transport(settings)
        suspend fun get(databaseName: String, id: String): JsonObject? {
            val response = runInterruptible { connection.send(CouchRequest("GET", "/$databaseName/$id", readTimeoutMs = 8_000, callTimeoutMs = 10_000)) }
            if (response.status != 200) return null
            return couchJson.parseToJsonElement(response.body.toString(Charsets.UTF_8)).jsonObject
                .takeUnless { it["_deleted"]?.jsonPrimitive?.booleanOrNull == true }
        }
        val record = get(database, "pagetext:$pageId") ?: return null
        val rawFormat = record["format"]
        val safeRecord = if (rawFormat is JsonPrimitive && rawFormat.isString) record else JsonObject(record - "format")
        val text = couchJson.decodeFromJsonElement<CloudPageText>(safeRecord)
        if (text.pageId != pageId || text.notebookId != notebookId || text.sourceRev != revision ||
            !text.sourceFingerprint.matches(Regex("sha256:[a-f0-9]{64}")) ||
            !text.pipelineVersion.matches(Regex("scribe-v2:[a-f0-9]{64}"))) return null
        val canonical = get(settings.couchDatabase, "page:$pageId") ?: return null
        if (canonical["_rev"]?.jsonPrimitive?.content != revision) return null
        val page = couchJson.decodeFromJsonElement<CouchPage>(canonical)
        if (page.type != "page" || page.schema != COUCH_SCHEMA_VERSION || page.notebookId != notebookId) return null
        return canonicalInkFingerprint(pageId, page)?.let { CloudTranscript(text.text, it, text.format) }
    }

    private companion object { val http = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).build() }
}
