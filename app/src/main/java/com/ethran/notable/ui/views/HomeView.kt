package com.ethran.notable.ui.views

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.Icon
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.ethran.notable.R
import com.ethran.notable.data.AppRepository
import com.ethran.notable.data.datastore.GlobalAppSettings
import com.ethran.notable.data.db.Folder
import com.ethran.notable.data.db.Notebook
import com.ethran.notable.editor.EditorDestination
import com.ethran.notable.editor.utils.autoEInkAnimationOnScroll
import com.ethran.notable.io.ExportEngine
import com.ethran.notable.navigation.NavigationDestination
import com.ethran.notable.sync.SyncScheduler
import com.ethran.notable.ui.LocalSnackContext
import com.ethran.notable.ui.SnackConf
import com.ethran.notable.ui.messageRes
import com.ethran.notable.ui.rememberKvProxy
import com.ethran.notable.ui.requestFullSync
import com.ethran.notable.ui.components.CoverActionTile
import com.ethran.notable.ui.components.FILE_BAR_WIDTH
import com.ethran.notable.ui.components.Kicker
import com.ethran.notable.ui.components.LibraryFileBar
import com.ethran.notable.ui.components.ListRow
import com.ethran.notable.ui.components.NotebookCoverCard
import com.ethran.notable.ui.components.NotebookListRow
import com.ethran.notable.ui.components.SectionHeader
import com.ethran.notable.ui.components.ShowPagesRow
import com.ethran.notable.ui.components.SquareButton
import com.ethran.notable.ui.dialogs.ConflictResolutionDialog
import com.ethran.notable.ui.dialogs.EmptyBookWarningHandler
import com.ethran.notable.ui.dialogs.FolderConfigDialog
import com.ethran.notable.ui.dialogs.NamePromptDialog
import com.ethran.notable.ui.dialogs.NewNotebookDialog
import com.ethran.notable.ui.dialogs.NotebookConfigDialog
import com.ethran.notable.ui.dialogs.PdfImportChoiceDialog
import com.ethran.notable.ui.noRippleClickable
import com.ethran.notable.ui.theme.Kaleido
import com.ethran.notable.ui.theme.KaleidoMetrics
import com.ethran.notable.ui.theme.coverColumnsForShelf
import com.ethran.notable.ui.theme.kaleidoMetrics
import com.ethran.notable.ui.viewmodels.LibrarySort
import com.ethran.notable.ui.viewmodels.LibrarySortOrder
import com.ethran.notable.ui.viewmodels.LibraryUiState
import com.ethran.notable.ui.viewmodels.LibraryViewModel
import com.ethran.notable.sync.SyncBadge
import compose.icons.FeatherIcons
import compose.icons.feathericons.FilePlus
import compose.icons.feathericons.FolderPlus
import compose.icons.feathericons.MoreVertical
import compose.icons.feathericons.Plus
import compose.icons.feathericons.RefreshCw
import compose.icons.feathericons.Search
import compose.icons.feathericons.Settings
import compose.icons.feathericons.Trash2
import compose.icons.feathericons.Upload
import compose.icons.feathericons.X
import compose.icons.feathericons.Zap
import io.shipbook.shipbooksdk.ShipBook
import kotlinx.coroutines.launch


object LibraryDestination : NavigationDestination {
    override val route = "library"
    const val FOLDER_ID_ARG = "folderId"
    val routeWithArgs = "$route?$FOLDER_ID_ARG={$FOLDER_ID_ARG}"
    fun createRoute(folderId: String? = null): String {
        return if (folderId != null) "$route?$FOLDER_ID_ARG=$folderId" else route
    }
}

private val log = ShipBook.getLogger("HomeView")

@Composable
fun Library(
    navController: NavController,
    folderId: String? = null,
    goToPage: (String) -> Unit = {},
    onCreateNewNote: (String?) -> Unit = {},
    viewModel: LibraryViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(folderId) {
        viewModel.loadFolder(folderId)
    }

    // Naming happens before the row exists, not after. Asking first costs one dialog refresh;
    // creating first and renaming after costs two, and in between the library shows an item under
    // a name the user did not choose.
    var pendingNewFolder by remember { mutableStateOf(false) }
    var pendingNewNotebook by remember { mutableStateOf(false) }

    val defaultFolderName = stringResource(R.string.home_new_folder)
    val defaultNotebookName = stringResource(R.string.home_new_notebook)

    if (pendingNewFolder) {
        NamePromptDialog(
            title = stringResource(R.string.name_prompt_folder_title),
            initialValue = defaultFolderName,
            onConfirm = { name ->
                pendingNewFolder = false
                viewModel.createNewFolder(name)
            },
            onDismiss = { pendingNewFolder = false }
        )
    }

    // A notebook is asked more than a folder is: a folder has nothing to configure, while a
    // notebook's sheet is fixed the moment its first page exists and cannot be changed afterwards.
    if (pendingNewNotebook) {
        val settings = GlobalAppSettings.current
        NewNotebookDialog(
            initialName = defaultNotebookName,
            initialPageSize = settings.defaultPageSize,
            initialTemplate = settings.defaultNativeTemplate,
            onConfirm = { name, pageSize, template, templateType ->
                pendingNewNotebook = false
                viewModel.onCreateNewNotebook(name, pageSize, template, templateType)
            },
            onDismiss = { pendingNewNotebook = false }
        )
    }

    // Sync is asked for from the composable rather than the view model for the same reason the
    // per-notebook "Sync now" is: the answer worth showing is a string about a backend, not screen
    // state — see [requestFullSync].
    val kvProxy = rememberKvProxy()
    val snackState = LocalSnackContext.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    LibraryContent(
        appRepository = viewModel.appRepository,
        exportEngine = viewModel.exportEngine,
        syncScheduler = viewModel.syncScheduler,
        uiState = uiState,
        onNavigateToFolder = { id -> navController.navigate(LibraryDestination.createRoute(id)) },
        onNavigateToSettings = { navController.navigate("settings") },
        onNavigateToTrash = { navController.navigate(TrashDestination.route) },
        onQueryChanged = viewModel::onQueryChanged,
        onSortChanged = viewModel::onSortChanged,
        onSyncNow = {
            scope.launch {
                val outcome = requestFullSync(kvProxy, viewModel.syncScheduler)
                snackState.showOrUpdateSnack(
                    SnackConf(text = context.getString(outcome.messageRes()), duration = 3000)
                )
            }
        },
        onNavigateToEditor = { pageId, bookId ->
            navController.navigate(EditorDestination.createRoute(pageId, bookId))
        },
        goToPage = goToPage,
        onCreateNewNote = { onCreateNewNote(uiState.folderId) },
        // The prompt is a preference, not a requirement: with it off, creation stays a single tap
        // and the item is named from the long-press menu if and when the user cares.
        onCreateNewFolder = {
            if (GlobalAppSettings.current.renameOnCreate) pendingNewFolder = true
            else viewModel.createNewFolder(defaultFolderName)
        },
        onDeleteEmptyBook = viewModel::deleteEmptyBook,
        onCreateNewNotebook = {
            if (GlobalAppSettings.current.renameOnCreate) pendingNewNotebook = true
            else viewModel.onCreateNewNotebook(defaultNotebookName)
        },
        onImportPdf = viewModel::onPdfFile,
        onImportXopp = viewModel::onXoppFile,
        onPreviewNeeded = viewModel::onPreviewRequested
    )
}


/**
 * The Library, drawn for a colour e-ink panel.
 *
 * One scrolling column of sections, each opened by a kicker over a 2px rule. Folders are
 * full-width rows with a saturated chip; notebooks are covers on a three-up grid, or — on a
 * one-handed device — the same rows with the cover shrunk to a chip. Nothing floats and
 * nothing depends on hover: an overlay that moves costs a full-screen refresh.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
fun LibraryContent(
    appRepository: AppRepository,
    exportEngine: ExportEngine,
    syncScheduler: SyncScheduler,
    uiState: LibraryUiState,
    onNavigateToFolder: (String?) -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToTrash: () -> Unit,
    onQueryChanged: (String) -> Unit,
    onSortChanged: (LibrarySortOrder, Boolean) -> Unit,
    onSyncNow: () -> Unit,
    onNavigateToEditor: (String, String) -> Unit,
    goToPage: (String) -> Unit,
    onCreateNewNote: () -> Unit,
    onCreateNewFolder: () -> Unit,
    onDeleteEmptyBook: (String) -> Unit,
    onCreateNewNotebook: () -> Unit,
    onImportPdf: (Uri, Boolean) -> Unit,
    onImportXopp: (Uri) -> Unit,
    onPreviewNeeded: (String) -> Unit
) {
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(Kaleido.Paper)
    ) {
        val screen = kaleidoMetrics(maxWidth)
        val pickImportFile = rememberImportPicker(onImportPdf, onImportXopp)

        // The file bar takes its width off the shelf, so the covers have to be measured against
        // what is left rather than against the panel. Sized against the panel they stayed at
        // three columns and simply got narrower, which is the one thing the cover width is
        // supposed to be fixed against.
        val shelfWidth = if (screen.wide) maxWidth - FILE_BAR_WIDTH - Kaleido.SectionRule
        else maxWidth
        val metrics =
            if (screen.wide) screen.copy(coverColumns = coverColumnsForShelf(shelfWidth))
            else screen

        // Sorted here rather than in the view model: the order is a snapshot-state setting, and
        // reading it during composition is what makes a change to it redraw the shelf.
        val settings = GlobalAppSettings.current
        val sortOrder = LibrarySortOrder.fromKeyOrDefault(settings.librarySortOrder)
        val sortedFolders = remember(uiState.folders, sortOrder, settings.librarySortDescending) {
            LibrarySort.folders(uiState.folders, sortOrder, settings.librarySortDescending)
        }
        val sortedBooks = remember(
            uiState.books, sortOrder, settings.librarySortDescending, uiState.lastEdited
        ) {
            LibrarySort.notebooks(
                uiState.books, sortOrder, settings.librarySortDescending, uiState.lastEdited
            )
        }

        Row(Modifier.fillMaxSize()) {
            // Only on a screen with the room: a one-handed device has one column's worth and
            // spends it on the shelf.
            if (screen.wide) LibraryFileBar(
                tree = uiState.tree,
                selectedFolderId = uiState.folderId,
                syncBadges = uiState.syncBadges,
                isSyncing = uiState.isSyncing,
                onSelectFolder = onNavigateToFolder,
                onOpenNotebook = { book ->
                    // A book with no page has nothing to open — the empty-import leftover the
                    // shelf warns about. Silently doing nothing beats crashing on `first()`.
                    val page = book.openPageId ?: book.pageIds.firstOrNull()
                    if (page != null) onNavigateToEditor(page, book.id)
                },
                onSyncNow = onSyncNow,
            )

        Column(Modifier.fillMaxSize()) {
            LibraryHeader(
                metrics = metrics,
                uiState = uiState,
                onNavigateToFolder = onNavigateToFolder,
                onNavigateToSettings = onNavigateToSettings,
                onSyncNow = onSyncNow,
                onCreateNewNotebook = onCreateNewNotebook,
                onCreateNewNote = onCreateNewNote,
                onImport = pickImportFile,
                onQueryChanged = onQueryChanged,
                onSortChanged = onSortChanged,
            )

            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .autoEInkAnimationOnScroll(),
                contentPadding = PaddingValues(
                    start = metrics.pad, end = metrics.pad,
                    top = metrics.pad, bottom = metrics.pad * 2
                )
            ) {
                item(key = "folders-header") {
                    SectionHeader(
                        stringResource(
                            if (uiState.isSearching) R.string.home_folders_found
                            else R.string.home_folders
                        )
                    )
                    Spacer(Modifier.height(12.dp))
                }
                items(sortedFolders, key = { "folder-${it.id}" }) { folder ->
                    FolderRow(
                        appRepository = appRepository,
                        folder = folder,
                        metrics = metrics,
                        bookCount = uiState.folderBookCounts[folder.id] ?: 0,
                        onOpen = { onNavigateToFolder(folder.id) },
                    )
                }
                if (!uiState.isSearching) item(key = "folder-add") {
                    ListRow(
                        hit = metrics.hit,
                        label = stringResource(R.string.home_add_new_folder),
                        onClick = onCreateNewFolder,
                        showChevron = false,
                        leading = {
                            Box(
                                Modifier
                                    .size(22.dp)
                                    .border(1.dp, Kaleido.Edge),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    FeatherIcons.FolderPlus, null,
                                    tint = Kaleido.Ink, modifier = Modifier.size(14.dp)
                                )
                            }
                        }
                    )
                }

                // Only once something is in it. A permanently visible Trash row is a permanent
                // reminder of a screen almost nobody needs; a row that appears the moment
                // something is deleted is how the user finds out deletion was recoverable at all.
                if (uiState.trashedCount > 0 && !uiState.isSearching) {
                    item(key = "trash-row") {
                        ListRow(
                            hit = metrics.hit,
                            label = stringResource(R.string.home_trash),
                            trailing = uiState.trashedCount.toString(),
                            onClick = onNavigateToTrash,
                            leading = {
                                Box(
                                    Modifier
                                        .size(22.dp)
                                        .border(1.dp, Kaleido.Edge),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        FeatherIcons.Trash2, null,
                                        tint = Kaleido.Ink, modifier = Modifier.size(14.dp)
                                    )
                                }
                            }
                        )
                    }
                }

                item(key = "books-header") {
                    Spacer(Modifier.height(22.dp))
                    SectionHeader(
                        stringResource(
                            if (uiState.isSearching) R.string.home_notebooks_found
                            else R.string.home_notebooks
                        )
                    )
                    Spacer(Modifier.height(14.dp))
                }

                // An empty notebook is a leftover from a failed import; warn once rather than
                // drawing a cover for a book with no page to preview.
                val (drawable, empty) = sortedBooks.partition { it.pageIds.isNotEmpty() }

                if (!uiState.isImporting) {
                    items(empty, key = { "empty-${it.id}" }) { book ->
                        EmptyBookWarningHandler(
                            emptyBook = book,
                            onDelete = { onDeleteEmptyBook(book.id) },
                            onDismiss = { })
                    }
                }

                if (metrics.wide) {
                    // Chunked into fixed-width rows rather than a nested lazy grid: the page is
                    // one scroll region, and the covers per row is a design constant, not a
                    // measured fit. The trailing null is the import tile, so it takes the next
                    // free cell instead of needing a row of its own.
                    // No trailing null any more: the import tile used to take the next free
                    // cell, and a shelf's last row is not where a once-a-year action belongs.
                    val rows = drawable.chunked(metrics.coverColumns)
                    itemsIndexed(rows) { index, row ->
                        NotebookRow(
                            books = row,
                            columns = metrics.coverColumns,
                            appRepository = appRepository,
                            exportEngine = exportEngine,
                            syncScheduler = syncScheduler,
                            syncBadges = uiState.syncBadges,
                            onNavigateToEditor = onNavigateToEditor,
                            onPreviewNeeded = onPreviewNeeded,
                        )
                        if (index != rows.lastIndex) Spacer(Modifier.height(18.dp))
                    }
                } else {
                    items(drawable, key = { "book-${it.id}" }) { book ->
                        NotebookEntry(
                            book = book,
                            compactHit = metrics.hit,
                            appRepository = appRepository,
                            exportEngine = exportEngine,
                            syncScheduler = syncScheduler,
                            syncBadge = uiState.syncBadges[book.id],
                            onNavigateToEditor = onNavigateToEditor,
                            onPreviewNeeded = onPreviewNeeded,
                        )
                    }
                }
            }

            if (metrics.narrow) {
                LibraryBottomBar(
                    onCreateNewNotebook = onCreateNewNotebook,
                    onCreateNewNote = onCreateNewNote,
                )
            }
        }
        }
    }
}

/**
 * Kicker path over the screen's title, then the three square actions: sync and settings
 * outlined, new notebook filled. Closed by the 2px rule that every section repeats.
 */
@Composable
private fun LibraryHeader(
    metrics: KaleidoMetrics,
    uiState: LibraryUiState,
    onNavigateToFolder: (String?) -> Unit,
    onNavigateToSettings: () -> Unit,
    onSyncNow: () -> Unit,
    onCreateNewNotebook: () -> Unit,
    onCreateNewNote: () -> Unit,
    onImport: () -> Unit,
    onQueryChanged: (String) -> Unit = {},
    onSortChanged: (LibrarySortOrder, Boolean) -> Unit = { _, _ -> },
) {
    val root = stringResource(R.string.home_view_name)
    var isOverflowOpen by remember { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = metrics.pad, end = metrics.pad, top = metrics.pad, bottom = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Kicker(root, Modifier.noRippleClickable { onNavigateToFolder(null) })
                    // Every ancestor but the current folder stays reachable; the current one
                    // is the title below.
                    uiState.breadcrumbFolders.dropLast(1).forEach { folder ->
                        Kicker("/")
                        Kicker(
                            folder.title,
                            Modifier.noRippleClickable { onNavigateToFolder(folder.id) })
                    }
                }
                Text(
                    text = uiState.breadcrumbFolders.lastOrNull()?.title
                        ?: stringResource(R.string.home_all_notes),
                    fontSize = metrics.titleSize,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = (-0.9).sp,
                    color = Kaleido.Ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(10.dp))
            // Sync fills while a run is in flight — the header is where you look to ask "is my
            // writing on the server yet?", and the per-notebook badges only answer it one book at
            // a time. It stays tappable while filled: the scheduler replaces an in-flight run
            // rather than dropping the tap, so asking again always carries the latest edit.
            SquareButton(
                hit = metrics.hit,
                onClick = onSyncNow,
                filled = uiState.isSyncing,
            ) {
                Icon(
                    FeatherIcons.RefreshCw, stringResource(R.string.sync_now),
                    tint = if (uiState.isSyncing) Kaleido.Paper else Kaleido.Ink,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(Modifier.width(8.dp))
            Box {
                SquareButton(hit = metrics.hit, onClick = onNavigateToSettings) {
                    Icon(
                        FeatherIcons.Settings, stringResource(R.string.settings_title),
                        tint = Kaleido.Ink, modifier = Modifier.size(20.dp)
                    )
                }
                // An update is worth a saturated fill: it is the one thing on this screen the
                // user cannot discover any other way.
                if (!uiState.isLatestVersion) {
                    Box(
                        Modifier
                            .align(Alignment.TopEnd)
                            .size(10.dp)
                            .background(Kaleido.Red)
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            // Import lives here rather than in the shelf. It was a permanent row above the first
            // notebook — and a permanent tile in the last grid cell — for something a library
            // does once or twice in its life; the shelf is for what you have, not for how it got
            // there.
            Box {
                SquareButton(
                    hit = metrics.hit,
                    onClick = { isOverflowOpen = true },
                    filled = isOverflowOpen,
                ) {
                    Icon(
                        FeatherIcons.MoreVertical, stringResource(R.string.home_more_actions),
                        tint = if (isOverflowOpen) Kaleido.Paper else Kaleido.Ink,
                        modifier = Modifier.size(20.dp)
                    )
                }
                if (isOverflowOpen) LibraryOverflowMenu(
                    onImport = {
                        isOverflowOpen = false
                        onImport()
                    },
                    onDismiss = { isOverflowOpen = false },
                )
            }
            Spacer(Modifier.width(8.dp))
            SquareButton(hit = metrics.hit, onClick = onCreateNewNotebook) {
                Icon(
                    FeatherIcons.Plus, stringResource(R.string.home_new_notebook),
                    tint = Kaleido.Ink, modifier = Modifier.size(22.dp)
                )
            }
            Spacer(Modifier.width(8.dp))
            // The filled one, because capture is the action this screen exists to get out of the
            // way. The bottom bar repeats it on a one-handed device for the same reason it repeats
            // `+`: this corner is what a single hand cannot reach.
            SquareButton(hit = metrics.hit, onClick = onCreateNewNote, filled = true) {
                Icon(
                    FeatherIcons.Zap, stringResource(R.string.home_new_note),
                    tint = Kaleido.Paper, modifier = Modifier.size(22.dp)
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        LibrarySearchRow(
            metrics = metrics,
            query = uiState.query,
            onQueryChanged = onQueryChanged,
            onSortChanged = onSortChanged,
        )
        Spacer(Modifier.height(10.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(Kaleido.SectionRule)
                .background(Kaleido.Ink)
        )
    }
}

/**
 * Search and sort, on one line under the title.
 *
 * Both are about *finding* something, and neither is worth a screen of its own: the library used
 * to offer no way to look for a notebook by name, and no order but the database's own reversed —
 * which is neither "recent" nor "alphabetical" but an artefact.
 */
@Composable
private fun LibrarySearchRow(
    metrics: KaleidoMetrics,
    query: String,
    onQueryChanged: (String) -> Unit,
    onSortChanged: (LibrarySortOrder, Boolean) -> Unit,
) {
    val settings = GlobalAppSettings.current
    val order = LibrarySortOrder.fromKeyOrDefault(settings.librarySortOrder)
    var isSortMenuOpen by remember { mutableStateOf(false) }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier
                .weight(1f)
                .height(metrics.hit)
                .border(1.dp, Kaleido.Edge)
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                FeatherIcons.Search, null,
                tint = Kaleido.Muted, modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.width(8.dp))
            BasicTextField(
                value = query,
                onValueChange = onQueryChanged,
                singleLine = true,
                textStyle = TextStyle(fontSize = 14.sp, color = Kaleido.Ink),
                cursorBrush = SolidColor(Kaleido.Ink),
                modifier = Modifier.weight(1f),
                decorationBox = { inner ->
                    if (query.isEmpty()) {
                        Text(
                            stringResource(R.string.home_search_hint),
                            fontSize = 14.sp, color = Kaleido.Muted, maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    inner()
                }
            )
            if (query.isNotEmpty()) {
                Icon(
                    FeatherIcons.X, stringResource(R.string.home_search_clear),
                    tint = Kaleido.Ink,
                    modifier = Modifier
                        .size(18.dp)
                        .noRippleClickable { onQueryChanged("") }
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Box {
            Row(
                Modifier
                    .height(metrics.hit)
                    .border(1.dp, Kaleido.Ink)
                    .padding(horizontal = 10.dp)
                    .noRippleClickable { isSortMenuOpen = true },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(order.label, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                    color = Kaleido.Ink, maxLines = 1)
            }
            if (isSortMenuOpen) {
                LibrarySortMenu(
                    order = order,
                    descending = settings.librarySortDescending,
                    onPick = { picked, descending ->
                        isSortMenuOpen = false
                        onSortChanged(picked, descending)
                    },
                    onDismiss = { isSortMenuOpen = false }
                )
            }
        }
    }
}

/**
 * The header's overflow: what the library can do that is not about a particular notebook.
 *
 * One entry today. It is a menu rather than a fifth square because the header is already four
 * squares wide and a one-handed panel has no room for a fifth beside a title.
 */
@Composable
private fun LibraryOverflowMenu(
    onImport: () -> Unit,
    onDismiss: () -> Unit,
) {
    Popup(
        alignment = Alignment.TopEnd,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true)
    ) {
        Column(
            Modifier
                .border(Kaleido.SectionRule, Kaleido.Ink)
                .background(Kaleido.Paper)
                .width(IntrinsicSize.Max)
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .noRippleClickable(onImport)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    FeatherIcons.Upload, null,
                    tint = Kaleido.Ink, modifier = Modifier.size(16.dp)
                )
                Text(
                    text = stringResource(R.string.home_import_notebook),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Kaleido.Ink,
                    maxLines = 1,
                )
            }
        }
    }
}

/** The sort choices, as a popup rather than a screen: it is one decision with six answers. */
@Composable
private fun LibrarySortMenu(
    order: LibrarySortOrder,
    descending: Boolean,
    onPick: (LibrarySortOrder, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    Popup(
        alignment = Alignment.TopEnd,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true)
    ) {
        Column(
            Modifier
                .border(1.dp, Kaleido.Ink)
                .background(Kaleido.Paper)
                .width(IntrinsicSize.Max)
        ) {
            LibrarySortOrder.entries.forEach { candidate ->
                // Picking the order you are already on flips the direction, which is the one
                // gesture every list in every app has agreed on.
                val selected = candidate == order
                Text(
                    text = buildString {
                        append(if (selected) "• " else "  ")
                        append(candidate.label)
                        if (selected) append(if (descending) "  ↓" else "  ↑")
                    },
                    fontSize = 14.sp,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                    color = Kaleido.Ink,
                    modifier = Modifier
                        .fillMaxWidth()
                        .noRippleClickable {
                            onPick(candidate, if (selected) !descending else true)
                        }
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                )
            }
        }
    }
}

@Composable
private fun FolderRow(
    appRepository: AppRepository,
    folder: Folder,
    metrics: KaleidoMetrics,
    bookCount: Int,
    onOpen: () -> Unit,
) {
    var isFolderSettingsOpen by remember { mutableStateOf(false) }
    if (isFolderSettingsOpen) FolderConfigDialog(
        appRepository,
        folderId = folder.id,
        onClose = {
            log.i("Closing Directory Dialog")
            isFolderSettingsOpen = false
        })

    ListRow(
        hit = metrics.hit,
        label = folder.title,
        trailing = bookCount.toString(),
        onClick = onOpen,
        onLongClick = { isFolderSettingsOpen = true },
        leading = {
            Box(
                Modifier
                    .size(22.dp)
                    .background(Kaleido.spineFor(folder.id))
            )
        }
    )
}

/**
 * One row of the cover grid. A null entry is the import tile; short rows keep their empty
 * cells so covers stay column-aligned down the page.
 */
@Composable
private fun NotebookRow(
    books: List<Notebook>,
    columns: Int,
    appRepository: AppRepository,
    exportEngine: ExportEngine,
    syncScheduler: SyncScheduler,
    syncBadges: Map<String, SyncBadge>,
    onNavigateToEditor: (String, String) -> Unit,
    onPreviewNeeded: (String) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        books.forEach { book ->
            Box(Modifier.weight(1f)) {
                NotebookEntry(
                    book = book,
                    compactHit = null,
                    appRepository = appRepository,
                    exportEngine = exportEngine,
                    syncScheduler = syncScheduler,
                    syncBadge = syncBadges[book.id],
                    onNavigateToEditor = onNavigateToEditor,
                    onPreviewNeeded = onPreviewNeeded,
                )
            }
        }
        repeat(columns - books.size) { Spacer(Modifier.weight(1f)) }
    }
}

/**
 * A notebook plus the dialogs it can open. [compactHit] null selects the cover variant; a
 * value selects the one-handed row at that hit target.
 */
@Composable
private fun NotebookEntry(
    book: Notebook,
    compactHit: Dp?,
    appRepository: AppRepository,
    exportEngine: ExportEngine,
    syncScheduler: SyncScheduler,
    syncBadge: SyncBadge?,
    onNavigateToEditor: (String, String) -> Unit,
    onPreviewNeeded: (String) -> Unit,
) {
    var isSettingsOpen by remember { mutableStateOf(false) }
    var isConflictOpen by remember { mutableStateOf(false) }

    // A conflicted notebook opens the resolution dialog on tap — the reachable entry point
    // for the CONFLICT badge — instead of the editor.
    val open = {
        if (syncBadge == SyncBadge.CONFLICT) isConflictOpen = true
        else onNavigateToEditor(book.openPageId ?: book.pageIds.first(), book.id)
    }

    if (compactHit == null) {
        NotebookCoverCard(
            notebook = book,
            onOpen = open,
            onOpenSettings = { isSettingsOpen = true },
            syncBadge = syncBadge,
            onPreviewNeeded = onPreviewNeeded,
        )
    } else {
        NotebookListRow(
            notebook = book,
            hit = compactHit,
            onOpen = open,
            onOpenSettings = { isSettingsOpen = true },
            syncBadge = syncBadge,
            onPreviewNeeded = onPreviewNeeded,
        )
    }

    if (isSettingsOpen) {
        NotebookConfigDialog(
            appRepository,
            exportEngine = exportEngine,
            syncScheduler = syncScheduler,
            bookId = book.id, onClose = { isSettingsOpen = false })
    }
    if (isConflictOpen) {
        ConflictResolutionDialog(
            bookId = book.id,
            title = book.title,
            onClose = { isConflictOpen = false })
    }
}

/**
 * Fixed bar for the one-handed build: the two creating actions, in thumb reach. New
 * notebook repeats the header's `+` on purpose — that corner is exactly what a single hand
 * cannot get to. Adding a folder stays a row, being the rarer of the three.
 */
@Composable
private fun LibraryBottomBar(
    onCreateNewNotebook: () -> Unit,
    onCreateNewNote: () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(Kaleido.SectionRule)
                .background(Kaleido.Ink)
        )
        Row(Modifier.height(64.dp)) {
            BottomBarAction(
                label = stringResource(R.string.home_new_notebook),
                icon = FeatherIcons.FilePlus,
                filled = false,
                onClick = onCreateNewNotebook,
                modifier = Modifier.weight(1f)
            )
            Box(
                Modifier
                    .width(Kaleido.RowRule)
                    .fillMaxHeight()
                    .background(Kaleido.Rule)
            )
            BottomBarAction(
                label = stringResource(R.string.home_new_note),
                icon = FeatherIcons.Zap,
                filled = true,
                onClick = onCreateNewNote,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun BottomBarAction(
    label: String,
    icon: ImageVector,
    filled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxHeight()
            .background(if (filled) Kaleido.Ink else Kaleido.Paper)
            .noRippleClickable(onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)
    ) {
        Icon(
            icon, null,
            tint = if (filled) Kaleido.Paper else Kaleido.Ink,
            modifier = Modifier.size(18.dp)
        )
        Text(
            text = label,
            fontSize = 12.sp,
            fontWeight = if (filled) FontWeight.ExtraBold else FontWeight.Bold,
            color = if (filled) Kaleido.Paper else Kaleido.Ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * The document picker behind every import affordance. A PDF gets the copy-or-observe
 * question first; anything else goes straight to the xopp importer.
 */
@Composable
private fun rememberImportPicker(
    onImportPdf: (Uri, Boolean) -> Unit,
    onImportXopp: (Uri) -> Unit,
): () -> Unit {
    val context = LocalContext.current
    val snackState = LocalSnackContext.current
    var pendingPdf by remember { mutableStateOf<Uri?>(null) }

    pendingPdf?.let { uri ->
        PdfImportChoiceDialog(uri = uri, onCopy = {
            pendingPdf = null
            onImportPdf(it, /* copy= */ true)
        }, onObserve = {
            pendingPdf = null
            onImportPdf(it, /* copy= */ false)
        }, onDismiss = { pendingPdf = null })
    }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) {
            log.w("OpenDocument: uri is null (user cancelled or provider returned null)")
            return@rememberLauncherForActivityResult
        }
        try {
            val mimeType = context.contentResolver.getType(uri)
            log.d("Selected file mimeType: $mimeType, uri: $uri")
            if (mimeType == "application/pdf" ||
                uri.toString().endsWith(".pdf", ignoreCase = true)
            ) {
                pendingPdf = uri
            } else {
                onImportXopp(uri)
            }
        } catch (e: Exception) {
            log.e("contentPicker failed: ${e.message}", e)
            snackState.showOrUpdateSnack(SnackConf(text = "Importing failed: ${e.message}"))
        }
    }

    return {
        launcher.launch(
            arrayOf(
                "application/x-xopp",
                "application/gzip",
                "application/octet-stream",
                "application/pdf"
            )
        )
    }
}


@Preview(showBackground = true, name = "Library — tablet", widthDp = 800, heightDp = 1200)
@Composable
private fun LibraryContentWidePreview() = LibraryPreview(
    LibraryUiState(
        folders = listOf(
            Folder(id = "folder_1", title = "Research"),
            Folder(id = "folder_2", title = "Studio"),
        ),
        books = listOf(
            Notebook(
                id = "book_1", title = "Field Notes",
                pageIds = List(12) { "p$it" }, defaultBackground = "dotted"
            ),
            Notebook(
                id = "book_2", title = "Grid studies",
                pageIds = List(4) { "q$it" }, defaultBackground = "squared"
            ),
        ),
    )
)

@Preview(showBackground = true, name = "Library — one-handed", widthDp = 380, heightDp = 760)
@Composable
private fun LibraryContentNarrowPreview() = LibraryPreview(
    LibraryUiState(
        isLatestVersion = false,
        folders = listOf(Folder(id = "folder_1", title = "Research")),
        books = listOf(
            Notebook(
                id = "book_1", title = "Field Notes",
                pageIds = List(128) { "p$it" }, defaultBackground = "lined"
            ),
        ),
    )
)

/**
 * Previews stop at the header and the chrome: everything below needs an [AppRepository],
 * which a preview cannot build.
 */
@Composable
private fun LibraryPreview(uiState: LibraryUiState) {
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(Kaleido.Paper)
    ) {
        val metrics = kaleidoMetrics(maxWidth)
        Column {
            LibraryHeader(
                metrics = metrics,
                uiState = uiState,
                onNavigateToFolder = {},
                onNavigateToSettings = {},
                onSyncNow = {},
                onCreateNewNotebook = {},
                onCreateNewNote = {},
                onImport = {},
            )
            Column(Modifier.padding(metrics.pad)) {
                SectionHeader(stringResource(R.string.home_folders))
                Spacer(Modifier.height(12.dp))
                uiState.folders.forEach { folder ->
                    ListRow(
                        hit = metrics.hit,
                        label = folder.title,
                        onClick = {},
                        leading = {
                            Box(
                                Modifier
                                    .size(22.dp)
                                    .background(Kaleido.spineFor(folder.id))
                            )
                        }
                    )
                }
                Spacer(Modifier.height(22.dp))
                SectionHeader(stringResource(R.string.home_notebooks))
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    uiState.books.forEach { book ->
                        Box(Modifier.weight(1f)) {
                            NotebookCoverCard(
                                notebook = book,
                                onOpen = {},
                                onOpenSettings = {},
                            )
                        }
                    }
                    repeat(metrics.coverColumns - uiState.books.size) {
                        Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}
