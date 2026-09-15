package com.ethran.notable.editor.text

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * How wide a new box is, and how the numbers behave at the edges of a sheet. Pure arithmetic, so
 * it runs without a device — the laid-out height needs `StaticLayout` and is covered on device.
 */
class TextBoxGeometryTest {

    private val metrics = TextBoxMetrics.STANDARD

    @Test
    fun `a box gets the preferred width when there is room`() {
        assertEquals(metrics.preferredWidth.toInt(), TextBoxLayout.defaultWidth(100f, 1400f))
    }

    @Test
    fun `a box near the right edge is pulled in`() {
        // 1400 - 1000 - 40 of right margin.
        assertEquals(360, TextBoxLayout.defaultWidth(1000f, 1400f))
    }

    @Test
    fun `a box past the point where even the minimum fits still gets the minimum`() {
        // Running past the edge beats a column one letter wide.
        assertEquals(metrics.minimumWidth.toInt(), TextBoxLayout.defaultWidth(1390f, 1400f))
    }

    @Test
    fun `heading sizes scale from the body size`() {
        assertEquals(metrics.body * 2, metrics.sizeFor(MarkdownText.LineKind.Heading(1)), 0.01f)
        assertEquals(metrics.body * 1.5f, metrics.sizeFor(MarkdownText.LineKind.Heading(2)), 0.01f)
        assertEquals(metrics.body, metrics.sizeFor(MarkdownText.LineKind.Body), 0.01f)
        // A level beyond the table clamps rather than crashing: an unknown heading is still a
        // heading, and the parser only ever produces 1-3 anyway.
        assertEquals(metrics.body * 1.25f, metrics.sizeFor(MarkdownText.LineKind.Heading(9)), 0.01f)
    }
}
