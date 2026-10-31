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
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/** A DM upload verifies by the nodes' `stored` endpoint: the sender may not read the peer's inbox. */
class StorageMediaDmVerifyTest {

    private val node = "https://node-a.example"
    private val inbox = "0xpeer/Pombo-DM-1"
    private val nextTs = AtomicLong(1_700_000_000_000L)

    @Before
    fun setUp() {
        mockkObject(StorageHttp, StoragePurge)
        coEvery { StorageHttp.directFetchRangeMeta(any(), any(), any(), any(), any()) } throws StorageHttp.HttpStatusException(403)
        coEvery { StorageHttp.directFetchRange(any(), any(), any(), any(), any(), any()) } throws StorageHttp.HttpStatusException(403)
        coEvery { StoragePurge.storedOn(any(), any(), any(), any(), any(), any()) } answers { arg<List<StoragePurge.Target>>(3).toSet() }
    }

    @After
    fun tearDown() = unmockkObject(StorageHttp, StoragePurge)

    private fun media(): StorageMedia {
        val bridge = mockk<PomboBridge>(relaxed = true)
        coEvery { bridge.call("getStreamInfo", any(), any()) } returns JSONObject().put("metadata", JSONObject().put("partitions", 13))
        coEvery { bridge.publishStorageChunk(any(), any(), any(), any(), any()) } answers { nextTs.getAndIncrement() }
        every { bridge.inFlight() } returns 0L
        val endpoints = mockk<StorageEndpoints>(relaxed = true)
        coEvery { endpoints.rotation(any()) } returns listOf(node)
        coEvery { endpoints.providersWith(any(), "stored") } returns listOf(StorageEndpoints.Node("0xnode", listOf(node)))
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

    @Test
    fun `never reads the peer inbox on a DM when the nodes answer stored`() = runBlocking {
        val peerPub = EthereumSigner.compressedPublicKey(SealedSenderCrypto.generateEphemeralKey())
        val result = media().sendFile(inbox, source(ByteArray(600 * 1024) { (it % 251).toByte() }), null, isDm = true, dmPeerPublicKey = peerPub)

        assertEquals(result.totalChunks, result.storedChunks)
        coVerify(exactly = 0) { StorageHttp.directFetchRangeMeta(any(), inbox, match { it >= StreamConstants.STORAGE_DM_FIRST_CHUNK_PARTITION }, any(), any()) }
        coVerify(exactly = 0) { StorageHttp.directFetchRange(any(), inbox, match { it >= StreamConstants.STORAGE_DM_FIRST_CHUNK_PARTITION }, any(), any(), any()) }
    }
}
