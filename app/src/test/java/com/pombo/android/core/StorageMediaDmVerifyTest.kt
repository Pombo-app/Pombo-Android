package com.pombo.android.core

import com.pombo.android.bridge.PomboBridge
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** A DM upload verifies by the nodes' `stored` endpoint: the sender may not read the peer's inbox. */
class StorageMediaDmVerifyTest {

    private val nodeA = StorageEndpoints.Node("0xa", listOf("https://node-a.example"))
    private val nodeB = StorageEndpoints.Node("0xb", listOf("https://node-b.example"))
    private val inbox = "0xpeer/Pombo-DM-1"
    // Inside the full sweep's window, which starts from the wall clock.
    private val nextTs = AtomicLong(System.currentTimeMillis())
    /** Every chunk publish (warm-up pings excluded): partition → timestamp. */
    private val chunkPublishes = Collections.synchronizedList(ArrayList<Pair<Int, Long>>())

    @Before
    fun setUp() {
        mockkObject(StorageHttp, StoragePurge)
        coEvery { StorageHttp.directFetchRangeMeta(any(), any(), any(), any(), any()) } throws StorageHttp.HttpStatusException(403)
        coEvery { StorageHttp.directFetchRange(any(), any(), any(), any(), any(), any()) } throws StorageHttp.HttpStatusException(403)
        coEvery { StoragePurge.storedOn(any(), any(), any(), any(), any(), any()) } answers { arg<List<StoragePurge.Target>>(3).toSet() }
    }

    @After
    fun tearDown() = unmockkObject(StorageHttp, StoragePurge)

    private fun media(providers: List<StorageEndpoints.Node> = listOf(nodeA)): StorageMedia {
        val bridge = mockk<PomboBridge>(relaxed = true)
        coEvery { bridge.call("getStreamInfo", any(), any()) } returns JSONObject().put("metadata", JSONObject().put("partitions", 13))
        coEvery { bridge.publishStorageChunk(any(), any(), any(), any(), any()) } answers {
            val ts = nextTs.getAndIncrement()
            if (thirdArg<ByteArray>().size > 1) chunkPublishes.add(secondArg<Int>() to ts)
            ts
        }
        every { bridge.inFlight() } returns 0L
        val endpoints = mockk<StorageEndpoints>(relaxed = true)
        coEvery { endpoints.rotation(any()) } returns providers.map { it.urls.first() }
        coEvery { endpoints.providersWith(any(), "stored") } returns providers
        every { endpoints.supportsMetaFormat(any()) } returns true
        val transport = object : StorageMedia.Transport {
            override suspend fun publishAnnounce(messageStreamId: String, announce: JSONObject, password: String?, isDm: Boolean) = nextTs.getAndIncrement()
            override suspend fun announceStored(messageStreamId: String, messageId: String) = true
            override fun myAddress() = "0xme"
            override fun username() = "me"
        }
        val myKey = SealedSenderCrypto.generateEphemeralKey()
        val dir = Files.createTempDirectory("dmverify").toFile()
        return StorageMedia(bridge, endpoints, CoroutineScope(SupervisorJob() + Dispatchers.Default), { dir }, { myKey }, transport)
    }

    private fun source(bytes: ByteArray) = object : StorageMedia.Source {
        override val fileName = "photo.jpg"
        override val fileType = "image/jpeg"
        override val size = bytes.size.toLong()
        override fun openStream(): InputStream = ByteArrayInputStream(bytes)
        override fun openSlicer() = object : StorageMedia.Source.Slicer {
            override fun readAt(offset: Long, length: Int) = bytes.copyOfRange(offset.toInt(), offset.toInt() + length)
            override fun close() {}
        }
    }

    private fun fileOf(chunks: Int) = source(ByteArray(chunks * 230 * 1024) { (it % 251).toByte() })
    private val peerPub = EthereumSigner.compressedPublicKey(SealedSenderCrypto.generateEphemeralKey())

    @Test
    fun `never reads the peer inbox on a DM when the nodes answer stored`() = runBlocking {
        val result = media().sendFile(inbox, fileOf(3), null, isDm = true, dmPeerPublicKey = peerPub)

        assertEquals(result.totalChunks, result.storedChunks)
        coVerify(exactly = 0) { StorageHttp.directFetchRangeMeta(any(), inbox, match { it >= StreamConstants.STORAGE_DM_FIRST_CHUNK_PARTITION }, any(), any()) }
        coVerify(exactly = 0) { StorageHttp.directFetchRange(any(), inbox, match { it >= StreamConstants.STORAGE_DM_FIRST_CHUNK_PARTITION }, any(), any(), any()) }
    }

    @Test
    fun `asks the partitions of every provider in parallel`() = runBlocking {
        val inFlight = AtomicInteger(0)
        val maxInFlight = AtomicInteger(0)
        coEvery { StoragePurge.storedOn(any(), any(), any(), any(), any(), any()) } coAnswers {
            maxInFlight.accumulateAndGet(inFlight.incrementAndGet(), ::maxOf)
            delay(20)
            inFlight.decrementAndGet()
            arg<List<StoragePurge.Target>>(3).toSet()
        }
        val result = media(listOf(nodeA, nodeB)).sendFile(inbox, fileOf(9), null, isDm = true, dmPeerPublicKey = peerPub)

        assertEquals(result.totalChunks, result.storedChunks)
        assertTrue("max in flight ${maxInFlight.get()}", maxInFlight.get() in 2..6)
    }

    @Test
    fun `announces once one provider holds every chunk, and repairs the other in the background`() = runBlocking {
        val releaseB = CompletableDeferred<Unit>()
        val firstOnP6 = { chunkPublishes.first { it.first == 6 }.second }
        coEvery { StoragePurge.storedOn(any(), any(), any(), any(), any(), any()) } coAnswers {
            val node = firstArg<List<StorageEndpoints.Node>>().single().nodeAddress
            val partition = thirdArg<Int>()
            if (node == nodeB.nodeAddress) releaseB.await()
            arg<List<StoragePurge.Target>>(3)
                .filter { node == nodeA.nodeAddress || partition != 6 || it.timestamp != firstOnP6() }
                .toSet()
        }
        val later = Collections.synchronizedList(ArrayList<DmSentRows.Row>())
        val media = media(listOf(nodeA, nodeB))

        // Every chunk partition in use: a node that hangs on all of them must not starve the other.
        val result = withTimeout(60_000) {
            media.sendFile(inbox, fileOf(9), null, isDm = true, dmPeerPublicKey = peerPub, onLaterChunkRows = { later.addAll(it) })
        }
        assertEquals(result.totalChunks, result.storedChunks)
        assertEquals(1, media.backgroundVerifies.value)
        assertTrue(media.hasActiveTransfers())
        assertEquals(1, chunkPublishes.count { it.first == 6 })

        releaseB.complete(Unit)
        withTimeout(90_000) { while (media.backgroundVerifies.value > 0) delay(100) }
        assertEquals(2, chunkPublishes.count { it.first == 6 })
        val republished = chunkPublishes.last { it.first == 6 }.second
        assertTrue(later.any { it.partition == 6 && it.timestamp == republished })
        assertTrue(result.chunkRows.none { it.timestamp == republished })
    }
}
