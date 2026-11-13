package com.ethran.notable.recognition

import com.ethran.notable.data.db.Stroke
import com.ethran.notable.data.db.StrokePoint
import com.ethran.notable.editor.utils.Pen
import org.junit.Assert.*
import org.junit.Test
import java.util.Date

class HandwritingFingerprintTest {
    private val stroke = Stroke(id = "ink", pageId = "page", size = 3f, pen = Pen.BALLPEN,
        top = 0f, bottom = 10f, left = 0f, right = 10f, points = listOf(StrokePoint(0f, 0f), StrokePoint(10f, 10f)),
        createdAt = Date(1), updatedAt = Date(2))

    @Test fun sameClockGeometryChangesInvalidateRecognitionAndKeepTheOriginalPoints() {
        val changed = stroke.copy(points = listOf(StrokePoint(0f, 0f), StrokePoint(5f, 10f)))
        assertEquals(stroke.updatedAt, changed.updatedAt)
        assertNotEquals(handwritingFingerprint(listOf(stroke), "en-US"), handwritingFingerprint(listOf(changed), "en-US"))
        assertEquals(10f, stroke.points.last().x, 0f)
    }

    @Test fun rowOrderDoesNotChangeAStableSourceFingerprintButLanguageAndRemovalDo() {
        val second = stroke.copy(id = "second", createdAt = Date(3))
        val expected = handwritingFingerprint(listOf(stroke, second), "en-US")
        assertEquals(expected, handwritingFingerprint(listOf(second, stroke), "en-US"))
        assertNotEquals(expected, handwritingFingerprint(listOf(stroke), "en-US"))
        assertNotEquals(expected, handwritingFingerprint(listOf(stroke, second), "fr-FR"))
    }
}
