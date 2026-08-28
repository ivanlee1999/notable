package com.ethran.notable.recognition

import com.ethran.notable.data.db.PageText
import com.ethran.notable.sync.couch.CouchQueryItem
import com.ethran.notable.sync.couch.CouchRequest
import com.ethran.notable.sync.couch.CouchTransport
import io.shipbook.shipbooksdk.ShipBook
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

private val log = ShipBook.getLogger("PageTextPublisher")

/**
 * A `pagetext:` document, as it travels. The shape is the contract in bopa's
 * docs/recognized-text.md, which the iPad and the Obsidian plugin implement against too.
 */
@Serializable
data class PageTextDocument(
    @SerialName("_id") val id: String,
    @SerialName("_rev") val rev: String? = null,
    val pageId: String,
    val notebookId: String? = null,
    val pageTitle: String? = null,
    val text: String = "",
    val engine: String = "",
    val language: String? = null,
    /** The page's `updatedAt` the recognition ran against, copied verbatim. */
    val recognizedClock: String = "",
    val updatedAt: String = "",
    val updatedBy: String = "",
)

/** What a publish attempt did, so callers know whether the row is settled. */
enum class PublishOutcome {
    /** The server now holds this text. */
    PUBLISHED,

    /** The server already held this text, or newer. Nothing to do, and nothing wrong. */
    ALREADY_CURRENT,

    /** The attempt failed. The row stays pending and is retried later. */
    FAILED,
}

/**
 * Publishes recognized text to the text database.
 *
 * The write is guarded rather than blind: two devices recognize the same ink with different
 * engines, and without a guard each would keep overwriting the other's result forever. Reading
 * the current document first, and standing down when it describes newer ink, bounds that race
 * to a single round. See docs/recognized-text.md for the rule this implements.
 *
 * Nothing here deletes. A page's text outliving its page is inert — nothing reads text for a page
 * it does not have — and the Obsidian plugin, which already holds the whole library, prunes it.
 */
class PageTextPublisher(
    private val transport: CouchTransport,
    private val database: String,
    private val deviceId: String,
) {
    // explicitNulls off is not cosmetic: a `"_rev": null` in the body is a revision *claim* to
    // CouchDB, and it answers 409 to every create. Absent and null read the same to every consumer
    // of these documents, so the optional fields are simply left out too.
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    fun publish(
        text: PageText,
        notebookId: String?,
        pageTitle: String?,
        pageUpdatedAt: Date,
    ): PublishOutcome {
        val documentId = documentId(text.pageId)
        val remote = try {
            fetch(documentId)
        } catch (e: Exception) {
            log.w("Could not read $documentId before publishing: ${e.message}")
            return PublishOutcome.FAILED
        }

        val local = PageTextDocument(
            id = documentId,
            rev = remote?.rev,
            pageId = text.pageId,
            notebookId = notebookId,
            pageTitle = pageTitle,
            text = text.text,
            engine = text.engine,
            language = text.language,
            recognizedClock = iso(Date(text.recognizedClock)),
            updatedAt = iso(text.updatedAt),
            updatedBy = deviceId,
        )

        if (remote != null && !supersedes(local, remote)) return PublishOutcome.ALREADY_CURRENT

        return when (val status = put(local)) {
            in 200..299 -> PublishOutcome.PUBLISHED
            // Someone wrote between the read and the write. Re-reading re-applies the guard, and
            // if their text describes newer ink this device stops rather than fighting for it.
            409 -> retry(local)
            else -> {
                log.w("Publishing $documentId failed with $status")
                PublishOutcome.FAILED
            }
        }
    }

    private fun retry(local: PageTextDocument): PublishOutcome {
        val remote = try {
            fetch(local.id)
        } catch (e: Exception) {
            return PublishOutcome.FAILED
        } ?: return PublishOutcome.FAILED

        if (!supersedes(local, remote)) return PublishOutcome.ALREADY_CURRENT
        val status = put(local.copy(rev = remote.rev))
        return if (status in 200..299) PublishOutcome.PUBLISHED else PublishOutcome.FAILED
    }

    private fun fetch(documentId: String): PageTextDocument? {
        val response = transport.send(CouchRequest(method = "GET", path = "/$database/$documentId"))
        return when (response.status) {
            in 200..299 -> json.decodeFromString<PageTextDocument>(
                response.body.toString(Charsets.UTF_8)
            )
            404 -> null
            else -> throw IllegalStateException("GET $documentId returned ${response.status}")
        }
    }

    private fun put(document: PageTextDocument): Int = transport.send(
        CouchRequest(
            method = "PUT",
            path = "/$database/${document.id}",
            headers = mapOf("Content-Type" to "application/json"),
            body = json.encodeToString(document).toByteArray(Charsets.UTF_8),
        )
    ).status

    companion object {
        fun documentId(pageId: String) = "pagetext:$pageId"

        /**
         * Whether [local] should replace [remote].
         *
         * Text describing newer ink always wins, whichever engine produced it and whenever it ran.
         * Only when both describe the same ink does it come down to which recognition is newer —
         * and identical text is not republished at all, since a write that changes nothing would
         * still wake every reader of the change feed.
         */
        internal fun supersedes(local: PageTextDocument, remote: PageTextDocument): Boolean {
            val localInk = millis(local.recognizedClock)
            val remoteInk = millis(remote.recognizedClock)
            if (localInk != remoteInk) return localInk > remoteInk

            val unchanged = local.text == remote.text &&
                    local.engine == remote.engine &&
                    local.language == remote.language
            if (unchanged) return false

            return millis(local.updatedAt) > millis(remote.updatedAt)
        }

        /** ISO-8601 in UTC, matching what the sync protocol puts on the wire. */
        internal fun iso(date: Date): String = isoFormat().format(date)

        /** Unparseable stamps sort below everything, so a broken document never wins a guard. */
        internal fun millis(stamp: String): Long = try {
            if (stamp.isBlank()) Long.MIN_VALUE else isoFormat().parse(stamp)?.time ?: Long.MIN_VALUE
        } catch (e: Exception) {
            Long.MIN_VALUE
        }

        private fun isoFormat() =
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
    }
}
