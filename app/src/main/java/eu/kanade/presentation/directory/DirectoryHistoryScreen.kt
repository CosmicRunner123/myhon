package eu.kanade.presentation.directory

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarActions
import eu.kanade.presentation.components.AppBarTitle
import eu.kanade.presentation.components.SearchToolbar
import eu.kanade.presentation.components.relativeDateText
import eu.kanade.presentation.manga.components.MangaCover
import eu.kanade.presentation.util.rememberIs24HourSystem
import eu.kanade.tachiyomi.data.coil.ComicThumbnail
import eu.kanade.tachiyomi.ui.directory.DirectoryHistoryViewModel
import eu.kanade.tachiyomi.util.lang.toTimestampString
import kotlinx.datetime.LocalDate
import mihon.app.di.appGraph
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.Delete
import mihon.icons.materialsymbols.rounded.DeleteSweep
import tachiyomi.domain.history.model.DirectoryHistory
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.FastScrollLazyColumn
import tachiyomi.presentation.core.components.ListGroupHeader
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.LoadingScreen
import tachiyomi.presentation.core.util.collectAsState
import tachiyomi.presentation.core.util.secondaryItemAlpha
import java.time.format.DateTimeFormatter

/**
 * The History tab of directory view, which lists the comic files read.
 */
@Composable
fun DirectoryHistoryScreen(
    state: DirectoryHistoryViewModel.State,
    snackbarHostState: SnackbarHostState,
    onSearchQueryChange: (String?) -> Unit,
    onClickFile: (DirectoryHistory) -> Unit,
    onDialogChange: (DirectoryHistoryViewModel.Dialog?) -> Unit,
) {
    Scaffold(
        topBar = { scrollBehavior ->
            SearchToolbar(
                titleContent = { AppBarTitle(stringResource(MR.strings.history)) },
                searchQuery = state.searchQuery,
                onChangeSearchQuery = onSearchQueryChange,
                actions = {
                    AppBarActions(
                        listOf(
                            AppBar.Action(
                                title = stringResource(MR.strings.pref_clear_history),
                                icon = MaterialSymbols.Rounded.DeleteSweep,
                                onClick = { onDialogChange(DirectoryHistoryViewModel.Dialog.DeleteAll) },
                            ),
                        ),
                    )
                },
                scrollBehavior = scrollBehavior,
            )
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
    ) { contentPadding ->
        val list = state.list
        when {
            list == null -> LoadingScreen(Modifier.padding(contentPadding))
            list.isEmpty() -> EmptyScreen(
                stringRes = if (!state.searchQuery.isNullOrEmpty()) {
                    MR.strings.no_results_found
                } else {
                    MR.strings.information_no_recent_manga
                },
                modifier = Modifier.padding(contentPadding),
            )
            else -> DirectoryHistoryContent(
                history = list,
                contentPadding = contentPadding,
                onClickFile = onClickFile,
                onClickDelete = { onDialogChange(DirectoryHistoryViewModel.Dialog.Delete(it)) },
            )
        }
    }
}

@Composable
private fun DirectoryHistoryContent(
    history: List<DirectoryHistoryUiModel>,
    contentPadding: PaddingValues,
    onClickFile: (DirectoryHistory) -> Unit,
    onClickDelete: (DirectoryHistory) -> Unit,
) {
    val context = LocalContext.current
    val locale = LocalLocale.current
    val is24HourSystem = rememberIs24HourSystem()
    val timeFormat by remember { context.appGraph.uiPreferences.timeFormat }.collectAsState()
    val timeFormatter = remember(timeFormat, locale, is24HourSystem) {
        UiPreferences.timeFormat(timeFormat, locale.platformLocale, is24HourSystem)
    }

    FastScrollLazyColumn(
        contentPadding = contentPadding,
    ) {
        items(
            items = history,
            key = {
                when (it) {
                    is DirectoryHistoryUiModel.Header -> "header-${it.date}"
                    is DirectoryHistoryUiModel.Item -> it.item.uri
                }
            },
            contentType = {
                when (it) {
                    is DirectoryHistoryUiModel.Header -> "header"
                    is DirectoryHistoryUiModel.Item -> "item"
                }
            },
        ) { item ->
            when (item) {
                is DirectoryHistoryUiModel.Header -> ListGroupHeader(
                    modifier = Modifier.animateItem(),
                    text = relativeDateText(item.date),
                )
                is DirectoryHistoryUiModel.Item -> DirectoryHistoryItem(
                    modifier = Modifier.animateItem(),
                    history = item.item,
                    timeFormatter = timeFormatter,
                    onClick = { onClickFile(item.item) },
                    onClickDelete = { onClickDelete(item.item) },
                )
            }
        }
    }
}

@Composable
private fun DirectoryHistoryItem(
    history: DirectoryHistory,
    timeFormatter: DateTimeFormatter,
    onClick: () -> Unit,
    onClickDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .clickable(onClick = onClick)
            .height(DirectoryHistoryItemHeight)
            .padding(horizontal = MaterialTheme.padding.medium, vertical = MaterialTheme.padding.small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MangaCover.Book(
            modifier = Modifier.fillMaxHeight(),
            data = ComicThumbnail(history.uri, history.lastModified, history.size),
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = MaterialTheme.padding.medium, end = MaterialTheme.padding.small),
        ) {
            Text(
                text = history.name,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = history.folderName,
                modifier = Modifier.secondaryItemAlpha(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
            )
            val readAt = remember(timeFormatter, history.lastRead) {
                history.lastRead.toTimestampString(timeFormatter)
            }
            Text(
                text = if (history.read) {
                    stringResource(MR.strings.directory_history_read_time, readAt)
                } else {
                    stringResource(MR.strings.directory_history_page_time, history.lastPageRead + 1, readAt)
                },
                modifier = Modifier.padding(top = 4.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        IconButton(onClick = onClickDelete) {
            Icon(
                imageVector = MaterialSymbols.Rounded.Delete,
                contentDescription = stringResource(MR.strings.action_delete),
                tint = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
fun DirectoryHistoryDeleteDialog(
    onDismissRequest: () -> Unit,
    onDelete: () -> Unit,
) {
    AlertDialog(
        title = {
            Text(text = stringResource(MR.strings.action_remove))
        },
        text = {
            Text(text = stringResource(MR.strings.directory_history_remove_description))
        },
        onDismissRequest = onDismissRequest,
        confirmButton = {
            TextButton(onClick = {
                onDelete()
                onDismissRequest()
            }) {
                Text(text = stringResource(MR.strings.action_remove))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(text = stringResource(MR.strings.action_cancel))
            }
        },
    )
}

@Composable
fun DirectoryHistoryDeleteAllDialog(
    onDismissRequest: () -> Unit,
    onDelete: () -> Unit,
) {
    AlertDialog(
        title = {
            Text(text = stringResource(MR.strings.action_remove_everything))
        },
        text = {
            Text(text = stringResource(MR.strings.directory_history_clear_description))
        },
        onDismissRequest = onDismissRequest,
        confirmButton = {
            TextButton(onClick = {
                onDelete()
                onDismissRequest()
            }) {
                Text(text = stringResource(MR.strings.action_ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(text = stringResource(MR.strings.action_cancel))
            }
        },
    )
}

sealed interface DirectoryHistoryUiModel {
    data class Header(val date: LocalDate) : DirectoryHistoryUiModel
    data class Item(val item: DirectoryHistory) : DirectoryHistoryUiModel
}

private val DirectoryHistoryItemHeight = 96.dp
