package com.pombo.android.core.channels

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RotationRetryTest {

    private val channel = "0xowner/room-1"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    /** Rotations fail while this is above zero, one per attempt. */
    private var failures = 0
    private var rotations = 0
    private var owned = true
    private var account = "0xOwner"
    private val covered = mutableListOf<Set<String>>()
    private val waits = mutableListOf<Long>()
    /** What this device keeps between sessions. */
    private val store = mutableMapOf<String, List<String>>()
    /** When set, the retry parks in its sleep until it completes. */
    private var clock: CompletableDeferred<Unit>? = null

    private fun newRetry() = RotationRetry(scope, object : RotationRetry.Host {
        override fun account() = account
        override suspend fun rotate(messageStreamId: String) {
            if (failures > 0) { failures--; throw IllegalStateException("no connections") }
            rotations++
        }
        override suspend fun covered(messageStreamId: String, addresses: Set<String>) {
            covered += addresses
        }
        override fun stillOwned(messageStreamId: String) = owned
        override fun loadOwed(key: String) = store[key].orEmpty()
        override fun saveOwed(key: String, addresses: List<String>) {
            if (addresses.isEmpty()) store.remove(key) else store[key] = addresses
        }
        override suspend fun sleep(ms: Long) {
            waits += ms
            clock?.await()
        }
    })

    private val retry = newRetry()

    @After fun tearDown() = scope.cancel()

    @Test fun `a rotation that goes out now covers the address and owes nothing`() = runBlocking {
        assertTrue(retry.rotateFor(channel, listOf("0xAbC")))

        assertEquals(1, rotations)
        assertEquals(listOf(setOf("0xabc")), covered)
        assertFalse(retry.isOwed(channel))
        assertTrue(store.isEmpty())
        assertTrue(waits.isEmpty())
    }

    @Test fun `a failed rotation stays owed and is retried on a backoff until it goes out`() = runBlocking {
        failures = 3

        assertFalse(retry.rotateFor(channel, listOf("0xabc")))

        assertEquals(listOf(5_000L, 10_000L, 20_000L), waits)
        assertEquals(1, rotations)
        assertEquals(listOf(setOf("0xabc")), covered)
        assertFalse(retry.isOwed(channel))
    }

    @Test fun `the backoff stays at its last step`() = runBlocking {
        failures = 7

        retry.rotateFor(channel, listOf("0xabc"))

        assertEquals(listOf(5_000L, 10_000L, 20_000L, 40_000L, 60_000L, 60_000L, 60_000L), waits)
        assertEquals(1, rotations)
    }

    @Test fun `a send is refused while the rotation cannot go out, and rotates first once it can`() = runBlocking {
        failures = Int.MAX_VALUE
        clock = CompletableDeferred()
        assertFalse(retry.rotateFor(channel, listOf("0xabc")))

        val refused = runCatching { retry.settle(channel) }.exceptionOrNull()
        assertEquals(RotationRetry.OWED_MESSAGE, refused?.message)
        assertEquals(0, rotations)

        failures = 0
        retry.settle(channel)

        assertEquals(1, rotations)
        assertEquals(listOf(setOf("0xabc")), covered)
        assertFalse(retry.isOwed(channel))
    }

    @Test fun `nothing owed means a send does not rotate`() = runBlocking {
        retry.settle(channel)

        assertEquals(0, rotations)
    }

    @Test fun `a channel this account no longer owns stops the retry`() = runBlocking {
        failures = Int.MAX_VALUE
        owned = false

        assertFalse(retry.rotateFor(channel, listOf("0xabc")))

        assertEquals(listOf(5_000L), waits)
        assertFalse(retry.isOwed(channel))
        assertTrue(covered.isEmpty())
    }

    @Test fun `one rotation covers every cut owed on the channel`() = runBlocking {
        failures = Int.MAX_VALUE
        clock = CompletableDeferred()
        retry.rotateFor(channel, listOf("0xabc"))

        failures = 0
        assertTrue(retry.rotateFor(channel, listOf("0xdef")))

        assertEquals(1, rotations)
        assertEquals(listOf(setOf("0xabc", "0xdef")), covered)
        assertFalse(retry.isOwed(channel))
    }

    @Test fun `the parked retry finds nothing left once a send settled it`() = runBlocking {
        failures = Int.MAX_VALUE
        clock = CompletableDeferred()
        retry.rotateFor(channel, listOf("0xabc"))
        failures = 0
        retry.settle(channel)

        clock!!.complete(Unit)

        assertEquals(1, rotations)
        assertEquals(1, covered.size)
    }

    @Test fun `what one session owed the next one owes, and takes up on connect`() = runBlocking {
        failures = Int.MAX_VALUE
        clock = CompletableDeferred()
        retry.rotateFor(channel, listOf("0xabc"))

        failures = 0
        val nextSession = newRetry()
        assertTrue(nextSession.isOwed(channel))

        nextSession.resume(listOf(channel, "0xowner/other"))

        assertEquals(1, rotations)
        assertEquals(listOf(setOf("0xabc")), covered)
        assertFalse(nextSession.isOwed(channel))
    }

    @Test fun `another account on the device owes nothing for this one's cuts`() = runBlocking {
        failures = Int.MAX_VALUE
        clock = CompletableDeferred()
        retry.rotateFor(channel, listOf("0xabc"))

        account = "0xSomeoneElse"

        assertFalse(retry.isOwed(channel))
        retry.settle(channel)
        assertEquals(0, rotations)
    }
}
