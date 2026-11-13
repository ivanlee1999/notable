package com.ethran.notable.editor.canvas

/** The firmware channel is shared; retiring an old surface must not disable its successor. */
internal class RawDrawingOwner {
    private var current: Any? = null
    fun claim(owner: Any) { current = owner }
    fun isOwner(owner: Any): Boolean = current === owner
    fun release(owner: Any, close: () -> Unit) {
        if (!isOwner(owner)) return
        current = null
        close()
    }
}

internal val rawDrawingOwner = RawDrawingOwner()
