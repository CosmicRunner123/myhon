package eu.kanade.tachiyomi.ui.directory

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.activity.compose.ReportDrawnWhen
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.tab.LocalTabNavigator
import cafe.adriel.voyager.navigator.tab.TabOptions
import dev.zacsweers.metrox.viewmodel.metroViewModel
import eu.kanade.presentation.directory.DirectoryScreen
import eu.kanade.presentation.util.Tab
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.util.storage.rememberAllFilesAccessRequest
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.Folder
import mihon.icons.materialsymbols.roundedfilled.Folder
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

/**
 * The first tab in directory view, which browses the device's storage like a file explorer instead of showing the
 * library.
 */
data object DirectoryTab : Tab {

    private val goToTopLevelEvent = Channel<Unit>()

    override val options: TabOptions
        @Composable
        get() {
            val isSelected = LocalTabNavigator.current.current.key == key
            val image = if (isSelected) MaterialSymbols.RoundedFilled.Folder else MaterialSymbols.Rounded.Folder
            return TabOptions(
                index = 0u,
                title = stringResource(MR.strings.label_folders),
                icon = rememberVectorPainter(image),
            )
        }

    override suspend fun onReselect(navigator: Navigator) {
        goToTopLevelEvent.send(Unit)
    }

    @Composable
    override fun Content() {
        val context = LocalContext.current
        val viewModel = metroViewModel<DirectoryViewModel>()
        val state by viewModel.state.collectAsStateWithLifecycle()
        val requestAllFilesAccess = rememberAllFilesAccessRequest()
        val scope = rememberCoroutineScope()

        DirectoryScreen(
            state = state,
            onClickUp = viewModel::goUp,
            onRefresh = viewModel::refresh,
            onClickFolder = viewModel::openFolder,
            onClickComic = viewModel::openComic,
            onClickAllowAccess = requestAllFilesAccess,
            onClickResume = { scope.launch { viewModel.findResume()?.let { onEvent(context, it) } } },
        )

        BackHandler(enabled = state.path.isNotEmpty(), onBack = viewModel::goUp)

        LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.onResume() }

        LaunchedEffect(state.volumes) {
            if (state.volumes != DirectoryViewModel.Volumes.Loading) {
                (context as? MainActivity)?.ready = true
            }
        }
        // Lets startup be measured up to the volumes, not just their loading screen
        ReportDrawnWhen { state.volumes != DirectoryViewModel.Volumes.Loading }

        LaunchedEffect(Unit) {
            launch { goToTopLevelEvent.receiveAsFlow().collect { viewModel.goToTopLevel() } }
            launch { viewModel.events.collect { onEvent(context, it) } }
        }
    }

    private fun onEvent(context: Context, event: DirectoryViewModel.Event) {
        when (event) {
            is DirectoryViewModel.Event.OpenFile -> context.startActivity(
                ReaderActivity.newIntent(context, event.folderUri, event.folderName, event.fileUri),
            )
            is DirectoryViewModel.Event.OpenFailed -> context.toast(
                event.message ?: context.stringResource(MR.strings.unknown_error),
            )
            DirectoryViewModel.Event.NoNextChapter -> context.toast(MR.strings.no_next_chapter)
        }
    }
}
