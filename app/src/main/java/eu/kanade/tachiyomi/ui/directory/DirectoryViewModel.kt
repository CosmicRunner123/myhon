package eu.kanade.tachiyomi.ui.directory

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.core.net.toUri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metrox.viewmodel.ViewModelAssistedFactory
import dev.zacsweers.metrox.viewmodel.ViewModelAssistedFactoryKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import logcat.LogPriority
import tachiyomi.core.common.storage.hasAllFilesAccess
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.history.interactor.GetDirectoryHistory
import tachiyomi.domain.history.model.DirectoryHistory
import tachiyomi.source.local.io.DirectoryEntry
import tachiyomi.source.local.io.listDirectory

@AssistedInject
class DirectoryViewModel(
    @Assisted private val savedState: SavedStateHandle,
    private val context: Context,
    private val getDirectoryHistory: GetDirectoryHistory,
) : ViewModel() {

    @AssistedFactory
    @ViewModelAssistedFactoryKey(DirectoryViewModel::class)
    @ContributesIntoMap(AppScope::class)
    fun interface Factory : ViewModelAssistedFactory {
        override fun create(extras: CreationExtras): DirectoryViewModel {
            return create(extras.createSavedStateHandle())
        }

        fun create(@Assisted savedState: SavedStateHandle): DirectoryViewModel
    }

    // So browsing goes on in the same folder when the process was killed while reading
    private val _state = MutableStateFlow(
        State(
            path = savedState.get<List<String>>(KEY_PATH_URIS).orEmpty()
                .zip(savedState.get<List<String>>(KEY_PATH_NAMES).orEmpty(), ::DirectoryFolder),
        ),
    )
    val state: StateFlow<State> = _state.asStateFlow()

    private val _events = Channel<Event>(Channel.UNLIMITED)
    val events: Flow<Event> = _events.receiveAsFlow()

    private var loadJob: Job? = null

    // Kept until the volumes are listed, so a listing cancelled by navigating doesn't leave them stale
    private var volumesOutdated = false

    init {
        load(reloadVolumes = true)
        viewModelScope.launch {
            state
                .map { it.folder?.uri }
                .distinctUntilChanged()
                .flatMapLatest { folderUri ->
                    if (folderUri != null) getDirectoryHistory.subscribeByFolder(folderUri) else flowOf(emptyList())
                }
                .flowOn(Dispatchers.IO)
                .collect { history ->
                    _state.update { state -> state.copy(history = history.associateBy { it.uri }) }
                }
        }
        viewModelScope.launch {
            getDirectoryHistory.subscribeLast()
                .flowOn(Dispatchers.IO)
                .collect { history -> _state.update { it.copy(lastRead = history) } }
        }
    }

    fun openFolder(folder: DirectoryFolder) {
        setPath(state.value.path + folder)
        load()
    }

    fun goUp() {
        if (state.value.path.isEmpty()) return
        setPath(state.value.path.dropLast(1))
        // Access or the inserted volumes may have changed while in a folder
        load(reloadVolumes = state.value.path.isEmpty())
    }

    fun goToTopLevel() {
        if (state.value.path.isEmpty()) return
        setPath(emptyList())
        load(reloadVolumes = true)
    }

    private fun setPath(path: List<DirectoryFolder>) {
        _state.update { it.copy(path = path) }
        savedState[KEY_PATH_URIS] = ArrayList(path.map { it.uri })
        savedState[KEY_PATH_NAMES] = ArrayList(path.map { it.name })
    }

    fun refresh() {
        load(reloadVolumes = state.value.path.isEmpty(), refreshing = true)
    }

    /**
     * Lists the volumes again at the top level, as access may have been granted or a volume inserted or removed
     * meanwhile.
     */
    fun onResume() {
        val state = state.value
        // While loading, they are being listed already
        if (state.path.isEmpty() && state.volumes != Volumes.Loading) load(reloadVolumes = true)
    }

    /**
     * Lists the folder shown, after the volumes when [reloadVolumes]. While [refreshing], the current listing stays
     * until the new one replaces it.
     */
    private fun load(reloadVolumes: Boolean = false, refreshing: Boolean = false) {
        loadJob?.cancel()
        volumesOutdated = volumesOutdated || reloadVolumes
        _state.update {
            it.copy(
                listing = if (refreshing) it.listing else Listing.Loading,
                isRefreshing = refreshing,
            )
        }
        loadJob = viewModelScope.launch {
            if (volumesOutdated) {
                val volumes = withContext(Dispatchers.IO) {
                    if (context.hasAllFilesAccess()) Volumes.Listed(listStorageVolumes(context)) else Volumes.NoAccess
                }
                volumesOutdated = false
                _state.update { it.copy(volumes = volumes) }
            }

            val folder = state.value.folder
            val listing = if (folder != null) {
                listDirectory(context, folder.uri.toUri())?.let(Listing::Entries) ?: Listing.Unreadable
            } else {
                Listing.Loading
            }
            _state.update { it.copy(listing = listing, isRefreshing = false) }
        }
    }

    fun openComic(file: DirectoryEntry) {
        val folder = state.value.folder ?: return
        // A resume is about to open a file
        if (state.value.isOpening) return
        _events.trySend(Event.OpenFile(folder.uri, folder.name, file.uri))
    }

    /**
     * Where to go on reading from [State.lastRead], see [findFileToResume]; null when there is no file read last or a
     * resume is under way. Runs in the caller's scope, so leaving the tab cancels it instead of opening the reader
     * later.
     */
    suspend fun findResume(): Event? {
        val lastRead = state.value.lastRead ?: return null
        if (state.value.isOpening) return null
        _state.update { it.copy(isOpening = true) }
        return try {
            val file = findFileToResume(context, lastRead)
            if (file != null) {
                Event.OpenFile(lastRead.folderUri, lastRead.folderName, file.uri)
            } else {
                Event.NoNextChapter
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Failed to resume ${lastRead.uri}" }
            Event.OpenFailed(e.message)
        } finally {
            _state.update { it.copy(isOpening = false) }
        }
    }

    @Immutable
    data class State(
        /** The top level, where browsing starts. */
        val volumes: Volumes = Volumes.Loading,
        /** The folders opened from the top level, the one shown last. */
        val path: List<DirectoryFolder> = emptyList(),
        /** The contents of [folder]. */
        val listing: Listing = Listing.Loading,
        /** How far the files of [folder] were read, by their URI. */
        val history: Map<String, DirectoryHistory> = emptyMap(),
        /** The file read last, if any. */
        val lastRead: DirectoryHistory? = null,
        val isRefreshing: Boolean = false,
        val isOpening: Boolean = false,
    ) {
        /** The folder shown, null at the top level. */
        val folder: DirectoryFolder?
            get() = path.lastOrNull()
    }

    @Immutable
    data class DirectoryFolder(val uri: String, val name: String)

    sealed interface Volumes {
        data object Loading : Volumes

        /** The device's storage can't be read without [hasAllFilesAccess]. */
        data object NoAccess : Volumes

        data class Listed(val volumes: List<DirectoryVolume>) : Volumes
    }

    sealed interface Listing {
        data object Loading : Listing
        data object Unreadable : Listing
        data class Entries(val entries: List<DirectoryEntry>) : Listing
    }

    sealed interface Event {
        data class OpenFile(val folderUri: String, val folderName: String, val fileUri: String) : Event
        data class OpenFailed(val message: String?) : Event
        data object NoNextChapter : Event
    }

    private companion object {
        const val KEY_PATH_URIS = "path_uris"
        const val KEY_PATH_NAMES = "path_names"
    }
}
