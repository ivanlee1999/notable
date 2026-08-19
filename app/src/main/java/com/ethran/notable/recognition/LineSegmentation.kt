package com.ethran.notable.recognition

import com.ethran.notable.data.db.Stroke

/**
 * Splitting a page's ink into recognizer-sized pieces.
 *
 * MyScript is handed a view of a fixed size and expects the ink to fall inside it. A notable
 * page is not bounded that way — continuous scroll lets one page grow taller than any screen —
 * so ink is grouped into bands of writing separated by blank space, and consecutive bands are
 * packed into chunks that fit the recognizer's view. Cutting only at blank gaps is what keeps a
 * line of text from being sliced through the middle, which is the one thing that reliably
 * destroys recognition accuracy.
 */
object LineSegmentation {

    /** A slice of the page: the strokes in it, already translated to start at y = 0. */
    data class Chunk(val strokes: List<Stroke>, val offsetY: Float)

    /**
     * The gap between two bands of writing must exceed this multiple of the median stroke height
     * before it counts as a line break rather than the space inside one line of text.
     */
    private const val GAP_RATIO = 1.5f

    /** Below this, a "gap" is just the space between a letter's parts. In page units. */
    private const val MIN_GAP = 40f

    /**
     * Groups [strokes] into chunks no taller than [viewHeight].
     *
     * Strokes taller than the view on their own are emitted alone: they cannot be made to fit,
     * and a diagram that recognizes as nothing is a better outcome than one that drags the
     * writing around it out of the view.
     */
    fun chunk(strokes: List<Stroke>, viewHeight: Float): List<Chunk> {
        if (strokes.isEmpty()) return emptyList()

        val bands = bands(strokes)
        val chunks = mutableListOf<Chunk>()
        var current = mutableListOf<List<Stroke>>()
        var currentTop = Float.MAX_VALUE
        var currentBottom = -Float.MAX_VALUE

        fun flush() {
            if (current.isEmpty()) return
            val offset = currentTop
            chunks += Chunk(
                strokes = current.flatten().map { it.translatedBy(-offset) },
                offsetY = offset,
            )
            current = mutableListOf()
            currentTop = Float.MAX_VALUE
            currentBottom = -Float.MAX_VALUE
        }

        for (band in bands) {
            val top = band.minOf { it.top }
            val bottom = band.maxOf { it.bottom }
            val wouldSpan = maxOf(currentBottom, bottom) - minOf(currentTop, top)
            if (current.isNotEmpty() && wouldSpan > viewHeight) flush()
            current += band
            currentTop = minOf(currentTop, top)
            currentBottom = maxOf(currentBottom, bottom)
        }
        flush()
        return chunks
    }

    /**
     * Strokes grouped into bands of writing, in reading order. A band is a run of strokes with no
     * blank horizontal gap between them wide enough to be a line break — usually one line of
     * text, but a word with a low-hanging descender laps into the next band's space, so bands
     * are merged on overlap rather than on strict ordering.
     */
    private fun bands(strokes: List<Stroke>): List<List<Stroke>> {
        val sorted = strokes.sortedBy { it.top }
        val threshold = maxOf(MIN_GAP, GAP_RATIO * medianHeight(sorted))

        val bands = mutableListOf<MutableList<Stroke>>()
        var bandBottom = -Float.MAX_VALUE

        for (stroke in sorted) {
            if (bands.isEmpty() || stroke.top - bandBottom > threshold) {
                bands += mutableListOf(stroke)
                bandBottom = stroke.bottom
            } else {
                bands.last() += stroke
                bandBottom = maxOf(bandBottom, stroke.bottom)
            }
        }
        return bands
    }

    private fun medianHeight(strokes: List<Stroke>): Float {
        val heights = strokes.map { it.bottom - it.top }.sorted()
        if (heights.isEmpty()) return 0f
        return heights[heights.size / 2]
    }
}

/**
 * This stroke moved up the page by [dy], bounds included. Only the geometry the recognizer reads
 * is touched — ids, timestamps and pressure are the original's, since these copies never reach
 * the database.
 */
internal fun Stroke.translatedBy(dy: Float): Stroke = copy(
    points = points.map { it.copy(y = it.y + dy) },
    top = top + dy,
    bottom = bottom + dy,
)
