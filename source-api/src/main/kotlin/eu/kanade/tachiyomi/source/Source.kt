package eu.kanade.tachiyomi.source

import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate

interface Source {

    /**
     * ID for the source. Must be unique.
     */
    val id: Long

    /**
     * Name of the source.
     */
    val name: String

    val lang: String
        get() = ""

    /**
     * Fetches updated information for a manga.
     *
     * Depending on the provided flags or source availability, this may include
     * updated manga metadata, available chapters, or both.
     *
     * If a value is not requested, the existing provided value can be returned as-is.
     * The host app may apply any returned updates regardless of the flags,
     * so care should be taken to only return accurate and intentional changes.
     *
     * @since tachiyomix 1.6
     * @param manga The manga to fetch updates for.
     * @param chapters Existing chapters of the manga
     * @param fetchDetails Whether to fetch updated manga details.
     * @param fetchChapters Whether to fetch available chapters.
     */
    suspend fun getMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate
}
