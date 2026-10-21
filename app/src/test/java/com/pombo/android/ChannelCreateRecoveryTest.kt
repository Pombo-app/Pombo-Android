package com.pombo.android

import com.pombo.android.data.PendingCreate
import com.pombo.android.data.PendingCreateStore
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * A kill part way through channel creation used to leave a paid channel, and
 * the only copy of its shared keys, nowhere. The record is written before the
 * first transaction; Finish asks the chain before every write.
 */
class ChannelCreateRecoveryTest {

    private val events = mutableListOf<String>()
    private val calls = mutableListOf<Pair<String, JSONObject>>()
    private var stored = listOf<PendingCreate>()
    private var existing = false
    private var failOn: String? = null
    private var notAllowed = listOf<String>()
    private var onGateCreate: () -> Unit = {}

    private val pendingStore: PendingCreateStore = mockk<PendingCreateStore>(relaxed = true).also { s ->
        every { s.load() } answers { stored }
        every { s.put(any()) } answers {
            val record = firstArg<PendingCreate>()
            stored = stored.filterNot { it.basePath == record.basePath } + record
            events += "put"
        }
        every { s.remove(any()) } answers {
            stored = stored.filterNot { it.basePath == firstArg<String>() }
            events += "remove"
        }
    }

    private val h = ChannelManagerHarness(pendingCreateStore = pendingStore)

    init {
        every { h.store.save(any()) } answers { events += "saveChannels" }
        every { h.epochKeyStore.load(any()) } returns null
        every { h.epochKeyStore.save(any(), any()) } answers { events += "saveKeys" }
        coEvery { h.bridge.call(any(), any(), any()) } answers {
            val method = firstArg<String>()
            val args = JSONObject(secondArg<JSONObject>().toString())
            calls += method to args
            events += method
            if (method == failOn) throw IllegalStateException("insufficient funds for gas")
            when (method) {
                "blockNumber" -> JSONObject().put("block", 1000L)
                "gateCreate" -> { onGateCreate(); JSONObject().put("gate", "0xgate") }
                "streamExists" -> JSONObject().put("exists", existing)
                "gateFindCreated" -> JSONObject().put("gate", JSONObject.NULL)
                "gateNotAllowed" -> JSONObject().put("missing", JSONArray(notAllowed))
                "addToStorageNode" -> JSONObject().put("retentionApplied", true)
                else -> JSONObject()
            }
        }
    }

    @After fun tearDown() = h.stop()

    private fun create(type: String = "public", members: List<String> = emptyList()) = runBlocking {
        h.manager.createChannel(name = "Group", type = type, members = members, storageDays = 30)
    }

    private fun interrupt(type: String = "public", members: List<String> = emptyList()): PendingCreate {
        failOn = "createStream"
        try {
            create(type, members)
            fail("the creation was meant to stop part way")
        } catch (e: IllegalStateException) {
            // expected
        }
        failOn = null
        calls.clear()
        events.clear()
        return stored.single()
    }

    private fun writes() = calls.filter { it.first in setOf("createStream", "setPermissions", "addToStorageNode") }

    @Test
    fun `writes the record, with the stream ids, before the first transaction`() {
        create()

        val firstPut = events.indexOf("put")
        assertTrue(firstPut in 0 until events.indexOf("createStream"))
        val firstStream = calls.first { it.first == "createStream" }.second
        assertTrue(firstStream.getString("id").matches(Regex("${h.me}/[0-9a-f]{16}-1")))
        assertFalse(firstStream.getBoolean("onlyIfMissing"))
    }

    @Test
    fun `a gated creation records the block before the gate and the gate before the streams`() {
        val captured = mutableListOf<PendingCreate>()
        every { pendingStore.put(any()) } answers {
            val record = firstArg<PendingCreate>()
            captured += record
            stored = stored.filterNot { it.basePath == record.basePath } + record
            events += "put"
        }

        create(type = "gated")

        assertEquals(listOf("blockNumber", "put", "gateCreate", "put"), events.take(4))
        assertTrue(events.indexOf("createStream") > 3)
        assertEquals(1000L, captured[0].startBlock)
        assertNotNull(captured[0].publishKey)
        assertNotNull(captured[0].interactionsKey)
        assertEquals("0xgate", captured[1].gateAddress)
        val grants = calls.first { it.first == "setPermissions" }.second.toString()
        assertTrue(grants.contains(captured[0].publishKey!!.address))
    }

    @Test
    fun `drops the record only after the channel is saved and its keys adopted`() {
        create(type = "gated")

        val saved = events.indexOf("saveChannels")
        val adopted = events.lastIndexOf("saveKeys")
        val dropped = events.indexOf("remove")
        assertTrue(saved in 0 until adopted)
        assertTrue(adopted < dropped)
        assertTrue(stored.isEmpty())
    }

    @Test
    fun `keeps the record when a transaction fails part way, free to finish later`() {
        val record = interrupt()

        assertTrue(h.manager.channels.value.isEmpty())
        assertTrue(h.manager.pendingCreates.claim(record.basePath))
    }

    @Test
    fun `Finish asks the chain before every write and reuses the ids, the gate and the keys`() = runBlocking {
        val record = interrupt(type = "gated")

        val channel = h.manager.finishPendingCreate(record.basePath)

        assertFalse(events.contains("gateCreate"))
        assertTrue(writes().isNotEmpty())
        assertTrue(writes().all { it.second.getBoolean("onlyIfMissing") })
        assertTrue(calls.filter { it.first == "createStream" }.all { it.second.getString("id").startsWith(record.basePath) })
        assertEquals("0xgate", channel.gateAddress)
        val grants = calls.first { it.first == "setPermissions" }.second.toString()
        assertTrue(grants.contains(record.publishKey!!.address))
        assertTrue(stored.isEmpty())
    }

    @Test
    fun `Finish allows only the members the gate does not allow yet`() = runBlocking {
        val record = interrupt(type = "gated", members = listOf("0xbob", "0xalice"))
        notAllowed = listOf("0xalice")

        h.manager.finishPendingCreate(record.basePath)

        val batch = calls.single { it.first == "gateAllowBatch" }.second
        assertEquals("[\"0xalice\"]", batch.getJSONArray("users").toString())
    }

    @Test
    fun `Finish refuses a creation nothing of which reached the chain, and keeps it`() = runBlocking {
        val record = interrupt()
        existing = false

        try {
            h.manager.finishPendingCreate(record.basePath)
            fail("finished a creation with nothing on chain")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("cannot be finished"))
        }
        assertEquals(1, stored.size)
    }

    @Test
    fun `lists a creation for Finish once the chain shows its -1`() = runBlocking {
        interrupt()
        existing = true

        h.manager.reconcilePendingCreates()

        assertEquals(listOf("Group"), h.manager.interruptedCreates.value.map { it.name })
    }

    @Test
    fun `Discard forgets the record`() {
        val record = interrupt()

        h.manager.discardPendingCreate(record.basePath)

        assertTrue(stored.isEmpty())
    }

    @Test
    fun `writes nothing into an account switched to while the gate deployed`() {
        onGateCreate = { h.address = "0xother" }

        try {
            create(type = "gated")
            fail("the creation went on in another account")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("account changed"))
        }

        assertFalse(events.contains("createStream"))
        assertEquals(null, stored.single().gateAddress)
    }
}
