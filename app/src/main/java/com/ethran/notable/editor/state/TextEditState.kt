package com.ethran.notable.editor.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.ethran.notable.data.db.Block
import com.ethran.notable.editor.PageView
import com.ethran.notable.editor.text.TextBoxLayout
import com.ethran.notable.editor.text.TextBoxMetrics
import com.ethran.notable.editor.utils.EpdRefreshArbiter
import com.ethran.notable.sync.SyncClock
import java.util.UUID
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The typing session: which text box is open, and what has been typed into it so far.
 *
 * Held beside [SelectionState] and shaped like it, because the two are the same kind of thing — a
 * modal interaction over the page that the canvas has to know about, that holds the e-ink panel in
 * fast-refresh mode for its lifetime, and that commits or is abandoned as a unit.
 *
 * Nothing is written to the page until [commit]. A box that is opened and left empty never
 * existed: a user who taps the paper by accident and taps away again should leave nothing behind.
 */
class TextEditState(
    /** Stamped into every box this device writes; the merge's tiebreak. Set once the sync
     *  settings are readable, which is later than the view model is built. */
    var deviceId: String = "",
) {

    /** The box being typed into, or null when nothing is. */
    var editing by mutableStateOf<Block?>(null)
        private set

    /** What has been typed, which is the box's markdown source, not its rendered text. */
    var draft by mutableStateOf("")

    /** Whether [editing] is a box that has never been written to the page. */
    var isNew by mutableStateOf(false)
        private set

    private var refreshHandle: EpdRefreshArbiter.Handle? = null

    val isActive: Boolean get() = editing != null

    /**
     * A new box with its top-left at ([x], [y]) in page units, clamped onto the paper.
     *
     * A box may still *end* past the bottom — the page grows downward as you write, and typing at
     * the foot of a sheet is ordinary — but it has to *start* somewhere reachable.
     */
    fun newBlockAt(
        x: Float,
        y: Float,
        pageId: String,
        pageWidth: Int,
        metrics: TextBoxMetrics = TextBoxMetrics.STANDARD,
    ): Block {
        val left = min(max(x, 0f), max(pageWidth - metrics.minimumWidth, 0f))
        val top = max(y, 0f)
        val width = TextBoxLayout.defaultWidth(left, pageWidth.toFloat(), metrics)
        return Block(
            id = UUID.randomUUID().toString(),
            pageId = pageId,
            kind = "md",
            // Empty is legal for a positioned block and sorts first (protocol §3.3.1). A key
            // orders a page's *flow*, and a box carrying its own coordinates is not in one — so
            // there is nothing to mint and nothing for two devices to disagree about.
            orderKey = "",
            text = "",
            x = left.roundToInt(),
            y = top.roundToInt(),
            width = width,
            height = TextBoxLayout.measuredHeight("", width.toFloat(), metrics),
            createdAt = SyncClock.nowDate(),
            updatedAt = SyncClock.nowDate(),
            deviceId = deviceId,
        )
    }

    fun begin(block: Block, isNew: Boolean) {
        editing = block
        draft = block.text.orEmpty()
        this.isNew = isNew
        // Held for the session so the panel stays in fast refresh while the keyboard is up.
        // Without it every keystroke would be an ordinary refresh, and typing a sentence would
        // flash the screen a word at a time.
        if (refreshHandle == null) refreshHandle = EpdRefreshArbiter.acquire("text-edit")
    }

    /**
     * Writes what was typed and ends the session.
     *
     * An empty box is deleted rather than stored: a box with nothing in it is invisible, so
     * leaving one behind would litter the page with things only a stray tap can find. A *new*
     * empty box is not deleted — it was never written — which is why the two cases are told
     * apart rather than both going through [PageView.removeBlocks].
     *
     * @return the operations to put on the undo stack, and the rectangle to refresh.
     */
    fun commit(page: PageView, metrics: TextBoxMetrics = TextBoxMetrics.STANDARD): Committed? {
        val block = editing ?: return null
        val typed = draft
        val before = if (isNew) null else page.blocks.firstOrNull { it.id == block.id }
        reset()

        if (typed.isBlank()) {
            if (before == null) return Committed(emptyList(), TextBoxLayout.bounds(block, metrics))
            page.removeBlocks(listOf(block.id))
            return Committed(
                listOf(Operation.AddBlock(listOf(before))),
                TextBoxLayout.bounds(before, metrics),
            )
        }

        val width = (block.width ?: metrics.preferredWidth.roundToInt()).toFloat()
        val written = block.copy(
            text = typed,
            height = TextBoxLayout.measuredHeight(typed, width, metrics),
            updatedAt = SyncClock.nowDate(),
            deviceId = deviceId,
        )
        page.addOrUpdateBlocks(listOf(written))
        val undo = if (before == null) {
            Operation.DeleteBlock(listOf(written.id))
        } else {
            Operation.UpdateBlock(listOf(before))
        }
        // Both rectangles: the box may have grown, and the old one has to be repainted too.
        val dirty = TextBoxLayout.bounds(written, metrics)
        if (before != null) TextBoxLayout.bounds(before, metrics)?.let { dirty?.union(it) }
        return Committed(listOf(undo), dirty)
    }

    /** Abandons the session without writing. A box never committed simply never existed. */
    fun cancel() = reset()

    private fun reset() {
        editing = null
        draft = ""
        isNew = false
        refreshHandle?.release()
        refreshHandle = null
    }

    /** What a finished session leaves behind: the undo step, and what to repaint. */
    data class Committed(
        val operations: List<Operation>,
        val dirtyRect: android.graphics.Rect?,
    )
}
