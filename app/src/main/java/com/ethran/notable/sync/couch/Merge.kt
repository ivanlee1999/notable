package com.ethran.notable.sync.couch

import java.time.Instant
import java.util.Locale

/**
 * Conflict-free merge for CouchDB documents. Normative spec: `docs/couch-sync-protocol.md` §4–5
 * in the bopa repo.
 *
 * Every function here is **commutative** (`merge(a,b) == merge(b,a)`) and **idempotent**
 * (`merge(merge(a,b), a) == merge(a,b)`), and none needs a common ancestor. That is what lets two
 * devices that were both offline reconcile without asking the user anything, and what makes
 * replaying the change feed from an old checkpoint harmless.
 *
 * bopa's `CouchMerge.swift` is the twin of this file; both are driven by
 * `couch-sync-vectors/vectors.json`, so a change here without the matching change there fails
 * both test suites. Nothing here may touch `android.*` — the vectors run as plain JVM tests.
 */
object CouchMerge {

    // region Ordering primitives

    /**
     * Epoch milliseconds, or [Long.MIN_VALUE] when unparseable.
     *
     * Timestamps must never be compared as strings: `"…:33.871Z" < "…:33Z"` lexicographically
     * (because `.` sorts below `Z`) while being *later* in time, so a string compare silently
     * picks the older document. An unparseable value loses every comparison rather than throwing —
     * a malformed timestamp is a reason to prefer the other side, not to abandon the merge.
     */
    fun millis(timestamp: String): Long = try {
        Instant.parse(timestamp).toEpochMilli()
    } catch (_: Exception) {
        Long.MIN_VALUE
    }

    /**
     * The source string whose instant is earlier. Equal instants fall back to the smaller string so
     * that two spellings of the same moment ("…:05Z" and "…:05.000Z") still pick the same one
     * regardless of argument order.
     */
    fun earlier(a: String, b: String): String {
        val ma = millis(a)
        val mb = millis(b)
        if (ma != mb) return if (ma < mb) a else b
        return if (byteCompare(a, b) <= 0) a else b
    }

    /** The source string whose instant is later, with the same spelling tiebreak as [earlier]. */
    fun later(a: String, b: String): String {
        val ma = millis(a)
        val mb = millis(b)
        if (ma != mb) return if (ma > mb) a else b
        return if (byteCompare(a, b) >= 0) a else b
    }

    /**
     * Lexicographic comparison over UTF-8 bytes — the protocol's string order (§4), used
     * wherever the merge breaks a tie on a string.
     *
     * Neither language's default is safe here. Kotlin's [String.compareTo] orders by UTF-16 code
     * unit, which files every supplementary-plane character — a device name with an emoji in it —
     * below parts of the BMP that UTF-8 files it above. Swift's `<` orders by Unicode canonical
     * equivalence, which also *equates* spellings (composed and decomposed "é") that differ on
     * the wire. Two merges that order the same pair differently pick different winners for the
     * same conflict, and the two apps quietly diverge on identical input. Bytes are the one
     * reading both languages produce identically; pinned by the `tiebreak-*` vectors.
     */
    fun byteCompare(a: String, b: String): Int {
        val x = a.toByteArray(Charsets.UTF_8)
        val y = b.toByteArray(Charsets.UTF_8)
        val n = minOf(x.size, y.size)
        for (i in 0 until n) {
            val d = (x[i].toInt() and 0xFF) - (y[i].toInt() and 0xFF)
            if (d != 0) return d
        }
        return x.size - y.size
    }

    /**
     * Total, commutative order over a document's scalar envelope. `true` means `a` wins.
     *
     * The [scalarKey] step only breaks ties between documents written in the same millisecond by
     * the same device id — unreachable while the two devices use distinct ids, and present so the
     * function is total rather than "usually total".
     */
    private fun wins(
        aUpdatedAt: String, aUpdatedBy: String, aScalarKey: String,
        bUpdatedAt: String, bUpdatedBy: String, bScalarKey: String,
    ): Boolean {
        val ma = millis(aUpdatedAt)
        val mb = millis(bUpdatedAt)
        if (ma != mb) return ma > mb
        val d = byteCompare(aUpdatedBy, bUpdatedBy)
        if (d != 0) return d > 0
        return byteCompare(aScalarKey, bScalarKey) >= 0
    }

    /**
     * Minimal key-sorted JSON of scalar fields, used only as the last-resort tiebreak. Kept free of
     * floating-point fields so Swift and Kotlin render it identically.
     */
    private fun scalarKey(pairs: List<Pair<String, String?>>): String =
        pairs.sortedBy { it.first }
            .joinToString(",") { (key, value) ->
                "\"$key\":" + if (value == null) "null" else "\"$value\""
            }

    // endregion

    // region Set primitives

    /**
     * Union keyed by `id`; whenever two elements share an id, [preferred] decides. Order of the
     * result is imposed by the caller's sort, never by input order.
     *
     * [preferred] is applied to collisions *within* a single input as well as across the two.
     * Skipping the intra-array case would make the outcome depend on element order, which a
     * document written by a buggy or older writer can easily vary — and a merge that is only
     * commutative for well-formed input is not commutative.
     */
    fun <T> unionById(
        a: List<T>, b: List<T>, id: (T) -> String, preferred: (T, T) -> T,
    ): List<T> {
        val merged = LinkedHashMap<String, T>()
        for (element in a + b) {
            val key = id(element)
            val existing = merged[key]
            merged[key] = if (existing == null) element else preferred(existing, element)
        }
        return merged.values.toList()
    }

    /**
     * Union of tombstones keeping the **earliest** `deletedAt` per id: a deletion is a fact that
     * cannot un-happen, so the earliest observation of it is the true one. Sorted by id so the
     * encoded document is byte-stable across devices.
     */
    fun unionTombstones(
        a: List<CouchTombstone>, b: List<CouchTombstone>,
    ): List<CouchTombstone> =
        unionById(a, b, id = { it.id }) { x, y ->
            CouchTombstone(id = x.id, deletedAt = earlier(x.deletedAt, y.deletedAt))
        }.sortedBy { it.id }

    // endregion

    // region Bookmarks and outline

    /**
     * Union keyed by `pageId`, keeping whichever side wrote last — protocol §3.2.1.
     *
     * Last-writer-wins rather than remove-wins, because a bookmark is re-addable under the same
     * id; see [CouchBookmark]. Equal instants fall back to `removed` losing, so two devices that
     * star and un-star in the same millisecond both end up keeping the star rather than
     * disagreeing — and, more importantly, agreeing is what makes this commutative.
     */
    fun unionBookmarks(
        a: List<CouchBookmark>, b: List<CouchBookmark>,
    ): List<CouchBookmark> =
        unionById(a, b, id = { it.pageId }) { x, y ->
            val mx = millis(x.updatedAt)
            val my = millis(y.updatedAt)
            when {
                mx != my -> if (mx > my) x else y
                x.removed != y.removed -> if (x.removed) y else x
                else -> if (x.updatedAt >= y.updatedAt) x else y
            }
        }.sortedBy { it.pageId }

    /**
     * Merge two outlines — protocol §3.2.2.
     *
     * Order comes from [winner], with entries only [loser] knows about appended in the loser's own
     * relative order; content per entry is last-writer-wins. This is deliberately the same rule as
     * `pageIds` in [mergeNotebook] rather than a fractional index or a position field: the outline
     * is a list the user reorders wholesale, the ordered add-wins union is already proven and
     * vector-tested here, and it needs no extra state on the entry to stay deterministic.
     *
     * Removed entries stay in the list. They are the tombstones, so dropping them here would let a
     * peer that still holds the entry put it back on the next merge.
     */
    fun mergeOutline(
        winner: List<CouchOutlineEntry>, loser: List<CouchOutlineEntry>,
    ): List<CouchOutlineEntry> {
        val content = LinkedHashMap<String, CouchOutlineEntry>()
        for (entry in winner + loser) {
            val held = content[entry.id]
            content[entry.id] = if (held == null) entry else {
                val mh = millis(held.updatedAt)
                val me = millis(entry.updatedAt)
                when {
                    mh != me -> if (mh > me) held else entry
                    // Same instant: `removed` loses, then the raw string breaks the tie, so both
                    // devices pick the same entry whichever order they merged in.
                    held.removed != entry.removed -> if (held.removed) entry else held
                    else -> if (held.updatedAt >= entry.updatedAt) held else entry
                }
            }
        }

        val known = winner.map { it.id }.toSet()
        val ordered = winner.map { it.id } + loser.map { it.id }.filter { it !in known }
        val seen = HashSet<String>()
        return ordered.mapNotNull { id -> if (seen.add(id)) content[id] else null }
    }

    // endregion

    // region Page

    fun mergePage(a: CouchPage, b: CouchPage): CouchPage {
        val deletedStrokes = unionTombstones(a.deletedStrokes, b.deletedStrokes)
        val deletedImages = unionTombstones(a.deletedImages, b.deletedImages)
        val deletedBlocks = unionTombstones(a.deletedBlocks, b.deletedBlocks)
        val removedStrokeIds = deletedStrokes.map { it.id }.toSet()
        val removedImageIds = deletedImages.map { it.id }.toSet()
        val removedBlockIds = deletedBlocks.map { it.id }.toSet()

        // Erasure beats drawing: a stroke one side still holds and the other tombstoned is gone on
        // both. Safe because a redrawn stroke always gets a fresh id, so "remove wins" can never
        // suppress later work.
        val strokes = unionById(a.strokes, b.strokes, id = { it.id }) { x, y ->
            preferredStroke(x, y)
        }
            .filter { it.id !in removedStrokeIds }
            .sortedBy { contentSortKey(it.createdAt, it.id) }

        val images = unionById(a.images, b.images, id = { it.id }) { x, y ->
            preferredImage(x, y)
        }
            .filter { it.id !in removedImageIds }
            .sortedBy { contentSortKey(it.createdAt, it.id) }

        // Blocks are the images clause with a different sort key, and the difference is the whole
        // point: strokes and images order by when they were made, because that is what decides
        // which is on top. A document orders by where its paragraphs are, and a creation instant
        // cannot say "between these two" — so a block carries its own key and the merge sorts on
        // it, bytewise like every other string here (§4).
        val blocks = unionById(a.blocks, b.blocks, id = { it.id }) { x, y ->
            preferredBlock(x, y)
        }
            .filter { it.id !in removedBlockIds }
            .sortedWith { x, y ->
                val byKey = byteCompare(x.orderKey, y.orderKey)
                if (byKey != 0) byKey else byteCompare(x.id, y.id)
            }

        val pageAWins = pageWins(a, b)
        val winner = if (pageAWins) a else b
        val pageLoser = if (pageAWins) b else a
        return CouchPage(
            type = CouchDocType.PAGE,
            schema = maxOf(a.schema, b.schema),
            notebookId = winner.notebookId,
            title = winner.title,
            background = winner.background,
            backgroundType = winner.backgroundType,
            // A declared sheet is never lost to a peer that has none: geometry describes how the
            // ink already on the page is laid out, so a writer that has not learned the field
            // cannot un-declare it by winning the scalar tiebreak. When both declare, the winner's
            // wins like any other scalar.
            pageWidth = winner.pageWidth ?: pageLoser.pageWidth,
            pageHeight = winner.pageHeight ?: pageLoser.pageHeight,
            strokes = strokes,
            deletedStrokes = deletedStrokes,
            images = images,
            deletedImages = deletedImages,
            blocks = blocks,
            deletedBlocks = deletedBlocks,
            createdAt = earlier(a.createdAt, b.createdAt),
            updatedAt = later(a.updatedAt, b.updatedAt),
            updatedBy = winner.updatedBy,
        )
    }

    /**
     * Strokes are immutable once drawn, so two copies of one id are normally identical; this only
     * has to be deterministic, not clever. It does have to be *total*, though — falling back to a
     * comparison that can itself tie reintroduces argument-order dependence.
     */
    private fun preferredStroke(x: CouchStroke, y: CouchStroke): CouchStroke {
        val mx = millis(x.updatedAt)
        val my = millis(y.updatedAt)
        if (mx != my) return if (mx > my) x else y
        return if (byteCompare(strokeTiebreak(x), strokeTiebreak(y)) >= 0) x else y
    }

    private fun preferredImage(x: CouchImage, y: CouchImage): CouchImage {
        val mx = millis(x.updatedAt)
        val my = millis(y.updatedAt)
        if (mx != my) return if (mx > my) x else y
        return if (byteCompare(imageTiebreak(x), imageTiebreak(y)) >= 0) x else y
    }

    private fun preferredBlock(x: CouchBlock, y: CouchBlock): CouchBlock {
        val mx = millis(x.updatedAt)
        val my = millis(y.updatedAt)
        if (mx != my) return if (mx > my) x else y
        return if (byteCompare(blockTiebreak(x), blockTiebreak(y)) >= 0) x else y
    }

    /**
     * Total order over every field of a stroke. Floats go in as their IEEE-754 bit patterns:
     * Swift's `Float.bitPattern` is a `UInt32`, so it is rendered here as an *unsigned* decimal —
     * `Float.floatToIntBits` returns a signed `Int` and would print negatives for any float with
     * the sign bit set. The two languages' default float *printing* does not agree, which is why
     * bit patterns are used at all.
     */
    private fun strokeTiebreak(s: CouchStroke): String = listOf(
        s.deviceId, s.createdAt, s.updatedAt, s.pen,
        s.color.toString(), s.maxPressure.toString(),
        bits(s.size), bits(s.top), bits(s.bottom), bits(s.left), bits(s.right),
        s.pointsData,
    ).joinToString("|")

    private fun bits(value: Float): String =
        Integer.toUnsignedString(java.lang.Float.floatToIntBits(value))

    private fun imageTiebreak(i: CouchImage): String = listOf(
        i.assetId ?: "", i.createdAt, i.updatedAt,
        i.x.toString(), i.y.toString(), i.width.toString(), i.height.toString(),
    ).joinToString("|")

    /**
     * Total order over every field of a block.
     *
     * No floats appear, and that is by construction rather than by luck: a block's geometry is `Int`
     * page units exactly like an image's, so the bit-pattern rendering [strokeTiebreak] needs is
     * unreachable here.
     *
     * [CouchBlock.text] is last because it is the only component that can contain the separator.
     * Every field before it is drawn from a grammar that excludes `|` — ids and asset ids are UUID-
     * or `asset:<hex>`-shaped, timestamps are ISO-8601, integers are decimal, and `kind` is
     * normatively `[a-z][a-z0-9-]*` — so with exactly one separator-bearing component, and it
     * terminal, the map from block to key is injective and this order is genuinely total.
     */
    private fun blockTiebreak(b: CouchBlock): String = listOf(
        b.deviceId, b.createdAt, b.updatedAt, b.kind, b.orderKey,
        b.x?.toString() ?: "", b.y?.toString() ?: "",
        b.width?.toString() ?: "", b.height?.toString() ?: "",
        b.startedAt ?: "", b.imageAssetId ?: "",
        b.segments.joinToString(",") { "${it.assetId}:${it.startMs}:${it.durationMs}" },
        b.strokeIds.joinToString(","),
        b.text ?: "",
    ).joinToString("|")

    /**
     * Sort key for drawn page content: creation instant, then id to break exact ties. Determines
     * the z-order two independently drawn strokes settle into.
     *
     * Named apart from [CouchBlock.orderKey], which is a different thing entirely: this is derived
     * from when content was made and decides what is on top, while a block's key is authored, says
     * where a paragraph sits in a document, and is the one thing a reorder edits.
     */
    private fun contentSortKey(createdAt: String, id: String): String =
        // Fixed-width so string comparison matches numeric comparison of the instant.
        String.format(Locale.ROOT, "%020d|%s", millis(createdAt), id)

    private fun pageWins(a: CouchPage, b: CouchPage): Boolean = wins(
        a.updatedAt, a.updatedBy, pageScalarKey(a),
        b.updatedAt, b.updatedBy, pageScalarKey(b),
    )

    /**
     * Every scalar the merge *picks* has to appear here, or the tiebreak stops distinguishing two
     * documents that genuinely differ: `wins` ends in `aScalarKey >= bScalarKey`, so an equal key
     * makes both argument orders return true and `merge(a, b)` and `merge(b, a)` disagree on the
     * uncovered field. `title` is picked from the winner, so it is keyed — same as the notebook and
     * folder keys, which have always included theirs.
     */
    private fun pageScalarKey(page: CouchPage): String = scalarKey(
        listOf(
            "type" to page.type, "schema" to page.schema.toString(),
            "createdAt" to page.createdAt, "updatedAt" to page.updatedAt,
            "updatedBy" to page.updatedBy, "notebookId" to page.notebookId,
            "title" to page.title,
            "background" to page.background, "backgroundType" to page.backgroundType,
            "pageWidth" to page.pageWidth?.toString(),
            "pageHeight" to page.pageHeight?.toString(),
        )
    )

    // endregion

    // region Notebook

    fun mergeNotebook(a: CouchNotebook, b: CouchNotebook): CouchNotebook {
        val aWins = notebookWins(a, b)
        val winner = if (aWins) a else b
        val loser = if (aWins) b else a
        val deletedPageIds = unionTombstones(a.deletedPageIds, b.deletedPageIds)
        val removed = deletedPageIds.map { it.id }.toSet()

        // Ordered add-wins union: the winner's ordering is authoritative, pages only the loser
        // knows about are appended keeping the loser's relative order. Deterministic for a fixed
        // pair of inputs, so both devices land on the same list.
        val known = winner.pageIds.toSet()
        val pageIds = (winner.pageIds + loser.pageIds.filter { it !in known })
            .filter { it !in removed }

        return CouchNotebook(
            type = CouchDocType.NOTEBOOK,
            schema = maxOf(a.schema, b.schema),
            title = winner.title,
            pageIds = pageIds,
            deletedPageIds = deletedPageIds,
            parentFolderId = winner.parentFolderId,
            // A bookmark on a deleted page is dropped. Safe to do here because a bookmark is keyed
            // by the very field being tested: the same pageId is filtered on every future merge,
            // so the drop cannot come undone.
            bookmarks = unionBookmarks(a.bookmarks, b.bookmarks)
                .filter { it.pageId !in removed },
            // The outline is deliberately *not* filtered the same way, though the dangling entries
            // it keeps are just as useless to tap. An entry is keyed by its own id while the test
            // would be on `pageId`, and those can disagree: if the surviving version of entry `e`
            // points at a deleted page, filtering erases `e` from the result entirely — and the
            // next merge against the peer that still holds `e` reads it as an entry this side has
            // never seen and adds it straight back. Not idempotent, and the randomised merge tests
            // catch it. Deleting a page instead marks its entries removed at the point of deletion,
            // which is an ordinary edit the merge already knows how to carry, and readers skip any
            // entry whose page is gone.
            outline = mergeOutline(winner.outline, loser.outline),
            defaultBackground = winner.defaultBackground,
            defaultBackgroundType = winner.defaultBackgroundType,
            defaultPageWidth = winner.defaultPageWidth ?: loser.defaultPageWidth,
            defaultPageHeight = winner.defaultPageHeight ?: loser.defaultPageHeight,
            // The winner's, not a union: unlike a tombstone, the Trash is a state that can be left.
            // Taking the earlier of the two would make a restore impossible to express — the peer
            // still holding the trashed copy would put it straight back on the next merge.
            deletedAt = winner.deletedAt,
            createdAt = earlier(a.createdAt, b.createdAt),
            updatedAt = later(a.updatedAt, b.updatedAt),
            updatedBy = winner.updatedBy,
        )
    }

    private fun notebookWins(a: CouchNotebook, b: CouchNotebook): Boolean = wins(
        a.updatedAt, a.updatedBy, notebookScalarKey(a),
        b.updatedAt, b.updatedBy, notebookScalarKey(b),
    )

    private fun notebookScalarKey(notebook: CouchNotebook): String = scalarKey(
        listOf(
            "type" to notebook.type, "schema" to notebook.schema.toString(),
            "createdAt" to notebook.createdAt, "updatedAt" to notebook.updatedAt,
            "updatedBy" to notebook.updatedBy, "title" to notebook.title,
            "parentFolderId" to notebook.parentFolderId,
            "defaultBackground" to notebook.defaultBackground,
            "defaultBackgroundType" to notebook.defaultBackgroundType,
            "defaultPageWidth" to notebook.defaultPageWidth?.toString(),
            "defaultPageHeight" to notebook.defaultPageHeight?.toString(),
            "deletedAt" to notebook.deletedAt,
        )
    )

    // endregion

    // region Folder

    fun mergeFolder(a: CouchFolder, b: CouchFolder): CouchFolder {
        val winner = if (folderWins(a, b)) a else b
        return CouchFolder(
            type = CouchDocType.FOLDER,
            schema = maxOf(a.schema, b.schema),
            title = winner.title,
            parentFolderId = winner.parentFolderId,
            deletedAt = winner.deletedAt,
            createdAt = earlier(a.createdAt, b.createdAt),
            updatedAt = later(a.updatedAt, b.updatedAt),
            updatedBy = winner.updatedBy,
        )
    }

    private fun folderWins(a: CouchFolder, b: CouchFolder): Boolean = wins(
        a.updatedAt, a.updatedBy, folderScalarKey(a),
        b.updatedAt, b.updatedBy, folderScalarKey(b),
    )

    private fun folderScalarKey(folder: CouchFolder): String = scalarKey(
        listOf(
            "type" to folder.type, "schema" to folder.schema.toString(),
            "createdAt" to folder.createdAt, "updatedAt" to folder.updatedAt,
            "updatedBy" to folder.updatedBy, "title" to folder.title,
            "parentFolderId" to folder.parentFolderId,
            "deletedAt" to folder.deletedAt,
        )
    )

    // endregion

    // region Delete vs edit

    enum class DeletionOutcome {
        /** The live document was edited after the deletion; it wins and is rewritten. */
        RESURRECT,

        /** The deletion stands; apply it locally. */
        APPLY_DELETION,
    }

    /**
     * Protocol §6.4. An edit strictly newer than the deletion resurrects the document; anything
     * else lets the deletion stand. Applies to notebooks and folders — pages live and die with
     * their notebook's `pageIds`.
     */
    fun resolveDeletion(liveUpdatedAt: String, tombstoneDeletedAt: String?): DeletionOutcome {
        if (tombstoneDeletedAt == null) return DeletionOutcome.RESURRECT
        return if (millis(liveUpdatedAt) > millis(tombstoneDeletedAt)) {
            DeletionOutcome.RESURRECT
        } else {
            DeletionOutcome.APPLY_DELETION
        }
    }

    // endregion
}
