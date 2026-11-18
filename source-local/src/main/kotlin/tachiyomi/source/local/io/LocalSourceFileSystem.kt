package tachiyomi.source.local.io

import com.hippo.unifile.UniFile
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.util.storage.DiskUtil
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

    /**
     * Whether a cover folder is chosen. The covers, ComicInfo.xml and .noxml files the app creates then go there,
     * one folder per series, and nothing in a series folder is created, changed or deleted.
     */
    fun hasCoverDirectory(): Boolean {
        return storageManager.hasLocalCoverDirectory()
    }

    /**
     * The cover folder, null when none is chosen or it can't be reached now.
     */
    fun getCoverDirectory(): UniFile? {
        return storageManager.getLocalCoverDirectory()
    }

    /**
     * The folder of the series [name] in the cover folder, null when there is no cover folder or it can't be
     * reached now, or when the series has no folder there and [create] is false.
     */
    fun getMangaCoverDirectory(name: String, create: Boolean): UniFile? {
        val coverDirectory = getCoverDirectory() ?: return null
        // SAF replaces the characters FAT rejects when it creates a folder, so the name must not have any to be
        // found again
        val folderName = DiskUtil.buildValidFilename(name)
        return if (create) {
            // The details and chapters of a series are fetched concurrently, and SAF gives a folder created while
            // one of the same name is being created another name
            synchronized(this) { coverDirectory.createDirectory(folderName) }
        } else {
            coverDirectory.findFile(folderName)?.takeIf { it.isDirectory }
        }
    }

    /**
     * The folder the app writes the files it creates for the series [name] to, null when it can't be reached now:
     * the series' folder in the cover folder if one is chosen, else the first folder of the series.
     */
    fun getMangaWriteDirectory(name: String): UniFile? {
        return if (hasCoverDirectory()) {
            getMangaCoverDirectory(name, create = true)
        } else {
            getMangaDirectories(name).firstOrNull()
        }
    }
}
