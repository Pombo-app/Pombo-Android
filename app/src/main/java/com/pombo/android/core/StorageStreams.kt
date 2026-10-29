package com.pombo.android.core

import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Storage videos that can play before their download finishes, by transferId.
 * The player reads the staged file directly: an uncompressed download is
 * staged at its final offsets, chunk i at `i * chunkSize`.
 */
object StorageStreams {

    const val SCHEME = "pombo-stream"

    class Stream(
        val file: File,
        val size: Long,
        val chunkSize: Int,
        private val hasChunk: (Int) -> Boolean
    ) {
        @Volatile var finished = false
        @Volatile var stopped = false
        /** The chunk a player is blocked on, or -1. */
        @Volatile var wanted = -1
        /** When the player started waiting on [wanted]. */
        @Volatile var wantedSince = 0L

        fun want(position: Long) {
            val chunk = (position / chunkSize).toInt()
            if (chunk != wanted) { wantedSince = System.currentTimeMillis(); wanted = chunk }
        }

        fun has(position: Long): Boolean = finished || hasChunk((position / chunkSize).toInt())

        /** Where the bytes known to be on disk from [position] end, at least. */
        fun availableEnd(position: Long): Long =
            if (finished) size else minOf(size, (position / chunkSize + 1) * chunkSize)
    }

    private val streams = ConcurrentHashMap<String, Stream>()

    fun put(transferId: String, stream: Stream) { streams[transferId] = stream }

    fun get(transferId: String): Stream? = streams[transferId]

    fun stop(transferId: String) { streams.remove(transferId)?.stopped = true }

    fun clear() {
        streams.values.forEach { it.stopped = true }
        streams.clear()
    }

    fun uriFor(transferId: String): android.net.Uri? =
        if (streams.containsKey(transferId)) android.net.Uri.parse("$SCHEME:$transferId") else null
}
