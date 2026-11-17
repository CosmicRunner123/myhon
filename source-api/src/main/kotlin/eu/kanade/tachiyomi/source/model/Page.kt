package eu.kanade.tachiyomi.source.model

open class Page(
    val index: Int,
) {

    val number: Int
        get() = index + 1
}
