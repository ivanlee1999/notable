package com.ethran.notable.editor.canvas

import android.graphics.Rect
import android.net.Uri
import io.shipbook.shipbooksdk.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.system.measureTimeMillis

object CanvasEventBus {
    val forceUpdate = MutableSharedFlow<Rect?>() // null for full redraw
    val refreshUi = MutableSharedFlow<Unit>()
    val refreshUiImmediately = MutableSharedFlow<Unit>(
        replay = 1, extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val reinitSignal = MutableSharedFlow<Unit>()

    /**
     * Pages whose rows were rewritten underneath the app — a sync pull applied them, or the user
     * asked for a refresh. An empty set means nothing needs re-reading.
     *
     * [com.ethran.notable.data.PageDataManager] is the collector, not the canvas: a page cached
     * in memory is stale whether or not an editor is open on it, and the editor is exactly where
     * this signal is *not* delivered when the user is somewhere else in the app. It drops the
     * cached copies and redraws through [forceUpdate] if one of them is the open page.
     *
     * Buffered deeply, and emitted into with `emit` rather than `tryEmit`: a dropped notification
     * is precisely the bug this exists to fix, since the page it named is then the one nobody
     * re-reads. A catch-up pull can apply hundreds of pages faster than the collector reads them,
     * so producers have to be willing to wait for a slot rather than give theirs up. (With no
     * collector at all — before the page cache is built — emission neither waits nor queues, which
     * is right: nothing is holding a stale page yet.)
     */
    val pagesChangedInDb = MutableSharedFlow<Set<String>>(extraBufferCapacity = 256)


    val isDrawing = MutableSharedFlow<Boolean>()

    // used for managing drawing state on regain focus
    val onFocusChange = MutableSharedFlow<Boolean>()

    // before undo we need to commit changes
    val commitHistorySignal = MutableSharedFlow<Unit>()
    val commitHistorySignalImmediately = MutableSharedFlow<Unit>()

    // used for checking if commit was completed
    var commitCompletion = CompletableDeferred<Unit>()

    // It might be bad idea, but plan is to insert graphic in this, and then take it from it
    // There is probably better way
    val addImageByUri = MutableStateFlow<Uri?>(null)

    /**
     * A pen tap while the text tool is up: where it landed, in page units, and which box it hit
     * (null for bare paper).
     *
     * A signal rather than a direct call because the pen arrives on the firmware's raw callback,
     * off the main thread, and what it means — open a box, make one, close the one that is open —
     * is a decision the editor makes with state the input handler cannot see.
     */
    val textBoxTapped = MutableSharedFlow<TextBoxTap>(
        extraBufferCapacity = 4, onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    /** A pen drag that started on a box: how far it went, in page units. */
    val textBoxDragged = MutableSharedFlow<TextBoxDrag>(
        extraBufferCapacity = 4, onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    /** Asked of whoever owns the page: finish the box that is open. */
    val textBoxEditRequested = MutableSharedFlow<TextBoxEdit>(
        extraBufferCapacity = 4, onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    // Event, not state: each emission is one gesture-selection request.
    val rectangleToSelectByGesture = MutableSharedFlow<Rect>()
    val drawingInProgress = Mutex()

    // For cleaning whole page, activated from toolbar menu
    val clearPageSignal = MutableSharedFlow<Unit>()

    // Signal to UI to close any open menus/modals,
    // observed in EditorView
    val closeMenusSignal = MutableSharedFlow<Unit>()


    // For QuickNav scrolling with previews
    val saveCurrent = MutableSharedFlow<Unit>()

    val isScrubbing = MutableStateFlow<Boolean> (false)
    val previewPage = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val restoreCanvas = MutableSharedFlow<Unit>(extraBufferCapacity = 1)


    val changePage = MutableSharedFlow<String>(extraBufferCapacity = 1)


    suspend fun waitForDrawing() {
        Log.d(
            "DrawCanvas.waitForDrawing", "waiting"
        )
        val elapsed = measureTimeMillis {
            withTimeoutOrNull(3000) {
                // Just to make sure wait 1ms before checking lock.
                delay(1)
                // Wait until drawingInProgress is unlocked before proceeding
                while (drawingInProgress.isLocked) {
                    delay(5)
                }
            } ?: Log.e(
                "DrawCanvas.waitForDrawing",
                "Timeout while waiting for drawing lock. Potential deadlock."
            )

        }
        when {
            elapsed > 3000 -> Log.e(
                "DrawCanvas.waitForDrawing", "Exceeded timeout ($elapsed ms)"
            )

            elapsed > 100 -> Log.w("DrawCanvas.waitForDrawing", "Took too long: $elapsed ms")
            else -> Log.d("DrawCanvas.waitForDrawing", "Finished waiting in $elapsed ms")
        }

    }

}

/** Where a text-tool tap landed, in page units, and the box under it if there was one. */
data class TextBoxTap(val x: Float, val y: Float, val blockId: String?)

/**
 * A text box dragged by a pen: which one, by how much in page units, and whether the drag began
 * on its right edge — which means "set the wrap width" rather than "move the box".
 */
data class TextBoxDrag(
    val blockId: String,
    val dx: Int,
    val dy: Int,
    val isResize: Boolean = false,
)

/** What to do with the open text box. */
enum class TextBoxEdit { CommitOpen }
