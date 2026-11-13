package com.ethran.notable.recognition

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ethran.notable.data.db.Stroke
import com.ethran.notable.data.db.StrokePoint
import com.ethran.notable.data.db.StrokeRepository
import com.ethran.notable.editor.topics.InkTopicRecognizer
import com.ethran.notable.editor.utils.Pen
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Date
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

@RunWith(AndroidJUnit4::class)
class PageTranscriptionTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val page = "transcript-test-${UUID.randomUUID()}"
    private val ink = Stroke(id = "ink", pageId = page, size = 3f, pen = Pen.BALLPEN,
        top = 0f, bottom = 10f, left = 0f, right = 10f, points = listOf(StrokePoint(0f, 0f), StrokePoint(10f, 10f)),
        createdAt = Date(1), updatedAt = Date(2))
    private val source = MutableStateFlow(listOf(ink))
    private val strokes = mockk<StrokeRepository>()
    private val recognizer = mockk<InkTopicRecognizer>()
    private val tagger = mockk<com.ethran.notable.data.ContentTagger>(relaxed = true)
    private val tagGeneration = AtomicLong()
    private lateinit var automaticTags: com.ethran.notable.data.AutomaticTagPreferences
    private var previousAutomatic = true
    private lateinit var preferences: HandwritingPreferences
    private lateinit var previous: HandwritingOptions
    private lateinit var repository: PageTranscriptionRepository
    private val state = MutableStateFlow(HandwritingPreview())
    private val observed = CopyOnWriteArrayList<String?>()

    @Before fun setup() {
        preferences = HandwritingPreferences(context)
        previous = preferences.options.value
        preferences.update(HandwritingOptions(automatic = true, textPreview = true, language = "en-US"))
        automaticTags = com.ethran.notable.data.AutomaticTagPreferences(context)
        previousAutomatic = automaticTags.enabled.value
        automaticTags.setEnabled(true)
        every { strokes.observeByPage(page) } returns source
        every { tagger.currentGeneration() } answers { tagGeneration.get() }
        coEvery { strokes.getByPage(page) } answers { source.value }
        coEvery { recognizer.recognize(any(), any(), any()) } returns "Recognized source"
        repository = PageTranscriptionRepository(context, strokes, recognizer, preferences, scope, Dispatchers.IO, tagger, automaticTags)
        scope.launch { repository.observe(page).collect { observed += it.text; state.value = it } }
    }

    @After fun cleanup() = runBlocking { scope.coroutineContext.job.cancelAndJoin(); preferences.update(previous); automaticTags.setEnabled(previousAutomatic) }

    @Test fun automaticRequestsCoalesceAndOnlyCreateDerivedText() = runBlocking {
        repeat(20) { repository.request(page) }
        withTimeout(6_000) { state.first { it.text == "Recognized source" } }
        coVerify(exactly = 1) { recognizer.recognize(any(), "en-US", any()) }
        assertEquals(listOf(ink), source.value)
        coVerify(exactly = 0) { strokes.update(any<List<Stroke>>()) }
        coVerify(exactly = 0) { strokes.create(any<List<Stroke>>()) }
        coVerify(exactly = 0) { strokes.deleteAll(any()) }
    }

    @Test fun sameClockInkChangesRejectTheOldRecognitionResult() = runBlocking {
        val started = CompletableDeferred<Unit>(); val finish = CompletableDeferred<String>()
        var calls = 0
        coEvery { recognizer.recognize(any(), any(), any()) } coAnswers {
            if (++calls == 1) { started.complete(Unit); finish.await() } else "Fresh source"
        }
        repository.request(page)
        withTimeout(6_000) { started.await() }
        source.value = listOf(ink.copy(points = listOf(StrokePoint(0f, 0f), StrokePoint(5f, 10f))))
        finish.complete("Stale source")
        withTimeout(6_000) { state.first { it.text == "Fresh source" } }
        assertFalse(observed.contains("Stale source"))
        assertEquals(ink.updatedAt, source.value.single().updatedAt)
    }

    @Test fun failedRecognitionFallsBackToInkWithoutRepeatingTheSameFailure() = runBlocking {
        val attempted = CompletableDeferred<Unit>()
        coEvery { recognizer.recognize(any(), any(), any()) } coAnswers {
            attempted.complete(Unit); throw IllegalStateException("model unavailable")
        }
        repository.request(page)
        withTimeout(6_000) { attempted.await(); state.first { it.textMode } }
        delay(1_000)
        assertNull(state.value.text)
        assertTrue(state.value.textMode)
        assertEquals(listOf(ink), source.value)
        coVerify(exactly = 1) { recognizer.recognize(any(), any(), any()) }
    }

    @Test fun changingLanguageRejectsAnInFlightResultFromThePreviousLanguage() = runBlocking {
        val started = CompletableDeferred<Unit>(); val finish = CompletableDeferred<String>()
        coEvery { recognizer.recognize(any(), "en-US", any()) } coAnswers { started.complete(Unit); finish.await() }
        coEvery { recognizer.recognize(any(), "fr-FR", any()) } returns "French source"
        repository.request(page)
        withTimeout(6_000) { started.await() }
        preferences.update(preferences.options.value.copy(language = "fr-FR"))
        finish.complete("Old English source")
        withTimeout(6_000) { state.first { it.text == "French source" } }
        assertFalse(observed.contains("Old English source"))
    }

    @Test fun aLongPenContactDefersRecognitionUntilReleaseAndThenReadsLatestDurableInk() = runBlocking {
        val contact = Any()
        repository.penContact(contact, true)
        repository.request(page)
        delay(1_200)
        coVerify(exactly = 0) { recognizer.recognize(any(), any(), any()) }
        source.value = listOf(ink.copy(points = listOf(StrokePoint(2f, 3f), StrokePoint(8f, 9f))))
        repository.penContact(contact, false)
        withTimeout(6_000) { state.first { it.text == "Recognized source" } }
        coVerify(exactly = 1) { recognizer.recognize(match { it.single().points.last().x == 6f }, "en-US", any()) }
    }

    @Test fun newPenContactCancelsAnInFlightAttemptWithoutStoppingTheQueue() = runBlocking {
        val started = CompletableDeferred<Unit>(); val cancelled = CompletableDeferred<Unit>()
        var attempts = 0
        coEvery { recognizer.recognize(any(), any(), any()) } coAnswers {
            if (++attempts == 1) {
                started.complete(Unit)
                try { awaitCancellation() } finally { cancelled.complete(Unit) }
            } else "After pen release"
        }
        repository.request(page)
        withTimeout(6_000) { started.await() }
        val contact = Any(); repository.penContact(contact, true)
        withTimeout(6_000) { cancelled.await() }
        assertNull(state.value.text)
        repository.penContact(contact, false)
        withTimeout(6_000) { state.first { it.text == "After pen release" } }
        assertEquals(2, attempts)
    }

    @Test fun aCachedPreviewStaysReadOnlyButAnExactLocallySavedUndoCanUseItsText() = runBlocking {
        withTimeout(6_000) { state.first { it.text == "Recognized source" } }
        verify(exactly = 0) { tagger.submit(any(), any(), any(), any()) }
        val submitted = CompletableDeferred<com.ethran.notable.data.ContentTagSource>()
        every { tagger.submit("book", page, any(), any()) } answers { submitted.complete(thirdArg()); Unit }
        repository.markLocalInkSaved(page, "book", source.value, "en-US")
        repository.request(page)
        val tagged = withTimeout(6_000) { submitted.await() } as com.ethran.notable.data.ContentTagSource.Ink
        assertEquals(handwritingFingerprint(source.value, "en-US"), tagged.fingerprint)
        assertEquals("Recognized source", tagged.text)
        coVerify(exactly = 1) { recognizer.recognize(any(), any(), any()) }
        repository.request(page)
        delay(1_200)
        verify(exactly = 1) { tagger.submit(any(), any(), any(), any()) }
    }

    @Test fun globalTagManagementInvalidatesOldInkEligibilityAndDelayedMarkersButAllowsANewLocalEdit() = runBlocking {
        val started = CompletableDeferred<Unit>(); val finish = CompletableDeferred<String>()
        coEvery { recognizer.recognize(any(), any(), any()) } coAnswers { started.complete(Unit); finish.await() }
        repository.markLocalInkSaved(page, "book", source.value, "en-US", generation = 0)
        repository.request(page)
        withTimeout(6_000) { started.await() }
        tagGeneration.incrementAndGet()
        finish.complete("Recognized source")
        withTimeout(6_000) { state.first { it.text == "Recognized source" } }
        verify(exactly = 0) { tagger.submit(any(), any(), any(), any()) }
        repository.markLocalInkSaved(page, "book", source.value, "en-US", generation = 0)
        repository.request(page)
        delay(1_200)
        verify(exactly = 0) { tagger.submit(any(), any(), any(), any()) }
        val submitted = CompletableDeferred<Unit>()
        every { tagger.submit("book", page, any(), 1) } answers { submitted.complete(Unit); Unit }
        repository.markLocalInkSaved(page, "book", source.value, "en-US", generation = 1)
        repository.request(page)
        withTimeout(6_000) { submitted.await() }
        verify(exactly = 1) { tagger.submit(any(), any(), any(), any()) }
    }
}
