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
        return if (a <= b) a else b
    }

    /** The source string whose instant is later, with the same spelling tiebreak as [earlier]. */
    fun later(a: String, b: String): String {
        val ma = millis(a)
        val mb = millis(b)
        if (ma != mb) return if (ma > mb) a else b
        return if (a >= b) a else b
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
        if (aUpdatedBy != bUpdatedBy) return aUpdatedBy > bUpdatedBy
        return aScalarKey >= bScalarKey
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

    // region Page

    fun mergePage(a: CouchPage, b: CouchPage): CouchPage {
        val deletedStrokes = unionTombstones(a.deletedStrokes, b.deletedStrokes)
        val deletedImages = unionTombstones(a.deletedImages, b.deletedImages)
        val removedStrokeIds = deletedStrokes.map { it.id }.toSet()
        val removedImageIds = deletedImages.map { it.id }.toSet()

        // Erasure beats drawing: a stroke one side still holds and the other tombstoned is gone on
        // both. Safe because a redrawn stroke always gets a fresh id, so "remove wins" can never
        // suppress later work.
        val strokes = unionById(a.strokes, b.strokes, id = { it.id }) { x, y ->
            preferredStroke(x, y)
        }
            .filter { it.id !in removedStrokeIds }
            .sortedBy { orderKey(it.createdAt, it.id) }

        val images = unionById(a.images, b.images, id = { it.id }) { x, y ->
            preferredImage(x, y)
        }
            .filter { it.id !in removedImageIds }
            .sortedBy { orderKey(it.createdAt, it.id) }

        val winner = if (pageWins(a, b)) a else b
        return CouchPage(
            type = CouchDocType.PAGE,
            schema = maxOf(a.schema, b.schema),
            notebookId = winner.notebookId,
            background = winner.background,
            backgroundType = winner.backgroundType,
            strokes = strokes,
            deletedStrokes = deletedStrokes,
            images = images,
            deletedImages = deletedImages,
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
        return if (strokeTiebreak(x) >= strokeTiebreak(y)) x else y
    }

    private fun preferredImage(x: CouchImage, y: CouchImage): CouchImage {
        val mx = millis(x.updatedAt)
        val my = millis(y.updatedAt)
        if (mx != my) return if (mx > my) x else y
        return if (imageTiebreak(x) >= imageTiebreak(y)) x else y
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
     * Sort key for page content: creation instant, then id to break exact ties. Determines the
     * z-order two independently drawn strokes settle into.
     */
    private fun orderKey(createdAt: String, id: String): String =
        // Fixed-width so string comparison matches numeric comparison of the instant.
        String.format(Locale.ROOT, "%020d|%s", millis(createdAt), id)

    private fun pageWins(a: CouchPage, b: CouchPage): Boolean = wins(
        a.updatedAt, a.updatedBy, pageScalarKey(a),
        b.updatedAt, b.updatedBy, pageScalarKey(b),
    )

    private fun pageScalarKey(page: CouchPage): String = scalarKey(
        listOf(
            "type" to page.type, "schema" to page.schema.toString(),
            "createdAt" to page.createdAt, "updatedAt" to page.updatedAt,
            "updatedBy" to page.updatedBy, "notebookId" to page.notebookId,
            "background" to page.background, "backgroundType" to page.backgroundType,
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
            defaultBackground = winner.defaultBackground,
            defaultBackgroundType = winner.defaultBackgroundType,
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
