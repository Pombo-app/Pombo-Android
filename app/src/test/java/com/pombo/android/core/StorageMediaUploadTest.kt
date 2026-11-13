package com.pombo.android.core

import com.pombo.android.bridge.PomboBridge
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import java.io.File
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The DM upload path end to end against a scripted storage node. The verify
 * waits run on the real clock, so the cases that reach the pre-repair drain
 * wait out [StorageMediaConfig.VERIFY_QUIET_MS] each.
 */
class StorageMediaUploadTest {

    private val base = "https://node.test"
    private val peerInbox = "0xbbbb000000000000000000000000000000000002/Pombo-DM-1"
    private val dir: File = Files.createTempDirectory("pombo-upload").toFile()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val myPk = SealedSenderCrypto.generateEphemeralKey()
    private val peerPub = EthereumSigner.compressedPublicKey(SealedSenderCrypto.generateEphemeralKey())

    private class Publish(val partition: Int, val ts: Long, val size: Int)

    private val clock = AtomicLong(System.currentTimeMillis())
    private val published = Collections.synchronizedList(ArrayList<Publish>())
    /** Timestamps the node never stored. */
    private val lost = Collections.synchronizedSet(HashSet<Long>())
    /** False: the node's `stored` endpoint does not answer. */
    @Volatile private var storedAnswers = true
    private val announces = Collections.synchronizedList(ArrayList<JSONObject>())

    private val bridge = mockk<PomboBridge>(relaxed = true)
    private val endpoints = mockk<StorageEndpoints>(relaxed = true)

    @Before fun setUp() {
        coEvery { bridge.call("getStreamInfo", any(), any()) } throws IllegalStateException("offline")
        every { bridge.inFlight() } returns 0L
        coEvery { bridge.publishStorageChunk(any(), any(), any(), any(), any()) } answers {
            val ts = clock.incrementAndGet()
            published.add(Publish(secondArg(), ts, thirdArg<ByteArray>().size))
            ts
        }
        coEvery { endpoints.rotation(any()) } returns listOf(base)
        coEvery { endpoints.providersWith(any(), "stored") } returns listOf(StorageEndpoints.Node("0xnode", listOf(base)))
        every { endpoints.supportsMetaFormat(any()) } returns true

        mockkObject(StorageHttp, StoragePurge)
        coEvery { StorageHttp.directFetchRangeMeta(any(), any(), any(), any(), any()) } throws StorageHttp.HttpStatusException(403)
        coEvery { StorageHttp.directFetchRange(any(), any(), any(), any(), any(), any()) } throws StorageHttp.HttpStatusException(403)
        coEvery { StoragePurge.storedOn(any(), any(), any(), any(), any(), any()) } answers {
            if (!storedAnswers) null
            else {
                val partition = thirdArg<Int>()
                val held = synchronized(published) { published.filter { it.partition == partition && it.ts !in lost }.map { it.ts }.toSet() }
                arg<List<StoragePurge.Target>>(3).filter { it.timestamp in held }.toSet()
            }
        }
    }

    @After fun tearDown() {
        unmockkAll()
        scope.cancel()
        dir.deleteRecursively()
    }

    private fun media() = StorageMedia(
        bridge = bridge,
        endpoints = endpoints,
        scope = scope,
        transferDir = { dir },
        myPrivateKey = { myPk },
        transport = object : StorageMedia.Transport {
            override suspend fun publishAnnounce(messageStreamId: String, announce: JSONObject, password: String?, isDm: Boolean): Long {
                announces.add(announce); return clock.incrementAndGet()
            }
            override suspend fun announceStored(messageStreamId: String, messageId: String, late: AtomicBoolean): Boolean = true
            override fun myAddress() = "0xaaaa000000000000000000000000000000000001"
            override fun username(): String? = null
        }
    )

    /** Three chunks of incompressible bytes, sent as is. */
    private fun source(): StorageMedia.Source {
        val bytes = ByteArray(600 * 1024).also { java.util.Random(7).nextBytes(it) }
        return object : StorageMedia.Source {
            override val fileName = "blob.zip"
            override val fileType = "application/zip"
            override val size = bytes.size.toLong()
            override fun openStream() = bytes.inputStream()
            override fun openSlicer() = object : StorageMedia.Source.Slicer {
                override fun readAt(offset: Long, length: Int) = bytes.copyOfRange(offset.toInt(), offset.toInt() + length)
                override fun close() {}
            }
        }
    }

    private suspend fun send() = media().sendFile(peerInbox, source(), null, isDm = true, dmPeerPublicKey = peerPub)

    private fun pings() = published.filter { it.size == 1 }
    private fun chunks() = published.filter { it.size > 1 }

    @Test
    fun `a confirmed upload keeps the warm-up pings among the rows its delete removes`() = runBlocking {
        val result = send()

        assertEquals(3, result.totalChunks)
        assertEquals(3, result.storedChunks)
        assertEquals(3, chunks().size)
        assertTrue(pings().isNotEmpty())
        val rows = result.chunkRows.map { it.partition to it.timestamp }.toSet()
        assertEquals(published.map { it.partition to it.ts }.toSet(), rows)
    }

    @Test
    fun `no stored answer and refused reads announce unverified without republishing`() = runBlocking {
        storedAnswers = false

        val result = send()

        assertNull(result.storedChunks)
        assertEquals(3, chunks().size)
        assertTrue(announces.single().getJSONObject("metadata").isNull("storedChunks"))
    }

    @Test
    fun `a chunk the node confirms missing is republished alone`() = runBlocking {
        coEvery { bridge.publishStorageChunk(any(), any(), any(), any(), any()) } answers {
            val ts = clock.incrementAndGet()
            val size = thirdArg<ByteArray>().size
            if (size > 1 && published.none { it.size > 1 }) lost.add(ts)
            published.add(Publish(secondArg(), ts, size))
            ts
        }

        val result = send()

        assertEquals(3, result.storedChunks)
        assertEquals(4, chunks().size)
        assertEquals(chunks().first().partition, chunks().last().partition)
    }
}
