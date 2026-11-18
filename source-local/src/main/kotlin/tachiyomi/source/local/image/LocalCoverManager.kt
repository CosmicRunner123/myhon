package tachiyomi.source.local.image

import android.content.Context
import com.hippo.unifile.UniFile
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.util.storage.DiskUtil
import tachiyomi.core.common.storage.nameWithoutExtension
import tachiyomi.core.common.util.system.ImageUtil
import tachiyomi.source.local.io.LocalSourceFileSystem
import java.io.InputStream

private const val DEFAULT_COVER_NAME = "cover.jpg"

@Inject
@SingleIn(AppScope::class)
class LocalCoverManager(
    private val context: Context,
    private val fileSystem: LocalSourceFileSystem,
) {

    /**
     * The cover in the first of [directories] that has one.
     */
    fun find(directories: List<UniFile>): UniFile? {
        return directories.firstNotNullOfOrNull { directory ->
            directory.listFiles().orEmpty()
                // Get all file whose names start with "cover"
                .filter { it.isFile && it.nameWithoutExtension.equals("cover", ignoreCase = true) }
                // Get the first actual image
                .firstOrNull { ImageUtil.isImage(it.name) { it.openInputStream() } }
        }
    }

    fun update(
        manga: SManga,
        inputStream: InputStream,
    ): UniFile? {
        // Read first, so the cover written there takes precedence over one in another folder
        val directory = fileSystem.getMangaWriteDirectory(manga.url)
        if (directory == null) {
            inputStream.close()
            return null
        }

        val targetFile = find(listOf(directory)) ?: directory.createFile(DEFAULT_COVER_NAME)!!

        inputStream.use { input ->
            targetFile.openOutputStream().use { output ->
                input.copyTo(output)
            }
        }

        // .nomedia also hides subfolders, so the cover folder needs only one, at its root
        DiskUtil.createNoMediaFile(fileSystem.getCoverDirectory() ?: directory, context)

        manga.thumbnail_url = targetFile.uri.toString()
        return targetFile
    }
}
