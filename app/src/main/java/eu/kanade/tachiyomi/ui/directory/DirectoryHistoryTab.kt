package eu.kanade.tachiyomi.ui.directory

import android.content.Context
import androidx.compose.animation.graphics.res.animatedVectorResource
import androidx.compose.animation.graphics.res.rememberAnimatedVectorPainter
import androidx.compose.animation.graphics.vector.AnimatedImageVector
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.tab.LocalTabNavigator
import cafe.adriel.voyager.navigator.tab.TabOptions
import dev.zacsweers.metrox.viewmodel.metroViewModel
import eu.kanade.presentation.directory.DirectoryHistoryDeleteAllDialog
import eu.kanade.presentation.directory.DirectoryHistoryDeleteDialog
import eu.kanade.presentation.directory.DirectoryHistoryScreen
import eu.kanade.presentation.util.Tab
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.receiveAsFlow
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

/**
 * The History tab in directory view, which lists the comic files read instead of the library's series.
 */
data object DirectoryHistoryTab : Tab {

    private val snackbarHostState = SnackbarHostState()

    private val resumeLastReadEvent = Channel<Unit>()

    override val options: TabOptions
        @Composable
        get() {
            val isSelected = LocalTabNavigator.current.current.key == key
            val image = AnimatedImageVector.animatedVectorResource(R.drawable.animated_history)
            return TabOptions(
                index = 1u,
                title = stringResource(MR.strings.label_recent_manga),
                icon = rememberAnimatedVectorPainter(image, isSelected),
            )
        }

    override suspend fun onReselect(navigator: Navigator) {
        resumeLastReadEvent.send(Unit)
    }

    @Composable
    override fun Content() {
        val context = LocalContext.current
        val viewModel = metroViewModel<DirectoryHistoryViewModel>()
        val state by viewModel.state.collectAsStateWithLifecycle()

        DirectoryHistoryScreen(
            state = state,
            snackbarHostState = snackbarHostState,
            onSearchQueryChange = viewModel::updateSearchQuery,
            onClickFile = {
                context.startActivity(ReaderActivity.newIntent(context, it.folderUri, it.folderName, it.uri))
            },
            onDialogChange = viewModel::setDialog,
        )

        val onDismissRequest = { viewModel.setDialog(null) }
        when (val dialog = state.dialog) {
            is DirectoryHistoryViewModel.Dialog.Delete -> {
                DirectoryHistoryDeleteDialog(
                    onDismissRequest = onDismissRequest,
                    onDelete = { viewModel.removeFromHistory(dialog.history) },
                )
            }
            is DirectoryHistoryViewModel.Dialog.DeleteAll -> {
                DirectoryHistoryDeleteAllDialog(
                    onDismissRequest = onDismissRequest,
                    onDelete = viewModel::removeAllHistory,
                )
            }
            null -> {}
        }

        LaunchedEffect(state.list) {
            if (state.list != null) {
                (context as? MainActivity)?.ready = true
            }
        }

        LaunchedEffect(Unit) {
            viewModel.events.collectLatest { onEvent(context, it) }
        }

        LaunchedEffect(Unit) {
            resumeLastReadEvent.receiveAsFlow().collectLatest { onEvent(context, viewModel.findResume()) }
        }
    }

    private suspend fun onEvent(context: Context, event: DirectoryHistoryViewModel.Event) {
        when (event) {
            is DirectoryHistoryViewModel.Event.OpenFile -> context.startActivity(
                ReaderActivity.newIntent(context, event.folderUri, event.folderName, event.fileUri),
            )
            is DirectoryHistoryViewModel.Event.OpenFailed -> snackbarHostState.showSnackbar(
                event.message ?: context.stringResource(MR.strings.unknown_error),
            )
            DirectoryHistoryViewModel.Event.NoNextChapter ->
                snackbarHostState.showSnackbar(context.stringResource(MR.strings.no_next_chapter))
            DirectoryHistoryViewModel.Event.InternalError ->
                snackbarHostState.showSnackbar(context.stringResource(MR.strings.internal_error))
            DirectoryHistoryViewModel.Event.HistoryCleared ->
                snackbarHostState.showSnackbar(context.stringResource(MR.strings.clear_history_completed))
        }
    }
}
