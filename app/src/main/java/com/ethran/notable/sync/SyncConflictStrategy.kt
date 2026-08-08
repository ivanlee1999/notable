package com.ethran.notable.sync

import kotlinx.serialization.Serializable

/**
 * How the sync engine resolves a *genuine* page conflict — a page edited both locally and on the
 * server since the last common sync (see [NotebookSyncPlanner.Reconcile] and
 * [NotebookSyncService.detectPageConflicts]).
 *
 * Independent edits on *different* pages are not conflicts and are always merged losslessly,
 * regardless of this setting. This only governs the same-page collision, where the two versions
 * cannot be combined and one policy has to decide.
 *
 * [ASK] is the only implemented strategy and the safe default: it never destroys either version —
 * it flags the notebook and lets the user pick per page. The other values are the intended future
 * automatic strategies (mirroring [com.ethran.notable.io.ImportConflictStrategy]); they are declared
 * and persisted in [SyncSettings] so the future feature has a stable place to hook in, but the sync
 * engine ignores the setting entirely today — it always flags. Nothing is threaded through the
 * reconcile path until a strategy is actually implemented.
 */
@Serializable
enum class SyncConflictStrategy {
    /** Flag the conflict and let the user resolve each page — never auto-overwrites. */
    ASK,

    /** Future: take the server copy automatically. */
    SERVER_WINS,

    /** Future: keep the local copy automatically. */
    LOCAL_WINS,
}

/**
 * The per-page choice a user makes for an [SyncConflictStrategy.ASK] conflict. Applied by
 * [NotebookSyncService.resolveConflictedPage] as a cheap re-baseline of the page's sync row; the
 * next sync then performs the actual transfer through the normal reconcile path.
 */
enum class PageConflictResolution {
    /** Leave the page diverged for now; it stays flagged and is asked again next sync. */
    SKIP,

    /** Overwrite the local page with the server copy on the next sync. */
    REPLACE_WITH_SERVER,

    /** Overwrite the server copy with the local page on the next sync. */
    UPLOAD_DB,
}

/**
 * One page edited both locally and on the server. [pageNumber] is the page's 1-based position in the
 * notebook, shown to the user so the conflict names something more meaningful than a raw id.
 */
data class PageConflict(
    val pageId: String,
    val pageNumber: Int,
)

/**
 * The full conflict picture for one notebook, surfaced to the resolution UI.
 *
 * @property pageConflicts pages edited both locally and on the server — each resolved individually
 *   via [PageConflictResolution].
 * @property structural whether the two manifests disagree on structure/metadata (page set, order,
 *   title, folder, or defaults), which cannot be merged per page and is resolved whole-notebook via
 *   [NotebookConflictResolution].
 */
data class NotebookConflict(
    val pageConflicts: List<PageConflict>,
    val structural: Boolean,
) {
    val isEmpty: Boolean get() = pageConflicts.isEmpty() && !structural
}

/**
 * The whole-notebook choice a user makes for a structural conflict, where one manifest has to win
 * outright because page set / ordering / title / open page diverged and cannot be combined.
 */
enum class NotebookConflictResolution {
    /** Keep the local notebook and its structure; the next sync overwrites the server copy. */
    KEEP_LOCAL,

    /** Take the server notebook and its structure; the next sync overwrites the local copy. */
    TAKE_SERVER,
}
