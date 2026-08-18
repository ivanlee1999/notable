package com.ethran.notable.editor.drawing

/**
 * The source-row arithmetic for drawing a background bitmap under a scrolled page, free of
 * android imports so the rule is testable on the JVM.
 *
 * A background bitmap covers `imageHeightPx · scaleFactor` page units from the top of the page
 * ([scaleFactor] is page units per bitmap pixel — `sheetWidth / imageWidth`). Scrolling down means
 * starting the draw further into the bitmap; what happens when the scroll runs *past* the bitmap
 * is what this decides. A repeating background wraps and keeps tiling. A non-repeating one is
 * paper: past its last row there is nothing, and the old unconditional modulo silently wrapped it
 * — scroll far enough down any page with an image background and the image started over.
 */
object BackgroundGeometry {

    /**
     * The bitmap row the visible background starts at, in bitmap pixels — or null when the scroll
     * is past a non-repeating background's last row and there is nothing left to draw.
     */
    fun sourceTopPx(
        scrollY: Float,
        scaleFactor: Float,
        imageHeightPx: Int,
        repeat: Boolean,
    ): Float? {
        if (scaleFactor <= 0f || imageHeightPx <= 0) return null
        val topPx = (scrollY / scaleFactor).coerceAtLeast(0f)
        return when {
            repeat -> topPx % imageHeightPx
            topPx < imageHeightPx -> topPx
            else -> null
        }
    }
}
