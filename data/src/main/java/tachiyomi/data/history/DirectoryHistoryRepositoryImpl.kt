package tachiyomi.data.history

import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.Flow
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.data.Database
import tachiyomi.data.subscribeToList
import tachiyomi.data.subscribeToOneOrNull
import tachiyomi.domain.history.model.DirectoryHistory
import tachiyomi.domain.history.repository.DirectoryHistoryRepository

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class DirectoryHistoryRepositoryImpl(
    private val database: Database,
) : DirectoryHistoryRepository {

    override suspend fun getByUri(uri: String): DirectoryHistory? {
        return database.directory_historyQueries
            .getByUri(uri, ::DirectoryHistory)
            .awaitAsOneOrNull()
    }

    override fun getByFolderUriAsFlow(folderUri: String): Flow<List<DirectoryHistory>> {
        return database.directory_historyQueries
            .getByFolderUri(folderUri, ::DirectoryHistory)
            .subscribeToList()
    }

    override fun getLastAsFlow(): Flow<DirectoryHistory?> {
        return database.directory_historyQueries
            .getLatest(::DirectoryHistory)
            .subscribeToOneOrNull()
    }

    override fun getHistory(query: String): Flow<List<DirectoryHistory>> {
        return database.directory_historyQueries
            .getHistory(query, ::DirectoryHistory)
            .subscribeToList()
    }

    override suspend fun upsert(history: DirectoryHistory) {
        try {
            database.directory_historyQueries.upsert(
                uri = history.uri,
                folderUri = history.folderUri,
                folderName = history.folderName,
                name = history.name,
                lastModified = history.lastModified,
                size = history.size,
                lastPageRead = history.lastPageRead,
                read = history.read,
                lastRead = history.lastRead,
            )
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, throwable = e)
        }
    }

    override suspend fun delete(uri: String) {
        try {
            database.directory_historyQueries.remove(uri)
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, throwable = e)
        }
    }

    override suspend fun deleteAll(): Boolean {
        return try {
            database.directory_historyQueries.removeAll()
            true
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, throwable = e)
            false
        }
    }

    override suspend fun getFolderViewerFlags(folderUri: String): Long {
        return database.directory_foldersQueries
            .getViewerFlags(folderUri)
            .awaitAsOneOrNull() ?: 0
    }

    override suspend fun setFolderViewerFlags(folderUri: String, flag: Long, mask: Long) {
        try {
            database.directory_foldersQueries.setViewerFlags(uri = folderUri, flag = flag, mask = mask)
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, throwable = e)
        }
    }

    override suspend fun resetFolderViewerFlags(): Boolean {
        return try {
            database.directory_foldersQueries.resetViewerFlags()
            true
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, throwable = e)
            false
        }
    }
}
