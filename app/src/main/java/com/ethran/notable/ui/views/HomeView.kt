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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.vector.ImageVector
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
import com.ethran.notable.ui.components.CoverActionTile
import com.ethran.notable.ui.components.Kicker
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
import com.ethran.notable.ui.dialogs.NotebookConfigDialog
import com.ethran.notable.ui.dialogs.PdfImportChoiceDialog
import com.ethran.notable.ui.noRippleClickable
import com.ethran.notable.ui.theme.Kaleido
import com.ethran.notable.ui.theme.KaleidoMetrics
import com.ethran.notable.ui.theme.kaleidoMetrics
import com.ethran.notable.ui.viewmodels.LibraryUiState
import com.ethran.notable.ui.viewmodels.LibraryViewModel
import com.ethran.notable.sync.SyncBadge
import compose.icons.FeatherIcons
import compose.icons.feathericons.FilePlus
import compose.icons.feathericons.FolderPlus
import compose.icons.feathericons.Plus
import compose.icons.feathericons.Settings
import compose.icons.feathericons.Upload
import compose.icons.feathericons.Zap
import io.shipbook.shipbooksdk.ShipBook


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
    onCreateNewQuickPage: (String?) -> Unit = {},
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

    if (pendingNewNotebook) {
        NamePromptDialog(
            title = stringResource(R.string.name_prompt_notebook_title),
            initialValue = defaultNotebookName,
            onConfirm = { name ->
                pendingNewNotebook = false
                viewModel.onCreateNewNotebook(name)
            },
            onDismiss = { pendingNewNotebook = false }
        )
    }

    LibraryContent(
        appRepository = viewModel.appRepository,
        exportEngine = viewModel.exportEngine,
        syncScheduler = viewModel.syncScheduler,
        uiState = uiState,
        onNavigateToFolder = { id -> navController.navigate(LibraryDestination.createRoute(id)) },
        onNavigateToSettings = { navController.navigate("settings") },
        onNavigateToEditor = { pageId, bookId ->
            navController.navigate(EditorDestination.createRoute(pageId, bookId))
        },
        goToPage = goToPage,
        onCreateNewQuickPage = { onCreateNewQuickPage(uiState.folderId) },
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
    onNavigateToEditor: (String, String) -> Unit,
    goToPage: (String) -> Unit,
    onCreateNewQuickPage: () -> Unit,
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
        val metrics = kaleidoMetrics(maxWidth)
        val pickImportFile = rememberImportPicker(onImportPdf, onImportXopp)

        Column(Modifier.fillMaxSize()) {
            LibraryHeader(
                metrics = metrics,
                uiState = uiState,
                onNavigateToFolder = onNavigateToFolder,
                onNavigateToSettings = onNavigateToSettings,
                onCreateNewNotebook = onCreateNewNotebook,
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
                    SectionHeader(stringResource(R.string.home_folders))
                    Spacer(Modifier.height(12.dp))
                }
                items(uiState.folders, key = { "folder-${it.id}" }) { folder ->
                    FolderRow(
                        appRepository = appRepository,
                        folder = folder,
                        metrics = metrics,
                        bookCount = uiState.folderBookCounts[folder.id] ?: 0,
                        onOpen = { onNavigateToFolder(folder.id) },
                    )
                }
                item(key = "folder-add") {
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

                item(key = "pages-header") {
                    Spacer(Modifier.height(22.dp))
                    SectionHeader(stringResource(R.string.home_quick_pages))
                    Spacer(Modifier.height(12.dp))
                }
                item(key = "pages-row") {
                    ShowPagesRow(
                        appRepository = appRepository,
                        pages = uiState.singlePages,
                        currentPageId = null,
                        title = null,
                        onSelectPage = goToPage,
                        showAddQuickPage = true,
                        onCreateNewQuickPage = onCreateNewQuickPage,
                        onPreviewNeeded = onPreviewNeeded
                    )
                }

                item(key = "books-header") {
                    Spacer(Modifier.height(22.dp))
                    SectionHeader(stringResource(R.string.home_notebooks))
                    Spacer(Modifier.height(14.dp))
                }

                // An empty notebook is a leftover from a failed import; warn once rather than
                // drawing a cover for a book with no page to preview.
                val books = uiState.books.reversed()
                val (drawable, empty) = books.partition { it.pageIds.isNotEmpty() }

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
                    val rows = (drawable + listOf(null)).chunked(metrics.coverColumns)
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
                            importTile = { ImportTile(onClick = pickImportFile) },
                        )
                        if (index != rows.lastIndex) Spacer(Modifier.height(18.dp))
                    }
                } else {
                    item(key = "import-row") {
                        ListRow(
                            // Matches NotebookListRow, so the cover chips line up down the list.
                            hit = metrics.hit + 22.dp,
                            label = stringResource(R.string.home_import_notebook),
                            onClick = pickImportFile,
                            showChevron = false,
                            leading = {
                                Box(
                                    Modifier
                                        .size(34.dp, 46.dp)
                                        .border(1.dp, Kaleido.Edge),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        FeatherIcons.Upload, null,
                                        tint = Kaleido.Ink, modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        )
                    }
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
                    onCreateNewQuickPage = onCreateNewQuickPage,
                )
            }
        }
    }
}

/**
 * Kicker path over the screen's title, then the two square actions: settings outlined,
 * new notebook filled. Closed by the 2px rule that every section repeats.
 */
@Composable
private fun LibraryHeader(
    metrics: KaleidoMetrics,
    uiState: LibraryUiState,
    onNavigateToFolder: (String?) -> Unit,
    onNavigateToSettings: () -> Unit,
    onCreateNewNotebook: () -> Unit,
) {
    val root = stringResource(R.string.home_view_name)
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
            SquareButton(hit = metrics.hit, onClick = onCreateNewNotebook, filled = true) {
                Icon(
                    FeatherIcons.Plus, stringResource(R.string.home_new_notebook),
                    tint = Kaleido.Paper, modifier = Modifier.size(22.dp)
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(Kaleido.SectionRule)
                .background(Kaleido.Ink)
        )
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
        appRepository.folderRepository,
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
    books: List<Notebook?>,
    columns: Int,
    appRepository: AppRepository,
    exportEngine: ExportEngine,
    syncScheduler: SyncScheduler,
    syncBadges: Map<String, SyncBadge>,
    onNavigateToEditor: (String, String) -> Unit,
    onPreviewNeeded: (String) -> Unit,
    importTile: @Composable () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        books.forEach { book ->
            Box(Modifier.weight(1f)) {
                if (book == null) importTile()
                else NotebookEntry(
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

@Composable
private fun ImportTile(onClick: () -> Unit) {
    CoverActionTile(
        label = stringResource(R.string.home_import_notebook),
        onClick = onClick,
        icon = {
            Icon(
                FeatherIcons.Upload, null,
                tint = Kaleido.Ink, modifier = Modifier.size(32.dp)
            )
        }
    )
}

/**
 * Fixed bar for the one-handed build: the two creating actions, in thumb reach. New
 * notebook repeats the header's `+` on purpose — that corner is exactly what a single hand
 * cannot get to. Adding a folder stays a row, being the rarer of the three.
 */
@Composable
private fun LibraryBottomBar(
    onCreateNewNotebook: () -> Unit,
    onCreateNewQuickPage: () -> Unit,
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
                label = stringResource(R.string.home_quick_note),
                icon = FeatherIcons.Zap,
                filled = true,
                onClick = onCreateNewQuickPage,
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
                onCreateNewNotebook = {},
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
