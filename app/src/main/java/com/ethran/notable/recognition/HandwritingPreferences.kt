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
    val language: String = Locale.getDefault().toLanguageTag())

/** Device-local recognition and viewing choices; never part of a notebook document. */
@Singleton
class HandwritingPreferences @Inject constructor(@param:ApplicationContext context: Context) {
    private val preferences = context.getSharedPreferences("handwriting-preview", Context.MODE_PRIVATE)
    private fun read() = HandwritingOptions(preferences.getBoolean("automatic", true),
        preferences.getBoolean("text-preview", false), preferences.getString("language", null)
            ?: Locale.getDefault().toLanguageTag())
    private val state = MutableStateFlow(read())
    val options = state.asStateFlow()
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> state.value = read() }
    init { preferences.registerOnSharedPreferenceChangeListener(listener) }
    fun update(value: HandwritingOptions) {
        preferences.edit().putBoolean("automatic", value.automatic).putBoolean("text-preview", value.textPreview)
            .putString("language", value.language).apply()
        state.value = value
    }
}
