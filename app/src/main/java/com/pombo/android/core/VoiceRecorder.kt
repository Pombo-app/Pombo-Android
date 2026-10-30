package com.pombo.android.core

import android.content.Context
import android.media.MediaMetadataRetriever
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.pombo.android.ChannelManager
import java.io.File

/**
 * Voice recorder: AAC in an MP4 container on every API level, the one format
 * every Pombo receiver plays (Firefox refuses the platform's Ogg/Opus).
 */
class VoiceRecorder(private val context: Context) {

    class Recording(val bytes: ByteArray, val mime: String, val durationMs: Long)

    private var recorder: MediaRecorder? = null
    private var file: File? = null
    private var startedAt = 0L

    /** @param onLimit called on the main thread when a cap stopped the recorder by itself */
    fun start(onLimit: () -> Unit) {
        val dir = dir(context).apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val out = File(dir, "rec-${System.currentTimeMillis()}.m4a")
        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()
        try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioChannels(1)
            r.setAudioSamplingRate(SAMPLE_RATE)
            r.setAudioEncodingBitRate(BIT_RATE)
            r.setMaxDuration(ChannelManager.AUDIO_MAX_DURATION_MS.toInt())
            r.setMaxFileSize((ChannelManager.AUDIO_MAX_ASSEMBLED_BYTES - SIZE_MARGIN).toLong())
            r.setOnInfoListener { _, what, _ ->
                if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED ||
                    what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED
                ) onLimit()
            }
            r.setOutputFile(out.absolutePath)
            r.prepare()
            r.start()
        } catch (e: Exception) {
            r.release()
            out.delete()
            throw e
        }
        recorder = r
        file = out
        startedAt = SystemClock.elapsedRealtime()
    }

    fun elapsedMs(): Long = if (startedAt == 0L) 0L else SystemClock.elapsedRealtime() - startedAt

    /** Loudness since the previous call, 0–1. MediaRecorder exposes no frequencies. */
    fun level(): Float {
        val peak = runCatching { recorder?.maxAmplitude ?: 0 }.getOrDefault(0)
        return kotlin.math.sqrt(peak / 32767f).coerceIn(0f, 1f)
    }

    /** Stops and returns the recording, or null when nothing usable was written. */
    fun stop(): Recording? {
        val durationMs = minOf(elapsedMs(), ChannelManager.AUDIO_MAX_DURATION_MS)
        val r = recorder ?: return null
        val out = file
        recorder = null
        file = null
        // A cap stops the recorder by itself, and a stop right after start has
        // no frames: both throw here, and only the second leaves no file.
        runCatching { r.stop() }.onFailure { Log.w(TAG, "stop failed: ${it.message}") }
        r.release()
        if (out == null || !out.exists() || out.length() == 0L) {
            Log.w(TAG, "no recording written after ${durationMs}ms")
            return null
        }
        return try {
            val measured = measure(out)
            if (measured == null) Log.w(TAG, "duration unreadable, using the clock: ${durationMs}ms")
            Recording(out.readBytes(), MIME, measured?.coerceAtMost(ChannelManager.AUDIO_MAX_DURATION_MS) ?: durationMs)
        } finally {
            out.delete()
        }
    }

    /** The file's own length: the clock can run well past what the encoder wrote, and players seek by the file. */
    private fun measure(out: File): Long? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(out.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.takeIf { it > 0 }
        } catch (e: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    fun cancel() {
        recorder?.let { runCatching { it.stop() }; it.release() }
        recorder = null
        file?.delete()
        file = null
    }

    companion object {
        private const val TAG = "VoiceRecorder"
        const val MIME = "audio/mp4"
        const val BIT_RATE = 32_000
        const val SAMPLE_RATE = 48_000
        // The muxer flushes in blocks; stop before the last one can cross the cap.
        private const val SIZE_MARGIN = 32 * 1024

        // Not cacheDir: under storage pressure the system purges it, file in progress included.
        private fun dir(context: Context) = File(context.noBackupFilesDir, "voice-rec")

        /** Drops what a recording interrupted by the process dying left behind. */
        fun sweep(context: Context) {
            dir(context).listFiles()?.forEach { it.delete() }
        }
    }
}
