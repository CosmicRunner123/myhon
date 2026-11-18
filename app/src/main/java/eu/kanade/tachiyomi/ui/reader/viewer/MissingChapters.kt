package eu.kanade.tachiyomi.ui.reader.viewer

import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import tachiyomi.domain.chapter.service.calculateChapterGap as domainCalculateChapterGap

fun calculateChapterGap(higherReaderChapter: ReaderChapter?, lowerReaderChapter: ReaderChapter?): Int {
    if (higherReaderChapter?.hasChapterNumber == false || lowerReaderChapter?.hasChapterNumber == false) return 0
    return domainCalculateChapterGap(
        higherReaderChapter?.chapter,
        lowerReaderChapter?.chapter,
    )
}
