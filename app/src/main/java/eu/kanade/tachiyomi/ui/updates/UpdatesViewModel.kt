package eu.kanade.tachiyomi.ui.updates

import android.content.Context
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import eu.kanade.core.preference.asState
import eu.kanade.core.util.addOrRemove
import eu.kanade.core.util.insertSeparators
import eu.kanade.domain.chapter.interactor.SetReadStatus
import eu.kanade.presentation.updates.UpdatesUiModel
import eu.kanade.tachiyomi.data.library.LibraryUpdateWorker
import eu.kanade.tachiyomi.util.lang.toLocalDate
import eu.kanade.tachiyomi.util.system.workManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
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
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import mihon.core.metro.AppCoroutineScope
import tachiyomi.core.common.preference.TriState
import tachiyomi.core.common.util.lang.SubscriberGate
import tachiyomi.domain.chapter.interactor.GetChapter
import tachiyomi.domain.chapter.interactor.UpdateChapter
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.updates.interactor.GetUpdates
import tachiyomi.domain.updates.model.UpdatesWithRelations
import tachiyomi.domain.updates.service.UpdatesPreferences
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
class UpdatesViewModel(
    @AppCoroutineScope private val appScope: CoroutineScope,
    private val context: Context,
    private val updateChapter: UpdateChapter,
    private val setReadStatus: SetReadStatus,
    private val getUpdates: GetUpdates,
    private val getChapter: GetChapter,
    private val libraryPreferences: LibraryPreferences,
    private val updatesPreferences: UpdatesPreferences,
) : ViewModel() {

    private val subscriberGate = SubscriberGate()

    val snackbarHostState: SnackbarHostState = SnackbarHostState()

    private val _events: Channel<Event> = Channel(Int.MAX_VALUE)
    val events: Flow<Event> = _events.receiveAsFlow()

    val lastUpdated by libraryPreferences.lastUpdatedTimestamp.asState(viewModelScope)

    // First and last selected index in list
    private val selectedPositions: Array<Int> = arrayOf(-1, -1)
    private val selectedChapterIds = MutableStateFlow(emptySet<Long>())

    private val dialog = MutableStateFlow<Dialog?>(null)

    private val hasActiveFilters = getUpdatesItemPreferenceFlow()
        .map { prefs ->
            listOf(
                prefs.filterUnread,
                prefs.filterStarted,
                prefs.filterBookmarked,
            )
                .any { it != TriState.DISABLED } ||
                prefs.filterExcludedScanlators ||
                listOf(
                    prefs.filterIncludedCategories,
                    prefs.filterExcludedCategories,
                )
                    .any { it.isNotEmpty() }
        }
        .distinctUntilChanged()

    private val updateItems = getUpdatesItemPreferenceFlow()
        .distinctUntilChanged()
        .flatMapLatest {
            getUpdates.subscribe(
                Clock.System.now().minus(3, DateTimeUnit.MONTH, TimeZone.currentSystemDefault()),
                unread = it.filterUnread.toBooleanOrNull(),
                started = it.filterStarted.toBooleanOrNull(),
                bookmarked = it.filterBookmarked.toBooleanOrNull(),
                hideExcludedScanlators = it.filterExcludedScanlators,
                includedCategories = it.filterIncludedCategories,
                excludedCategories = it.filterExcludedCategories,
            ).distinctUntilChanged()
        }
        .map { updates -> updates.map { UpdatesItem(it) } }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope + subscriberGate, SharingStarted.Eagerly, null)

    val state: StateFlow<State> = combine(
        updateItems,
        selectedChapterIds,
        dialog,
        hasActiveFilters,
    ) { items, selectedIds, dialog, hasActiveFilters ->
        State(
            isLoading = items == null,
            hasActiveFilters = hasActiveFilters,
            items = items.orEmpty().map { item ->
                item.copy(selected = item.update.chapterId in selectedIds)
            },
            dialog = dialog,
        )
    }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope + subscriberGate, subscriberGate.whileSubscribed(5.seconds), State())

    suspend fun updateLibrary(): Boolean {
        val started = LibraryUpdateWorker.startNow(context.workManager)
        _events.send(Event.LibraryUpdateTriggered(started))
        return started
    }

    /**
     * Mark the selected updates list as read/unread.
     * @param updates the list of selected updates.
     * @param read whether to mark chapters as read or unread.
     */
    fun markUpdatesRead(updates: List<UpdatesItem>, read: Boolean) {
        appScope.launch {
            setReadStatus.await(
                read = read,
                chapters = updates
                    .mapNotNull { getChapter.await(it.update.chapterId) }
                    .toTypedArray(),
            )
        }
        toggleAllSelection(false)
    }

    /**
     * Bookmarks the given list of chapters.
     * @param updates the list of chapters to bookmark.
     */
    fun bookmarkUpdates(updates: List<UpdatesItem>, bookmark: Boolean) {
        viewModelScope.launch {
            updates
                .filterNot { it.update.bookmark == bookmark }
                .map { ChapterUpdate(it.update.chapterId) { this.bookmark = bookmark } }
                .let { updateChapter.awaitAll(it) }
        }
        toggleAllSelection(false)
    }

    fun toggleSelection(
        item: UpdatesItem,
        selected: Boolean,
        fromLongPress: Boolean = false,
    ) {
        val items = state.value.items
        val selectedIndex = items.indexOfFirst { it.update.chapterId == item.update.chapterId }
        if (selectedIndex < 0) return

        // Read selection from its own flow, not the derived items, which lag behind it.
        val currentSelection = selectedChapterIds.value
        if ((item.update.chapterId in currentSelection) == selected) return

        // Only visible items count, since the id set can retain ids filtered out of the list
        val firstSelection = items.none { it.update.chapterId in currentSelection }
        val newSelection = currentSelection.toHashSet()
        newSelection.addOrRemove(item.update.chapterId, selected)

        if (selected && fromLongPress) {
            if (firstSelection) {
                selectedPositions[0] = selectedIndex
                selectedPositions[1] = selectedIndex
            } else {
                // Try to select the items in-between when possible
                val range: IntRange
                if (selectedIndex < selectedPositions[0]) {
                    range = selectedIndex + 1..<selectedPositions[0]
                    selectedPositions[0] = selectedIndex
                } else if (selectedIndex > selectedPositions[1]) {
                    range = (selectedPositions[1] + 1)..<selectedIndex
                    selectedPositions[1] = selectedIndex
                } else {
                    // Just select itself
                    range = IntRange.EMPTY
                }

                range.forEach { newSelection.add(items[it].update.chapterId) }
            }
        } else if (!fromLongPress) {
            if (!selected) {
                if (selectedIndex == selectedPositions[0]) {
                    selectedPositions[0] = items.indexOfFirst { it.update.chapterId in newSelection }
                } else if (selectedIndex == selectedPositions[1]) {
                    selectedPositions[1] = items.indexOfLast { it.update.chapterId in newSelection }
                }
            } else {
                if (selectedIndex < selectedPositions[0]) {
                    selectedPositions[0] = selectedIndex
                } else if (selectedIndex > selectedPositions[1]) {
                    selectedPositions[1] = selectedIndex
                }
            }
        }

        selectedChapterIds.update { newSelection }
    }

    fun toggleAllSelection(selected: Boolean) {
        val ids = if (selected) state.value.items.map { it.update.chapterId }.toSet() else emptySet()
        selectedChapterIds.update { ids }

        selectedPositions[0] = -1
        selectedPositions[1] = -1
    }

    fun invertSelection() {
        val current = selectedChapterIds.value
        val ids = state.value.items
            .map { it.update.chapterId }
            .filterNot { it in current }
            .toSet()
        selectedChapterIds.update { ids }

        selectedPositions[0] = -1
        selectedPositions[1] = -1
    }

    fun setDialog(dialog: Dialog?) {
        this.dialog.update { dialog }
    }

    fun resetNewUpdatesCount() {
        libraryPreferences.newUpdatesCount.set(0)
    }

    private fun getUpdatesItemPreferenceFlow(): Flow<ItemPreferences> {
        return combine(
            updatesPreferences.filterUnread.changes(),
            updatesPreferences.filterStarted.changes(),
            updatesPreferences.filterBookmarked.changes(),
            updatesPreferences.filterExcludedScanlators.changes(),
            updatesPreferences.filterIncludedCategories.changes(),
            updatesPreferences.filterExcludedCategories.changes(),
        ) {
            @Suppress("UNCHECKED_CAST")
            ItemPreferences(
                filterUnread = it[0] as TriState,
                filterStarted = it[1] as TriState,
                filterBookmarked = it[2] as TriState,
                filterExcludedScanlators = it[3] as Boolean,
                filterIncludedCategories = it[4] as List<Long>,
                filterExcludedCategories = it[5] as List<Long>,
            )
        }
    }

    fun showFilterDialog() {
        dialog.update { Dialog.FilterSheet }
    }

    @Immutable
    private data class ItemPreferences(
        val filterUnread: TriState,
        val filterStarted: TriState,
        val filterBookmarked: TriState,
        val filterExcludedScanlators: Boolean,
        val filterIncludedCategories: List<Long>,
        val filterExcludedCategories: List<Long>,
    )

    @Immutable
    data class State(
        val isLoading: Boolean = true,
        val hasActiveFilters: Boolean = false,
        val items: List<UpdatesItem> = listOf(),
        val dialog: Dialog? = null,
    ) {
        val selected = items.filter { it.selected }
        val selectionMode = selected.isNotEmpty()

        fun getUiModel(): List<UpdatesUiModel> {
            return items
                .map { UpdatesUiModel.Item(it) }
                .insertSeparators { before, after ->
                    val beforeDate = before?.item?.update?.dateFetch?.toLocalDate()
                    val afterDate = after?.item?.update?.dateFetch?.toLocalDate()
                    when {
                        beforeDate != afterDate && afterDate != null -> UpdatesUiModel.Header(afterDate)
                        // Return null to avoid adding a separator between two items.
                        else -> null
                    }
                }
        }
    }

    sealed interface Dialog {
        data object FilterSheet : Dialog
    }

    sealed interface Event {
        data object InternalError : Event
        data class LibraryUpdateTriggered(val started: Boolean) : Event
    }
}

private fun TriState.toBooleanOrNull(): Boolean? {
    return when (this) {
        TriState.DISABLED -> null
        TriState.ENABLED_IS -> true
        TriState.ENABLED_NOT -> false
    }
}

@Immutable
data class UpdatesItem(
    val update: UpdatesWithRelations,
    val selected: Boolean = false,
)
