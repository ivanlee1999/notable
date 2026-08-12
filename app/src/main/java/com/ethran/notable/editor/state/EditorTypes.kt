package com.ethran.notable.editor.state

/**
 * Drawing mode for the editor.
 */
enum class Mode {
    Draw, Erase, Select, Line
}

/**
 * Shapes the SHAPE tool can draw, all backed by [Mode.Line] — the mode is "drawing a shape", and
 * which shape is a separate choice.
 *
 * Each comes out as one ordinary stroke of interpolated points (see
 * [com.ethran.notable.editor.utils.ShapeGeometry]), which is why nothing downstream needs to know
 * a shape tool was involved: a rectangle drawn here is ink on the BOOX and ink on the iPad, and it
 * erases, moves, exports and syncs like any other.
 */
enum class Shape(val label: String) {
    LINE("Line"),
    RECT("Rectangle"),
    ELLIPSE("Ellipse"),
    ARROW("Arrow"),
}

/**
 * Placement mode for selection operations.
 * If state is Move then applySelectionDisplace() will delete original strokes and images.
 */
enum class PlacementMode {
    Move, Paste
}
