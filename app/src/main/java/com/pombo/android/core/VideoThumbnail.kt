package com.pombo.android.core

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.Base64
import java.io.ByteArrayOutputStream

/** The first-frame thumbnail a storage video announce carries (web makeVideoThumbnail). */
object VideoThumbnail {

    private const val WIDTH = 320
    private const val MAX_BYTES = 24 * 1024
    /** Longest base64 a receiver accepts (web videoThumbnailSrc). */
    const val MAX_B64 = 40_000

    /** A small JPEG of the video's first frame, base64, or null when it cannot be decoded. */
    fun make(context: Context, uri: Uri): String? {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(context, uri)
            val frame = (if (Build.VERSION.SDK_INT >= 27) {
                r.getScaledFrameAtTime(100_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, WIDTH, WIDTH)
            } else {
                r.getFrameAtTime(100_000)
            }) ?: return null
            val scaled = if (frame.width > WIDTH) {
                Bitmap.createScaledBitmap(frame, WIDTH, maxOf(1, frame.height * WIDTH / frame.width), true)
            } else frame
            for (quality in listOf(70, 50, 35)) {
                val out = ByteArrayOutputStream()
                scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)
                if (out.size() <= MAX_BYTES) return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
            }
            return null
        } catch (e: Exception) {
            return null
        } finally {
            runCatching { r.release() }
        }
    }

    /** The announce field when it is plain base64 of a bounded size; anything else is dropped. */
    fun accept(b64: String?): String? =
        b64?.takeIf { it.length in 1..MAX_B64 && Regex("^[A-Za-z0-9+/]+={0,2}$").matches(it) }
}
