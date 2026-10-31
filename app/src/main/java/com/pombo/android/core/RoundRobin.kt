package com.pombo.android.core

/**
 * The web poller's rotation: up to [size] items from [index] on, wrapping, and
 * the index the next turn starts from.
 */
fun <T> roundRobin(items: List<T>, index: Int, size: Int): Pair<List<T>, Int> {
    if (items.isEmpty()) return emptyList<T>() to index
    val n = minOf(size, items.size)
    return List(n) { items[(index + it) % items.size] } to (index + n) % items.size
}
