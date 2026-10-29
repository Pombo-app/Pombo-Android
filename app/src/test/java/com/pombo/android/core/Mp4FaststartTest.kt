package com.pombo.android.core

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class Mp4FaststartTest {

    private class BytesSlicer(val bytes: ByteArray) : StorageMedia.Source.Slicer {
        override fun readAt(offset: Long, length: Int) = bytes.copyOfRange(offset.toInt(), offset.toInt() + length)
        override fun close() {}
    }

    private fun box(type: String, vararg body: ByteArray): ByteArray {
        val content = body.fold(ByteArray(0)) { acc, b -> acc + b }
        return ByteBuffer.allocate(8 + content.size).putInt(8 + content.size).put(type.toByteArray(Charsets.ISO_8859_1)).put(content).array()
    }

    private fun stco(vararg offsets: Int): ByteArray {
        val b = ByteBuffer.allocate(8 + offsets.size * 4).putInt(0).putInt(offsets.size)
        offsets.forEach { b.putInt(it) }
        return box("stco", b.array())
    }

    private val ftyp = box("ftyp", "isom".toByteArray(), ByteArray(4))
    private val payload = ByteArray(100) { (it * 7).toByte() }
    private val mdat = box("mdat", payload)
    private val dataStart = ftyp.size + 8
    private fun moov() = box("moov", box("trak", box("mdia", box("minf", box("stbl", stco(dataStart, dataStart + 50))))))

    private fun stcoOffsets(file: ByteArray): List<Int> {
        val at = String(file, Charsets.ISO_8859_1).indexOf("stco") + 4 + 4
        val n = ByteBuffer.wrap(file).getInt(at)
        return (0 until n).map { ByteBuffer.wrap(file).getInt(at + 4 + it * 4) }
    }

    @Test
    fun `moov moves to the front and the chunk offsets still point at the same bytes`() {
        val moov = moov()
        val original = ByteArrayOutputStream().apply { write(ftyp); write(mdat); write(moov) }.toByteArray()

        val result = Mp4Faststart.remux(BytesSlicer(original), original.size.toLong())

        val slicer = result.slicer!!
        val remuxed = slicer.readAt(0, original.size)
        assertEquals("moov", String(remuxed, ftyp.size + 4, 4, Charsets.ISO_8859_1))
        assertEquals("mdat", String(remuxed, ftyp.size + moov.size + 4, 4, Charsets.ISO_8859_1))
        val offsets = stcoOffsets(remuxed)
        assertEquals(listOf(dataStart + moov.size, dataStart + 50 + moov.size), offsets)
        assertArrayEquals(payload.copyOfRange(0, 50), remuxed.copyOfRange(offsets[0], offsets[0] + 50))
        assertArrayEquals(payload.copyOfRange(50, 100), remuxed.copyOfRange(offsets[1], offsets[1] + 50))
        assertArrayEquals(remuxed.copyOfRange(30, 90), slicer.readAt(30, 60))
    }

    @Test
    fun `a file whose moov already comes first is left as it is`() {
        val original = ByteArrayOutputStream().apply { write(ftyp); write(moov()); write(mdat) }.toByteArray()

        val result = Mp4Faststart.remux(BytesSlicer(original), original.size.toLong())

        assertNull(result.slicer)
        assertEquals("already faststart", result.reason)
    }

    @Test
    fun `a file that is not an MP4 is left as it is`() {
        val junk = ByteArray(64) { 0x41 }
        assertNull(Mp4Faststart.remux(BytesSlicer(junk), junk.size.toLong()).slicer)
    }

    private fun u32s(vararg xs: Int) = ByteBuffer.allocate(xs.size * 4).apply { xs.forEach { putInt(it) } }.array()
    private fun cat(vararg parts: ByteArray) = parts.fold(ByteArray(0)) { acc, b -> acc + b }

    private val fragMoov = box("moov",
        box("trak", box("tkhd", u32s(0, 0, 0, 1, 0)), box("mdia", box("mdhd", u32s(0, 0, 0, 1000, 0)))),
        box("mvex", box("trex", u32s(0, 1, 1, 0, 0, 0))))
    private fun fragment(tfhd: ByteArray, vararg rest: ByteArray) =
        box("moof", box("mfhd", u32s(0, 1)), box("traf", tfhd, *rest))
    // Per-sample durations in trun; then a default duration in tfhd.
    private val moof1 = fragment(box("tfhd", u32s(0x020000, 1)), box("tfdt", u32s(0x01000000, 0, 1000)), box("trun", u32s(0x100, 2, 100, 150)))
    private val moof2 = fragment(box("tfhd", u32s(0x020008, 1, 200)), box("trun", u32s(0, 3)))
    private val mdat1 = box("mdat", ByteArray(20))
    private val mdat2 = box("mdat", ByteArray(10))
    private val free = box("free", ByteArray(4))

    @Test
    fun `a fragmented MP4 gains a sidx after the moov describing each fragment`() {
        val original = cat(ftyp, fragMoov, free, moof1, mdat1, moof2, mdat2, box("mfra", ByteArray(16)))

        val result = Mp4Faststart.addSegmentIndex(BytesSlicer(original), original.size.toLong())

        val out = result.slicer!!.readAt(0, result.size.toInt())
        val at = ftyp.size + fragMoov.size
        val v = ByteBuffer.wrap(out, at, out.size - at).slice()
        assertEquals("sidx", String(out, at + 4, 4, Charsets.ISO_8859_1))
        assertEquals(original.size + v.getInt(0), out.size)
        assertEquals(1, v.getInt(12))
        assertEquals(1000, v.getInt(16))
        assertEquals(1000L, v.getLong(20))
        assertEquals(free.size.toLong(), v.getLong(28))
        assertEquals(2, v.getShort(38).toInt())
        assertEquals(listOf(moof1.size + mdat1.size, 250), listOf(v.getInt(40), v.getInt(44)))
        assertEquals(listOf(moof2.size + mdat2.size, 600), listOf(v.getInt(52), v.getInt(56)))
        assertArrayEquals(original.copyOfRange(at, original.size), out.copyOfRange(at + v.getInt(0), out.size))
    }

    @Test
    fun `a progressive or already indexed MP4 is left as it is`() {
        val progressive = cat(ftyp, moov(), mdat)
        assertNull(Mp4Faststart.addSegmentIndex(BytesSlicer(progressive), progressive.size.toLong()).slicer)
        val indexed = cat(ftyp, fragMoov, box("sidx", ByteArray(24)), moof1, mdat1)
        assertEquals("already indexed", Mp4Faststart.addSegmentIndex(BytesSlicer(indexed), indexed.size.toLong()).reason)
        val junk = ByteArray(64) { 0x41 }
        assertNull(Mp4Faststart.addSegmentIndex(BytesSlicer(junk), junk.size.toLong()).slicer)
    }
}
