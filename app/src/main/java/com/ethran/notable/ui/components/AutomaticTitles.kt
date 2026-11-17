package com.ethran.notable.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ethran.notable.data.db.Notebook
import com.ethran.notable.recognition.NoteTitles
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

@EntryPoint
@InstallIn(SingletonComponent::class)
interface NoteTitlesEntryPoint { fun noteTitles(): NoteTitles }

@Composable
private fun noteTitles(): NoteTitles {
    val context = LocalContext.current
    return remember(context) { EntryPointAccessors.fromApplication(context.applicationContext, NoteTitlesEntryPoint::class.java).noteTitles() }
}

@Composable
fun rememberNotebookTitle(notebook: Notebook): String = rememberNotebookTitle(notebook.id, notebook.title)

@Composable
fun rememberNotebookTitle(notebookId: String?, rawTitle: String?): String {
    if (!rawTitle.isNullOrBlank()) return rawTitle
    if (notebookId == null || LocalInspectionMode.current) return "Untitled"
    val titles = noteTitles()
    val title by remember(titles, notebookId) { titles.notebook(notebookId) }.collectAsStateWithLifecycle()
    return title
}

@Composable
fun rememberPageTitle(pageId: String?, notebookId: String?, rawTitle: String?): String? {
    if (!rawTitle.isNullOrBlank()) return rawTitle
    if (pageId == null || notebookId == null || LocalInspectionMode.current) return null
    val titles = noteTitles()
    val title by remember(titles, pageId, notebookId) { titles.page(pageId, notebookId) }.collectAsStateWithLifecycle()
    return title
}

@Composable
fun rememberNotebookNames(books: List<Notebook>, enabled: Boolean): Map<String, String> {
    if (!enabled || LocalInspectionMode.current) return emptyMap()
    val titles = noteTitles()
    val names by remember(titles, books) { titles.names(books) }.collectAsStateWithLifecycle(initialValue = emptyMap())
    return names
}
