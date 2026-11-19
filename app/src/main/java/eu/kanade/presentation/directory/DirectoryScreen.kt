package eu.kanade.presentation.directory

import android.text.format.Formatter
import androidx.compose.animation.graphics.res.animatedVectorResource
import androidx.compose.animation.graphics.res.rememberAnimatedVectorPainter
import androidx.compose.animation.graphics.vector.AnimatedImageVector
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
import androidx.compose.material3.SmallExtendedFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.material3.animateFloatingActionButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.manga.components.MangaCover
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.coil.ComicThumbnail
import eu.kanade.tachiyomi.ui.directory.DirectoryViewModel
import eu.kanade.tachiyomi.ui.directory.DirectoryVolume
import eu.kanade.tachiyomi.ui.directory.fileName
import eu.kanade.tachiyomi.ui.directory.recentFolderLocation
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.Folder
import mihon.icons.materialsymbols.rounded.Mobile
import mihon.icons.materialsymbols.rounded.Refresh
import mihon.icons.materialsymbols.rounded.Security
import mihon.icons.materialsymbols.rounded.Storage
import mihon.icons.materialsymbols.roundedfilled.PlayArrow
import tachiyomi.domain.history.model.DirectoryHistory
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
    onClickRecent: () -> Unit,
    onClickComic: (DirectoryEntry) -> Unit,
    onClickAllowAccess: () -> Unit,
    onClickResume: () -> Unit,
) {
    Scaffold(
        topBar = { scrollBehavior ->
            AppBar(
                title = state.path.lastOrNull()?.name ?: stringResource(MR.strings.label_folders),
                navigateUp = onClickUp.takeIf { state.path.isNotEmpty() },
                scrollBehavior = scrollBehavior,
            )
        },
        floatingActionButton = {
            val lastRead = state.lastRead
            if (lastRead != null) {
                val description = stringResource(MR.strings.directory_resume, lastRead.fileName)
                SmallExtendedFloatingActionButton(
                    text = { Text(text = stringResource(MR.strings.action_resume)) },
                    icon = { Icon(imageVector = MaterialSymbols.RoundedFilled.PlayArrow, contentDescription = null) },
                    onClick = onClickResume,
                    modifier = Modifier
                        .animateFloatingActionButton(visible = state.folder == null, alignment = Alignment.BottomEnd)
                        .semantics { contentDescription = description },
                )
            }
        },
    ) { contentPadding ->
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
                folder == null -> when (val volumes = state.volumes) {
                    DirectoryViewModel.Volumes.Loading -> LoadingScreen(Modifier.padding(contentPadding))
                    DirectoryViewModel.Volumes.NoAccess -> DirectoryPullRefresh(state, onRefresh, contentPadding) {
                        EmptyScreen(
                            message = stringResource(
                                MR.strings.directory_access_info,
                                stringResource(MR.strings.app_name),
                            ),
                            modifier = Modifier.padding(contentPadding),
                            actions = listOf(
                                EmptyScreenAction(
                                    stringRes = MR.strings.directory_access_allow,
                                    icon = MaterialSymbols.Rounded.Security,
                                    onClick = onClickAllowAccess,
                                ),
                            ),
                        )
                    }
                    is DirectoryViewModel.Volumes.Listed -> stateHolder.SaveableStateProvider(key = VOLUMES_KEY) {
                        DirectoryPullRefresh(state, onRefresh, contentPadding) {
                            VolumeList(
                                volumes = volumes.volumes,
                                lastRead = state.lastRead,
                                listState = rememberLazyListState(),
                                contentPadding = contentPadding,
                                onClickFolder = onClickFolder,
                                onClickRecent = onClickRecent,
                            )
                        }
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
                                    history = state.history,
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
private fun VolumeList(
    volumes: List<DirectoryVolume>,
    lastRead: DirectoryHistory?,
    listState: LazyListState,
    contentPadding: PaddingValues,
    onClickFolder: (DirectoryViewModel.DirectoryFolder) -> Unit,
    onClickRecent: () -> Unit,
) {
    val context = LocalContext.current
    val atTop by remember {
        derivedStateOf { listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0 }
    }
    // Recent can be added after the volumes are listed, and the list would keep the first volume in view
    if (lastRead != null && atTop) listState.requestScrollToItem(0)
    FastScrollLazyColumn(
        modifier = Modifier.fillMaxSize(),
        state = listState,
        contentPadding = contentPadding,
    ) {
        if (lastRead != null) {
            item(key = RECENT_KEY) {
                DirectoryItem(
                    title = stringResource(MR.strings.directory_recent),
                    subtitle = remember(lastRead.folderUri, volumes) {
                        recentFolderLocation(lastRead.folderUri, volumes)
                    },
                    onClick = onClickRecent,
                    dimmed = false,
                ) {
                    // The History tab's icon, which only exists animated
                    val image = AnimatedImageVector.animatedVectorResource(R.drawable.animated_history)
                    ItemIcon(rememberAnimatedVectorPainter(image, atEnd = false))
                }
            }
        }
        items(items = volumes, key = { it.uri }) { volume ->
            DirectoryItem(
                title = volume.name,
                subtitle = stringResource(
                    MR.strings.directory_volume_space,
                    Formatter.formatShortFileSize(context, volume.freeBytes),
                    Formatter.formatShortFileSize(context, volume.totalBytes),
                ),
                onClick = { onClickFolder(DirectoryViewModel.DirectoryFolder(volume.uri, volume.name)) },
                dimmed = false,
            ) {
                ItemIcon(if (volume.isPrimary) MaterialSymbols.Rounded.Mobile else MaterialSymbols.Rounded.Storage)
            }
        }
    }
}

@Composable
private fun EntryList(
    entries: List<DirectoryEntry>,
    history: Map<String, DirectoryHistory>,
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
                    ItemIcon(MaterialSymbols.Rounded.Folder)
                }
            } else {
                val progress = history[entry.uri]
                DirectoryItem(
                    title = entry.name,
                    subtitle = when {
                        progress == null -> null
                        progress.read -> stringResource(MR.strings.directory_comic_read)
                        progress.lastPageRead > 0 -> stringResource(
                            MR.strings.chapter_progress,
                            progress.lastPageRead + 1,
                        )
                        else -> null
                    },
                    onClick = { onClickComic(entry) },
                    dimmed = progress?.read == true,
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
    onClick: () -> Unit,
    dimmed: Boolean,
    image: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier
            .clickable(onClick = onClick)
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
private fun ItemIcon(imageVector: ImageVector) {
    ItemIcon(rememberVectorPainter(imageVector))
}

@Composable
private fun ItemIcon(painter: Painter) {
    Icon(
        painter = painter,
        contentDescription = null,
        modifier = Modifier.size(32.dp),
        tint = MaterialTheme.colorScheme.primary,
    )
}

private val DirectoryItemHeight = 72.dp

private const val VOLUMES_KEY = "volumes"

private const val RECENT_KEY = "recent"
