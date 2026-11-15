package com.ethran.notable.recognition

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

data class HandwritingOptions(val automatic: Boolean = true, val textPreview: Boolean = false,
    val language: String = Locale.getDefault().toLanguageTag(),
    val cloudTranscripts: Boolean = false, val transcriptDatabase: String = "notes_text")

/** Device-local recognition and viewing choices; never part of a notebook document. */
@Singleton
class HandwritingPreferences @Inject constructor(@param:ApplicationContext context: Context) {
    private val preferences = context.getSharedPreferences("handwriting-preview", Context.MODE_PRIVATE)
    private fun read() = HandwritingOptions(preferences.getBoolean("automatic", true),
        preferences.getBoolean("text-preview", false), preferences.getString("language", null)
            ?: Locale.getDefault().toLanguageTag(), preferences.getBoolean("cloud-transcripts", false),
        preferences.getString("transcript-database", "notes_text") ?: "notes_text")
    private val state = MutableStateFlow(read())
    val options = state.asStateFlow()
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> state.value = read() }
    init { preferences.registerOnSharedPreferenceChangeListener(listener) }
    fun update(value: HandwritingOptions) {
        preferences.edit().putBoolean("automatic", value.automatic).putBoolean("text-preview", value.textPreview)
            .putString("language", value.language).putBoolean("cloud-transcripts", value.cloudTranscripts)
            .putString("transcript-database", value.transcriptDatabase).apply()
        state.value = value
    }
}
