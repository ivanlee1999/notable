package com.ethran.notable.recognition

import com.ethran.notable.data.db.Stroke
import com.ethran.notable.data.db.StrokePoint
import com.ethran.notable.editor.utils.Pen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How a page is cut up for the recognizer.
 *
 * MyScript reads a view of a fixed height, and a notable page can be much taller than any view,
 * so the ink has to be handed over in pieces. What matters is *where* the cuts fall: through the
 * blank space between lines of writing, never through a line — a half-height line of text
 * recognizes as garbage, and the recognizer gives no sign that it happened.
 */
class LineSegmentationTest {

    private var nextId = 0

    /** A stroke shaped like a line of handwriting: a horizontal run at [top]. */
    private fun line(top: Float, height: Float = 40f, left: Float = 0f, right: Float = 400f) =
        Stroke(
            id = "s${nextId++}",
            size = 3f,
            pen = Pen.BALLPEN,
            top = top,
            bottom = top + height,
            left = left,
            right = right,
            points = listOf(
                StrokePoint(x = left, y = top + height / 2),
                StrokePoint(x = right, y = top + height / 2),
            ),
            pageId = "page",
        )

    @Test
    fun `an empty page has nothing to recognize`() {
        assertEquals(emptyList<LineSegmentation.Chunk>(), LineSegmentation.chunk(emptyList(), 1872f))
    }

    @Test
    fun `a page that fits the view is handed over whole`() {
        val strokes = listOf(line(100f), line(200f), line(300f))

        val chunks = LineSegmentation.chunk(strokes, viewHeight = 1872f)

        assertEquals(1, chunks.size)
        assertEquals(3, chunks[0].strokes.size)
    }

    @Test
    fun `the first chunk starts at the top of the view`() {
        // Writing that begins a long way down the page still has to reach the recognizer inside
        // its view, so every chunk is translated up to start at zero.
        val chunks = LineSegmentation.chunk(listOf(line(5000f), line(5100f)), viewHeight = 1872f)

        assertEquals(1, chunks.size)
        assertEquals(5000f, chunks[0].offsetY, 0.01f)
        assertEquals(0f, chunks[0].strokes.minOf { it.top }, 0.01f)
        assertEquals(0f, chunks[0].strokes.flatMap { it.points }.minOf { it.y }, 20.01f)
    }

    @Test
    fun `a page taller than the view is split into chunks that each fit`() {
        // Twenty lines spaced 200 apart spans 4000 units — more than two 1872-unit views.
        val strokes = (0 until 20).map { line(top = it * 200f) }

        val chunks = LineSegmentation.chunk(strokes, viewHeight = 1872f)

        assertTrue("expected more than one chunk, got ${chunks.size}", chunks.size > 1)
        for (chunk in chunks) {
            val span = chunk.strokes.maxOf { it.bottom } - chunk.strokes.minOf { it.top }
            assertTrue("chunk spans $span, taller than the view", span <= 1872f)
        }
    }

    @Test
    fun `every stroke reaches exactly one chunk`() {
        val strokes = (0 until 20).map { line(top = it * 200f) }

        val chunks = LineSegmentation.chunk(strokes, viewHeight = 1872f)

        val ids = chunks.flatMap { chunk -> chunk.strokes.map { it.id } }
        assertEquals(strokes.map { it.id }.toSet(), ids.toSet())
        assertEquals("a stroke was recognized twice", ids.size, ids.toSet().size)
    }

    @Test
    fun `a line of writing is never split across chunks`() {
        // Lines of several strokes each — words, as they are actually written — laid out so the
        // cut has to fall between two lines rather than in the middle of one.
        val strokes = (0 until 20).flatMap { row ->
            (0 until 4).map { word ->
                line(top = row * 200f, left = word * 100f, right = word * 100f + 90f)
            }
        }

        val chunks = LineSegmentation.chunk(strokes, viewHeight = 1872f)

        // Each row's four strokes share a top; none of those groups may span two chunks.
        val chunkOfRow = mutableMapOf<Float, Int>()
        chunks.forEachIndexed { index, chunk ->
            chunk.strokes.forEach { stroke ->
                val row = stroke.top + chunk.offsetY
                val seen = chunkOfRow.putIfAbsent(row, index)
                assertEquals("row at $row was split across chunks", seen ?: index, index)
            }
        }
    }

    @Test
    fun `strokes are grouped into a line even when they overlap vertically`() {
        // A descender ("g") hangs below its line and starts above the next one. Grouping by a
        // strict top-to-bottom cut would open a new band there; overlap has to win.
        val strokes = listOf(
            line(top = 100f, height = 40f, left = 0f, right = 100f),
            line(top = 120f, height = 60f, left = 110f, right = 160f),  // the descender
            line(top = 100f, height = 40f, left = 170f, right = 300f),
        )

        val chunks = LineSegmentation.chunk(strokes, viewHeight = 200f)

        assertEquals("one line of writing became more than one chunk", 1, chunks.size)
    }

    @Test
    fun `a stroke taller than the view is handed over on its own`() {
        // A diagram or a long bracket. It cannot be made to fit, and dragging its neighbours
        // out of the view with it would lose writing that would otherwise have been read.
        val strokes = listOf(
            line(top = 0f, height = 30f),
            line(top = 500f, height = 3000f),
            line(top = 4000f, height = 30f),
        )

        val chunks = LineSegmentation.chunk(strokes, viewHeight = 1872f)

        val tall = chunks.single { chunk -> chunk.strokes.any { it.bottom - it.top > 1872f } }
        assertEquals(1, tall.strokes.size)
    }

    @Test
    fun `chunks come back in reading order`() {
        val strokes = listOf(line(4000f), line(0f), line(2000f))

        val chunks = LineSegmentation.chunk(strokes, viewHeight = 500f)

        val offsets = chunks.map { it.offsetY }
        assertEquals(offsets.sorted(), offsets)
    }
}
