package com.ethran.notable.editor.ui.toolbar.model

/**
 * Identifiers for the rail's static elements — the key each is registered under in
 * [ToolbarElements], and how `RailGroups` names the ones it places.
 */
enum class ToolbarElementId {
    /** The toolbar's own open/close control — always rendered first, outside the groups. */
    TOGGLE,

    /**
     * Shared by every pen instance ([PenElement] built from a [ToolbarPen] preset). Pens are
     * identified by preset id, not by this — it is a kind, not a slot, so no pen is
     * registered under it.
     */
    PEN,

    SHAPE, ERASER, SELECT, IMAGE, PASTE, RESET_VIEW,
    UNDO, REDO, PAGE_NAV, QUICK_NAV, HOME, MENU;
    // future: TEXT
}
