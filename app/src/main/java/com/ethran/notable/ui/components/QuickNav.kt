package com.ethran.notable.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.lazy.items
import com.ethran.notable.data.db.Notebook
import com.ethran.notable.data.datastore.RecentNotebooks
import com.ethran.notable.data.datastore.recentNotebooks
import com.ethran.notable.data.datastore.resumePageId
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import com.ethran.notable.ui.theme.Kaleido
import androidx.compose.material.Icon
import androidx.compose.material.icons.filled.Close
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ethran.notable.data.AppRepository
import com.ethran.notable.data.db.Folder
import com.ethran.notable.data.db.Page
import com.ethran.notable.editor.ui.toolbar.ToolbarButton
import com.ethran.notable.editor.utils.autoEInkAnimationOnScroll
import com.ethran.notable.io.ThumbnailBackfillQueue
import com.ethran.notable.sync.couch.CouchOutlineEntry
import com.ethran.notable.ui.SnackDispatcher
import com.ethran.notable.ui.dialogs.NamePromptDialog
import com.ethran.notable.ui.dialogs.ShowSimpleConfirmationDialog
import com.ethran.notable.ui.noRippleClickable
import com.ethran.notable.ui.viewmodels.QuickNavTab
import com.ethran.notable.ui.viewmodels.QuickNavUiState
import com.ethran.notable.ui.viewmodels.QuickNavViewModel
import dagger.hilt.EntryPoint
import dagger.hilt.EntryPoints
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.shipbook.shipbooksdk.ShipBook

private val logQuickNav = ShipBook.getLogger("QuickNav")

@EntryPoint
@InstallIn(SingletonComponent::class)
interface QuickNavEntryPoint {
    fun thumbnailBackfillQueue(): ThumbnailBackfillQueue
    fun snackDispatcher(): SnackDispatcher
}


@Composable
fun QuickNav(
    appRepository: AppRepository,
    currentPageId: String?,
    quickNavSourcePageId: String?,
    onClose: () -> Unit,
    goToPage: (String) -> Unit,
    goToFolder: (String?) -> Unit,
    initialTab: QuickNavTab = QuickNavTab.PAGES,
) {
    BackHandler(onBack = onClose)
    val context = LocalContext.current
    val entryPoint = remember(context) {
        EntryPoints.get(context.applicationContext, QuickNavEntryPoint::class.java)
    }
    val thumbnailBackfillQueue = entryPoint.thumbnailBackfillQueue()

    // Provide the ViewModel using a custom Factory to inject appRepository
    val viewModel: QuickNavViewModel = viewModel(factory = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return QuickNavViewModel(
                appRepository = appRepository,
                thumbnailBackfillQueue = thumbnailBackfillQueue,
                snackDispatcher = entryPoint.snackDispatcher()
            ) as T
        }
    })

    // Observe the UI State lifecycle-safely
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // Load data when the page changes
    LaunchedEffect(initialTab) { viewModel.selectTab(initialTab) }

    LaunchedEffect(currentPageId) {
        viewModel.loadPageData(currentPageId)
    }

    QuickNavContent(
        appRepository = appRepository,
        uiState = uiState,
        onClose = {
            logQuickNav.d("outside tap -> close")
            onClose()
        },
        onNavigateBreadcrumb = { goToFolder(it) },
        onSelectTab = viewModel::selectTab,
        onToggleBookmark = viewModel::toggleBookmark,
        onSetBookmark = viewModel::setBookmark,
        onTogglePinned = viewModel::togglePinned,
        onAddOutlineEntry = viewModel::addOutlineEntry,
        onRenameOutlineEntry = viewModel::renameOutlineEntry,
        onChangeOutlineDepth = viewModel::changeOutlineDepth,
        onRemoveOutlineEntry = viewModel::removeOutlineEntry,
        onMoveOutlineEntry = viewModel::moveOutlineEntry,
        onGenerateBookPreviews = viewModel::generateThumbnailsForCurrentBook,
        onScrubStart = viewModel::onScrubStart,
        onScrubPreview = viewModel::onScrubPreview,
        onScrubEnd = viewModel::onScrubEnd,
        onReturnClick = { viewModel.onReturnClick(quickNavSourcePageId) },
        goToPage = { goToPage(it) },
    )
}

/**
 * The four ways into a long notebook: its pages, its table of contents, the pages you starred, and
 * the cross-notebook jump list this panel has always had.
 *
 * One sheet with tabs rather than four screens, because that is what a reader reaches for as one
 * gesture — "where am I, and where do I want to be". Goodnotes' sidebar and the BOOX reader's own
 * TOC panel arrived at the first three independently, which is a strong enough signal to follow.
 *
 * Pinned keeps its own tab rather than being folded into Bookmarks. They look alike and are not:
 * bookmarks belong to the notebook and sync to the iPad, pinned pages span notebooks and stay on
 * this device. Merging them would have to lose one of those properties.
 */
@Composable
fun QuickNavContent(
    appRepository: AppRepository?,
    uiState: QuickNavUiState,
    onClose: () -> Unit,
    onNavigateBreadcrumb: (String?) -> Unit,
    onSelectTab: (QuickNavTab) -> Unit,
    onToggleBookmark: () -> Unit,
    onSetBookmark: (String, Boolean) -> Unit,
    onTogglePinned: () -> Unit,
    onAddOutlineEntry: (String, String) -> Unit,
    onRenameOutlineEntry: (String, String) -> Unit,
    onChangeOutlineDepth: (String, Int) -> Unit,
    onRemoveOutlineEntry: (String) -> Unit,
    onMoveOutlineEntry: (String, Int) -> Unit,
    onGenerateBookPreviews: () -> Unit,
    onScrubStart: () -> Unit,
    onScrubPreview: (Int) -> Unit,
    onScrubEnd: (Int) -> Unit,
    onReturnClick: () -> Unit,
    goToPage: (String) -> Unit,
) {
    var namingOutlineEntry by remember { mutableStateOf(false) }

    BoxWithConstraints(Modifier.fillMaxWidth().fillMaxHeight()) {
        val compactHeight = maxHeight < 480.dp
        Column(
            Modifier
                .fillMaxWidth()
                .fillMaxHeight()
        ) {
            // Tap outside to dismiss
            Spacer(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .noRippleClickable(onClick = onClose)
            )

            // Top divider above the sheet
            Box(
                modifier = Modifier
                    .height(1.dp)
                    .fillMaxWidth()
                    .background(Color.Black)
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.White)
                    .padding(10.dp)
            ) {

                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Navigate", fontSize = 18.sp, fontWeight = FontWeight.Medium,
                        color = Kaleido.Ink, modifier = Modifier.weight(1f).padding(start = 8.dp))
                    ToolbarButton(imageVector = Icons.Filled.Close,
                        contentDescription = "Close navigation", onSelect = onClose)
                }

                if (uiState.tab != QuickNavTab.NOTES) QuickNavHeaderRow(
                    folders = uiState.breadcrumbFolders,
                    isBookmarked = uiState.isCurrentPageBookmarked,
                    canToggleBookmark = uiState.currentPageId != null && uiState.bookId != null,
                    canGeneratePreviews = uiState.bookPageIds.isNotEmpty(),
                    canAddOutlineEntry = uiState.currentPageId != null && uiState.bookId != null,
                    onNavigateBreadcrumb = onNavigateBreadcrumb,
                    onToggleBookmark = onToggleBookmark,
                    onAddOutlineEntry = { namingOutlineEntry = true },
                    onGenerateBookPreviews = onGenerateBookPreviews
                )

                Spacer(Modifier.height(8.dp))

                QuickNavTabRow(selected = uiState.tab, onSelect = onSelectTab)

                Spacer(Modifier.height(10.dp))

                // Measure fixed controls first. The list gets the space left on short windows,
                // and stays capped on tablets so the sheet does not cover all of the canvas.
                Box(Modifier.weight(1f, fill = false).heightIn(max = 320.dp)) {
                    when (uiState.tab) {
                        QuickNavTab.NOTES -> NotesTab(
                            appRepository = appRepository,
                            currentBookId = uiState.bookId,
                            onSelect = { pageId ->
                                goToPage(pageId)
                                onClose()
                            },
                        )
                        QuickNavTab.PAGES -> PagesTab(
                            pageIds = uiState.bookPageIds,
                            currentPageId = uiState.currentPageId,
                            bookmarkedPageIds = uiState.bookmarkedPageIds,
                            onSelectPage = goToPage,
                            onSetBookmark = onSetBookmark,
                            onAddOutlineEntry = onAddOutlineEntry,
                        )

                        QuickNavTab.OUTLINE -> OutlineTab(
                            entries = uiState.outline,
                            pageIds = uiState.bookPageIds,
                            currentPageId = uiState.currentPageId,
                            onSelectPage = goToPage,
                            onRename = onRenameOutlineEntry,
                            onChangeDepth = onChangeOutlineDepth,
                            onRemove = onRemoveOutlineEntry,
                            onMove = onMoveOutlineEntry,
                        )

                        QuickNavTab.BOOKMARKS -> BookmarksTab(
                            pageIds = uiState.bookmarkedPageIds,
                            allPageIds = uiState.bookPageIds,
                            currentPageId = uiState.currentPageId,
                            onSelectPage = goToPage,
                            onRemoveBookmark = { onSetBookmark(it, false) },
                        )

                        QuickNavTab.PINNED -> PinnedTab(
                            appRepository = appRepository,
                            pages = uiState.pinnedPages,
                            currentPageId = uiState.currentPageId,
                            isCurrentPagePinned = uiState.isCurrentPagePinned,
                            canPin = uiState.currentPageId != null,
                            onTogglePinned = onTogglePinned,
                            onSelectPage = goToPage,
                        )
                    }
                }

                // Below the tabs, not inside one: the scrubber crosses the notebook whichever tab is
                // showing, and it is the fastest way to move on a panel that redraws slowly.
                if (uiState.bookPageCount >= 2) {
                    Spacer(modifier = Modifier.height(12.dp))

                    Row(modifier = Modifier.fillMaxWidth()) {
                        PageHorizontalSliderWithReturn(
                            modifier = Modifier.testTag("navigation-page-scrubber"),
                            showPageIndicator = !compactHeight,
                            pageCount = uiState.bookPageCount,
                            currentIndex = uiState.currentBookIndex,
                            favIndexes = uiState.favoriteIndexesInBook,
                            onDragStart = onScrubStart,
                            onPreviewIndexChanged = onScrubPreview,
                            onDragEnd = onScrubEnd,
                            onReturnClick = onReturnClick
                        )
                    }
                }
            }
        }

    }

    if (namingOutlineEntry) {
        val pageId = uiState.currentPageId
        NamePromptDialog(
            title = "Add to outline",
            initialValue = "",
            onConfirm = { title ->
                if (pageId != null && title.isNotBlank()) {
                    onAddOutlineEntry(pageId, title.trim())
                    // Straight to the tab it landed in, so the entry is visibly somewhere rather
                    // than the dialog just closing on the page you were already looking at.
                    onSelectTab(QuickNavTab.OUTLINE)
                }
                namingOutlineEntry = false
            },
            onDismiss = { namingOutlineEntry = false },
        )
    }
}

@Composable
private fun QuickNavHeaderRow(
    folders: List<Folder>,
    isBookmarked: Boolean,
    canToggleBookmark: Boolean,
    canGeneratePreviews: Boolean,
    canAddOutlineEntry: Boolean,
    onNavigateBreadcrumb: (String?) -> Unit,
    onToggleBookmark: () -> Unit,
    onAddOutlineEntry: () -> Unit,
    onGenerateBookPreviews: () -> Unit
) {
    Row(modifier = Modifier.fillMaxWidth()) {
        BreadCrumb(
            modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
            folders = folders, fontSize = 14, onSelectFolderId = onNavigateBreadcrumb
        )

        ToolbarButton(
            contentDescription = if (isBookmarked) "Remove bookmark" else "Bookmark this page",
            imageVector = if (isBookmarked) Icons.Filled.Bookmark else Icons.Filled.BookmarkBorder,
            onSelect = {
                if (canToggleBookmark) {
                    onToggleBookmark()
                } else {
                    // A loose page belongs to no notebook, and a bookmark is a fact about one.
                    logQuickNav.w("bookmark toggle ignored, page is not in a notebook")
                }
            })

        // Naming a section used to be reachable only by holding a page thumbnail, which is to say
        // it was not reachable: nothing said the long-press was there. It sits beside the bookmark
        // toggle now — the two things you do to the page you are looking at.
        ToolbarButton(
            imageVector = Icons.AutoMirrored.Filled.FormatListBulleted,
            contentDescription = "Add to outline",
            onSelect = {
                if (canAddOutlineEntry) {
                    onAddOutlineEntry()
                } else {
                    logQuickNav.w("outline entry ignored, page is not in a notebook")
                }
            })

        ToolbarButton(
            imageVector = Icons.Filled.Image,
            contentDescription = "Refresh page previews",
            onSelect = {
                if (canGeneratePreviews) {
                    onGenerateBookPreviews()
                } else {
                    logQuickNav.w("generate previews ignored, no book pages")
                }
            }
        )
    }
}

/** Labels over a rule: the selected tab is marked by ink weight, which survives an e-ink redraw. */
@Composable
private fun QuickNavTabRow(selected: QuickNavTab, onSelect: (QuickNavTab) -> Unit) {
    val tabs = listOf(
        QuickNavTab.NOTES to "Notebooks",
        QuickNavTab.PAGES to "Pages",
        QuickNavTab.OUTLINE to "Outline",
        QuickNavTab.BOOKMARKS to "Bookmarks",
        QuickNavTab.PINNED to "Pinned",
    )
    Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
        tabs.forEach { (tab, label) ->
            Column(
                modifier = Modifier
                    .width(if (tab == QuickNavTab.NOTES) 116.dp else 100.dp)
                    .heightIn(min = 48.dp)
                    .semantics { this.selected = tab == selected; role = Role.Tab }
                    .noRippleClickable { onSelect(tab) },
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = label,
                    fontSize = 13.sp,
                    fontWeight = if (tab == selected) FontWeight.Bold else FontWeight.Normal,
                    color = Color.Black,
                    modifier = Modifier.padding(vertical = 14.dp),
                    maxLines = 1
                )
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(if (tab == selected) 2.dp else 1.dp)
                        .background(if (tab == selected) Color.Black else Color.LightGray)
                )
            }
        }
    }
}

/** Switch notes without a trip through the library. Recent first, then all other live notes. */
@Composable
private fun NotesTab(
    appRepository: AppRepository?,
    currentBookId: String?,
    onSelect: (String) -> Unit,
) {
    if (appRepository == null) return
    val context = LocalContext.current
    val history = remember(context) { RecentNotebooks.get(context) }
    val ids by history.ids.collectAsStateWithLifecycle()
    val booksFlow = remember(appRepository) { appRepository.bookRepository.getAllFlow() }
    val books by booksFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val ordered = remember(ids, books) {
        val recent = recentNotebooks(ids, books)
        val recentIds = recent.map { it.id }.toSet()
        recent + books.filter { it.id !in recentIds && it.deletedAt == null && it.resumePageId() != null }
            .sortedWith(compareBy<Notebook> { it.title.lowercase() }.thenBy { it.id })
    }
    NotebookSwitcherList(ordered, ids, currentBookId, onSelect)
}

/** Stateless presentation also used by the real-screen regression tests. */
@Composable
internal fun NotebookSwitcherList(
    books: List<Notebook>,
    recentIds: List<String>,
    currentBookId: String?,
    onSelect: (String) -> Unit,
) {
    if (books.isEmpty()) {
        QuickNavEmpty("No notebooks to open yet.")
        return
    }
    val recent = books.filter { it.id in recentIds }
    val others = books.filterNot { it.id in recentIds }
    LazyColumn(Modifier.fillMaxWidth().autoEInkAnimationOnScroll()) {
        fun androidx.compose.foundation.lazy.LazyListScope.section(title: String, notebooks: List<Notebook>) {
            if (notebooks.isEmpty()) return
            item(key = "section-$title") {
                Text(title, color = Kaleido.Muted, fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp))
            }
            items(notebooks, key = { it.id }) { book ->
                val current = book.id == currentBookId
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 64.dp)
                        .semantics { selected = current }
                        .noRippleClickable { book.resumePageId()?.let(onSelect) }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    // A narrow notebook outline, not a colorful card, keeps the list quiet.
                    Box(Modifier.width(22.dp).height(30.dp).border(1.dp, Kaleido.Ink)) {
                        Box(Modifier.width(4.dp).fillMaxHeight().background(Kaleido.Ink))
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(book.title, fontWeight = if (current) FontWeight.Medium else FontWeight.Normal,
                            color = Kaleido.Ink, fontSize = 15.sp,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                        val page = book.pageIds.indexOf(book.resumePageId()).coerceAtLeast(0) + 1
                        Text("Page $page of ${book.pageIds.size}", fontSize = 12.sp, color = Kaleido.Muted)
                    }
                    if (current) Text("Open", fontSize = 12.sp, color = Kaleido.Ink)
                }
                RowRule()
            }
        }
        section("Recent", recent)
        section("All notebooks", others)
    }
}

// region Tabs

/**
 * Every page of the notebook, as thumbnails. Long-press opens the actions that fill the other two
 * tabs — starring and outlining — which is where a reader is already looking at the page they mean.
 */
@Composable
private fun PagesTab(
    pageIds: List<String>,
    currentPageId: String?,
    bookmarkedPageIds: List<String>,
    onSelectPage: (String) -> Unit,
    onSetBookmark: (String, Boolean) -> Unit,
    onAddOutlineEntry: (String, String) -> Unit,
) {
    if (pageIds.isEmpty()) {
        QuickNavEmpty("This page is not in a notebook, so it has no pages to show.")
        return
    }

    var outliningPageId by remember { mutableStateOf<String?>(null) }

    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxHeight < 200.dp) {
            // A thumbnail squeezed into a short row loses both its label and usable target.
            // Compact windows keep every page accessible as a scrollable, named 48dp row.
            LazyColumn(Modifier.fillMaxWidth().autoEInkAnimationOnScroll()) {
                itemsIndexed(pageIds, key = { _, id -> id }) { index, pageId ->
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp)
                            .semantics {
                                contentDescription = "Open page ${index + 1}"
                                selected = pageId == currentPageId
                            }
                            .combinedClickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null, role = Role.Button,
                                onClick = { onSelectPage(pageId) },
                                onLongClick = { outliningPageId = pageId },
                            )
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Page ${index + 1}", modifier = Modifier.weight(1f), color = Kaleido.Ink)
                        if (pageId == currentPageId) Text("Open", color = Kaleido.Ink, fontSize = 12.sp)
                    }
                }
            }
        } else {
            // Two rows that scroll sideways, not a vertical grid: the sheet is short by design, and this
            // keeps the page you are on reachable without the sheet growing over the canvas.
            LazyHorizontalGrid(
                rows = GridCells.Fixed(2),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(300.dp)
                    .autoEInkAnimationOnScroll()
            ) {
                gridItemsIndexed(pageIds) { index, pageId ->
                    PageThumbnail(
                        pageId = pageId,
                        label = "${index + 1}",
                        isCurrent = pageId == currentPageId,
                        isBookmarked = pageId in bookmarkedPageIds,
                        onClick = { onSelectPage(pageId) },
                        onLongClick = { outliningPageId = pageId },
                    )
                }
            }
        }
    }

    outliningPageId?.let { pageId ->
        val index = pageIds.indexOf(pageId)
        PageActionsDialog(
            pageLabel = "Page ${index + 1}",
            isBookmarked = pageId in bookmarkedPageIds,
            onToggleBookmark = {
                onSetBookmark(pageId, pageId !in bookmarkedPageIds)
                outliningPageId = null
            },
            onAddToOutline = { title ->
                onAddOutlineEntry(pageId, title)
                outliningPageId = null
            },
            onDismiss = { outliningPageId = null },
        )
    }
}

/**
 * The table of contents. Entries are the reader's own — ink has no headings to extract — so this is
 * empty until they name something, and says so rather than showing a blank panel.
 */
@Composable
private fun OutlineTab(
    entries: List<CouchOutlineEntry>,
    pageIds: List<String>,
    currentPageId: String?,
    onSelectPage: (String) -> Unit,
    onRename: (String, String) -> Unit,
    onChangeDepth: (String, Int) -> Unit,
    onRemove: (String) -> Unit,
    onMove: (String, Int) -> Unit,
) {
    if (entries.isEmpty()) {
        QuickNavEmpty(
            "No outline yet. Name a section with the list button above, or hold a page in "
                + "the Pages tab."
        )
        return
    }

    var renaming by remember { mutableStateOf<CouchOutlineEntry?>(null) }
    var deleting by remember { mutableStateOf<CouchOutlineEntry?>(null) }

    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .autoEInkAnimationOnScroll()
    ) {
        itemsIndexed(entries, key = { _, entry -> entry.id }) { index, entry ->
            OutlineRow(
                entry = entry,
                pageNumber = pageIds.indexOf(entry.pageId).takeIf { it >= 0 }?.plus(1),
                isCurrent = entry.pageId == currentPageId,
                canMoveUp = index > 0,
                canMoveDown = index < entries.size - 1,
                onSelect = { onSelectPage(entry.pageId) },
                onRename = { renaming = entry },
                onIndent = { onChangeDepth(entry.id, entry.depth + 1) },
                onOutdent = { onChangeDepth(entry.id, entry.depth - 1) },
                // By the entry's own id, one step against its visible neighbors. Indices into
                // this list used to be handed to the repository, whose stored outline also holds
                // entries this tab hides — so a hidden entry above the row moved the wrong one.
                onMoveUp = { onMove(entry.id, -1) },
                onMoveDown = { onMove(entry.id, 1) },
                onRemove = { deleting = entry },
            )
        }
    }

    renaming?.let { entry ->
        NamePromptDialog(
            title = "Rename entry",
            initialValue = entry.title,
            onConfirm = { title ->
                if (title.isNotBlank()) onRename(entry.id, title.trim())
                renaming = null
            },
            onDismiss = { renaming = null },
        )
    }

    // Confirmed, like every other destructive tap in the app: the button sits in a row of
    // reorder arrows, and the tombstone it writes travels to every device.
    deleting?.let { entry ->
        ShowSimpleConfirmationDialog(
            title = "Delete outline entry",
            message = "Remove \"${entry.title}\" from the outline? The page itself is kept.",
            onConfirm = {
                onRemove(entry.id)
                deleting = null
            },
            onCancel = { deleting = null },
        )
    }
}

@Composable
private fun OutlineRow(
    entry: CouchOutlineEntry,
    pageNumber: Int?,
    isCurrent: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onSelect: () -> Unit,
    onRename: () -> Unit,
    onIndent: () -> Unit,
    onOutdent: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRemove: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onSelect, onLongClick = { expanded = !expanded })
                .padding(start = (entry.depth * 16).dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = entry.title,
                // Depth reads as weight as well as indent: on a washed-out panel an indent alone is
                // easy to lose, and heading-versus-subheading is the whole point of nesting.
                fontSize = if (entry.depth == 0) 15.sp else 14.sp,
                fontWeight = if (entry.depth == 0) FontWeight.Bold else FontWeight.Normal,
                color = Color.Black,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (pageNumber != null) {
                Text(
                    text = "$pageNumber",
                    fontSize = 12.sp,
                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                    color = Color.Black,
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
        }

        // The row's actions, revealed by a long press rather than parked in a swipe: a swipe on
        // this panel is already the gesture that opened it.
        if (expanded) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = (entry.depth * 16).dp, bottom = 6.dp)
                    .horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ToolbarButton(imageVector = Icons.Filled.Edit, contentDescription = "Rename outline entry", onSelect = onRename)
                if (entry.depth > 0) {
                    ToolbarButton(
                        imageVector = Icons.Filled.KeyboardArrowLeft, contentDescription = "Outdent outline entry", onSelect = onOutdent
                    )
                }
                if (entry.depth < CouchOutlineEntry.MAX_DEPTH) {
                    ToolbarButton(
                        imageVector = Icons.Filled.KeyboardArrowRight, contentDescription = "Indent outline entry", onSelect = onIndent
                    )
                }
                if (canMoveUp) {
                    ToolbarButton(imageVector = Icons.Filled.KeyboardArrowUp, contentDescription = "Move outline entry up", onSelect = onMoveUp)
                }
                if (canMoveDown) {
                    ToolbarButton(
                        imageVector = Icons.Filled.KeyboardArrowDown, contentDescription = "Move outline entry down", onSelect = onMoveDown
                    )
                }
                Spacer(Modifier.width(8.dp))
                ToolbarButton(imageVector = Icons.Filled.Delete, contentDescription = "Remove outline entry", onSelect = onRemove)
            }
        }

        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(Color.LightGray)
        )
    }
}

/**
 * The pages starred in this notebook, in page order.
 *
 * Thumbnails rather than a list: a bookmark has no name — it is a page you recognised — so the page
 * itself is the only useful label, which is also why starring needs no prompt.
 */
@Composable
private fun BookmarksTab(
    pageIds: List<String>,
    allPageIds: List<String>,
    currentPageId: String?,
    onSelectPage: (String) -> Unit,
    onRemoveBookmark: (String) -> Unit,
) {
    if (pageIds.isEmpty()) {
        QuickNavEmpty(
            "No bookmarks yet. Tap the bookmark above to star this page — bookmarks sync, so a "
                + "page starred here is starred on the iPad too."
        )
        return
    }

    LazyHorizontalGrid(
        rows = GridCells.Fixed(1),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .height(150.dp)
            .autoEInkAnimationOnScroll()
    ) {
        gridItems(pageIds) { pageId ->
            PageThumbnail(
                pageId = pageId,
                label = allPageIds.indexOf(pageId).takeIf { it >= 0 }?.plus(1)?.toString() ?: "",
                isCurrent = pageId == currentPageId,
                isBookmarked = true,
                onClick = { onSelectPage(pageId) },
                onLongClick = { onRemoveBookmark(pageId) },
            )
        }
    }
}

/**
 * The global jump list: pages pinned from anywhere in the library.
 *
 * Kept as it was, renamed from "favourites" so it is not confused with the notebook's own
 * bookmarks. It stays on this device — nothing in the sync protocol describes a page one device
 * finds handy — which is exactly the difference the two names have to carry.
 */
@Composable
private fun PinnedTab(
    appRepository: AppRepository?,
    pages: List<Page>,
    currentPageId: String?,
    isCurrentPagePinned: Boolean,
    canPin: Boolean,
    onTogglePinned: () -> Unit,
    onSelectPage: (String) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = if (isCurrentPagePinned) "This page is pinned" else "Pin this page",
                fontSize = 13.sp,
                color = Color.Black,
                modifier = Modifier.weight(1f)
            )
            ToolbarButton(
                imageVector = if (isCurrentPagePinned) {
                    Icons.Filled.Favorite
                } else {
                    Icons.Filled.FavoriteBorder
                },
                onSelect = {
                    if (canPin) onTogglePinned() else logQuickNav.w("pin ignored, pageId=null")
                }
            )
        }

        Spacer(Modifier.height(6.dp))

        if (pages.isEmpty()) {
            QuickNavEmpty("Pinned pages stay reachable from every notebook, on this device.")
        } else if (appRepository != null) {
            ShowPagesRow(
                appRepository = appRepository,
                pages = pages,
                currentPageId = currentPageId,
                title = null,
                onSelectPage = onSelectPage
            )
        }
    }
}

// endregion

// region Shared

/** One page, as a thumbnail. A starred page carries a solid tab in its corner. */
@Composable
private fun PageThumbnail(
    pageId: String,
    label: String,
    isCurrent: Boolean,
    isBookmarked: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Column(Modifier.width(100.dp)) {
        Box {
            PagePreview(
                modifier = Modifier
                    .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                    .fillMaxWidth()
                    .aspectRatio(3f / 4f)
                    .border(if (isCurrent) 4.dp else 1.dp, Color.Black, RectangleShape),
                pageId = pageId,
            )
            if (isBookmarked) {
                // A solid tab, not a tint: a colour wash is the first thing an e-ink panel loses.
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(end = 8.dp)
                        .width(8.dp)
                        .height(18.dp)
                        .background(Color.Black)
                )
            }
        }
        Spacer(Modifier.height(2.dp))
        Text(text = label, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** What a tab shows before the reader has put anything in it, or when it cannot apply. */
@Composable
private fun QuickNavEmpty(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 24.dp, horizontal = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text = text, fontSize = 13.sp, color = Color.DarkGray)
    }
}

/** Star, or name a section, without leaving the page you are looking at. */
@Composable
private fun PageActionsDialog(
    pageLabel: String,
    isBookmarked: Boolean,
    onToggleBookmark: () -> Unit,
    onAddToOutline: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var naming by remember { mutableStateOf(false) }

    if (naming) {
        NamePromptDialog(
            title = "Add to outline",
            // Seeded with the page's own label: an entry for page 3 is very often called
            // something the reader would rather type over than delete first.
            initialValue = pageLabel,
            onConfirm = { title ->
                if (title.isNotBlank()) onAddToOutline(title.trim()) else onDismiss()
            },
            onDismiss = onDismiss,
        )
        return
    }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .background(Color.White)
                .border(1.dp, Color.Black, RectangleShape)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(text = pageLabel, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Spacer(Modifier.height(6.dp))
            Text(
                text = if (isBookmarked) "Remove bookmark" else "Bookmark",
                fontSize = 16.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .noRippleClickable(onClick = onToggleBookmark)
                    .padding(vertical = 10.dp)
            )
            Text(
                text = "Add to outline",
                fontSize = 16.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .noRippleClickable { naming = true }
                    .padding(vertical = 10.dp)
            )
        }
    }
}

// endregion

@Preview(showBackground = true)
@Composable
fun QuickNavContentPreview() {
    QuickNavContent(
        appRepository = null,
        uiState = QuickNavUiState(
            pinnedPages = listOf(Page(id = "page1", notebookId = "book1")),
            isLoading = false,
            currentPageId = "page1",
            folderId = "folder1",
            bookId = "book1",
            isCurrentPagePinned = true,
            isCurrentPageBookmarked = true,
            bookPageIds = listOf("page1", "page2", "page3"),
            bookmarkedPageIds = listOf("page1"),
            outline = listOf(
                CouchOutlineEntry(id = "e1", pageId = "page1", title = "Intro", depth = 0),
                CouchOutlineEntry(id = "e2", pageId = "page2", title = "Method", depth = 1),
            ),
            bookPageCount = 10,
            currentBookIndex = 4,
            favoriteIndexesInBook = listOf(0, 4, 9)
        ),
        onClose = {},
        onNavigateBreadcrumb = {},
        onSelectTab = {},
        onToggleBookmark = {},
        onSetBookmark = { _, _ -> },
        onTogglePinned = {},
        onAddOutlineEntry = { _, _ -> },
        onRenameOutlineEntry = { _, _ -> },
        onChangeOutlineDepth = { _, _ -> },
        onRemoveOutlineEntry = {},
        onMoveOutlineEntry = { _, _ -> },
        onGenerateBookPreviews = {},
        onScrubStart = {},
        onScrubPreview = {},
        onScrubEnd = {},
        onReturnClick = {},
        goToPage = {},
    )
}
