package com.ethran.notable.sync.couch

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * CouchDB document bodies. See `docs/couch-sync-protocol.md` in the bopa repo — this file and
 * bopa's `CouchModels.swift` must stay field-for-field identical.
 *
 * Decoding is lenient (missing collections default to empty) because a document written by an
 * older build, or by the other app before it learned a field, must still merge rather than fail.
 * Swift expresses that with `decodeIfPresent(...) ?? fallback`; kotlinx-serialization needs the
 * same fallbacks spelled out as constructor defaults. The two fallbacks Swift derives from
 * another field (`updatedAt` defaulting to `createdAt`) cannot be written as a constructor
 * default, so they are normalized in an `init` block — kotlinx runs those.
 *
 * Nothing here may touch `android.*`: the merge layer is exercised by plain JVM unit tests.
 */

/** Lenient decoder for Couch document bodies: unknown fields are a newer peer, not an error. */
val couchJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = true
}

/**
 * The schema version this build writes and can merge. A document carrying a higher value is
 * handled by the conflict-copy path (protocol §6.5) rather than merged on guesswork.
 */
const val COUCH_SCHEMA_VERSION: Int = 1

/** Document id prefixes. Ids are `<type>:<id>`; titles never appear in them. */
object CouchDocId {
    fun folder(id: String): String = "folder:$id"
    fun notebook(id: String): String = "notebook:$id"
    fun page(id: String): String = "page:$id"
    fun asset(sha256Hex: String): String = "asset:$sha256Hex"

    /** Splits `"page:abc"` into `("page", "abc")`. Null when the id carries no prefix. */
    fun split(documentId: String): Pair<String, String>? {
        val colon = documentId.indexOf(':')
        if (colon < 0) return null
        return documentId.substring(0, colon) to documentId.substring(colon + 1)
    }
}

object CouchDocType {
    const val FOLDER = "folder"
    const val NOTEBOOK = "notebook"
    const val PAGE = "page"
    const val ASSET = "asset"
}

/**
 * A removed stroke/image/page. Deletions are permanent facts, so merging keeps the *earliest*
 * `deletedAt` — see [CouchMerge.unionTombstones].
 */
@Serializable
data class CouchTombstone(
    val id: String,
    val deletedAt: String,
)

/**
 * One ink stroke. Geometry fields carry the same semantics as the WebDAV stroke DTO: [color] is a
 * signed Android ARGB int, [pointsData] is base64 of the SB binary encoding.
 */
@Serializable
data class CouchStroke(
    val id: String,
    val createdAt: String,
    /** Empty only transiently: normalized to [createdAt] below, matching Swift's decoder. */
    var updatedAt: String = "",
    /** Which device drew it. Informational plus a tiebreak when the same id somehow differs. */
    val deviceId: String = "",
    val pen: String = "BALLPEN",
    val color: Int = -16_777_216,
    val size: Float = 3f,
    val maxPressure: Int = 1,
    val top: Float = 0f,
    val bottom: Float = 0f,
    val left: Float = 0f,
    val right: Float = 0f,
    val pointsData: String = "",
) {
    init {
        if (updatedAt.isEmpty()) updatedAt = createdAt
    }
}

/** A placed image. [assetId] is the `asset:<sha256>` document holding the bytes. */
@Serializable
data class CouchImage(
    val id: String,
    val assetId: String? = null,
    val x: Int = 0,
    val y: Int = 0,
    val width: Int = 0,
    val height: Int = 0,
    val createdAt: String,
    var updatedAt: String = "",
) {
    init {
        if (updatedAt.isEmpty()) updatedAt = createdAt
    }
}

@Serializable
data class CouchPage(
    val type: String = CouchDocType.PAGE,
    val schema: Int = COUCH_SCHEMA_VERSION,
    val notebookId: String? = null,
    val background: String = "blank",
    val backgroundType: String = "native",
    val strokes: List<CouchStroke> = emptyList(),
    val deletedStrokes: List<CouchTombstone> = emptyList(),
    val images: List<CouchImage> = emptyList(),
    val deletedImages: List<CouchTombstone> = emptyList(),
    val createdAt: String,
    var updatedAt: String = "",
    val updatedBy: String = "",
) {
    init {
        if (updatedAt.isEmpty()) updatedAt = createdAt
    }
}

@Serializable
data class CouchNotebook(
    val type: String = CouchDocType.NOTEBOOK,
    val schema: Int = COUCH_SCHEMA_VERSION,
    val title: String = "",
    val pageIds: List<String> = emptyList(),
    val deletedPageIds: List<CouchTombstone> = emptyList(),
    val parentFolderId: String? = null,
    val defaultBackground: String = "blank",
    val defaultBackgroundType: String = "native",
    val createdAt: String,
    var updatedAt: String = "",
    val updatedBy: String = "",
) {
    init {
        if (updatedAt.isEmpty()) updatedAt = createdAt
    }
}

@Serializable
data class CouchFolder(
    val type: String = CouchDocType.FOLDER,
    val schema: Int = COUCH_SCHEMA_VERSION,
    val title: String = "",
    val parentFolderId: String? = null,
    val createdAt: String,
    var updatedAt: String = "",
    val updatedBy: String = "",
) {
    init {
        if (updatedAt.isEmpty()) updatedAt = createdAt
    }
}
