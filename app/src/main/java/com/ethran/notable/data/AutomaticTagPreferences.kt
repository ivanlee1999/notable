package com.ethran.notable.data

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AutomaticTagPreferences @Inject constructor(@param:ApplicationContext context: Context) {
    private val preferences = context.getSharedPreferences("automatic-tags", Context.MODE_PRIVATE)
    private val state = MutableStateFlow(preferences.getBoolean("enabled", true))
    val enabled = state.asStateFlow()
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "enabled") state.value = preferences.getBoolean("enabled", true)
    }
    init { preferences.registerOnSharedPreferenceChangeListener(listener) }
    fun setEnabled(value: Boolean) { state.value = value; preferences.edit().putBoolean("enabled", value).apply() }
}
