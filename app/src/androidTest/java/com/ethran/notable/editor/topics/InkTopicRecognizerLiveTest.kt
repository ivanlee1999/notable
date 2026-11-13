package com.ethran.notable.editor.topics

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit opt-in: downloads Google's English language pack into the test installation. */
@RunWith(AndroidJUnit4::class)
class InkTopicRecognizerLiveTest {
    @Test fun downloadsLanguageAndRecognizesSelectedInkWithoutChangingIt() = runBlocking {
        assumeTrue("Set NOTABLE_LIVE_INK_RECOGNITION=true to exercise real model download/inference",
            InstrumentationRegistry.getArguments().getString("NOTABLE_LIVE_INK_RECOGNITION") == "true")
        val recognizer = InkTopicRecognizer()
        assertTrue(recognizer.languages().any { it.tag == "en-US" })
        // A large capital L, made from actual timed stroke points rather than an OCR bitmap.
        val points = (0..20).map { TopicInkPoint(20f, 20f + it * 8f, it * 10L) } +
            (1..12).map { TopicInkPoint(20f + it * 8f, 180f, 200L + it * 10L) }
        val ink = listOf(TopicInkStroke(points))
        val before = ink.map { it.copy(points = it.points.toList()) }
        val text = withTimeout(150_000) { recognizer.recognize(ink, "en-US") {} }
        assertTrue("Actual ML Kit inference must return a candidate", text.isNotBlank())
        assertEquals(before, ink)
    }
}
