package com.ethran.notable.sync.couch

import kotlinx.serialization.SerialName
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

    /**
     * Protocol bookkeeping, never a library item (§1.1). Reserved as a *prefix* so a client that
     * meets a `sync-meta:` id it does not know still recognises it as ours and steps past it,
     * rather than filing it as a document from a future schema.
     */
    const val SYNC_META = "sync-meta"
}

/**
 * Documents the protocol reserves for itself. None of them carry user content, so none of them are
 * enumerated, merged, conflict-copied, or shown.
 */
object CouchMetaDocId {
    /** §1.2 — which database this is, and whether this client may sync it. */
    const val DATABASE = "sync-meta:database"

    /** Whether an id belongs to the reserved namespace. */
    fun isReserved(documentId: String): Boolean =
        documentId.startsWith("${CouchDocType.SYNC_META}:")
}

/**
 * The protocol version this build speaks (§1.2). Distinct from [COUCH_SCHEMA_VERSION], which
 * describes one document's shape: this describes the conversation.
 */
const val COUCH_PROTOCOL_VERSION: Int = 1

/**
 * §1.2. The identity of the database itself, so a device can tell "the library I have been syncing"
 * from "a new database that happens to have the same name at the same address".
 */
@Serializable
data class CouchDatabaseMetadata(
    val type: String = DOCUMENT_TYPE,
    val protocolVersion: Int = COUCH_PROTOCOL_VERSION,
    /**
     * The lowest protocol version allowed to sync this database. A client below it must refuse
     * rather than guess at documents written by a newer one.
     */
    val minimumClientProtocol: Int = COUCH_PROTOCOL_VERSION,
    /** Minted with the database. Its only job is to be different when the database is not the same. */
    val generation: String,
    /** Set while a rebuild is in progress; no client may pull or push ordinary documents. */
    val locked: Boolean = false,
    val lockReason: String? = null,
    val updatedAt: String,
) {
    companion object {
        const val DOCUMENT_TYPE = "sync-database-metadata"
    }
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

/**
 * One segment of a recording — protocol §3.3.2.
 *
 * A recording is stored as several assets rather than one, because an asset travels as a single
 * base64-inlined PUT with no chunking layer, and the smallest request-body cap on the path — an
 * nginx `client_max_body_size` left at its 1 MB default — refuses anything much over 768 KiB of
 * raw bytes (§3.4). Segmenting also bounds what a crash loses and lets a recording still in
 * progress reach the peer.
 */
@Serializable
data class CouchAudioSegment(
    /** The `asset:<sha256>` document holding this segment's bytes. */
    val assetId: String = "",
    /**
     * Offset of this segment's first sample from the block's [CouchBlock.startedAt], in
     * milliseconds.
     *
     * Authoritative, and the reason this is not derived by summing durations: a reader that has not
     * yet fetched segment 2 still needs to know where segment 3 begins, or everything after a gap
     * plays at the wrong offset. A missing segment must read as silence, not as a shift.
     */
    val startMs: Int = 0,
    /**
     * This segment's length. Advisory — a reader holding the blob may recompute it, and a
     * disagreement with the next segment's [startMs] is resolved in [startMs]'s favour.
     */
    val durationMs: Int = 0,
)

/**
 * A block of page content that is not ink — protocol §3.3.1.
 *
 * A block with neither [x] nor [y] joins the page's linear top-to-bottom flow: the flowing blocks,
 * in `(orderKey, id)` order, with their [text] joined by a blank line, are a markdown document. A
 * block that declares both sits at that point on the canvas, like a placed image.
 *
 * Blocks merge exactly the way [CouchImage]s do — union by id, tombstones win, whole-element
 * last-writer-wins — with one difference: they are ordered by [orderKey] rather than by creation
 * time. See `CouchMerge.mergePage`.
 */
@Serializable
data class CouchBlock(
    /**
     * The merge key. Never reused: retyping a paragraph after deleting it mints a new id, the same
     * rule a redrawn stroke follows, and that is what makes remove-wins sound here.
     */
    val id: String,
    /**
     * `md` | `image` | `audio` | `ink`.
     *
     * A string rather than an enum, and an unrecognized value is carried verbatim and drawn as a
     * placeholder — never dropped, never coerced. This is the field that lets a fifth kind ship on
     * one app before the other without §6.5 quarantining every page that uses it. Writers must
     * match `[a-z][a-z0-9-]*`, so it can never carry the `blockTiebreak` separator.
     */
    val kind: String = "md",
    /**
     * Where this block sits in the flow: a fractional index, compared as UTF-8 bytes like every
     * other string in the merge. Flow order is `(orderKey, id)` ascending.
     *
     * **How a key is generated is not normative; only how it is compared.** Two devices minting
     * different keys for concurrent inserts at one point are not in disagreement — the blocks sort
     * adjacent, broken by [id]. That is the whole reason to prefer this to an ordered array: an
     * array's order has to be *produced* identically by two languages, and a key only has to be
     * *compared* identically, which §4 already guarantees.
     *
     * Carrying order per block rather than per page is also what keeps a move from colliding with
     * an unrelated edit. A page-level order would be a scalar, and §5.5 would hand the whole of it
     * to one writer — so dragging a paragraph on one device would be undone by a typo fix on the
     * other. Empty sorts first and is legal: a writer with no opinion is not a decode failure.
     */
    val orderKey: String = "",
    /**
     * Markdown *source*, for `kind == "md"`; null otherwise.
     *
     * Not a parsed tree, not rendered HTML, not a table of attributed runs. Those would oblige two
     * implementations to agree on a parser — which the conformance vectors could not pin, and which
     * a peer with a different flavour would rewrite on re-encode. The source is the one
     * representation both apps carry losslessly without agreeing on anything.
     */
    val text: String? = null,
    /** The `asset:<sha256>` holding the picture, for `kind == "image"`; null otherwise. */
    val imageAssetId: String? = null,
    /** The recording, in playback order, for `kind == "audio"`; empty otherwise. */
    val segments: List<CouchAudioSegment> = emptyList(),
    /**
     * The [CouchPage.strokes] this block groups, for `kind == "ink"`; empty otherwise.
     *
     * The strokes stay in the page's stroke list and are named from here rather than nested inside.
     * A peer that has not learned about blocks strips this field, which costs the *grouping* — and
     * the union merge restores that from whichever device still holds it. Nested, the same push
     * would strip the *strokes*, and they would be gone from the list the peer would have
     * re-offered them from. Ids naming strokes that no longer exist are kept, not filtered; readers
     * skip them, the way §5.2.2 keeps an outline entry whose page is gone.
     */
    val strokeIds: List<String> = emptyList(),
    /**
     * Page units, top-left, the same coordinate space and the same `Int` type as [CouchImage].
     *
     * **Both null means flowing; both present means positioned; exactly one present means
     * flowing** — a reader rule, never a decode failure. Unlike a page's sheet, zero is a
     * meaningful value here (the top-left corner), so there is no non-positive rule.
     */
    val x: Int? = null,
    val y: Int? = null,
    /**
     * The wrap width and laid-out height of a positioned block; null for a flowing one. [height] is
     * advisory, since text reflows and a reader recomputes it, but it is carried so a peer can lay
     * a page out before it has shaped the text.
     */
    val width: Int? = null,
    val height: Int? = null,
    /**
     * When the recording started, on the corrected clock (§7.1a); `audio` only.
     *
     * The anchor ink replay is measured from: a stroke's offset into the recording is
     * `stroke.createdAt - startedAt`. Storing that per stroke would be a wire field per stroke to
     * say something both clocks already say.
     */
    val startedAt: String? = null,
    val createdAt: String,
    var updatedAt: String = "",
    /** Which device last wrote this block. The first component of `blockTiebreak`. */
    val deviceId: String = "",
) {
    init {
        if (updatedAt.isEmpty()) updatedAt = createdAt
    }

    /** Whether this block joins the page's linear flow, rather than sitting at a point on it. */
    val isFlowing: Boolean get() = x == null || y == null

    /**
     * The assets this block's bytes live in, whatever its kind — what the push ordering, the
     * "still to download" enumeration and §3.5.1's referenced set all read.
     */
    val referencedAssetIds: List<String>
        get() = listOfNotNull(imageAssetId) + segments.map { it.assetId }
}

@Serializable
data class CouchPage(
    val type: String = CouchDocType.PAGE,
    val schema: Int = COUCH_SCHEMA_VERSION,
    val notebookId: String? = null,
    // Defaulted so documents written before pages could be named still decode.
    val title: String? = null,
    val background: String = "blank",
    val backgroundType: String = "native",
    // The sheet this page's coordinates are laid out on, in page units; null for a page written
    // before page sizes existed. Mirrors the WebDAV page DTO — see [PageSize].
    val pageWidth: Int? = null,
    val pageHeight: Int? = null,
    val strokes: List<CouchStroke> = emptyList(),
    val deletedStrokes: List<CouchTombstone> = emptyList(),
    val images: List<CouchImage> = emptyList(),
    val deletedImages: List<CouchTombstone> = emptyList(),
    // Typed text, pictures, recordings and ink groupings — see [CouchBlock]. Absent from every page
    // written before blocks existed, and decoded as empty, which is what a page with none means
    // anyway.
    val blocks: List<CouchBlock> = emptyList(),
    // The block half of [deletedStrokes]. Blocks tombstone rather than carrying a `removed` flag
    // because, like a stroke and unlike a bookmark, a block never comes back under the same id.
    val deletedBlocks: List<CouchTombstone> = emptyList(),
    val createdAt: String,
    var updatedAt: String = "",
    val updatedBy: String = "",
) {
    init {
        if (updatedAt.isEmpty()) updatedAt = createdAt
    }
}

/**
 * A page the reader starred, or the record of it being un-starred — protocol §3.2.1.
 *
 * Deliberately *not* a list of ids plus a [CouchTombstone] list, which is how every other removal
 * in this protocol is expressed. That pattern makes removal permanent, and it is sound everywhere
 * it is used because the thing removed never comes back under the same id: a redrawn stroke is a
 * new stroke with a new id. A bookmark is the exception — the page keeps its id, so starring the
 * same page again is a thing users do routinely, and "remove wins forever" would make the second
 * star impossible to express. Carrying [removed] on the entry instead lets whichever write came
 * last say either thing, which is what last-writer-wins per [pageId] needs.
 */
@Serializable
data class CouchBookmark(
    val pageId: String,
    val updatedAt: String = "",
    /**
     * True for a page that was bookmarked and then un-bookmarked. Kept rather than dropped so the
     * un-starring propagates to a peer that still holds the star.
     */
    val removed: Boolean = false,
)

/**
 * One line of a notebook's outline — its table of contents, protocol §3.2.2.
 *
 * An entry points at a *page*, not at a position on one. Both apps this protocol has to satisfy
 * anchor the same way (Goodnotes' outline and the BOOX reader's TOC), and a page anchor is the
 * only one that survives the page being written on: ink has no headings to re-find, so an offset
 * anchor would drift the moment the page was edited on the other device.
 */
@Serializable
data class CouchOutlineEntry(
    /**
     * The entry's own id, not the page's. A page is allowed to appear in the outline more than
     * once — both reference apps allow it, and it is how a page that opens one section and closes
     * another gets to say so — which rules out keying entries by page.
     */
    val id: String,
    val pageId: String = "",
    val title: String = "",
    /**
     * 0, 1 or 2: heading, subheading, sub-subheading. Three levels is what both reference apps
     * settled on. Clamped rather than rejected on decode, so a document from a build that one day
     * allows four levels degrades to a flatter outline instead of failing to merge.
     */
    var depth: Int = 0,
    val updatedAt: String = "",
    /** True for a deleted entry, kept for the same reason as [CouchBookmark.removed]. */
    val removed: Boolean = false,
) {
    init {
        depth = depth.coerceIn(0, MAX_DEPTH)
    }

    companion object {
        /** The deepest [depth] this build understands. */
        const val MAX_DEPTH: Int = 2
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
    /**
     * Starred pages, including the un-starred ones — see [CouchBookmark]. Sorted by `pageId` in a
     * merged document so the encoded body is byte-stable across devices.
     */
    val bookmarks: List<CouchBookmark> = emptyList(),
    /**
     * The notebook's table of contents, in reading order. Order is carried by the list itself, the
     * way [pageIds] carries page order, and merged the same way.
     */
    val outline: List<CouchOutlineEntry> = emptyList(),
    val defaultBackground: String = "blank",
    val defaultBackgroundType: String = "native",
    // Sheet for new pages here, in page units; null for a notebook created before page sizes.
    val defaultPageWidth: Int? = null,
    val defaultPageHeight: Int? = null,
    /**
     * In the Trash since — protocol §3.2. Null is a notebook in the library.
     *
     * The Trash is a *state of the notebook*, not a fact about one device: it is staged deletion,
     * so it hides the notebook everywhere and can be undone from anywhere. Only emptying the Trash
     * deletes for good, and that is a `_deleted` tombstone (§6.4), not this.
     */
    val deletedAt: String? = null,
    val createdAt: String,
    var updatedAt: String = "",
    val updatedBy: String = "",
) {
    init {
        if (updatedAt.isEmpty()) updatedAt = createdAt
    }
}

/**
 * The bytes behind an `asset:<sha256>` document — protocol §3.4.
 *
 * Content-addressed, and therefore immutable: two devices holding the same image agree on its id
 * without talking to each other, nobody ever has to merge one, and a `409` on upload means
 * "already there" rather than "someone else wrote this".
 *
 * The blob is carried in [attachments] as base64 rather than as a `ByteArray`, so the document is
 * exactly what goes on the wire and equality means what it says — a `ByteArray` field would make
 * the generated `equals` compare by reference and quietly lie.
 */
@Serializable
data class CouchAsset(
    val type: String = CouchDocType.ASSET,
    val schema: Int = COUCH_SCHEMA_VERSION,
    val contentType: String = "application/octet-stream",
    val createdAt: String,
    /** Empty only transiently: normalized to [createdAt] below, matching Swift's decoder. */
    var updatedAt: String = "",
    val updatedBy: String = "",
    @SerialName("_attachments") val attachments: Map<String, CouchAttachment> = emptyMap(),
) {
    init {
        if (updatedAt.isEmpty()) updatedAt = createdAt
    }

    /** The blob, or null when this is the stub a server hands back without its bytes. */
    val bytes: ByteArray?
        get() = attachments[CouchAssetId.BLOB_NAME]?.data?.fromAttachmentData()

    companion object {
        /** A fresh asset for [bytes], with its content type sniffed from them by default. */
        fun of(
            bytes: ByteArray,
            at: String,
            updatedBy: String,
            contentType: String = CouchAssetId.contentTypeOf(bytes),
        ): CouchAsset {
            return CouchAsset(
                contentType = contentType,
                createdAt = at,
                updatedAt = at,
                updatedBy = updatedBy,
                attachments = mapOf(
                    CouchAssetId.BLOB_NAME to CouchAttachment(
                        contentType = contentType,
                        data = bytes.toAttachmentData(),
                    )
                ),
            )
        }
    }
}

/**
 * One attachment. Written with its [data] inlined, so the document and its bytes land in a single
 * `PUT`; read back as a `{"stub": true}` placeholder, because CouchDB never inlines bytes into a
 * document read that did not ask for them.
 */
@Serializable
data class CouchAttachment(
    @SerialName("content_type") val contentType: String = "application/octet-stream",
    val data: String? = null,
    val stub: Boolean? = null,
)

@Serializable
data class CouchFolder(
    val type: String = CouchDocType.FOLDER,
    val schema: Int = COUCH_SCHEMA_VERSION,
    val title: String = "",
    val parentFolderId: String? = null,
    /** In the Trash since; see [CouchNotebook.deletedAt]. Hides the whole subtree, one field. */
    val deletedAt: String? = null,
    val createdAt: String,
    var updatedAt: String = "",
    val updatedBy: String = "",
) {
    init {
        if (updatedAt.isEmpty()) updatedAt = createdAt
    }
}
