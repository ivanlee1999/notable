package com.ethran.notable.sync.couch

import com.ethran.notable.data.db.decodeStrokePoints
import com.ethran.notable.data.db.encodeStrokePoints
import com.ethran.notable.data.model.PageSize
import java.security.MessageDigest
import java.util.Base64
import kotlin.math.floor

/**
 * Cutting a page that grew past its sheet into one page per sheet.
 *
 * A page has always been an endless vertical canvas: the declared sheet was only where export
 * divided it, so writing to the bottom and carrying on made the *same* page taller. Everything
 * below the first sheet was then invisible to anything that works in pages — the overview showed
 * one thumbnail, a bookmark could only point at the whole scroll, and reordering could not reach
 * it. This turns each of those sheets into a page of its own.
 *
 * The rules are shared with the iPad (`PageSplit.swift`) and pinned by the conformance vectors in
 * `couch-sync-vectors/`, described normatively in `couch-sync-protocol.md` §6.6. Four of them
 * carry the weight:
 *
 * - **The first sheet keeps the page's own id.** Bookmarks and outline entries name a page id, so
 *   giving sheet 0 a new one would strand every entry that pointed at the page.
 * - **Later sheets get ids derived from the parent's**, so two devices that split the same page
 *   while neither can see the other produce the *same* pages rather than two rival sets the merge
 *   would then keep both of. This is the whole reason the id is a hash and not a UUID.
 * - **Ink is never cut.** A stroke belongs to the sheet its top edge falls in and travels whole,
 *   so a descender crossing the boundary stays in one piece rather than being severed.
 * - **The parent remembers what left it.** The first sheet carries a tombstone for every stroke
 *   and image that moved to a child. Without them, a peer still holding the tall copy unions the
 *   moved ink straight back into the parent on merge — the page re-grows on every pull, each side
 *   pushes its own version back, and the notebook never converges. The tombstones are scoped to
 *   the parent *document*, so the same ink living on a child under the same id is untouched, and
 *   a peer on an older build converges too, because every merge already honours `deletedStrokes`.
 *   The children start with no tombstones at all: one handed down could name ink that already
 *   lives on that child — moved there by an earlier division of the same page, before it re-grew
 *   under a peer's push — and would erase it on the next merge. Erasures made while the page was
 *   tall stay recorded on sheet 0, whose id is the page's own.
 */
object PageSplit {

    /**
     * The sheet a page is divided by: what it declares, else the notebook's default, else the
     * geometry a page written before sheets existed is read with.
     *
     * The fallback has to be this constant and not "the screen", which is what this app lays an
     * undeclared page out at: a sheet the two apps disagree about would divide the page into a
     * different number of pages on each of them, which is exactly the split the derived ids exist
     * to prevent. Every page this produces declares its size, so the ambiguity is spent once.
     */
    fun sheetFor(pageWidth: Int?, pageHeight: Int?, notebookDefault: PageSize?): PageSize {
        if (pageWidth != null && pageHeight != null && pageWidth > 0 && pageHeight > 0) {
            return PageSize(pageWidth, pageHeight)
        }
        if (notebookDefault != null && notebookDefault.width > 0 && notebookDefault.height > 0) {
            return notebookDefault
        }
        return PageSize.LEGACY_UNDECLARED_ON_IPAD
    }

    /**
     * The id of sheet [index] of [parentId] — `index` 0 is the parent itself.
     *
     * SHA-256 over an ASCII string, rendered in the shape of a UUID because that is what every
     * other page id looks like. Not a UUIDv5: no namespace, no version nibble, just the first 16
     * bytes of the digest — the only property required of it is that Kotlin and Swift compute the
     * same one.
     */
    fun childId(parentId: String, index: Int): String {
        if (index == 0) return parentId
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("notable-page-split:$parentId:$index".toByteArray(Charsets.UTF_8))
        val hex = digest.joinToString("") { "%02x".format(it) }.take(32)
        return "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-" +
            "${hex.substring(16, 20)}-${hex.substring(20, 32)}"
    }

    /**
     * Which sheet a piece of content belongs to: the one its *top edge* falls in.
     *
     * The single rule the two apps have to agree on item by item. Everything else about the split
     * is bookkeeping around it.
     */
    fun sheetIndexOfTop(top: Float, sheetHeight: Int): Int {
        if (sheetHeight <= 0) return 0
        return floor(maxOf(0f, top) / sheetHeight).toInt()
    }

    /**
     * How many sheets content occupies, counted from where it *starts*, not from how far it
     * reaches.
     *
     * Measuring the extent instead would make this non-idempotent: a stroke beginning just above
     * the boundary and trailing past it keeps the page taller than one sheet, so every later run
     * would find two sheets, assign nothing to the second, and file an empty page — once per open,
     * for ever. Counting where content begins means a split page always reports one sheet.
     */
    fun sheetCount(tops: List<Float>, sheetHeight: Int): Int =
        (tops.maxOfOrNull { sheetIndexOfTop(it, sheetHeight) } ?: 0) + 1

    /** A page the split produced, with the id it must be stored under. */
    data class Divided(val id: String, val page: CouchPage)

    /**
     * Divides [page] into one page per sheet, in order, the first of which *is* [page].
     *
     * Returns a single page when there is nothing below the first sheet, so this is safe to run on
     * every page of every notebook and cheap to run again.
     */
    fun split(
        page: CouchPage,
        id: String,
        sheet: PageSize,
        now: String,
        updatedBy: String,
    ): List<Divided> {
        val tops = page.strokes.map { it.top } + page.images.map { it.y.toFloat() }
        val count = sheetCount(tops, sheet.height)
        if (count <= 1) return listOf(Divided(id, declaring(sheet, page)))

        // What the first sheet has to remember leaving it — see the class doc. Stamped with the
        // split's own clock: the tombstone must outrank the copy a peer's tall page still holds.
        val movedStrokes = page.strokes
            .filter { sheetIndexOfTop(it.top, sheet.height) > 0 }
            .map { CouchTombstone(id = it.id, deletedAt = now) }
        val movedImages = page.images
            .filter { sheetIndexOfTop(it.y.toFloat(), sheet.height) > 0 }
            .map { CouchTombstone(id = it.id, deletedAt = now) }

        return (0 until count).map { index ->
            val offset = index * sheet.height.toFloat()
            val strokes = page.strokes
                .filter { sheetIndexOfTop(it.top, sheet.height) == index }
                .map { shift(it, -offset) }
            val images = page.images
                .filter { sheetIndexOfTop(it.y.toFloat(), sheet.height) == index }
                .map { it.copy(y = it.y - offset.toInt()) }
            Divided(
                childId(id, index),
                declaring(sheet, page).copy(
                    strokes = strokes,
                    images = images,
                    // Sheet 0 keeps the page's tombstones and adds the moved ink's; the children
                    // start clean — see the class doc for why handing one down would be a trap.
                    deletedStrokes =
                        if (index == 0) page.deletedStrokes + movedStrokes else emptyList(),
                    deletedImages =
                        if (index == 0) page.deletedImages + movedImages else emptyList(),
                    updatedBy = updatedBy,
                ).also { it.updatedAt = now },
            )
        }
    }

    /** The page with its sheet written down, so the next reader does not have to guess it. */
    private fun declaring(sheet: PageSize, page: CouchPage): CouchPage =
        page.copy(pageWidth = sheet.width, pageHeight = sheet.height)

    /**
     * Moves a stroke, points and bounds together.
     *
     * The points are the stroke — the bounds are a cached reading of them — so both have to move
     * or the page overview and the eraser would look in the wrong place.
     */
    private fun shift(stroke: CouchStroke, delta: Float): CouchStroke {
        // java.util.Base64, not android.util: this runs in the JVM unit tests that drive the
        // shared vectors, where the android.* stubs throw.
        val points = decodeStrokePoints(Base64.getDecoder().decode(stroke.pointsData))
        val moved = points.map { it.copy(y = it.y + delta) }
        return stroke.copy(
            top = stroke.top + delta,
            bottom = stroke.bottom + delta,
            pointsData = Base64.getEncoder().encodeToString(encodeStrokePoints(moved)),
        )
    }
}
