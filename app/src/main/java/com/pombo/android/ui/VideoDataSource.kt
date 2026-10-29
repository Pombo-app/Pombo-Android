package com.pombo.android.ui

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.TransferListener
import com.pombo.android.core.StorageStreams
import java.io.IOException
import java.io.InterruptedIOException
import java.io.RandomAccessFile

/**
 * Reads a storage video while it downloads: blocks until the chunk under the
 * read position is on disk. Runs on the player's loading thread, which the
 * player interrupts to cancel a read.
 */
@UnstableApi
private class StorageStreamDataSource : BaseDataSource(false) {
    private var stream: StorageStreams.Stream? = null
    private var file: RandomAccessFile? = null
    private var uri: Uri? = null
    private var position = 0L
    private var remaining = 0L
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        val s = StorageStreams.get(dataSpec.uri.schemeSpecificPart) ?: throw IOException("video download is gone")
        transferInitializing(dataSpec)
        stream = s
        file = RandomAccessFile(s.file, "r")
        position = dataSpec.position
        remaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.length else s.size - position
        if (remaining < 0) throw IOException("read position past the end")
        opened = true
        transferStarted(dataSpec)
        return remaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        val s = stream ?: throw IOException("not open")
        while (!s.has(position)) {
            if (s.stopped) throw IOException("video download stopped")
            try { Thread.sleep(50) } catch (e: InterruptedException) { throw InterruptedIOException() }
        }
        val n = minOf(length.toLong(), remaining, s.availableEnd(position) - position).toInt()
        val f = file ?: throw IOException("not open")
        f.seek(position)
        val read = f.read(buffer, offset, n)
        if (read < 0) return C.RESULT_END_OF_INPUT
        position += read
        remaining -= read
        bytesTransferred(read)
        return read
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        uri = null
        try { file?.close() } finally {
            file = null
            stream = null
            if (opened) { opened = false; transferEnded() }
        }
    }
}

/** The player's data source: storage streams by their scheme, everything else as usual. */
@UnstableApi
class VideoDataSource(context: Context) : DataSource {
    private val default = DefaultDataSource.Factory(context).createDataSource()
    private val streaming = StorageStreamDataSource()
    private var active: DataSource? = null

    override fun addTransferListener(transferListener: TransferListener) {
        default.addTransferListener(transferListener)
        streaming.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val source = if (dataSpec.uri.scheme == StorageStreams.SCHEME) streaming else default
        active = source
        return source.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        active?.read(buffer, offset, length) ?: throw IOException("not open")

    override fun getUri(): Uri? = active?.uri

    override fun getResponseHeaders(): Map<String, List<String>> = active?.responseHeaders ?: emptyMap()

    override fun close() {
        try { active?.close() } finally { active = null }
    }
}
