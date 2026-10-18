package com.ethran.notable.editor.canvas

/**
 * A raw-ink handoff is safe only after a replacement frame has been drawn AND posted.
 * Keep unlocking in finally (even if drawing fails), but never report that cleanup as a
 * successful frame. A destroyed surface is expected during editor/page transitions.
 */
internal fun <T : Any> postSurfaceFrame(
    lock: () -> T?,
    draw: (T) -> Unit,
    unlockAndPost: (T) -> Unit,
    onFailure: (IllegalStateException) -> Unit = {},
): Boolean {
    return try {
        val canvas = lock() ?: return false
        try {
            draw(canvas)
        } finally {
            unlockAndPost(canvas)
        }
        true
    } catch (error: IllegalStateException) {
        onFailure(error)
        false
    }
}

/** Report completion from inside the queued frame, never from the enqueueing caller. */
internal fun enqueueSurfaceFrame(
    enqueue: (() -> Unit) -> Boolean,
    render: () -> Boolean,
    onPosted: (Boolean) -> Unit,
) {
    val accepted = enqueue {
        var posted = false
        try {
            posted = render()
        } finally {
            onPosted(posted)
        }
    }
    if (!accepted) onPosted(false)
}

/** Serializes pen-down with the raw-layer clear and invalidates frames queued for older ink. */
internal class InkHandoffGuard {
    private var epoch = 0L
    private var active = false

    val generation: Long
        @Synchronized get() = epoch

    @Synchronized fun begin() {
        epoch++
        active = true
    }

    @Synchronized fun end() {
        active = false
    }

    @Synchronized fun runIfIdle(generation: Long, handoff: () -> Unit) {
        if (!active && generation == epoch) handoff()
    }
}

/** Evaluate the mode at completion, not when its asynchronous frame was requested. */
internal inline fun handoffPostedFrame(
    posted: Boolean,
    isCurrent: () -> Boolean,
    handoff: () -> Unit,
) {
    if (posted && isCurrent()) handoff()
}
