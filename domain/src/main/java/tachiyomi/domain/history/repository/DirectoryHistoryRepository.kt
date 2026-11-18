package tachiyomi.domain.history.repository

import kotlinx.coroutines.flow.Flow
import tachiyomi.domain.history.model.DirectoryHistory

interface DirectoryHistoryRepository {

    suspend fun getByUri(uri: String): DirectoryHistory?

    fun getByFolderUriAsFlow(folderUri: String): Flow<List<DirectoryHistory>>

    fun getLastAsFlow(): Flow<DirectoryHistory?>

    fun getHistory(query: String): Flow<List<DirectoryHistory>>

    suspend fun upsert(history: DirectoryHistory)

    suspend fun delete(uri: String)

    suspend fun deleteAll(): Boolean

    suspend fun getFolderViewerFlags(folderUri: String): Long

    suspend fun setFolderViewerFlags(folderUri: String, flag: Long, mask: Long)
}
