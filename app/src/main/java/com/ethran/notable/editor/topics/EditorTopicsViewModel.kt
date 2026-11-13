package com.ethran.notable.editor.topics

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ethran.notable.data.JournalEntry
import com.ethran.notable.data.JournalRepository
import com.ethran.notable.data.topicName
import com.ethran.notable.data.db.Stroke
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject

data class TopicRequest(val notebookId: String, val pageId: String, val ink: List<TopicInkStroke>)

@HiltViewModel
class EditorTopicsViewModel @Inject constructor(
    private val journal: JournalRepository,
    private val recognizer: InkTopicRecognizer,
    @param:ApplicationContext context: Context,
) : ViewModel() {
    val entries = MutableStateFlow<List<JournalEntry>>(emptyList())
    val ready = MutableStateFlow(false)
    val request = MutableStateFlow<TopicRequest?>(null)
    val saving = MutableStateFlow(false)
    val recognizing = MutableStateFlow(false)
    val status = MutableStateFlow<String?>(null)
    val error = MutableStateFlow<String?>(null)
    val candidate = MutableStateFlow("")
    private val preferences = context.getSharedPreferences("topic-handwriting", Context.MODE_PRIVATE)
    val languages by lazy { runCatching { recognizer.languages() }.getOrDefault(emptyList()) }
    val language = MutableStateFlow(preferences.getString("language", null) ?: Locale.getDefault().toLanguageTag())
    private var editedCandidate = false
    private var recognition: Job? = null
    private var recognitionGeneration = 0
    private var loading: Job? = null

    init { load() }

    fun load() {
        loading?.cancel()
        loading = viewModelScope.launch {
            try {
                journal.entries.collect { entries.value = it; ready.value = true }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { ready.value = false; error.value = "Could not load topics. Try again." }
        }
    }

    fun open(notebookId: String, pageId: String, strokes: List<Stroke> = emptyList()) {
        close()
        request.value = TopicRequest(notebookId, pageId, topicInk(strokes))
        candidate.value = ""
        editedCandidate = false
        error.value = null
        val supported = languages
        if (supported.none { it.tag == language.value }) {
            val locale = Locale.forLanguageTag(language.value)
            language.value = supported.firstOrNull {
                val candidate = Locale.forLanguageTag(it.tag)
                candidate.language == locale.language && candidate.country == locale.country
            }?.tag ?: supported.firstOrNull { Locale.forLanguageTag(it.tag).language == locale.language }?.tag
                ?: supported.firstOrNull { it.tag == "en-US" }?.tag
                ?: supported.firstOrNull()?.tag ?: "en-US"
        }
        if (request.value!!.ink.isNotEmpty()) recognize()
    }

    fun close() {
        recognitionGeneration++
        recognition?.cancel()
        recognition = null
        request.value = null
        recognizing.value = false
        status.value = null
    }

    fun retainContext(notebookId: String?, pageId: String?) {
        request.value?.let { if (it.notebookId != notebookId || it.pageId != pageId) close() }
    }

    fun editCandidate(value: String) { editedCandidate = true; candidate.value = value }

    fun setLanguage(tag: String) {
        language.value = tag
        preferences.edit().putString("language", tag).apply()
        recognize()
    }

    fun recognize() {
        val target = request.value ?: return
        if (target.ink.isEmpty()) return
        val generation = ++recognitionGeneration
        val selectedLanguage = language.value
        editedCandidate = false
        recognition?.cancel()
        recognition = viewModelScope.launch {
            recognizing.value = true
            error.value = null
            status.value = "Reading handwriting…"
            try {
                val text = recognizer.recognize(target.ink, selectedLanguage) {
                    if (request.value === target && generation == recognitionGeneration) status.value = "Downloading handwriting language…"
                }
                if (request.value === target && generation == recognitionGeneration && !editedCandidate) candidate.value = text.trim()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (request.value === target && generation == recognitionGeneration) error.value = "Could not read handwriting. Check the connection for the first language download, or enter a topic."
            } finally {
                if (request.value === target && generation == recognitionGeneration) { recognizing.value = false; status.value = null }
            }
        }
    }

    fun topic(name: String, selected: Boolean, existing: Boolean = true) {
        val target = request.value ?: return
        if (!ready.value || saving.value || (if (existing) name.isEmpty() else topicName(name).isEmpty())) return
        // Catalog labels are already canonical. Escape a literal leading # exactly once;
        // raw typed/recognized input is normalized by the repository instead.
        val input = if (existing && name.startsWith("#")) "#$name" else name
        saving.value = true
        error.value = null
        viewModelScope.launch {
            try {
                if (selected) journal.addTopic(target.notebookId, input)
                else journal.removeTopic(target.notebookId, name)
                if (request.value === target && selected) { candidate.value = ""; editedCandidate = true }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (request.value === target) error.value = "Could not save topic. Try again." }
            finally { saving.value = false }
        }
    }
}
