package com.pombo.android.core

/**
 * Storage-node read windows — native mirror of the web storageMedia.js window
 * helpers (chunkWindowsFor / mergeWindows / buildMissingWindows /
 * partitionOfChunk). A "window" is a `{partition, from, to}` time range read
 * from a storage node; a download or a verify sweep issues one or more of these
 * and matches rows back to chunk indices.
 *
 * Pure and deterministic, so the estimate logic is unit-tested without a network.
 */
object StorageWindows {

    data class Window(val partition: Int, val from: Long, val to: Long)

    /**
     * Hands out each window once, shared by the fetch workers: in list order,
     * unless a player waits at [wantedFrom] — then the earliest windows still
     * ahead of that time come first, and what lies behind it is filled last.
     */
    class Picker(private val windows: List<Window>, private val wantedFrom: () -> Long? = { null }) {
        private val taken = BooleanArray(windows.size)
        private var cursor = 0

        @Synchronized
        fun next(): Window? {
            val t = wantedFrom()
            if (t != null) {
                var best = -1
                for (i in windows.indices) {
                    if (!taken[i] && windows[i].to > t && (best < 0 || windows[i].from < windows[best].from)) best = i
                }
                if (best >= 0) { taken[best] = true; return windows[best] }
            }
            while (cursor < windows.size && taken[cursor]) cursor++
            if (cursor >= windows.size) return null
            taken[cursor] = true
            return windows[cursor++]
        }
    }

    /** Where a player waiting on chunk [wanted] needs the download to be, a round of partitions early. */
    fun wantedTime(firstChunkTs: Long?, lastChunkTs: Long?, totalChunks: Int, chunkPartitions: Int, wanted: Int): Long? {
        if (wanted < 0 || firstChunkTs == null || lastChunkTs == null || totalChunks <= 1) return null
        val interval = (lastChunkTs - firstChunkTs).toDouble() / (totalChunks - 1)
        return firstChunkTs + Math.round(interval * (wanted - chunkPartitions))
    }

    /** Partition holding chunk [i] per an announce's layout (reader trusts the announce). */
    fun partitionOfChunk(i: Int, firstPartition: Int, chunkPartitions: Int): Int =
        StreamConstants.storageChunkPartition(i, firstPartition, chunkPartitions)

    /** One window per chunk partition, all over the same [fromT]..[toT] range. */
    fun chunkWindowsFor(firstPartition: Int, chunkPartitions: Int, fromT: Long, toT: Long): List<Window> =
        (0 until chunkPartitions).map { Window(firstPartition + it, fromT, toT) }

    /** Merge overlapping same-partition windows. Input need not be sorted. */
    fun mergeWindows(raw: List<Window>): List<Window> {
        val sorted = raw.sortedWith(compareBy({ it.partition }, { it.from }))
        val out = ArrayList<Window>()
        for (w in sorted) {
            val last = out.lastOrNull()
            if (last != null && w.partition == last.partition && w.from <= last.to) {
                out[out.size - 1] = last.copy(to = maxOf(last.to, w.to))
            } else out.add(w)
        }
        return out
    }

    /**
     * Narrow re-request windows for [missing] chunks, estimated linearly between
     * the announced first/last chunk timestamps, merged per partition, plus a
     * tail window per partition for chunks republished by verify & repair (whose
     * real timestamps sit after lastChunkTs). Falls back to full per-partition
     * ranges when the estimate cannot help (no timestamps, single chunk, or more
     * than 30% missing — at that point narrow windows save nothing).
     */
    fun buildMissingWindows(
        firstChunkTs: Long?, lastChunkTs: Long?, totalChunks: Int,
        firstPartition: Int, chunkPartitions: Int,
        missing: List<Int>, fromT: Long, toT: Long
    ): List<Window> {
        if (firstChunkTs == null || lastChunkTs == null || totalChunks <= 1 || missing.size > totalChunks * 0.3) {
            return chunkWindowsFor(firstPartition, chunkPartitions, fromT, toT)
        }
        val interval = (lastChunkTs - firstChunkTs).toDouble() / (totalChunks - 1)
        val pad = 30_000L
        val raw = missing.map { i ->
            val est = firstChunkTs + (i * interval).toLong()
            Window(partitionOfChunk(i, firstPartition, chunkPartitions), est - pad, est + pad)
        }
        val windows = ArrayList(mergeWindows(raw))
        val tailParts = missing.map { partitionOfChunk(it, firstPartition, chunkPartitions) }.toSet()
        for (p in tailParts) windows.add(Window(p, lastChunkTs - 5000, toT))
        return windows
    }

    /**
     * Windows that bring a video in roughly front to back, so it can play while
     * it downloads: contiguous time slices over every partition, the first
     * slice, then the last (a non-faststart MP4 keeps its index there), then
     * the rest. The slices tile [fromT]..[toT] exactly, so they read what the
     * full windows do.
     */
    fun sequentialWindows(
        firstChunkTs: Long?, lastChunkTs: Long?, totalChunks: Int,
        firstPartition: Int, chunkPartitions: Int,
        fromT: Long, toT: Long, sliceChunks: Int
    ): List<Window> {
        if (firstChunkTs == null || lastChunkTs == null || totalChunks <= 1) {
            return chunkWindowsFor(firstPartition, chunkPartitions, fromT, toT)
        }
        val interval = (lastChunkTs - firstChunkTs).toDouble() / (totalChunks - 1)
        val sliceMs = maxOf(1.0, interval * sliceChunks)
        val edges = ArrayList<Long>().apply { add(fromT) }
        var t = firstChunkTs + sliceMs
        while (t < lastChunkTs) { edges.add(Math.round(t)); t += sliceMs }
        edges.add(toT)
        val slices = (0 until edges.size - 1).map { edges[it] to edges[it + 1] }
        val ordered = if (slices.size > 2) listOf(slices.first(), slices.last()) + slices.subList(1, slices.size - 1) else slices
        return ordered.flatMap { (from, to) -> chunkWindowsFor(firstPartition, chunkPartitions, from, to) }
    }
}
