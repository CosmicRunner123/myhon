package eu.kanade.tachiyomi.util

import eu.kanade.domain.manga.interactor.UpdateManga
import eu.kanade.domain.manga.model.toSManga
import eu.kanade.tachiyomi.data.cache.CoverCache
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.source.local.image.LocalCoverManager
import tachiyomi.source.local.isLocal
import java.io.InputStream
import kotlin.time.Clock

suspend fun Manga.editCover(
    coverManager: LocalCoverManager,
    stream: InputStream,
    updateManga: UpdateManga,
    coverCache: CoverCache,
) {
    if (isLocal()) {
        val cover = checkNotNull(coverManager.update(toSManga(), stream)) { "No folder to save the cover to" }
        // The cover can be a new file, in a different folder than the stored one
        updateManga.await(
            MangaUpdate(id) {
                thumbnailUrl = cover.uri.toString()
                coverLastModified = Clock.System.now()
            },
        )
    } else if (favorite) {
        coverCache.setCustomCoverToCache(this, stream)
        updateManga.awaitUpdateCoverLastModified(id)
    }
}
