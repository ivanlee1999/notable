package com.ethran.notable.editor.canvas

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PenInputDiagnosticsTest {
    @Test
    fun reopening_resets_counters_and_ignores_retired_native_callbacks() {
        val retired = PenInputDiagnostics.beginSession("BOOX native")
        PenInputDiagnostics.rawStroke(retired)
        val current = PenInputDiagnostics.beginSession("Android compatibility")
        PenInputDiagnostics.rawStroke(retired)
        PenInputDiagnostics.frameworkStroke(retired, 100)
        assertEquals(PenInputSnapshot(backend = "Android compatibility"), PenInputDiagnostics.snapshot())
        PenInputDiagnostics.frameworkStroke(current, 12)
        assertEquals(1, PenInputDiagnostics.snapshot().frameworkStrokes)
        assertEquals(12L, PenInputDiagnostics.snapshot().latestEventAgeMs)
    }

    @Test
    fun raw_callback_activity_is_separate_from_helper_allocation() {
        val owner = PenInputDiagnostics.beginSession("BOOX native")
        assertEquals(0, PenInputDiagnostics.snapshot().rawCallbacks)
        assertNull(PenInputDiagnostics.snapshot().latestEventAgeMs)
        PenInputDiagnostics.rawStroke(owner)
        assertEquals(1, PenInputDiagnostics.snapshot().rawCallbacks)
    }
}
