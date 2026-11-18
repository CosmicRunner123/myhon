package eu.kanade.tachiyomi.data.backup.restore

import android.content.Context
import android.net.Uri
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import eu.kanade.tachiyomi.data.backup.BackupDecoder
import eu.kanade.tachiyomi.data.backup.BackupNotifier
import eu.kanade.tachiyomi.data.backup.models.BackupCategory
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import eu.kanade.tachiyomi.data.backup.models.BackupPreference
import eu.kanade.tachiyomi.data.backup.restore.restorers.CategoriesRestorer
import eu.kanade.tachiyomi.data.backup.restore.restorers.MangaRestorer
import eu.kanade.tachiyomi.data.backup.restore.restorers.PreferenceRestorer
import eu.kanade.tachiyomi.util.system.createFileInCacheDir
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.chunked
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import logcat.LogPriority
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.system.logcat
import tachiyomi.i18n.MR
import tachiyomi.source.local.DirectorySource
import tachiyomi.source.local.LocalSource
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch

@OptIn(ExperimentalAtomicApi::class)
@AssistedInject
class BackupRestorer(
    @Assisted private val notifier: BackupNotifier,
    @Assisted private val isSync: Boolean,
    private val context: Context,
    private val categoriesRestorer: CategoriesRestorer,
    private val preferenceRestorer: PreferenceRestorer,
    private val mangaRestorer: MangaRestorer,
    private val backupDecoder: BackupDecoder,
) {

    @AssistedFactory
    fun interface Factory {
        fun create(notifier: BackupNotifier, isSync: Boolean): BackupRestorer
    }

    private var restoreAmount = 0
    private val restoreProgress = AtomicInt(0)
    private val errors = CopyOnWriteArrayList<Pair<Date, String>>()

    /**
     * Mapping of source ID to source name from backup data
     */
    private var sourceMapping: Map<Long, String> = emptyMap()

    suspend fun restore(uri: Uri, options: RestoreOptions) {
        val startTime = System.currentTimeMillis()

        restoreFromFile(uri, options)

        val time = System.currentTimeMillis() - startTime

        val logFile = writeErrorLog()

        notifier.showRestoreComplete(
            time,
            errors.size,
            logFile.parent,
            logFile.name,
            isSync,
        )
    }

    private suspend fun restoreFromFile(uri: Uri, options: RestoreOptions) {
        val (entryCount, backup) = backupDecoder.decodeMetadata(uri)

        // Store source mapping for error messages
        sourceMapping = backup.backupSources.associate { it.sourceId to it.name }

        if (options.libraryEntries) {
            restoreAmount += entryCount
        }
        if (options.categories) {
            restoreAmount += 1
        }
        if (options.appSettings) {
            restoreAmount += 1
        }

        coroutineScope {
            val restoreCategoriesJob = if (options.categories) {
                restoreCategories(backup.backupCategories)
            } else {
                null
            }
            if (options.appSettings) {
                restoreAppPreferences(
                    backup.backupPreferences,
                    backup.backupCategories.takeIf { options.categories },
                    restoreCategoriesJob,
                )
            }
            if (options.libraryEntries) {
                restoreManga(
                    uri,
                    if (options.categories) backup.backupCategories else emptyList(),
                    restoreCategoriesJob,
                )
            }

            // TODO: optionally trigger online library + tracker update
        }
    }

    private fun CoroutineScope.restoreCategories(backupCategories: List<BackupCategory>) = launch {
        ensureActive()
        categoriesRestorer(backupCategories)

        val progress = restoreProgress.incrementAndFetch()
        notifier.showRestoreProgress(
            context.stringResource(MR.strings.categories),
            progress,
            restoreAmount,
            isSync,
        )
    }

    private fun CoroutineScope.restoreManga(
        uri: Uri,
        backupCategories: List<BackupCategory>,
        categoriesRestoreJob: Job?,
    ) = launch {
        categoriesRestoreJob?.join()

        val skippedReason = context.stringResource(MR.strings.restore_skipped_not_local)

        backupDecoder.decodeEntries(uri)
            .filter { backupManga ->
                backupManga.isRestorable().also { restorable ->
                    if (!restorable) {
                        addError(backupManga, skippedReason)
                        restoreProgress.incrementAndFetch()
                    }
                }
            }
            .chunked(100)
            .collect { chunk ->
                val restoredAsBatch = try {
                    ensureActive()
                    mangaRestorer.restore(chunk, backupCategories)
                    true
                } catch (e: Exception) {
                    ensureActive()
                    logcat(LogPriority.WARN, e) { "Batch restore failed, retrying entry by entry" }
                    false
                }

                if (restoredAsBatch) {
                    restoreProgress.addAndFetch(chunk.size)
                } else {
                    chunk.forEach {
                        ensureActive()

                        try {
                            mangaRestorer.restore(listOf(it), backupCategories)
                        } catch (e: Exception) {
                            ensureActive()
                            addError(it, e.message)
                        }

                        restoreProgress.incrementAndFetch()
                    }
                }

                notifier.showRestoreProgress(chunk.last().title, restoreProgress.load(), restoreAmount, isSync)
            }
    }

    private fun CoroutineScope.restoreAppPreferences(
        preferences: List<BackupPreference>,
        categories: List<BackupCategory>?,
        categoriesRestoreJob: Job?,
    ) = launch {
        ensureActive()
        categoriesRestoreJob?.join()
        preferenceRestorer.restoreApp(
            preferences,
            categories,
        )

        val progress = restoreProgress.incrementAndFetch()
        notifier.showRestoreProgress(
            context.stringResource(MR.strings.app_settings),
            progress,
            restoreAmount,
            isSync,
        )
    }

    private fun addError(backupManga: BackupManga, message: String?) {
        val sourceName = sourceMapping[backupManga.source] ?: backupManga.source.toString()
        errors.add(Date() to "${backupManga.title} [$sourceName]: $message")
    }

    private fun writeErrorLog(): File {
        try {
            if (errors.isNotEmpty()) {
                val file = context.createFileInCacheDir("myhon_restore_error.txt")
                val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())

                file.bufferedWriter().use { out ->
                    errors.forEach { (date, message) ->
                        out.write("[${sdf.format(date)}] $message\n")
                    }
                }
                return file
            }
        } catch (_: Exception) {
            // Empty
        }
        return File("")
    }
}

/**
 * Series of any other source can't be read by this app.
 */
internal fun isRestorableSource(sourceId: Long): Boolean = sourceId == LocalSource.ID || sourceId == DirectorySource.ID

internal fun BackupManga.isRestorable(): Boolean = isRestorableSource(source)
