package eu.kanade.presentation.directory

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.manga.components.MangaCover
import eu.kanade.tachiyomi.data.coil.ComicThumbnail
import eu.kanade.tachiyomi.ui.directory.DirectoryViewModel
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.Folder
import mihon.icons.materialsymbols.rounded.Refresh
import mihon.icons.materialsymbols.rounded.Settings
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.FastScrollLazyColumn
import tachiyomi.presentation.core.components.material.DISABLED_ALPHA
import tachiyomi.presentation.core.components.material.PullRefresh
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.EmptyScreenAction
import tachiyomi.presentation.core.screens.LoadingScreen
import tachiyomi.presentation.core.util.secondaryItemAlpha
import tachiyomi.source.local.io.DirectoryEntry

@Composable
fun DirectoryScreen(
    state: DirectoryViewModel.State,
    onClickUp: () -> Unit,
    onRefresh: () -> Unit,
    onClickFolder: (DirectoryViewModel.DirectoryFolder) -> Unit,
    onClickComic: (DirectoryEntry) -> Unit,
    onClickSettings: () -> Unit,
) {
    Scaffold(
        topBar = { scrollBehavior ->
            AppBar(
                title = state.path.lastOrNull()?.name ?: stringResource(MR.strings.label_folders),
                navigateUp = onClickUp.takeIf { state.path.isNotEmpty() },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { contentPadding ->
        val roots = state.roots
        val folder = state.folder
        // Each open folder keeps its scroll position for when it is shown again
        val stateHolder = rememberSaveableStateHolder()
        val openFolderUris = remember(state.path) { state.path.map { it.uri } }
        val previousOpenFolderUris = remember { arrayListOf<String>() }
        LaunchedEffect(openFolderUris) {
            // The folders left are forgotten, so the saved state doesn't grow with every folder browsed
            previousOpenFolderUris.filterNot { it in openFolderUris }.forEach(stateHolder::removeState)
            previousOpenFolderUris.clear()
            previousOpenFolderUris.addAll(openFolderUris)
        }
        Box {
            when {
                roots == null -> LoadingScreen(Modifier.padding(contentPadding))
                roots.none { it.uri != null } -> DirectoryPullRefresh(state, onRefresh, contentPadding) {
                    EmptyScreen(
                        stringRes = MR.strings.directory_no_folders,
                        modifier = Modifier.padding(contentPadding),
                        actions = listOf(
                            EmptyScreenAction(
                                stringRes = MR.strings.label_data_storage,
                                icon = MaterialSymbols.Rounded.Settings,
                                onClick = onClickSettings,
                            ),
                        ),
                    )
                }
                folder == null -> stateHolder.SaveableStateProvider(key = ROOTS_KEY) {
                    DirectoryPullRefresh(state, onRefresh, contentPadding) {
                        RootList(
                            roots = roots,
                            listState = rememberLazyListState(),
                            contentPadding = contentPadding,
                            onClickFolder = onClickFolder,
                        )
                    }
                }
                else -> stateHolder.SaveableStateProvider(key = folder.uri) {
                    // Outside the listing, so the position stays while the folder is listed again
                    val listState = rememberLazyListState()
                    DirectoryPullRefresh(state, onRefresh, contentPadding) {
                        when (val listing = state.listing) {
                            DirectoryViewModel.Listing.Loading -> LoadingScreen(Modifier.padding(contentPadding))
                            DirectoryViewModel.Listing.Unreadable -> EmptyScreen(
                                stringRes = MR.strings.directory_folder_unreadable,
                                modifier = Modifier.padding(contentPadding),
                                actions = listOf(
                                    EmptyScreenAction(
                                        stringRes = MR.strings.action_retry,
                                        icon = MaterialSymbols.Rounded.Refresh,
                                        onClick = onRefresh,
                                    ),
                                ),
                            )
                            is DirectoryViewModel.Listing.Entries -> if (listing.entries.isEmpty()) {
                                EmptyScreen(
                                    stringRes = MR.strings.directory_folder_empty,
                                    modifier = Modifier.padding(contentPadding),
                                )
                            } else {
                                EntryList(
                                    entries = listing.entries,
                                    chapters = state.chapters,
                                    listState = listState,
                                    contentPadding = contentPadding,
                                    onClickFolder = onClickFolder,
                                    onClickComic = onClickComic,
                                )
                            }
                        }
                    }
                }
            }

            if (state.isOpening) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .padding(top = contentPadding.calculateTopPadding())
                        .fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun DirectoryPullRefresh(
    state: DirectoryViewModel.State,
    onRefresh: () -> Unit,
    contentPadding: PaddingValues,
    content: @Composable () -> Unit,
) {
    PullRefresh(
        refreshing = state.isRefreshing,
        enabled = true,
        onRefresh = onRefresh,
        indicatorPadding = PaddingValues(top = contentPadding.calculateTopPadding()),
        content = content,
    )
}

@Composable
private fun RootList(
    roots: List<DirectoryViewModel.DirectoryRoot>,
    listState: LazyListState,
    contentPadding: PaddingValues,
    onClickFolder: (DirectoryViewModel.DirectoryFolder) -> Unit,
) {
    FastScrollLazyColumn(
        modifier = Modifier.fillMaxSize(),
        state = listState,
        contentPadding = contentPadding,
    ) {
        items(roots) { root ->
            val folder = root.folder
            DirectoryItem(
                title = if (root.isDefault) stringResource(MR.strings.pref_series_folders_default) else root.name,
                subtitle = if (folder != null) root.path else stringResource(MR.strings.directory_folder_unavailable),
                onClick = folder?.let { { onClickFolder(it) } },
                dimmed = folder == null,
            ) {
                FolderIcon()
            }
        }
    }
}

@Composable
private fun EntryList(
    entries: List<DirectoryEntry>,
    chapters: Map<String, Chapter>,
    listState: LazyListState,
    contentPadding: PaddingValues,
    onClickFolder: (DirectoryViewModel.DirectoryFolder) -> Unit,
    onClickComic: (DirectoryEntry) -> Unit,
) {
    // Fills the screen, so pulling to refresh works below a short list too
    FastScrollLazyColumn(
        modifier = Modifier.fillMaxSize(),
        state = listState,
        contentPadding = contentPadding,
    ) {
        items(
            items = entries,
            key = { it.uri },
            contentType = { it.isDirectory },
        ) { entry ->
            if (entry.isDirectory) {
                DirectoryItem(
                    title = entry.title,
                    subtitle = null,
                    onClick = { onClickFolder(DirectoryViewModel.DirectoryFolder(entry.uri, entry.name)) },
                    dimmed = false,
                ) {
                    FolderIcon()
                }
            } else {
                val chapter = chapters[entry.uri]
                DirectoryItem(
                    title = entry.title,
                    subtitle = when {
                        chapter == null -> null
                        chapter.read -> stringResource(MR.strings.directory_comic_read)
                        chapter.lastPageRead > 0 -> stringResource(
                            MR.strings.chapter_progress,
                            chapter.lastPageRead + 1,
                        )
                        else -> null
                    },
                    onClick = { onClickComic(entry) },
                    dimmed = chapter?.read == true,
                ) {
                    MangaCover.Book(
                        data = ComicThumbnail(entry.uri, entry.lastModified, entry.size),
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}

/**
 * A row of the list, with [image] in a space shaped like a cover, so folders and comics line up.
 */
@Composable
private fun DirectoryItem(
    title: String,
    subtitle: String?,
    onClick: (() -> Unit)?,
    dimmed: Boolean,
    image: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier
            .clickable(enabled = onClick != null, onClick = { onClick?.invoke() })
            .height(DirectoryItemHeight)
            .padding(horizontal = MaterialTheme.padding.medium, vertical = MaterialTheme.padding.small)
            .alpha(if (dimmed) DISABLED_ALPHA else 1f),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .aspectRatio(MangaCover.Book.ratio),
            contentAlignment = Alignment.Center,
        ) {
            image()
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = MaterialTheme.padding.medium),
        ) {
            Text(
                text = title,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    modifier = Modifier.secondaryItemAlpha(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun FolderIcon() {
    Icon(
        imageVector = MaterialSymbols.Rounded.Folder,
        contentDescription = null,
        modifier = Modifier.size(32.dp),
        tint = MaterialTheme.colorScheme.primary,
    )
}

private val DirectoryItemHeight = 72.dp

private const val ROOTS_KEY = "roots"
