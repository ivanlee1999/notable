package com.ethran.notable.editor.utils

/**
 * How much of the outgoing screen stays visible after a paged step.
 *
 * A turn that moved a clean viewport height would routinely cut a line of handwriting in
 * half across the seam. Keeping a sliver of the previous screen costs a little travel and
 * makes reading back continuous.
 */
const val PAGED_STEP_OVERLAP = 0.06f

/**
 * The scroll delta for one paged step, in screen pixels, in the sign convention
 * `GestureActions.requestScroll` uses — the same sign as the finger movement that would
 * have produced it.
 *
 * [direction] is +1 for the next screen (further down the page) and -1 for the previous
 * one. Advancing means the finger travelled *up*, hence the negated result.
 *
 * The step is a whole viewport rather than the distance the finger actually covered: in
 * paged mode a flick and a long drag mean the same thing, one turn.
 */
fun pagedScrollDelta(viewportHeightPx: Int, direction: Int): Float {
    if (direction == 0 || viewportHeightPx <= 0) return 0f
    val step = viewportHeightPx * (1f - PAGED_STEP_OVERLAP)
    return if (direction > 0) -step else step
}
