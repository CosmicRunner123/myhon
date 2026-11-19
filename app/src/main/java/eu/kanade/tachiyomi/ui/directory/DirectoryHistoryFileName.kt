package eu.kanade.tachiyomi.ui.directory

import tachiyomi.domain.history.model.DirectoryHistory
import tachiyomi.source.local.io.comicFileName

/**
 * The file's name with its extension, which its URI tells even for older entries, whose [DirectoryHistory.name] can be
 * another.
 */
val DirectoryHistory.fileName: String
    get() = comicFileName(uri) ?: name
