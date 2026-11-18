package tachiyomi.source.local

import android.content.Context
import com.hippo.unifile.UniFile
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.util.lang.compareToCaseInsensitiveNaturalOrder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import logcat.LogPriority
import mihon.core.archive.archiveReader
import mihon.core.archive.epubReader
import nl.adaptivity.xmlutil.core.AndroidXmlReader
import nl.adaptivity.xmlutil.serialization.XML
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.storage.extension
import tachiyomi.core.common.storage.nameWithoutExtension
import tachiyomi.core.common.util.system.ImageUtil
import tachiyomi.core.common.util.system.logcat
import tachiyomi.core.metadata.comicinfo.COMIC_INFO_FILE
import tachiyomi.core.metadata.comicinfo.ComicInfo
import tachiyomi.core.metadata.comicinfo.copyFromComicInfo
import tachiyomi.core.metadata.comicinfo.getComicInfo
import tachiyomi.core.metadata.tachiyomi.MangaDetails
import tachiyomi.domain.chapter.service.ChapterRecognition
import tachiyomi.domain.manga.model.Manga
import tachiyomi.i18n.MR
import tachiyomi.source.local.image.LocalCoverManager
import tachiyomi.source.local.io.Archive
import tachiyomi.source.local.io.Format
import tachiyomi.source.local.io.LocalSourceFileSystem
import tachiyomi.source.local.metadata.fillMetadata
import java.io.InputStream
import java.nio.charset.StandardCharsets

@Inject
@SingleIn(AppScope::class)
class LocalSource(
    private val context: Context,
    private val fileSystem: LocalSourceFileSystem,
    private val coverManager: LocalCoverManager,
    private val json: Json,
    private val xml: XML,
) : Source {

    override val name: String = context.stringResource(MR.strings.local_source)

    override val id: Long = ID

    override val lang: String = "other"

    override fun toString() = name

    suspend fun getSeriesListing(): SeriesListing = withContext(Dispatchers.IO) {
        val namesByBaseDirectory = fileSystem.getBaseDirectories().map { directory ->
            directory?.listFiles()
                ?.filter { !it.name.orEmpty().startsWith('.') && it.isDirectory }
                ?.mapNotNull { it.name }
                // UniFile answers a failed query with "no files" and "not a directory"
                ?.takeIf { directory.exists() }
        }
        mergeSeriesNames(namesByBaseDirectory)
    }

    override suspend fun getMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate = supervisorScope {
        val baseDirs = withContext(Dispatchers.IO) { fileSystem.getBaseDirectories() }
        val mangaDirs = withContext(Dispatchers.IO) { fileSystem.getMangaDirectories(manga.url, baseDirs) }
        val mangaCoverDir = withContext(Dispatchers.IO) {
            fileSystem.getMangaCoverDirectory(manga.url, create = false)
        }
        val asyncManga = if (fetchDetails) async { getMangaDetails(manga, mangaCoverDir, mangaDirs) } else null
        val asyncChapters = if (fetchChapters) {
            async { getChapterList(manga, baseDirs, mangaCoverDir, mangaDirs) }
        } else {
            null
        }
        SMangaUpdate(asyncManga?.await() ?: manga, asyncChapters?.await() ?: chapters)
    }

    // Manga details related
    private suspend fun getMangaDetails(
        manga: SManga,
        mangaCoverDir: UniFile?,
        mangaDirs: List<UniFile>,
    ): SManga = withContext(Dispatchers.IO) {
        coverManager.find(listOfNotNull(mangaCoverDir) + mangaDirs)?.let {
            manga.thumbnail_url = it.uri.toString()
        }

        // Augment manga details based on metadata files
        try {
            check(mangaDirs.isNotEmpty()) { "${manga.url} is not a valid directory" }
            val coverDirFiles = mangaCoverDir?.listFiles().orEmpty().asList()
            val mangaDirFiles = mangaDirs.flatMap { it.listFiles().orEmpty().asList() }
            // In precedence order, so a file is taken from the first folder that has it
            val files = coverDirFiles + mangaDirFiles
            val deletableFiles = if (fileSystem.hasCoverDirectory()) coverDirFiles else mangaDirFiles

            val comicInfoFile = files
                .firstOrNull { it.name == COMIC_INFO_FILE }
            val noXmlFile = files
                .firstOrNull { it.name == ".noxml" }
            val legacyJsonDetailsFile = files
                .firstOrNull { it.extension == "json" }

            when {
                // Top level ComicInfo.xml
                comicInfoFile != null -> {
                    noXmlFile?.takeIf { it in deletableFiles }?.delete()
                    setMangaDetailsFromComicInfoFile(comicInfoFile.openInputStream(), manga)
                }

                // Old custom JSON format
                // TODO: remove support for this entirely after a while
                legacyJsonDetailsFile != null -> {
                    json.decodeFromStream<MangaDetails>(legacyJsonDetailsFile.openInputStream()).run {
                        title?.let { manga.title = it }
                        author?.let { manga.author = it }
                        artist?.let { manga.artist = it }
                        description?.let { manga.description = it }
                        genre?.let { manga.genre = it.joinToString() }
                        status?.let { manga.status = it }
                    }
                    // Replace with ComicInfo.xml file
                    val comicInfo = manga.getComicInfo()
                    fileSystem.getMangaWriteDirectory(manga.url)
                        ?.createFile(COMIC_INFO_FILE)
                        ?.openOutputStream()
                        ?.use {
                            val comicInfoString = xml.encodeToString(ComicInfo.serializer(), comicInfo)
                            it.write(comicInfoString.toByteArray())
                            if (legacyJsonDetailsFile in deletableFiles) legacyJsonDetailsFile.delete()
                        }
                }

                // Copy ComicInfo.xml from chapter archive to top level if found
                noXmlFile == null -> {
                    val comicInfo = mangaDirFiles
                        .filter(Archive::isSupported)
                        .firstNotNullOfOrNull { chapter -> getComicInfoForChapter(chapter) { it.readBytes() } }

                    if (comicInfo != null) {
                        fileSystem.getMangaWriteDirectory(manga.url)
                            ?.createFile(COMIC_INFO_FILE)
                            ?.openOutputStream()
                            ?.use { it.write(comicInfo) }
                        setMangaDetailsFromComicInfoFile(comicInfo.inputStream(), manga)
                    } else {
                        // Avoid re-scanning
                        fileSystem.getMangaWriteDirectory(manga.url)?.createFile(".noxml")
                    }
                }
            }
        } catch (e: Throwable) {
            logcat(LogPriority.ERROR, e) { "Error setting manga details from local metadata for ${manga.title}" }
        }

        return@withContext manga
    }

    private fun <T> getComicInfoForChapter(chapter: UniFile, block: (InputStream) -> T): T? {
        return if (chapter.isDirectory) {
            chapter.findFile(COMIC_INFO_FILE)?.openInputStream()?.use(block)
        } else {
            chapter.archiveReader(context).use { reader ->
                reader.getInputStream(COMIC_INFO_FILE)?.use(block)
            }
        }
    }

    private fun parseComicInfo(stream: InputStream): ComicInfo {
        return AndroidXmlReader(stream, StandardCharsets.UTF_8.name()).use {
            xml.decodeFromReader<ComicInfo>(it)
        }
    }

    private fun setMangaDetailsFromComicInfoFile(stream: InputStream, manga: SManga) {
        manga.copyFromComicInfo(parseComicInfo(stream))
    }

    private fun setChapterDetailsFromComicInfoFile(stream: InputStream, chapter: SChapter) {
        val comicInfo = parseComicInfo(stream)

        comicInfo.title?.let { chapter.name = it.value }
        comicInfo.number?.value?.toFloatOrNull()?.let { chapter.chapter_number = it }
        comicInfo.translator?.let { chapter.scanlator = it.value }

        // only bother with partial dates if the year is not null, abandon date parsing otherwise
        val year = comicInfo.year?.value?.takeIf { it > 0 }?.toString()?.padStart(4, '0') ?: return
        val month = (comicInfo.month?.value?.coerceIn(1, 12) ?: 1).toString().padStart(2, '0')
        val day = (comicInfo.day?.value?.coerceIn(1, 31) ?: 1).toString().padStart(2, '0')

        val dateInstant = try {
            LocalDate.parse("$year-$month-$day").atStartOfDayIn(TimeZone.currentSystemDefault())
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
            null
        }
        dateInstant?.let { chapter.date_upload = it.toEpochMilliseconds() }
    }

    // Chapters
    private suspend fun getChapterList(
        manga: SManga,
        baseDirs: List<UniFile?>,
        mangaCoverDir: UniFile?,
        mangaDirs: List<UniFile>,
    ): List<SChapter> = withContext(Dispatchers.IO) {
        val filesByMangaDir = mangaDirs.map { mangaDir ->
            mangaDir.listFiles()
                // Only keep supported formats
                ?.filterNot { it.name.orEmpty().startsWith('.') }
                ?.filter { it.isDirectory || Archive.isSupported(it) || it.extension.equals("epub", true) }
                // UniFile answers a failed query with "no files" and "not a directory"
                ?.takeIf { mangaDir.exists() }
        }
        // The chapter sync deletes the chapters that are not listed, so nothing is listed unless every base
        // directory could be reached when mangaDirs were looked up in it (else it is null) and still can be now,
        // and every folder of the series could be read
        if (baseDirs.any { it?.exists() != true }) {
            throw Exception(context.stringResource(MR.strings.local_series_folders_unavailable))
        }
        if (filesByMangaDir.isEmpty() || null in filesByMangaDir) {
            throw Exception(context.stringResource(MR.strings.local_series_folder_not_found))
        }

        val chapters = mergeSeriesFiles(filesByMangaDir.filterNotNull()) { it.name }
            .map { chapterFile ->
                SChapter.create().apply {
                    url = "${manga.url}/${chapterFile.name}"
                    name = if (chapterFile.isDirectory) {
                        chapterFile.name
                    } else {
                        chapterFile.nameWithoutExtension
                    }.orEmpty()
                    date_upload = chapterFile.lastModified()
                    chapter_number = ChapterRecognition
                        .parseChapterNumber(manga.title, this.name, this.chapter_number.toDouble())
                        .toFloat()

                    try {
                        val format = Format.valueOf(chapterFile)
                        if (format is Format.Epub) {
                            format.file.epubReader(context).use { epub ->
                                epub.fillMetadata(manga, this)
                            }
                        } else {
                            getComicInfoForChapter(chapterFile) { stream ->
                                setChapterDetailsFromComicInfoFile(stream, this)
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        logcat(LogPriority.ERROR, e) { "Failed to read metadata for ${chapterFile.name}" }
                    }
                }
            }
            .sortedWith { c1, c2 ->
                c2.name.compareToCaseInsensitiveNaturalOrder(c1.name)
            }

        // Looked up rather than taken from the stored cover, which can be in a cover folder no longer in use
        val cover = coverManager.find(listOfNotNull(mangaCoverDir) + mangaDirs)
        if (cover != null) {
            manga.thumbnail_url = cover.uri.toString()
        } else if (!fileSystem.hasCoverDirectory() || fileSystem.getCoverDirectory() != null) {
            // Copy the cover from the first chapter found, unless the cover folder that may hold it is unreachable
            chapters.lastOrNull()?.let { chapter ->
                updateCover(chapter, manga)
            }
        }

        chapters
    }

    fun getFormat(chapter: SChapter): Format {
        try {
            val (mangaDirName, chapterName) = chapter.url.split('/', limit = 2)
            return fileSystem.getMangaDirectories(mangaDirName)
                .firstNotNullOfOrNull { it.findFile(chapterName) }
                ?.let(Format.Companion::valueOf)
                ?: throw Exception(context.stringResource(MR.strings.chapter_not_found))
        } catch (e: Format.UnknownFormatException) {
            throw Exception(context.stringResource(MR.strings.local_invalid_format))
        } catch (e: Exception) {
            throw e
        }
    }

    private fun updateCover(chapter: SChapter, manga: SManga): UniFile? {
        return try {
            when (val format = getFormat(chapter)) {
                is Format.Directory -> {
                    val entry = format.file.listFiles()
                        ?.sortedWith { f1, f2 ->
                            f1.name.orEmpty().compareToCaseInsensitiveNaturalOrder(
                                f2.name.orEmpty(),
                            )
                        }
                        ?.find {
                            !it.isDirectory && ImageUtil.isImage(it.name) { it.openInputStream() }
                        }

                    entry?.let { coverManager.update(manga, it.openInputStream()) }
                }
                is Format.Archive -> {
                    format.file.archiveReader(context).use { reader ->
                        val entry = reader.useEntries { entries ->
                            entries
                                .sortedWith { f1, f2 -> f1.name.compareToCaseInsensitiveNaturalOrder(f2.name) }
                                .find { it.isFile && ImageUtil.isImage(it.name) { reader.getInputStream(it.name)!! } }
                        }

                        entry?.let { coverManager.update(manga, reader.getInputStream(it.name)!!) }
                    }
                }
                is Format.Epub -> {
                    format.file.epubReader(context).use { epub ->
                        val entry = epub.getImagesFromPages().firstOrNull()

                        entry?.let { coverManager.update(manga, epub.getInputStream(it)!!) }
                    }
                }
            }
        } catch (e: Throwable) {
            logcat(LogPriority.ERROR, e) { "Error updating cover for ${manga.title}" }
            null
        }
    }

    companion object {
        const val ID = 0L
    }
}

fun Manga.isLocal(): Boolean = source == LocalSource.ID

/**
 * The series found in the base directories that could be listed.
 *
 * @param complete false when a base directory could not be listed, so [names] may lack series.
 */
data class SeriesListing(val names: List<String>, val complete: Boolean)

/**
 * Series folders with the same name in several base directories are one series.
 *
 * @param namesByBaseDirectory the series folder names of each base directory, null where it could not be listed.
 */
internal fun mergeSeriesNames(namesByBaseDirectory: List<List<String>?>): SeriesListing {
    return SeriesListing(
        names = namesByBaseDirectory.filterNotNull().flatten().distinct(),
        complete = null !in namesByBaseDirectory,
    )
}

/**
 * Merges the files of the folders of one series, given in precedence order: of the files with the same name, the
 * one in the first folder is kept.
 */
internal fun <T> mergeSeriesFiles(filesByFolder: List<List<T>>, name: (T) -> String?): List<T> {
    return filesByFolder.flatten().distinctBy(name)
}
