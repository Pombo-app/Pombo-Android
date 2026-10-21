package com.pombo.android.core.channels

import com.pombo.android.core.channels.PendingCreates.State
import com.pombo.android.data.PendingCreate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the chain says is left of an interrupted channel creation, and what that does to the record. */
class PendingCreatesTest {

    private val now = 10_000_000L
    private var stored = listOf<PendingCreate>()
    private var streamReads = 0
    private var gateReads = 0
    private var streamExists: suspend (String) -> Boolean = { false }
    private var findGate: suspend (PendingCreate) -> String? = { null }

    private val pending = PendingCreates(
        records = { stored },
        put = { record -> stored = stored.filterNot { it.basePath == record.basePath } + record },
        remove = { basePath -> stored = stored.filterNot { it.basePath == basePath } },
        streamExists = { streamReads++; streamExists(it) },
        findGate = { gateReads++; findGate(it) },
        now = { now }
    )

    private fun record(
        type: String = "public", startedAt: Long = now - 1_000, gateAddress: String? = null
    ) = PendingCreate(
        basePath = "0xowner/abcd", owner = "0xowner", name = "Test", type = type, password = null,
        exposure = "hidden", readOnly = false, description = "", language = "en", category = "general",
        members = emptyList(), classification = null, storageProvider = "streamr", customStorageAddress = null,
        storageDays = 180, gateMode = 0, gateToken = null, gateMinBalance = null, gatePrice = null,
        gateDuration = null, wireIdentity = "sealed", publishKey = null, interactionsKey = null,
        startBlock = 100, gateAddress = gateAddress, startedAt = startedAt
    ).also { pending.update(it) }

    @Test
    fun `a -1 on chain makes a creation resumable and lists it`() = runBlocking {
        streamExists = { it == "0xowner/abcd-1" }
        val r = record()

        assertEquals(State.RESUMABLE, pending.reconcile(r))
        assertEquals(listOf("0xowner/abcd"), pending.resumable.value.map { it.basePath })
    }

    @Test
    fun `nothing on chain within the hour is kept and stays out of Finish`() = runBlocking {
        val r = record()

        assertEquals(State.WAITING, pending.reconcile(r))
        assertEquals(1, stored.size)
        assertTrue(pending.resumable.value.isEmpty())
    }

    @Test
    fun `nothing on chain after the hour drops the record`() = runBlocking {
        val r = record(startedAt = now - PendingCreates.MAX_AGE_MS)

        assertEquals(State.DISCARDED, pending.reconcile(r))
        assertTrue(stored.isEmpty())
    }

    @Test
    fun `an unreadable chain keeps even an old record`() = runBlocking {
        streamExists = { throw IllegalStateException("RPC down") }
        val r = record(startedAt = 0)

        assertEquals(State.UNREADABLE, pending.reconcile(r))
        assertEquals(1, stored.size)
    }

    @Test
    fun `a gated creation whose gate is recorded is resumable without reading the chain`() = runBlocking {
        val r = record(type = "gated", gateAddress = "0xgate")

        assertEquals(State.RESUMABLE, pending.reconcile(r))
        assertEquals(0, gateReads)
        assertEquals(0, streamReads)
    }

    @Test
    fun `a gate the record never saw is found on chain and written into it`() = runBlocking {
        findGate = { "0xfound" }
        val r = record(type = "gated")

        assertEquals(State.RESUMABLE, pending.reconcile(r))
        assertEquals("0xfound", stored.single().gateAddress)
    }

    @Test
    fun `a gated creation with no gate after the hour is dropped`() = runBlocking {
        val r = record(type = "gated", startedAt = now - PendingCreates.MAX_AGE_MS - 1)

        assertEquals(State.DISCARDED, pending.reconcile(r))
        assertTrue(stored.isEmpty())
    }

    @Test
    fun `leaves alone a creation this session is running`() = runBlocking {
        val r = record(startedAt = 0)
        pending.begin(r)

        assertEquals(State.RUNNING, pending.reconcile(r))
        assertEquals(0, streamReads)
        assertEquals(1, stored.size)
    }

    @Test
    fun `does not write back a gate for a record finished meanwhile`() = runBlocking {
        findGate = { pending.complete("0xowner/abcd"); "0xfound" }
        val r = record(type = "gated")

        pending.reconcile(r)

        assertTrue(stored.isEmpty())
    }

    @Test
    fun `claims a creation once, until released`() {
        assertTrue(pending.claim("0xowner/abcd"))
        assertFalse(pending.claim("0xowner/abcd"))
        pending.release("0xowner/abcd")
        assertTrue(pending.claim("0xowner/abcd"))
    }

    @Test(expected = IllegalStateException::class)
    fun `refuses to discard a creation still running`() {
        pending.begin(record())
        pending.discard("0xowner/abcd")
    }

    @Test
    fun `complete drops the record, the listing and the claim`() = runBlocking {
        streamExists = { true }
        val r = record()
        pending.reconcile(r)
        pending.claim(r.basePath)

        pending.complete(r.basePath)

        assertNull(pending.get(r.basePath))
        assertTrue(pending.resumable.value.isEmpty())
        assertTrue(pending.claim(r.basePath))
    }
}
