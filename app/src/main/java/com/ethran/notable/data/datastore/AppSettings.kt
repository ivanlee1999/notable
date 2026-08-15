package com.ethran.notable.data.datastore

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.unit.Dp
import com.ethran.notable.data.model.PageSize
import com.ethran.notable.data.model.PageSizePreset
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
    // The paper size new notebooks and quick pages are created with, as a PageSizePreset key.
    // Only consulted at creation: from then on the page's own pageWidth/pageHeight decides, on
    // this app and on the iPad alike. Pages created before page sizes existed declare none and
    // keep falling back to the screen width they were written against.
    val defaultPageSizeKey: String = PageSizePreset.DEFAULT.key,
    val quickNavPages: List<String> = listOf(),
    val scribbleToEraseEnabled: Boolean = false,
    val toolbarPosition: Position = Position.Top,
    val smoothScroll: Boolean = true,
    val verticalNavigation: VerticalNavigation = VerticalNavigation.Continuous,
    val continuousZoom: Boolean = false,
    val continuousStrokeSlider: Boolean = false,
    val paginatePdf: Boolean = true,
    // On by default, because [paginatePdf] is. Export splits the infinite canvas at fixed
    // sheet-height intervals, and with nothing drawn there the user writes straight across a page
    // break without knowing one exists — the export is still complete, but a diagram or a line of
    // handwriting comes out cut in half. Showing where the breaks fall costs one hairline per
    // sheet; not showing them costs the user a reprint.
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
     * The sheet new pages get. Falls back to the default rather than throwing if the stored key
     * came from a build with a preset this one does not have.
     */
    val defaultPageSize: PageSize
        get() = (PageSizePreset.entries.firstOrNull { it.key == defaultPageSizeKey }
            ?: PageSizePreset.DEFAULT).size

    enum class GestureAction {
        None, Undo, Redo, PreviousPage, NextPage, ChangeTool, ToggleZen, Select
    }

    /**
     * How a one-finger vertical gesture moves the canvas.
     *
     * [Continuous] follows the finger — the canvas is one long surface and you stop
     * wherever you let go. [Paged] advances exactly one screen per swipe regardless of how
     * far the finger travelled, so a turn is a single full refresh instead of a run of
     * partial ones. On an e-ink panel that is the difference between reading handwriting
     * back cleanly and watching it smear; it also means a line always lands in the same
     * place on screen.
     *
     * Both modes keep the horizontal axis as it was: left/right still turns between the
     * notebook's pages.
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
