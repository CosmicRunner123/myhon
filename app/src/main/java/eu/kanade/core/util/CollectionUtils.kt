package eu.kanade.core.util

import androidx.compose.ui.util.fastForEach
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.contract

fun <T : R, R : Any> List<T>.insertSeparators(
    generator: (before: T?, after: T?) -> R?,
): List<R> {
    if (isEmpty()) return emptyList()
    val newList = mutableListOf<R>()
    for (i in -1..lastIndex) {
        val before = getOrNull(i)
        before?.let(newList::add)
        val after = getOrNull(i + 1)
        val separator = generator.invoke(before, after)
        separator?.let(newList::add)
    }
    return newList
}

fun <E> HashSet<E>.addOrRemove(value: E, shouldAdd: Boolean) {
    if (shouldAdd) {
        add(value)
    } else {
        remove(value)
    }
}

/**
 * Returns the number of entries not matching the given [predicate].
 *
 * **Do not use for collections that come from public APIs**, since they may not support random
 * access in an efficient way, and this method may actually be a lot slower. Only use for
 * collections that are created by code we control and are known to support random access.
 */
@OptIn(ExperimentalContracts::class)
inline fun <T> List<T>.fastCountNot(predicate: (T) -> Boolean): Int {
    contract { callsInPlace(predicate) }
    var count = size
    fastForEach { if (predicate(it)) --count }
    return count
}
