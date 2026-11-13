package com.ethran.notable.editor.topics

import com.ethran.notable.data.db.Stroke
import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognition
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognitionModel
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognitionModelIdentifier
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognizerOptions
import com.google.mlkit.vision.digitalink.recognition.Ink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.util.Locale
import javax.inject.Inject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class TopicInkPoint(val x: Float, val y: Float, val time: Long)
data class TopicInkStroke(val points: List<TopicInkPoint>)
data class TopicLanguage(val tag: String, val name: String)

/** Copy only selected geometry. Recognition never mutates, replaces, or deletes stored ink. */
internal fun topicInk(strokes: List<Stroke>): List<TopicInkStroke> {
    val ordered = strokes.sortedWith(compareBy<Stroke> { it.createdAt.time }.thenBy { it.id })
    val origin = ordered.firstOrNull()?.createdAt?.time ?: return emptyList()
    val points = ordered.flatMap { it.points }.filter { it.x.isFinite() && it.y.isFinite() }
    val left = points.minOfOrNull { it.x } ?: return emptyList()
    val top = points.minOf { it.y }
    return ordered.mapNotNull { stroke ->
        val copied = stroke.points.mapIndexedNotNull { index, point ->
            if (!point.x.isFinite() || !point.y.isFinite()) null else TopicInkPoint(
                point.x - left, point.y - top,
                (stroke.createdAt.time - origin).coerceAtLeast(0) + (point.dt?.toLong() ?: index * 10L),
            )
        }
        copied.takeIf { it.isNotEmpty() }?.let(::TopicInkStroke)
    }
}

open class InkTopicRecognizer @Inject constructor() {
    open fun languages(): List<TopicLanguage> = DigitalInkRecognitionModelIdentifier.allModelIdentifiers()
        .mapNotNull { model -> model.languageTag?.takeIf { !it.contains("-x-") } }
        .distinct().map { TopicLanguage(it, Locale.forLanguageTag(it).getDisplayName(Locale.getDefault())) }
        .sortedBy { it.name }

    open suspend fun recognize(
        strokes: List<TopicInkStroke>, language: String, onDownloading: () -> Unit,
    ): String {
        require(strokes.isNotEmpty()) { "Select some handwriting first." }
        val identifier = DigitalInkRecognitionModelIdentifier.fromLanguageTag(language)
            ?: error("This handwriting language is unavailable. Choose another language.")
        val model = DigitalInkRecognitionModel.builder(identifier).build()
        val manager = RemoteModelManager.getInstance()
        try {
            withTimeout(90_000) {
                if (!manager.isModelDownloaded(model).awaitResult()) {
                    onDownloading()
                    manager.download(model, DownloadConditions.Builder().build()).awaitResult()
                }
            }
        } catch (_: TimeoutCancellationException) { throw IOException("Handwriting language download timed out.") }
        val ink = withContext(Dispatchers.Default) {
            Ink.builder().apply {
                strokes.forEach { stroke ->
                    addStroke(Ink.Stroke.builder().apply {
                        stroke.points.forEach { addPoint(Ink.Point.create(it.x, it.y, it.time)) }
                    }.build())
                }
            }.build()
        }
        val recognizer = DigitalInkRecognition.getClient(DigitalInkRecognizerOptions.builder(model).build())
        return try {
            withTimeout(30_000) { recognizer.recognize(ink).awaitResult() }.candidates.firstOrNull()?.text
                ?.takeIf { it.isNotBlank() } ?: error("Could not read this selection. Edit the topic or try again.")
        } catch (_: TimeoutCancellationException) {
            throw IOException("Handwriting recognition timed out.")
        } finally { recognizer.close() }
    }
}

private suspend fun <T> Task<T>.awaitResult(): T = suspendCancellableCoroutine { continuation ->
    addOnSuccessListener { if (continuation.isActive) continuation.resume(it) }
    addOnFailureListener { if (continuation.isActive) continuation.resumeWithException(it) }
    addOnCanceledListener { continuation.cancel() }
}
