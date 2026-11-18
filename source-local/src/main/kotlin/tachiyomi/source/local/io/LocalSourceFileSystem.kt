package tachiyomi.source.local.io

import com.hippo.unifile.UniFile
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import tachiyomi.domain.storage.service.StorageManager

@Inject
@SingleIn(AppScope::class)
class LocalSourceFileSystem(
    private val storageManager: StorageManager,
) {

    /**
     * The folders that hold series, in precedence order. A folder that can't be reached now is null.
     */
    fun getBaseDirectories(): List<UniFile?> {
        return storageManager.getLocalSourceDirectories()
    }

    /**
     * The folders of the series [name] in those of [baseDirectories] that can be reached, in precedence order.
     */
    fun getMangaDirectories(name: String, baseDirectories: List<UniFile?> = getBaseDirectories()): List<UniFile> {
        return baseDirectories.mapNotNull { baseDirectory ->
            baseDirectory?.findFile(name)?.takeIf { it.isDirectory }
        }
    }
}
