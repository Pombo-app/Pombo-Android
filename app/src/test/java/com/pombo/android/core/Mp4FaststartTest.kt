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
}
