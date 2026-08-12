package com.ethran.notable.editor.state

import android.graphics.Rect
import com.ethran.notable.data.PageMemoryModel
import com.ethran.notable.data.db.Image
import com.ethran.notable.data.db.Stroke
import com.ethran.notable.data.events.AppEvent
import com.ethran.notable.data.events.AppEventBus
import com.ethran.notable.editor.PageView
import com.ethran.notable.editor.canvas.CanvasEventBus
import com.ethran.notable.editor.utils.imageBoundsInt
import com.ethran.notable.editor.utils.strokeBounds
import com.ethran.notable.utils.logCallStack
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CompletableDeferred


sealed class Operation {
    data class DeleteStroke(val strokeIds: List<String>) : Operation()
    data class AddStroke(val strokes: List<Stroke>) : Operation()
    data class AddImage(val images: List<Image>) : Operation()
    data class DeleteImage(val imageIds: List<String>) : Operation()

    // In-place update of existing entities (same ids): move, resize, recolour, … Its inverse is
    // another Update carrying the previous values, so undo/redo never delete-then-reinsert the same
    // id (which raced to a UNIQUE(Image.id) crash).
    data class UpdateStroke(val strokes: List<Stroke>) : Operation()
    data class UpdateImage(val images: List<Image>) : Operation()
}

typealias OperationBlock = List<Operation>
typealias OperationList = MutableList<OperationBlock>

enum class UndoRedoType {
    Undo,
    Redo
}

sealed class HistoryBusActions {
    data class RegisterHistoryOperationBlock(val operationBlock: OperationBlock) :
        HistoryBusActions()

    data class MoveHistory(val type: UndoRedoType) : HistoryBusActions()
}

class History @AssistedInject constructor(
    @Assisted private val pageView: PageView,
    private val appEventBus: AppEventBus
) {
    private var undoList: OperationList = mutableListOf()
    private var redoList: OperationList = mutableListOf()
    private val pageModel = pageView

    suspend fun handleHistoryBusActions(actions: HistoryBusActions) {
        when (actions) {
            is HistoryBusActions.MoveHistory -> {
                // Wait for commit to history to complete
                if (actions.type == UndoRedoType.Undo) {
                    CanvasEventBus.commitCompletion = CompletableDeferred()
                    CanvasEventBus.commitHistorySignalImmediately.emit(Unit)
                    CanvasEventBus.commitCompletion.await()
                }
                val zoneAffected = undoRedo(type = actions.type)
                if (zoneAffected != null) {
                    pageModel.drawAreaPageCoordinates(zoneAffected)
                    //moved to refresh after drawing
                    CanvasEventBus.refreshUi.emit(Unit)
                } else {
                    val message = when (actions.type) {
                        UndoRedoType.Undo -> "Nothing to undo"
                        UndoRedoType.Redo -> "Nothing to redo"
                    }
                    appEventBus.emit(AppEvent.ActionHint(message, 3000))
                }
            }

            is HistoryBusActions.RegisterHistoryOperationBlock -> {
                addOperationsToHistory(actions.operationBlock)
            }

        }
    }

    suspend fun undo() {
        handleHistoryBusActions(HistoryBusActions.MoveHistory(UndoRedoType.Undo))
    }

    suspend fun redo() {
        handleHistoryBusActions(HistoryBusActions.MoveHistory(UndoRedoType.Redo))
    }


    fun cleanHistory() {
        undoList.clear()
        redoList.clear()
    }

    private fun treatOperation(operation: Operation): Pair<Operation, Rect> {
        when (operation) {
            is Operation.AddStroke -> {
                pageModel.addStrokes(operation.strokes)
                return Operation.DeleteStroke(strokeIds = operation.strokes.map { it.id }) to strokeBounds(
                    operation.strokes
                )
            }

            is Operation.DeleteStroke -> {
                val strokes = pageModel.getStrokes(operation.strokeIds).filterNotNull()
                pageModel.removeStrokes(operation.strokeIds)
                return Operation.AddStroke(strokes = strokes) to strokeBounds(strokes)
            }

            is Operation.AddImage -> {
                pageModel.addImage(operation.images)
                return Operation.DeleteImage(imageIds = operation.images.map { it.id }) to imageBoundsInt(
                    operation.images
                )
            }

            is Operation.DeleteImage -> {
                val images = pageModel.getImages(operation.imageIds).filterNotNull()
                pageModel.removeImages(operation.imageIds)
                return Operation.AddImage(images = images) to imageBoundsInt(images)
            }

            is Operation.UpdateStroke -> {
                // Snapshot current values first — that's the inverse. Then apply the new values.
                val previous = pageModel.getStrokes(operation.strokes.map { it.id }).filterNotNull()
                pageModel.updateStrokes(operation.strokes)
                return Operation.UpdateStroke(strokes = previous) to
                        strokeBounds(operation.strokes + previous)
            }

            is Operation.UpdateImage -> {
                val previous = pageModel.getImages(operation.images.map { it.id }).filterNotNull()
                pageModel.updateImages(operation.images)
                return Operation.UpdateImage(images = previous) to
                        imageBoundsInt(operation.images + previous)
            }
        }
    }

    private fun undoRedo(type: UndoRedoType): Rect? {
        val originList =
            if (type == UndoRedoType.Undo) undoList else redoList
        val targetList =
            if (type == UndoRedoType.Undo) redoList else undoList

        if (originList.isEmpty()) return null

        val operationBlock = originList.removeAt(originList.lastIndex)
        val revertOperations = mutableListOf<Operation>()
        val zoneAffected = Rect()
        for (operation in operationBlock) {
            val (cancelOperation, thisZoneAffected) = treatOperation(operation = operation)
            revertOperations.add(cancelOperation)
            zoneAffected.union(thisZoneAffected)
        }
        targetList.add(revertOperations.reversed())

        // update the affected zone
        return zoneAffected
    }

    fun addOperationsToHistory(operations: OperationBlock) {
        if (operations.isEmpty()) {
            logCallStack("History: No operations to add to history")
            return
        }
        undoList.add(operations)
        trimUndoList()
        redoList.clear()
    }

    /**
     * Drops the oldest history until it fits the budget.
     *
     * The cap used to be five operation blocks, full stop. Five is not a handwriting session: a
     * lasso move, a resize and a colour change spend three of them without a word being written,
     * and erasing spends one per gesture — so "undo my last few minutes" was routinely impossible,
     * and a mistaken clear could not be taken back at all once anything followed it.
     *
     * Bounded by bytes rather than by count, because what a block costs varies by four orders of
     * magnitude: a block holding one short stroke is a few kilobytes, and one holding a
     * select-all move of a dense page is tens of megabytes. A count cannot be set to a number that
     * is both deep enough for the first and safe for the second. [MAX_BLOCKS] is only a backstop
     * against pathological churn of blocks too small for the byte budget to notice.
     */
    private fun trimUndoList() {
        while (undoList.size > MAX_BLOCKS) undoList.removeAt(0)

        var bytes = undoList.sumOf { estimateBytes(it) }
        // Never below one block: the most recent operation stays undoable whatever it cost, since
        // a history that drops the thing you just did is worse than no history at all.
        while (undoList.size > 1 && bytes > MAX_BYTES) {
            bytes -= estimateBytes(undoList.removeAt(0))
        }
    }

    /**
     * What a block holds, by the same counting constants the page cache is budgeted with — an
     * over-estimate on purpose, so history errs toward forgetting rather than toward an OOM.
     *
     * Delete operations carry ids, not content, so they are charged the per-object cost alone.
     */
    private fun estimateBytes(block: OperationBlock): Long = block.sumOf { operation ->
        when (operation) {
            is Operation.AddStroke -> strokeBytes(operation.strokes)
            is Operation.UpdateStroke -> strokeBytes(operation.strokes)
            is Operation.DeleteStroke ->
                operation.strokeIds.size * PageMemoryModel.BYTES_PER_STROKE
            is Operation.AddImage -> operation.images.size * PageMemoryModel.BYTES_PER_IMAGE
            is Operation.UpdateImage -> operation.images.size * PageMemoryModel.BYTES_PER_IMAGE
            is Operation.DeleteImage -> operation.imageIds.size * PageMemoryModel.BYTES_PER_IMAGE
        }
    }

    private fun strokeBytes(strokes: List<Stroke>): Long = PageMemoryModel.entryBytes(
        strokeCount = strokes.size,
        totalPointCount = strokes.sumOf { it.points.size.toLong() },
        imageCount = 0,
    )

    @AssistedFactory
    interface Factory {
        fun create(pageView: PageView): History
    }

    private companion object {
        /**
         * How much of the heap the undo stack may hold. Deliberately small next to the page cache:
         * history is a convenience and the page is the work, so under pressure this is what should
         * be given up. Large enough all the same that an ordinary session of writing and erasing
         * never reaches it.
         */
        const val MAX_BYTES = 24L * 1024 * 1024

        /** Backstop for blocks too small for [MAX_BYTES] to notice — a long run of tiny edits. */
        const val MAX_BLOCKS = 500
    }
}