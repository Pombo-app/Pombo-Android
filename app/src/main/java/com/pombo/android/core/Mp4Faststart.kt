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

    data class Indexed(
        /** The file with a segment index, or null when it uploads unchanged. */
        val slicer: StorageMedia.Source.Slicer?,
        val size: Long,
        val reason: String
    )

    /**
     * As the web's addSegmentIndex: gives a fragmented MP4 a sidx right after
     * its moov, so a player knows the duration and where each fragment starts
     * without walking a file that is still downloading. Fragment data offsets
     * are relative to their moof, so the inserted box moves nothing they point at.
     */
    fun addSegmentIndex(source: StorageMedia.Source.Slicer, size: Long): Indexed {
        fun bail(reason: String) = Indexed(null, size, reason)
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
            if (boxes.any { it.type == "sidx" }) return bail("already indexed")
            val moov = boxes.firstOrNull { it.type == "moov" }
            val moofs = boxes.filter { it.type == "moof" }
            if (moov == null || moofs.isEmpty() || moov.pos > moofs[0].pos) return bail("not a fragmented MP4")
            if (moov.size > Int.MAX_VALUE) return bail("moov too large")

            fun u32(b: ByteBuffer, at: Int) = b.getInt(at).toLong() and 0xFFFFFFFFL
            fun children(b: ByteBuffer, start: Int, end: Int): List<Box> {
                val out = ArrayList<Box>()
                var p = start
                while (p + 8 <= end) {
                    val s = u32(b, p)
                    if (s < 8 || p + s > end) break
                    out.add(Box(String(ByteArray(4) { b.get(p + 4 + it) }, Charsets.ISO_8859_1), p.toLong(), s))
                    p += s.toInt()
                }
                return out
            }
            fun Box.kids(b: ByteBuffer) = children(b, this.pos.toInt() + 8, (this.pos + this.size).toInt())

            val mv = ByteBuffer.wrap(source.readAt(moov.pos, moov.size.toInt()))
            val mvKids = children(mv, 8, moov.size.toInt())
            val trak = mvKids.firstOrNull { it.type == "trak" }
            val mvex = mvKids.firstOrNull { it.type == "mvex" }
            if (trak == null || mvex == null) return bail("no track or mvex")
            val tkhd = trak.kids(mv).firstOrNull { it.type == "tkhd" }
            val mdhd = trak.kids(mv).firstOrNull { it.type == "mdia" }?.kids(mv)?.firstOrNull { it.type == "mdhd" }
            if (tkhd == null || mdhd == null) return bail("no tkhd/mdhd")
            val trackId = u32(mv, tkhd.pos.toInt() + if (mv.get(tkhd.pos.toInt() + 8).toInt() == 1) 28 else 20)
            val timescale = u32(mv, mdhd.pos.toInt() + if (mv.get(mdhd.pos.toInt() + 8).toInt() == 1) 28 else 20)
            val trex = mvex.kids(mv).firstOrNull { it.type == "trex" && u32(mv, it.pos.toInt() + 12) == trackId }
            val trexDuration = trex?.let { u32(mv, it.pos.toInt() + 20) } ?: 0L

            val refSizes = ArrayList<Long>()
            val refDurations = ArrayList<Long>()
            var earliest: Long? = null
            for ((i, m) in moofs.withIndex()) {
                var k = boxes.indexOf(m) + 1
                while (k < boxes.size && boxes[k].type == "mdat") k++
                val end = boxes[k - 1].pos + boxes[k - 1].size
                if (i + 1 < moofs.size && end != moofs[i + 1].pos) return bail("fragments are not contiguous")
                if (m.size > Int.MAX_VALUE) return bail("moof too large")
                val dv = ByteBuffer.wrap(source.readAt(m.pos, m.size.toInt()))
                val traf = children(dv, 8, m.size.toInt()).filter { it.type == "traf" }.firstOrNull { t ->
                    t.kids(dv).firstOrNull { it.type == "tfhd" }?.let { u32(dv, it.pos.toInt() + 12) == trackId } == true
                } ?: return bail("a fragment without the indexed track")
                val parts = traf.kids(dv)
                val tfhd = parts.first { it.type == "tfhd" }
                val tfhdFlags = dv.getInt(tfhd.pos.toInt() + 8) and 0xffffff
                var o = tfhd.pos.toInt() + 16
                if (tfhdFlags and 0x1 != 0) o += 8
                if (tfhdFlags and 0x2 != 0) o += 4
                val defaultDuration = if (tfhdFlags and 0x8 != 0) u32(dv, o) else trexDuration
                val tfdt = parts.firstOrNull { it.type == "tfdt" }
                if (tfdt != null && earliest == null) {
                    val at = tfdt.pos.toInt()
                    earliest = if (dv.get(at + 8).toInt() == 1) dv.getLong(at + 12) else u32(dv, at + 12)
                }
                var duration = 0L
                for (trun in parts.filter { it.type == "trun" }) {
                    val at = trun.pos.toInt()
                    val flags = dv.getInt(at + 8) and 0xffffff
                    val count = u32(dv, at + 12)
                    var q = at + 16 + (if (flags and 0x1 != 0) 4 else 0) + (if (flags and 0x4 != 0) 4 else 0)
                    val stride = listOf(0x100, 0x200, 0x400, 0x800).count { flags and it != 0 } * 4
                    if (flags and 0x100 != 0) {
                        for (s in 0 until count) { duration += u32(dv, q); q += stride }
                    } else {
                        duration += count * defaultDuration
                    }
                }
                if (duration == 0L) return bail("a fragment with no duration")
                val refSize = end - m.pos
                if (refSize > 0x7fffffffL || duration > 0xFFFFFFFFL) return bail("fragment too large for a segment index")
                refSizes.add(refSize)
                refDurations.add(duration)
            }

            if (refSizes.size > 0xffff) return bail("too many fragments for a segment index")
            val cut = moov.pos + moov.size
            val sidx = ByteBuffer.allocate(40 + refSizes.size * 12)
                .putInt(40 + refSizes.size * 12).putInt(0x73696478).putInt(0x01000000)
                .putInt(trackId.toInt()).putInt(timescale.toInt())
                .putLong(earliest ?: 0L).putLong(moofs[0].pos - cut)
                .putShort(0).putShort(refSizes.size.toShort())
            for (r in refSizes.indices) sidx.putInt(refSizes[r].toInt()).putInt(refDurations[r].toInt()).putInt(0x90000000.toInt())
            val bytes = sidx.array()
            return Indexed(
                RemuxSlicer(source, listOf(Segment.Source(0, cut), Segment.Bytes(bytes), Segment.Source(cut, size - cut))),
                size + bytes.size,
                "segment index over ${refSizes.size} fragment(s) added"
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
