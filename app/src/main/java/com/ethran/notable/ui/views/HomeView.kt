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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import com.ethran.notable.ui.components.ActionMenu
import com.ethran.notable.ui.components.MenuAction
import com.ethran.notable.ui.components.TextAction
import com.ethran.notable.ui.components.RowRule
import compose.icons.feathericons.ArrowLeft
import java.util.Date
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.ethran.notable.R
import com.ethran.notable.data.AppRepository
import com.ethran.notable.data.datastore.AppSettings
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
import com.ethran.notable.ui.components.FILE_BAR_WIDTH
import com.ethran.notable.ui.components.Kicker
import com.ethran.notable.ui.components.LibraryFileBar
import com.ethran.notable.ui.components.ListRow
import com.ethran.notable.ui.components.NotebookCoverCard
import com.ethran.notable.ui.components.NotebookListRow
import com.ethran.notable.ui.components.SectionHeader
import com.ethran.notable.ui.components.SquareButton
import com.ethran.notable.ui.dialogs.ConflictResolutionDialog
import com.ethran.notable.ui.dialogs.EmptyBookWarningHandler
import com.ethran.notable.ui.dialogs.FolderConfigDialog
import com.ethran.notable.ui.dialogs.NamePromptDialog
import com.ethran.notable.ui.dialogs.NewNotebookDialog
import com.ethran.notable.ui.dialogs.NotebookConfigDialog
import com.ethran.notable.ui.dialogs.PdfImportChoiceDialog
import com.ethran.notable.ui.dialogs.TelemetryConsentDialog
import com.ethran.notable.ui.noRippleClickable
import com.ethran.notable.ui.theme.Kaleido
import com.ethran.notable.ui.theme.KaleidoMetrics
import com.ethran.notable.ui.theme.kaleidoMetrics
import com.ethran.notable.ui.viewmodels.LibrarySort
import com.ethran.notable.ui.viewmodels.LibrarySortOrder
import com.ethran.notable.ui.viewmodels.LibraryUiState
import com.ethran.notable.ui.viewmodels.LibraryViewModel
import com.ethran.notable.sync.SyncBadge
import compose.icons.FeatherIcons
import compose.icons.feathericons.FolderPlus
import compose.icons.feathericons.MoreVertical
import compose.icons.feathericons.Plus
import compose.icons.feathericons.Search
import compose.icons.feathericons.Settings
import compose.icons.feathericons.Sidebar
import compose.icons.feathericons.Trash2
import compose.icons.feathericons.X
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

    // Asked once, on the library rather than over a notebook, and only after the welcome screen
    // has been cleared — a permissions wall is not the moment to put another question.
    // Dismissing it leaves the consent Unknown, so it is asked again next launch rather than
    // being read as a refusal.
    var telemetryAsked by remember { mutableStateOf(false) }
    val telemetryConsent = GlobalAppSettings.current.telemetryConsent
    if (!telemetryAsked &&
        !GlobalAppSettings.current.showWelcome &&
        telemetryConsent == AppSettings.TelemetryConsent.Unknown
    ) {
        TelemetryConsentDialog(
            onAllow = {
                telemetryAsked = true
                viewModel.setTelemetryConsent(AppSettings.TelemetryConsent.Granted)
            },
            onDeny = {
                telemetryAsked = true
                viewModel.setTelemetryConsent(AppSettings.TelemetryConsent.Denied)
            },
            // Not persisted: leaving it Unknown is what brings the question back.
            onAskLater = { telemetryAsked = true },
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
        onNavigateToPages = { navController.navigate(PagesDestination.createRoute(it)) },
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
    onPreviewNeeded: (String) -> Unit,
    onNavigateToPages: (String) -> Unit = {},
) {
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(Kaleido.Paper)
    ) {
        val pickImportFile = rememberImportPicker(onImportPdf, onImportXopp)
        var sidebarRequested by rememberSaveable { mutableStateOf(true) }
        val canShowSidebar = maxWidth >= 900.dp
        val showSidebar = canShowSidebar && sidebarRequested
        val shelfWidth = if (showSidebar) maxWidth - FILE_BAR_WIDTH - Kaleido.SectionRule else maxWidth
        val metrics = kaleidoMetrics(shelfWidth).copy(
            coverColumns = ((shelfWidth - 40.dp) / 200.dp).toInt().coerceAtLeast(1))
        var gridChoice by rememberSaveable { mutableStateOf<Boolean?>(null) }
        val showGrid = gridChoice ?: (shelfWidth >= 500.dp)
        var conflictedBook by remember { mutableStateOf<Notebook?>(null) }
        conflictedBook?.let { book ->
            ConflictResolutionDialog(bookId = book.id, title = book.title,
                onClose = { conflictedBook = null })
        }
        val openPages: (String) -> Unit = { bookId ->
            if (uiState.syncBadges[bookId] == SyncBadge.CONFLICT) {
                conflictedBook = uiState.tree.books.firstOrNull { it.id == bookId }
                    ?: uiState.books.firstOrNull { it.id == bookId }
            } else onNavigateToPages(bookId)
        }
        val openNotebook: (String, String) -> Unit = { pageId, bookId ->
            if (uiState.syncBadges[bookId] == SyncBadge.CONFLICT) {
                conflictedBook = uiState.tree.books.firstOrNull { it.id == bookId }
                    ?: uiState.books.firstOrNull { it.id == bookId }
            } else onNavigateToEditor(pageId, bookId)
        }

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

        val foldersById = remember(uiState.tree.folders) { uiState.tree.folders.associateBy { it.id } }
        val locationFor: (String?) -> String? = { parentId ->
            if (uiState.isSearching) LibrarySort.folderPath(parentId, foldersById) else null
        }

        Row(Modifier.fillMaxSize()) {
            // Only on a screen with the room: a one-handed device has one column's worth and
            // spends it on the shelf.
            if (showSidebar) LibraryFileBar(
                tree = uiState.tree,
                selectedFolderId = uiState.folderId,
                isSyncing = uiState.isSyncing,
                onSelectFolder = onNavigateToFolder,
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
                    onCreateNewFolder = onCreateNewFolder,
                    onNavigateToTrash = onNavigateToTrash,
                    gridView = showGrid,
                    onGridChanged = { gridChoice = it },
                    onToggleSidebar = if (canShowSidebar) ({ sidebarRequested = !sidebarRequested }) else null,
                    sidebarVisible = showSidebar,
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
                    if (sortedFolders.isEmpty() && sortedBooks.isEmpty()) item(key = "empty-library") {
                        Column(Modifier.fillMaxWidth().padding(vertical = 24.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(if (uiState.isSearching) "No results for “${uiState.query}”" else if (uiState.folderId != null) "Empty folder" else "Your library starts here",
                                color = Kaleido.Ink, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                            Text(if (uiState.isSearching) "Try another notebook or folder name."
                                else "Create a notebook or import your notes from More.", color = Kaleido.Muted)
                            if (uiState.isSearching) TextAction("Clear search", { onQueryChanged("") })
                        }
                    }
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
                            location = locationFor(folder.parentFolderId),
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

                    if (showGrid) {
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
                                lastEdited = uiState.lastEdited,
                                locationFor = locationFor,
                                onNavigateToPages = openPages,
                                onNavigateToEditor = openNotebook,
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
                                editedAt = maxOf(book.updatedAt, uiState.lastEdited[book.id] ?: book.updatedAt),
                                location = locationFor(book.parentFolderId),
                                onNavigateToPages = openPages,
                                onNavigateToEditor = openNotebook,
                                onPreviewNeeded = onPreviewNeeded,
                            )
                        }
                    }
                }

            }
        }
    }
}

/**
 * The screen's masthead: where you are, and what you can do about it.
 *
 * Two visible actions, both top right — a filled `+` for a new notebook, the one thing this
 * screen exists to get out of the way, and a gear holding everything else. What stays on the bar
 * is what you reach for without thinking about the library itself: the folder toggle, the way
 * back up, the title, and search. Everything that is a statement *about* the library rather than
 * work in it — how the shelf is laid out, where it syncs, what was thrown away — is one tap
 * further off, which for a once-a-session decision costs nothing and buys back two rows.
 *
 * Grid/List lives at the head of that menu rather than as a pair of buttons on a row of their
 * own: it is the item most often come for, and two ticked entries say which one is on without
 * spending a line of the header saying it permanently.
 */
@Composable
internal fun LibraryHeader(
    metrics: KaleidoMetrics,
    uiState: LibraryUiState,
    onNavigateToFolder: (String?) -> Unit = {},
    onNavigateToSettings: () -> Unit = {},
    onSyncNow: () -> Unit = {},
    onCreateNewNotebook: () -> Unit = {},
    onCreateNewNote: () -> Unit = {},
    onImport: () -> Unit = {},
    onQueryChanged: (String) -> Unit = {},
    onSortChanged: (LibrarySortOrder, Boolean) -> Unit = { _, _ -> },
    onCreateNewFolder: () -> Unit = {},
    onNavigateToTrash: () -> Unit = {},
    gridView: Boolean = true,
    onGridChanged: (Boolean) -> Unit = {},
    onToggleSidebar: (() -> Unit)? = null,
    sidebarVisible: Boolean = false,
) {
    var isMoreOpen by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(metrics.pad)) {
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // Only where there is a second column to show: below the breakpoint the shelf has
            // the screen to itself and a toggle would promise one that cannot appear.
            onToggleSidebar?.let { toggle ->
                SquareButton(48.dp, toggle) {
                    Icon(
                        FeatherIcons.Sidebar,
                        if (sidebarVisible) "Hide folders" else "Show folders",
                        tint = Kaleido.Ink
                    )
                }
            }
            if (uiState.folderId != null) SquareButton(48.dp, {
                onNavigateToFolder(uiState.breadcrumbFolders.dropLast(1).lastOrNull()?.id)
            }) { Icon(FeatherIcons.ArrowLeft, "Parent folder", tint = Kaleido.Ink) }
            Text(uiState.breadcrumbFolders.lastOrNull()?.title ?: "Library",
                fontSize = metrics.titleSize, fontWeight = FontWeight.ExtraBold,
                color = Kaleido.Ink, modifier = Modifier.weight(1f), maxLines = 2,
                overflow = TextOverflow.Ellipsis)
            SquareButton(48.dp, onCreateNewNotebook, filled = true) {
                Icon(
                    FeatherIcons.Plus, stringResource(R.string.home_new_notebook),
                    tint = Kaleido.Paper
                )
            }
            Box {
                SquareButton(48.dp, { isMoreOpen = true }, filled = isMoreOpen) {
                    Icon(
                        FeatherIcons.Settings, stringResource(R.string.home_more_actions),
                        tint = if (isMoreOpen) Kaleido.Paper else Kaleido.Ink
                    )
                }
                // An update is the one thing on this screen the user cannot discover any other
                // way, and it now lives two taps inside the menu — so the mark that says to look
                // belongs on the thing that opens it.
                if (!uiState.isLatestVersion) {
                    Box(Modifier.align(Alignment.TopEnd).size(10.dp).background(Kaleido.Red)
                        .semantics { contentDescription = "Update available" })
                }
                if (isMoreOpen) ActionMenu(onDismiss = { isMoreOpen = false }) {
                    MenuAction("Grid", { isMoreOpen = false; onGridChanged(true) }, gridView)
                    MenuAction("List", { isMoreOpen = false; onGridChanged(false) }, !gridView)
                    RowRule()
                    MenuAction("New folder", { isMoreOpen = false; onCreateNewFolder() })
                    MenuAction(stringResource(R.string.home_new_note), { isMoreOpen = false; onCreateNewNote() })
                    MenuAction(stringResource(R.string.home_import_notebook), { isMoreOpen = false; onImport() })
                    RowRule()
                    MenuAction(
                        stringResource(
                            if (uiState.isSyncing) R.string.sync_status_syncing else R.string.sync_now
                        ),
                        { isMoreOpen = false; onSyncNow() })
                    MenuAction(stringResource(R.string.home_trash), { isMoreOpen = false; onNavigateToTrash() })
                    MenuAction(if (uiState.isLatestVersion) "Settings" else "Settings · Update available",
                        { isMoreOpen = false; onNavigateToSettings() })
                }
            }
        }
        if (uiState.folderId != null) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically) {
                Text("Library", color = Kaleido.Ink,
                    modifier = Modifier.heightIn(min = 48.dp).noRippleClickable { onNavigateToFolder(null) }
                        .padding(vertical = 14.dp, horizontal = 8.dp))
                uiState.breadcrumbFolders.dropLast(1).forEach { folder ->
                    Text("/", color = Kaleido.Muted)
                    Text(folder.title, color = Kaleido.Ink,
                        modifier = Modifier.heightIn(min = 48.dp).noRippleClickable { onNavigateToFolder(folder.id) }
                            .padding(vertical = 14.dp, horizontal = 8.dp))
                }
            }
        }
        // Only while a run is in flight. The menu says "Syncing…" too, but the header is where
        // you look to ask "is my writing on the server yet?", and a line that costs a row only
        // during a sync is cheaper than a control that answers it permanently.
        if (uiState.isSyncing) {
            Text(stringResource(R.string.sync_status_syncing), color = Kaleido.Ink, fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 8.dp))
        }
        Spacer(Modifier.height(12.dp))
        LibrarySearchRow(metrics, uiState.query, onQueryChanged, onSortChanged)
        Spacer(Modifier.height(12.dp))
        RowRule()
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
                modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                    .semantics { contentDescription = "Search notebooks and folders" },
                decorationBox = { inner ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
                        if (query.isEmpty()) {
                            Text(
                                stringResource(R.string.home_search_hint),
                                fontSize = 14.sp, color = Kaleido.Muted, maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        inner()
                    }
                }
            )
            if (query.isNotEmpty()) {
                Icon(
                    FeatherIcons.X, stringResource(R.string.home_search_clear),
                    tint = Kaleido.Ink,
                    modifier = Modifier
                        .size(48.dp)
                        .noRippleClickable { onQueryChanged("") }
                        .padding(15.dp)
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Box {
            Row(
                Modifier
                    .height(metrics.hit)
                    .border(1.dp, Kaleido.Ink)
                    .noRippleClickable { isSortMenuOpen = true }
                    .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Sort", fontSize = 14.sp, fontWeight = FontWeight.Bold,
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

/** The sort choices, as a popup rather than a screen: it is one decision with six answers. */
@Composable
private fun LibrarySortMenu(
    order: LibrarySortOrder,
    descending: Boolean,
    onPick: (LibrarySortOrder, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    ActionMenu(onDismiss = onDismiss) {
        LibrarySortOrder.entries.forEach { candidate ->
            MenuAction(candidate.label, { onPick(candidate, descending) }, candidate == order)
        }
        RowRule()
        val firstLabel = if (order == LibrarySortOrder.TITLE) "A to Z" else "Newest first"
        val secondLabel = if (order == LibrarySortOrder.TITLE) "Z to A" else "Oldest first"
        val firstDescending = order != LibrarySortOrder.TITLE
        MenuAction(firstLabel, { onPick(order, firstDescending) }, descending == firstDescending)
        MenuAction(secondLabel, { onPick(order, !firstDescending) }, descending != firstDescending)
    }
}


@Composable
private fun FolderRow(
    appRepository: AppRepository,
    folder: Folder,
    metrics: KaleidoMetrics,
    bookCount: Int,
    location: String?,
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

    Row(verticalAlignment = Alignment.CenterVertically) {
    ListRow(
        modifier = Modifier.weight(1f),
        hit = metrics.hit,
        label = folder.title,
        secondary = location,
        secondaryMaxLines = 2,
        trailing = bookCount.toString(),
        showChevron = false,
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
        SquareButton(48.dp, { isFolderSettingsOpen = true }) {
            Icon(FeatherIcons.MoreVertical, "${folder.title} options", tint = Kaleido.Ink)
        }
    }
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
    lastEdited: Map<String, Date>,
    locationFor: (String?) -> String?,
    onNavigateToPages: (String) -> Unit,
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
                    editedAt = maxOf(book.updatedAt, lastEdited[book.id] ?: book.updatedAt),
                    location = locationFor(book.parentFolderId),
                    onNavigateToPages = onNavigateToPages,
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
    editedAt: Date,
    location: String?,
    onNavigateToPages: (String) -> Unit,
    onNavigateToEditor: (String, String) -> Unit,
    onPreviewNeeded: (String) -> Unit,
) {
    var isMoreOpen by remember(book.id) { mutableStateOf(false) }
    var isSettingsOpen by remember(book.id) { mutableStateOf(false) }
    val open = {
        val page = book.openPageId?.takeIf { it in book.pageIds } ?: book.pageIds.firstOrNull()
        if (page == null) onNavigateToPages(book.id) else onNavigateToEditor(page, book.id)
    }
    val options: @Composable () -> Unit = {
        Box {
            SquareButton(48.dp, { isMoreOpen = true }) {
                Icon(FeatherIcons.MoreVertical, "${book.title} options", tint = Kaleido.Ink)
            }
            if (isMoreOpen) ActionMenu(onDismiss = { isMoreOpen = false }) {
                MenuAction("Pages", { isMoreOpen = false; onNavigateToPages(book.id) })
                MenuAction("Notebook details", { isMoreOpen = false; isSettingsOpen = true })
            }
        }
    }
    if (compactHit == null) {
        Column {
            NotebookCoverCard(notebook = book, onOpen = open,
                onOpenSettings = { isMoreOpen = true }, editedAt = editedAt, location = location,
                syncBadge = syncBadge, onPreviewNeeded = onPreviewNeeded)
            Box(Modifier.align(Alignment.End)) { options() }
        }
    } else {
        Row(verticalAlignment = Alignment.CenterVertically) {
            NotebookListRow(notebook = book, hit = compactHit, onOpen = open,
                onOpenSettings = { isMoreOpen = true }, editedAt = editedAt, location = location,
                syncBadge = syncBadge, onPreviewNeeded = onPreviewNeeded,
                modifier = Modifier.weight(1f))
            options()
        }
    }

    if (isSettingsOpen) {
        NotebookConfigDialog(
            appRepository,
            exportEngine = exportEngine,
            syncScheduler = syncScheduler,
            bookId = book.id, onClose = { isSettingsOpen = false })
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
