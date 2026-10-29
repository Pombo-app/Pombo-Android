package com.pombo.android.core

import java.nio.ByteBuffer

/**
 * qt-faststart, as the web's makeFaststartSource: moves the moov atom before
 * mdat and patches the stco/co64 offset tables, so a stored MP4 can play while
 * it downloads. The result is a [StorageMedia.Source.Slicer] over the new
 * layout: same total size, no mdat copy. On any doubt the file stays as it is.
 */
object Mp4Faststart {

    data class Result(
        /** The remuxed layout, or null when the file uploads unchanged. */
        val slicer: StorageMedia.Source.Slicer?,
        val reason: String
    )

    private class Box(val type: String, val pos: Long, val size: Long)

    private val CONTAINERS = setOf("moov", "trak", "mdia", "minf", "stbl", "edts", "udta", "mvex")

    fun isMp4Like(fileName: String, fileType: String): Boolean =
        Regex("\\.(mp4|m4v|mov)$", RegexOption.IGNORE_CASE).containsMatchIn(fileName) ||
            Regex("video/(mp4|quicktime)", RegexOption.IGNORE_CASE).containsMatchIn(fileType)

    fun remux(source: StorageMedia.Source.Slicer, size: Long): Result {
        fun bail(reason: String) = Result(null, reason)
        try {
            val boxes = ArrayList<Box>()
            var pos = 0L
            while (pos + 8 <= size) {
                val hdr = ByteBuffer.wrap(source.readAt(pos, minOf(16L, size - pos).toInt()))
                var boxSize = hdr.getInt(0).toLong() and 0xFFFFFFFFL
                val type = String(ByteArray(4) { hdr.get(4 + it) }, Charsets.ISO_8859_1)
                if (boxSize == 1L) {
                    if (hdr.limit() < 16) return bail("truncated 64-bit box")
                    boxSize = hdr.getLong(8)
                } else if (boxSize == 0L) {
                    boxSize = size - pos
                }
                if (boxSize < 8 || pos + boxSize > size) return bail("invalid box '$type'")
                boxes.add(Box(type, pos, boxSize))
                pos += boxSize
            }
            val ftyp = boxes.firstOrNull { it.type == "ftyp" }
            val moov = boxes.firstOrNull { it.type == "moov" }
            val mdat = boxes.firstOrNull { it.type == "mdat" }
            if (moov == null || mdat == null) return bail("no moov/mdat — not a classic MP4")
            if (moov.pos < mdat.pos) return bail("already faststart")
            if (boxes.any { it.type == "mdat" && it.pos > moov.pos }) return bail("mdat after moov — unsupported layout")
            if (moov.size > Int.MAX_VALUE) return bail("moov too large")

            val moovBuf = ByteBuffer.wrap(source.readAt(moov.pos, moov.size.toInt()))
            val delta = moov.size
            var overflow = false
            var cmov = false
            var patched = 0
            fun walk(start: Int, end: Int) {
                var p = start
                while (p + 8 <= end && !overflow && !cmov) {
                    var boxSize = moovBuf.getInt(p).toLong() and 0xFFFFFFFFL
                    val type = String(ByteArray(4) { moovBuf.get(p + 4 + it) }, Charsets.ISO_8859_1)
                    var hs = 8
                    if (boxSize == 1L) { boxSize = moovBuf.getLong(p + 8); hs = 16 }
                    else if (boxSize == 0L) boxSize = (end - p).toLong()
                    if (boxSize < hs || p + boxSize > end) return
                    when {
                        type == "cmov" -> { cmov = true; return }
                        type == "stco" -> {
                            val n = moovBuf.getInt(p + hs + 4)
                            for (i in 0 until n) {
                                val o = p + hs + 8 + i * 4
                                val v = (moovBuf.getInt(o).toLong() and 0xFFFFFFFFL) + delta
                                if (v > 0xFFFFFFFFL) { overflow = true; return }
                                moovBuf.putInt(o, v.toInt())
                            }
                            patched++
                        }
                        type == "co64" -> {
                            val n = moovBuf.getInt(p + hs + 4)
                            for (i in 0 until n) {
                                val o = p + hs + 8 + i * 8
                                moovBuf.putLong(o, moovBuf.getLong(o) + delta)
                            }
                            patched++
                        }
                        type in CONTAINERS -> walk(p + hs, (p + boxSize).toInt())
                    }
                    p += boxSize.toInt()
                }
            }
            val moovHs = if (moovBuf.getInt(0) == 1) 16 else 8
            walk(moovHs, moov.size.toInt())
            if (cmov) return bail("compressed moov (cmov) not supported")
            if (overflow) return bail("offsets would exceed 32 bits after patching")

            val ftypEnd = ftyp?.let { it.pos + it.size } ?: 0L
            val segments = ArrayList<Segment>()
            if (ftypEnd > 0) segments.add(Segment.Source(0, ftypEnd))
            segments.add(Segment.Bytes(moovBuf.array()))
            for (b in boxes) {
                if (b === moov || b.pos < ftypEnd) continue
                segments.add(Segment.Source(b.pos, b.size))
            }
            return Result(
                RemuxSlicer(source, segments),
                "moov (${moov.size / 1024}KB) moved to the front · $patched offset table(s) patched"
            )
        } catch (e: Exception) {
            return bail("parse error: ${e.message}")
        }
    }

    private sealed class Segment(val length: Long) {
        class Source(val from: Long, length: Long) : Segment(length)
        class Bytes(val bytes: ByteArray) : Segment(bytes.size.toLong())
    }

    private class RemuxSlicer(
        private val source: StorageMedia.Source.Slicer,
        private val segments: List<Segment>
    ) : StorageMedia.Source.Slicer {
        private val starts = LongArray(segments.size).also { s ->
            var at = 0L
            segments.forEachIndexed { i, seg -> s[i] = at; at += seg.length }
        }

        override fun readAt(offset: Long, length: Int): ByteArray {
            val out = ByteArray(length)
            var done = 0
            var i = starts.indexOfLast { it <= offset }.coerceAtLeast(0)
            while (done < length && i < segments.size) {
                val seg = segments[i]
                val within = offset + done - starts[i]
                val take = minOf((seg.length - within), (length - done).toLong()).toInt()
                if (take > 0) {
                    when (seg) {
                        is Segment.Source -> System.arraycopy(source.readAt(seg.from + within, take), 0, out, done, take)
                        is Segment.Bytes -> System.arraycopy(seg.bytes, within.toInt(), out, done, take)
                    }
                    done += take
                }
                i++
            }
            return if (done == length) out else out.copyOf(done)
        }

        override fun close() = source.close()
    }
}
