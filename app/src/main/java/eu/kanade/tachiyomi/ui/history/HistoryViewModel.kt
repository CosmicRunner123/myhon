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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
import kotlinx.coroutines.withContext
import logcat.LogPriority
import mihon.domain.source.interactor.OpenDirectoryComic
import tachiyomi.core.common.util.lang.SubscriberGate
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.history.interactor.GetHistory
import tachiyomi.domain.history.interactor.GetNextChapters
import tachiyomi.domain.history.interactor.RemoveHistory
import tachiyomi.domain.history.model.HistoryWithRelations
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.source.local.DirectorySource
import kotlin.time.Duration.Companion.seconds

@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
class HistoryViewModel(
    private val getHistory: GetHistory,
    private val getNextChapters: GetNextChapters,
    private val getManga: GetManga,
    private val openDirectoryComic: OpenDirectoryComic,
    private val removeHistory: RemoveHistory,
) : ViewModel() {

    private val subscriberGate = SubscriberGate()

    val snackbarHostState: SnackbarHostState = SnackbarHostState()

    private val _events: Channel<Event> = Channel(Channel.UNLIMITED)
    val events: Flow<Event> = _events.receiveAsFlow()

    private val searchQuery = MutableStateFlow<String?>(null)

    private val dialog = MutableStateFlow<Dialog?>(null)

    private var resumeJob: Job? = null

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

    fun getNextChapter() {
        resume {
            val history = getHistory.awaitLast() ?: return@resume null
            nextChapter(history.mangaId, history.chapterId)
        }
    }

    fun getNextChapterForManga(mangaId: Long, chapterId: Long) {
        resume { nextChapter(mangaId, chapterId) }
    }

    /**
     * Sends the chapter [block] finds to be opened, unless one is still being found: a directory view folder is
     * listed first, which can take a while.
     */
    private fun resume(block: suspend () -> Chapter?) {
        if (resumeJob?.isActive == true) return
        resumeJob = viewModelScope.launch {
            val event = try {
                Event.OpenChapter(block())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e)
                Event.OpenFailed(e.message)
            }
            _events.send(event)
        }
    }

    private suspend fun nextChapter(mangaId: Long, chapterId: Long): Chapter? {
        val manga = getManga.await(mangaId)
        if (manga?.source == DirectorySource.ID) {
            return withContext(Dispatchers.IO) { openDirectoryComic.resume(manga, chapterId) }
        }
        return getNextChapters.await(mangaId, chapterId).firstOrNull()
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
        data class OpenFailed(val message: String?) : Event
        data object InternalError : Event
        data object HistoryCleared : Event
    }
}
