package com.ethran.notable.data.datastore

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.unit.Dp
import com.ethran.notable.data.model.PageSize
import com.ethran.notable.data.model.PageSizePreset
import com.ethran.notable.editor.ui.toolbar.model.ERASER_BASE_WIDTH
import com.ethran.notable.editor.ui.toolbar.model.NibWidth
import com.ethran.notable.editor.ui.toolbar.model.ToolbarPen
import com.ethran.notable.ui.theme.KALEIDO_WIDE_BREAKPOINT
import com.ethran.notable.ui.theme.kaleidoMetrics
import kotlinx.serialization.Serializable


// Define the target page size (A4 in points: 595 x 842)
const val A4_WIDTH = 595
const val A4_HEIGHT = 842
const val BUTTON_SIZE = 37

/**
 * The docked rail measured across its short edge — one button plus the rule that closes it.
 * Height when the rail is horizontal, width when it is vertical.
 */
const val TOOLBAR_THICKNESS = BUTTON_SIZE + 3


object GlobalAppSettings {
    private val _current = mutableStateOf(AppSettings(version = 1))
    val current: AppSettings
        get() = _current.value

    /**
     * Updates the globally observed settings. This is a Compose snapshot state read all over the UI
     * during composition, yet [update] is called from background coroutines (e.g. when persisting
     * settings on Dispatchers.IO). Writing the snapshot state directly from a non-composition thread
     * can race the recomposer and throw "Unsupported concurrent change during composition", so the
     * write is committed inside its own global mutable snapshot.
     */
    fun update(settings: AppSettings) {
        Snapshot.withMutableSnapshot {
            _current.value = settings
        }
    }
}

@Serializable
data class AppSettings(
    // General
    val version: Int,
    val monitorBgFiles: Boolean = false,
    val defaultNativeTemplate: String = "blank",
    // The paper size new notebooks are created with, as a PageSizePreset key.
    // Only consulted at creation: from then on the page's own pageWidth/pageHeight decides, on
    // this app and on the iPad alike. Pages created before page sizes existed declare none and
    // keep falling back to the screen width they were written against.
    val defaultPageSizeKey: String = PageSizePreset.DEFAULT.key,
    val quickNavPages: List<String> = listOf(),
    val scribbleToEraseEnabled: Boolean = false,
    // How broad the eraser is, as a [NibWidth] key. The eraser was the one implement with a
    // single fixed size — a rubber the size of a nib takes all day to clear a paragraph, and
    // there was no way to ask for a bigger one. Defaults to the step whose width is the swath
    // it always deleted, so an eraser nobody has touched behaves exactly as it did.
    val eraserWidth: String = NibWidth.MEDIUM.key,
    val toolbarPosition: Position = Position.Top,
    val smoothScroll: Boolean = true,
    val verticalNavigation: VerticalNavigation = VerticalNavigation.Continuous,
    // Kept only so older settings blobs continue to decode and round-trip. Navigation is
    // owned entirely by [verticalNavigation]: scrolling in Continuous, swiping in Paged.
    val pageTurn: PageTurn = PageTurn.Vertical,
    val continuousZoom: Boolean = false,
    val continuousStrokeSlider: Boolean = false,
    val paginatePdf: Boolean = true,
    // Kept only so older settings blobs continue to decode and round-trip, like [pageTurn].
    // It toggled the on-screen "Subpage" break markers of the endless canvas; every page is one
    // bounded sheet now, so there is no break inside a page left to mark.
    val visualizePdfPagination: Boolean = true,
    // How the library is arranged. A preference about the library rather than about this visit to
    // it: coming back to a differently-ordered shelf is its own small confusion. Stored as the
    // enum's name so an unknown value from a newer build falls back rather than crashing.
    val librarySortOrder: String = "UPDATED",
    val librarySortDescending: Boolean = true,
    // User-created pen instances. The preset is the single source of truth for a pen's
    // color/size — the rail's nib dots, its ink strip and StrokeMenu all write back here.
    // A settings blob written before the rail became fixed still carries a "toolbarLayout"
    // key; KvProxy decodes with ignoreUnknownKeys, so it is simply dropped on read.
    val toolbarPens: List<ToolbarPen> = ToolbarPen.DEFAULT_PENS,

    // Gestures
    val doubleTapAction: GestureAction = GestureAction.Undo,
    val twoFingerTapAction: GestureAction = GestureAction.ChangeTool,
    val swipeLeftAction: GestureAction = GestureAction.NextPage,
    val swipeRightAction: GestureAction = GestureAction.PreviousPage,
    // Fired by *three*-finger swipes (two fingers are pan/zoom); the field
    // names are kept for persisted-settings compatibility.
    val twoFingerSwipeLeftAction: GestureAction = GestureAction.ToggleZen,
    val twoFingerSwipeRightAction: GestureAction = GestureAction.ToggleZen,
    val holdAction: GestureAction = GestureAction.Select,
    val enableQuickNav: Boolean = true,
    // Onyx only: broadcast onyx.action.INTERCEPT_GESTURE while the app is resumed so
    // SystemUI's three-finger screenshot cannot steal our multi-finger gestures. The
    // same SystemUI pipeline serves the side/bottom edge navigation swipes, so those
    // stop working inside the app while this is on — hence opt-in.
    val blockSystemGestures: Boolean = false,
    val renameOnCreate: Boolean = true,

    // Debug
    val showWelcome: Boolean = true,
    // [system information -- does not have a setting]
    val debugMode: Boolean = false,
    val simpleRendering: Boolean = false,
    val openGLRendering: Boolean = true,
    val muPdfRendering: Boolean = true,
    val destructiveMigrations: Boolean = false,

    ) {
    /**
     * The swath the eraser deletes, in page units — [eraserWidth] resolved against the eraser's
     * own ramp base. Read at the point of erasing rather than threaded through the input path,
     * the same way [scribbleToEraseEnabled] is.
     */
    val eraserSwathWidth: Float
        get() = NibWidth.fromKeyOrDefault(eraserWidth).sizeFor(ERASER_BASE_WIDTH)

    /**
     * The sheet new pages get. Falls back to the default rather than throwing if the stored key
     * came from a build with a preset this one does not have.
     */
    val defaultPageSize: PageSize
        get() = (PageSizePreset.entries.firstOrNull { it.key == defaultPageSizeKey }
            ?: PageSizePreset.DEFAULT).size

    enum class GestureAction {
        None, Undo, Redo, PreviousPage, NextPage, ChangeTool, ToggleZen, Select
    }

    /** Legacy persisted value; no longer shown or consulted. */
    enum class PageTurn {
        /** Pull past the bottom edge to turn. */
        Vertical,
        /** Swipe sideways to turn. */
        Horizontal;

        val isVertical: Boolean get() = this == Vertical
    }

    /**
     * How the notebook is navigated. One axis, one meaning, per mode — the separation every
     * major note app enforces (GoodNotes documents that sideways swipes are dead in its
     * vertical-scrolling mode; Notability's Seamless gives horizontal no navigation role).
     *
     * [Continuous]: **scrolling is the only navigation.** The notebook is one long surface;
     * scrolling follows the finger and flows across the seam between pages, and scrolling or
     * writing past the last page's end grows the notebook by a page — appended under the seam,
     * never jumped to. Sideways swipes do not turn pages here; the two metaphors on one sheet
     * of paper is exactly what produced duplicate blank pages and a view that disagreed with
     * itself.
     *
     * [Paged]: **swiping is the only navigation.** One whole sheet is fitted per screen; a
     * swipe (sideways, or vertical) turns exactly one page, and swiping forward past the last
     * page creates the next one. A turn is a single full refresh — on an e-ink panel that is
     * the difference between reading handwriting back cleanly and watching it smear.
     */
    enum class VerticalNavigation {
        Continuous, Paged;

        val isPaged: Boolean get() = this == Paged
    }

    /**
     * Which edge the tool rail is docked to. Left/Right turn it into a vertical rail —
     * the tablet arrangement, where the rail sits within thumb reach of the bezel and the
     * writing area keeps its full height.
     *
     * The rail is always docked. It never floats and never moves under a drag: a moving
     * overlay costs a full-screen e-ink refresh.
     */
    enum class Position {
        Top, Bottom, Left, Right;

        val isVertical: Boolean get() = this == Left || this == Right
    }
}

/**
 * The edge a fresh install docks the tool rail to, given the display it is being installed on.
 *
 * On a one-handed device — a Palma-class panel, the same step [kaleidoMetrics] drops the
 * Library to a single column at — the rail belongs at the bottom, where a thumb already is.
 * A vertical rail would spend a fifth of a 6" screen's width on chrome, and a top one puts
 * every tool at the far end of the reach.
 *
 * Only the starting point. Once a position is stored it is the user's, and this is never
 * consulted again — including on an install that predates it.
 */
fun defaultToolbarPosition(screenWidth: Dp): AppSettings.Position =
    if (screenWidth < KALEIDO_WIDE_BREAKPOINT) AppSettings.Position.Bottom
    else AppSettings.Position.Top
