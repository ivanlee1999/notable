package com.ethran.notable.data.datastore

import com.ethran.notable.editor.ui.toolbar.model.ToolbarPen
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class AppSettingsToolbarPensTest {

    // Same configuration as KvProxy's Json instance.
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    @Test
    fun `settings persisted before the field decode with the default pens`() {
        val decoded = json.decodeFromString(AppSettings.serializer(), """{"version":1}""")
        assertEquals(ToolbarPen.DEFAULT_PENS, decoded.toolbarPens)
    }

    @Test
    fun `toolbarPens round-trip through AppSettings serialization`() {
        val settings = AppSettings(
            version = 1,
            toolbarPens = ToolbarPen.DEFAULT_PENS + ToolbarPen(
                id = "a1b2c3d4",
                pen = com.ethran.notable.editor.utils.Pen.FOUNTAIN,
                color = android.graphics.Color.MAGENTA,
                size = 7f,
            ),
        )
        val decoded = json.decodeFromString(
            AppSettings.serializer(),
            json.encodeToString(AppSettings.serializer(), settings),
        )
        assertEquals(settings.toolbarPens, decoded.toolbarPens)
    }

    /**
     * The rail used to be user-ordered, and that order was persisted as `toolbarLayout`.
     * A device upgrading across that change still has the key in its stored blob; decoding
     * must drop it rather than throw, or the user loses every other setting with it.
     */
    @Test
    fun `a settings blob still carrying the old toolbar layout decodes`() {
        val decoded = json.decodeFromString(
            AppSettings.serializer(),
            """{"version":1,"toolbarLayout":{"scrollable":["PEN:ball","DIVIDER"],""" +
                """"pinned":["UNDO","MENU"]},"smoothScroll":false}""",
        )
        assertEquals(ToolbarPen.DEFAULT_PENS, decoded.toolbarPens)
        assertEquals(false, decoded.smoothScroll)
        assertEquals(false, decoded.compatibilityPenInput)
    }

    @Test
    fun `compatibility input round trips without changing existing pen and navigation settings`() {
        val old = json.decodeFromString(AppSettings.serializer(),
            """{"version":1,"smoothScroll":false,"canvasLocked":true,"toolbarPosition":"Bottom"}""")
        val updated = old.copy(compatibilityPenInput = true)
        val restored = json.decodeFromString(AppSettings.serializer(),
            json.encodeToString(AppSettings.serializer(), updated))
        assertEquals(updated, restored)
        assertEquals(old.toolbarPens, restored.toolbarPens)
        assertEquals(old.canvasLocked, restored.canvasLocked)
        assertEquals(old.toolbarPosition, restored.toolbarPosition)
    }
}
