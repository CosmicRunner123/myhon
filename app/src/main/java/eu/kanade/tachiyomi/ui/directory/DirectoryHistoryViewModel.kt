package eu.kanade.tachiyomi.ui.directory

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import eu.kanade.core.util.insertSeparators
import eu.kanade.presentation.directory.DirectoryHistoryUiModel
import eu.kanade.tachiyomi.util.lang.toLocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import logcat.LogPriority
import tachiyomi.core.common.util.lang.SubscriberGate
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.history.interactor.GetDirectoryHistory
import tachiyomi.domain.history.interactor.RemoveDirectoryHistory
import tachiyomi.domain.history.model.DirectoryHistory
import kotlin.time.Duration.Companion.seconds

@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
class DirectoryHistoryViewModel(
    private val context: Context,
    private val getDirectoryHistory: GetDirectoryHistory,
    private val removeDirectoryHistory: RemoveDirectoryHistory,
) : ViewModel() {

    private val subscriberGate = SubscriberGate()

    private val _events: Channel<Event> = Channel(Channel.UNLIMITED)
    val events: Flow<Event> = _events.receiveAsFlow()

    private val searchQuery = MutableStateFlow<String?>(null)

    private val dialog = MutableStateFlow<Dialog?>(null)

    private val history = searchQuery
        .flatMapLatest { query ->
            getDirectoryHistory.subscribe()
                .distinctUntilChanged()
                .catch { error ->
                    logcat(LogPriority.ERROR, error)
                    _events.send(Event.InternalError)
                }
                .map { it.toUiModels(query.orEmpty()) }
                .flowOn(Dispatchers.Default)
        }
        .stateIn(viewModelScope + subscriberGate, SharingStarted.Eagerly, emptyList())

    val state: StateFlow<State> = combine(
        searchQuery,
        history,
        dialog,
    ) { searchQuery, history, dialog ->
        State(searchQuery = searchQuery, list = history, dialog = dialog)
    }
        .stateIn(viewModelScope + subscriberGate, subscriberGate.whileSubscribed(5.seconds), State())

    private fun List<DirectoryHistory>.toUiModels(query: String): List<DirectoryHistoryUiModel> {
        // Here rather than in SQL, as older entries store another name than the one shown
        return filter { it.fileName.contains(query, ignoreCase = true) }
            .map { DirectoryHistoryUiModel.Item(it) }
            .insertSeparators { before, after ->
                val beforeDate = before?.item?.lastRead?.toLocalDate()
                val afterDate = after?.item?.lastRead?.toLocalDate()
                if (beforeDate != afterDate && afterDate != null) DirectoryHistoryUiModel.Header(afterDate) else null
            }
    }

    /**
     * Where to go on reading from the file read last, like the Resume button of the Folders tab. Runs in the
     * caller's scope, so leaving the tab cancels it instead of opening the reader later.
     */
    suspend fun findResume(): Event {
        return try {
            val lastRead = getDirectoryHistory.subscribeLast().first()
            val file = lastRead?.let { findFileToResume(context, it) }
            if (lastRead != null && file != null) {
                Event.OpenFile(lastRead.folderUri, lastRead.folderName, file.uri)
            } else {
                Event.NoNextChapter
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Failed to resume the directory history" }
            Event.OpenFailed(e.message)
        }
    }

    fun removeFromHistory(history: DirectoryHistory) {
        viewModelScope.launch {
            removeDirectoryHistory.await(history.uri)
        }
    }

    fun removeAllHistory() {
        viewModelScope.launch {
            val result = removeDirectoryHistory.awaitAll()
            if (!result) return@launch
            _events.send(Event.HistoryCleared)
        }
    }

    fun updateSearchQuery(query: String?) {
        searchQuery.update { query }
    }

    fun setDialog(dialog: Dialog?) {
        this.dialog.update { dialog }
    }

    @Immutable
    data class State(
        val searchQuery: String? = null,
        val list: List<DirectoryHistoryUiModel>? = null,
        val dialog: Dialog? = null,
    )

    sealed interface Dialog {
        data object DeleteAll : Dialog
        data class Delete(val history: DirectoryHistory) : Dialog
    }

    sealed interface Event {
        data class OpenFile(val folderUri: String, val folderName: String, val fileUri: String) : Event
        data class OpenFailed(val message: String?) : Event
        data object NoNextChapter : Event
        data object InternalError : Event
        data object HistoryCleared : Event
    }
}
