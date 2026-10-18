package com.ethran.notable.data.datastore

import android.content.Context
import com.ethran.notable.data.db.Notebook
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Device-local reading history. Opening a note must never bump its sync clock. */
class RecentNotebooks private constructor(context: Context) {
    private val preferences = context.getSharedPreferences("recent_notebooks", Context.MODE_PRIVATE)
    private val _ids = MutableStateFlow(
        runCatching {
            Json.decodeFromString<List<String>>(preferences.getString("ids", "[]") ?: "[]")
                .filter(String::isNotBlank).distinct().take(LIMIT)
        }.getOrDefault(emptyList())
    )
    val ids = _ids.asStateFlow()

    @Synchronized
    fun record(notebookId: String) {
        val updated = recordRecentNotebook(_ids.value, notebookId)
        if (updated == _ids.value) return
        _ids.value = updated
        preferences.edit().putString("ids", Json.encodeToString(updated)).apply()
    }

    companion object {
        const val LIMIT = 20
        @Volatile private var instance: RecentNotebooks? = null
        fun get(context: Context): RecentNotebooks = instance ?: synchronized(this) {
            instance ?: RecentNotebooks(context.applicationContext).also { instance = it }
        }
    }
}

internal fun recordRecentNotebook(ids: List<String>, notebookId: String): List<String> =
    if (notebookId.isBlank()) ids else (listOf(notebookId) + ids)
        .filter(String::isNotBlank).distinct().take(RecentNotebooks.LIMIT)

/** Ignore stale reading positions after deletion or a sync reorder. */
fun Notebook.resumePageId(): String? = openPageId?.takeIf { it in pageIds } ?: pageIds.firstOrNull()

/** Resolve history against live rows: deleted and empty notes are never navigation targets. */
fun recentNotebooks(ids: List<String>, books: List<Notebook>): List<Notebook> {
    val byId = books.filter { it.deletedAt == null && it.resumePageId() != null }.associateBy { it.id }
    return ids.distinct().mapNotNull(byId::get)
}
