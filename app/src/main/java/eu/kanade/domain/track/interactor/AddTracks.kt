package eu.kanade.domain.track.interactor

import dev.zacsweers.metro.Inject
import eu.kanade.domain.track.model.toDbTrack
import eu.kanade.domain.track.model.toDomainTrack
import eu.kanade.tachiyomi.data.database.models.Track
import eu.kanade.tachiyomi.data.track.Tracker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.history.interactor.GetHistory
import tachiyomi.domain.track.interactor.UpsertTrack
import kotlin.time.Instant

@Inject
class AddTracks(
    private val upsertTrack: UpsertTrack,
    private val getChaptersByMangaId: GetChaptersByMangaId,
    private val getHistory: GetHistory,
) {

    // TODO: update all trackers based on common data
    suspend fun bind(tracker: Tracker, item: Track, mangaId: Long) = withContext(Dispatchers.IO) {
        val allChapters = getChaptersByMangaId.await(mangaId)
        val hasReadChapters = allChapters.any { it.read }
        tracker.bind(item, hasReadChapters)

        var track = item.toDomainTrack(idRequired = false) ?: return@withContext

        upsertTrack.await(track)

        // Update chapter progress if newer chapters marked read locally
        if (hasReadChapters) {
            val latestLocalReadChapterNumber = allChapters
                .sortedBy { it.chapterNumber }
                .takeWhile { it.read }
                .lastOrNull()
                ?.chapterNumber ?: -1.0

            if (latestLocalReadChapterNumber > track.lastChapterRead) {
                track = track.copy(
                    lastChapterRead = latestLocalReadChapterNumber,
                )
                tracker.setRemoteLastChapterRead(track.toDbTrack(), latestLocalReadChapterNumber.toInt())
            }

            if (track.startDate == null) {
                val firstReadChapterDate = getHistory.await(mangaId)
                    .mapNotNull { it.readAt }
                    .minOrNull()

                firstReadChapterDate?.let {
                    val startDate = firstReadChapterDate.toEpochMilliseconds()
                    track = track.copy(
                        startDate = Instant.fromEpochMilliseconds(startDate),
                    )
                    tracker.setRemoteStartDate(track.toDbTrack(), startDate)
                }
            }
        }
    }
}
