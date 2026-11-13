package com.ethran.notable.editor.canvas

data class PenInputSnapshot(
    val backend: String = "Not opened",
    val rawCallbacks: Int = 0,
    val frameworkStrokes: Int = 0,
    val latestEventAgeMs: Long? = null,
)

/** Session-level counters only: no handwriting, coordinates or per-point logging. */
object PenInputDiagnostics {
    private var current = PenInputSnapshot()
    private var session = 0L

    @Synchronized fun beginSession(backend: String): Long {
        current = PenInputSnapshot(backend)
        return ++session
    }
    @Synchronized fun rawStroke(owner: Long) {
        if (owner == session) current = current.copy(rawCallbacks = current.rawCallbacks + 1)
    }
    @Synchronized fun frameworkStroke(owner: Long, eventAgeMs: Long) {
        if (owner != session) return
        current = current.copy(frameworkStrokes = current.frameworkStrokes + 1,
            latestEventAgeMs = eventAgeMs.coerceAtLeast(0))
    }
    @Synchronized fun snapshot(): PenInputSnapshot = current
}
