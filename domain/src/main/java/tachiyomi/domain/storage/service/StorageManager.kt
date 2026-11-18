package tachiyomi.domain.storage.service

import android.content.Context
import androidx.core.net.toUri
import com.hippo.unifile.UniFile
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.shareIn
import mihon.core.metro.AppCoroutineScope
import java.util.concurrent.ConcurrentHashMap

@Inject
@SingleIn(AppScope::class)
class StorageManager(
    @AppCoroutineScope private val scope: CoroutineScope,
    private val context: Context,
    private val storagePreferences: StoragePreferences,
) {

    @Volatile
    private var baseDirLazy: Lazy<UniFile?> = lazy { getBaseDir(storagePreferences.baseStorageDirectory.get()) }
    private val baseDir: UniFile?
        get() = baseDirLazy.value

    // On SAF, createDirectory lists the whole base directory to find an existing child, so a resolved child
    // is reused while it's under the current base directory and still exists; it can be deleted outside the app
    private val childDirectories = ConcurrentHashMap<String, Pair<UniFile, UniFile>>()

    private val _changes: Channel<Unit> = Channel(Channel.UNLIMITED)
    val changes = _changes.receiveAsFlow()
        .shareIn(scope, SharingStarted.Lazily, 1)

    init {
        storagePreferences.baseStorageDirectory.changes()
            .drop(1)
            .distinctUntilChanged()
            .onEach { uri ->
                baseDirLazy = lazyOf(getBaseDir(uri))
                baseDir?.let { parent ->
                    parent.createDirectory(AUTOMATIC_BACKUPS_PATH)
                    parent.createDirectory(LOCAL_SOURCE_PATH)
                }
                _changes.send(Unit)
            }
            .launchIn(scope)

        storagePreferences.localSourceDirectories.changes()
            .drop(1)
            .distinctUntilChanged()
            .onEach { _changes.send(Unit) }
            .launchIn(scope)
    }

    private fun getBaseDir(uri: String): UniFile? {
        return UniFile.fromUri(context, uri.toUri())
            .takeIf { it?.exists() == true }
    }

    fun getAutomaticBackupsDirectory(): UniFile? {
        return getChildDirectory(AUTOMATIC_BACKUPS_PATH)
    }

    /**
     * The folders series are read from, in precedence order: the "local" folder of the storage location, then
     * the folders the user added, sorted by URI. A folder that can't be reached now is null.
     */
    fun getLocalSourceDirectories(): List<UniFile?> {
        val addedDirectories = storagePreferences.localSourceDirectories.get().sorted()
            .map { uri -> UniFile.fromUri(context, uri.toUri())?.takeIf { it.exists() } }
        return listOf(getChildDirectory(LOCAL_SOURCE_PATH)) + addedDirectories
    }

    /**
     * Whether a folder is chosen for the covers and details of series. Nothing is then written into series folders,
     * even while that folder can't be reached.
     */
    fun hasLocalCoverDirectory(): Boolean {
        return storagePreferences.localCoverDirectory.get().isNotEmpty()
    }

    /**
     * The folder chosen for the covers and details of series, null when none is chosen or it can't be reached now.
     */
    fun getLocalCoverDirectory(): UniFile? {
        val uri = storagePreferences.localCoverDirectory.get().ifEmpty { return null }
        return UniFile.fromUri(context, uri.toUri())?.takeIf { it.exists() }
    }

    private fun getChildDirectory(name: String): UniFile? {
        val current = baseDirLazy
        // Looked up again while unavailable, as its volume can be mounted later; not kept if the location changed
        // meanwhile
        val parent = current.value
            ?: getBaseDir(storagePreferences.baseStorageDirectory.get())
                ?.also { if (baseDirLazy === current) baseDirLazy = lazyOf(it) }
            ?: return null
        childDirectories[name]
            ?.takeIf { (cachedParent, directory) -> cachedParent === parent && directory.exists() }
            ?.let { (_, directory) -> return directory }
        return parent.createDirectory(name)?.also { childDirectories[name] = parent to it }
    }
}

private const val AUTOMATIC_BACKUPS_PATH = "autobackup"
private const val LOCAL_SOURCE_PATH = "local"
