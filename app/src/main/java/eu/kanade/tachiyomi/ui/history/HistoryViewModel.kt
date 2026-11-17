package eu.kanade.tachiyomi.ui.history

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import eu.kanade.core.util.insertSeparators
import eu.kanade.presentation.history.HistoryUiModel
import eu.kanade.tachiyomi.util.lang.toLocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
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
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.history.interactor.GetHistory
import tachiyomi.domain.history.interactor.GetNextChapters
import tachiyomi.domain.history.interactor.RemoveHistory
import tachiyomi.domain.history.model.HistoryWithRelations
import kotlin.time.Duration.Companion.seconds

@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
class HistoryViewModel(
    private val getHistory: GetHistory,
    private val getNextChapters: GetNextChapters,
    private val removeHistory: RemoveHistory,
) : ViewModel() {

    private val subscriberGate = SubscriberGate()

    val snackbarHostState: SnackbarHostState = SnackbarHostState()

    private val _events: Channel<Event> = Channel(Channel.UNLIMITED)
    val events: Flow<Event> = _events.receiveAsFlow()

    private val searchQuery = MutableStateFlow<String?>(null)

    private val dialog = MutableStateFlow<Dialog?>(null)

    private val history = searchQuery
        .flatMapLatest { query ->
            getHistory.subscribe(query ?: "")
                .distinctUntilChanged()
                .catch { error ->
                    logcat(LogPriority.ERROR, error)
                    _events.send(Event.InternalError)
                }
                .map { it.toHistoryUiModels() }
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

    private fun List<HistoryWithRelations>.toHistoryUiModels(): List<HistoryUiModel> {
        return map { HistoryUiModel.Item(it) }
            .insertSeparators { before, after ->
                val beforeDate = before?.item?.readAt?.toLocalDate()
                val afterDate = after?.item?.readAt?.toLocalDate()
                when {
                    beforeDate != afterDate && afterDate != null -> HistoryUiModel.Header(afterDate)
                    // Return null to avoid adding a separator between two items.
                    else -> null
                }
            }
    }

    suspend fun getNextChapter(): Chapter? {
        return getNextChapters.await().firstOrNull()
    }

    fun getNextChapterForManga(mangaId: Long, chapterId: Long) {
        viewModelScope.launch {
            sendNextChapterEvent(getNextChapters.await(mangaId, chapterId))
        }
    }

    private suspend fun sendNextChapterEvent(chapters: List<Chapter>) {
        val chapter = chapters.firstOrNull()
        _events.send(Event.OpenChapter(chapter))
    }

    fun removeFromHistory(history: HistoryWithRelations) {
        viewModelScope.launch {
            removeHistory.await(history)
        }
    }

    fun removeAllFromHistory(mangaId: Long) {
        viewModelScope.launch {
            removeHistory.await(mangaId)
        }
    }

    fun removeAllHistory() {
        viewModelScope.launch {
            val result = removeHistory.awaitAll()
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
        val list: List<HistoryUiModel>? = null,
        val dialog: Dialog? = null,
    )

    sealed interface Dialog {
        data object DeleteAll : Dialog
        data class Delete(val history: HistoryWithRelations) : Dialog
    }

    sealed interface Event {
        data class OpenChapter(val chapter: Chapter?) : Event
        data object InternalError : Event
        data object HistoryCleared : Event
    }
}
