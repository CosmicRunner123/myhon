package eu.kanade.tachiyomi.data.backup

import android.net.Uri
import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.data.backup.models.BackupTracking
import eu.kanade.tachiyomi.data.backup.restore.isRestorableSource
import eu.kanade.tachiyomi.data.track.TrackerManager
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

@Inject
class BackupFileValidator(
    private val trackerManager: TrackerManager,
    private val backupDecoder: BackupDecoder,
) {

    /**
     * Checks for critical backup file data.
     *
     * @return List of missing trackers.
     */
    fun validate(uri: Uri): Results {
        val trackerIds = mutableSetOf<Int>()
        try {
            backupDecoder.decodeMetadata(uri, TrackedManga.serializer()) { manga ->
                // Restore skips the series of other sources, so their trackers don't matter
                if (isRestorableSource(manga.source)) manga.tracking.mapTo(trackerIds) { it.syncId }
            }
        } catch (e: Exception) {
            throw IllegalStateException(e)
        }

        val missingTrackers = trackerIds
            .mapNotNull { trackerManager.get(it.toLong()) }
            .filter { !it.isLoggedIn }
            .map { it.name }
            .sorted()

        return Results(missingTrackers)
    }

    data class Results(
        val missingTrackers: List<String>,
    )

    // Only the fields validation reads, so decoding skips the rest of each entry
    @Serializable
    private class TrackedManga(
        @ProtoNumber(1) val source: Long,
        @ProtoNumber(18) val tracking: List<BackupTracking> = emptyList(),
    )
}
