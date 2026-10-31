package com.pombo.android.data

import android.content.Context
import com.pombo.android.core.EpochKeyManager
import com.pombo.android.core.SecurePrefs
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The record a channel creation leaves before its first transaction, per account. */
class PendingCreateStoreTest {

    private val prefs = FakePrefs()
    private val a = "0xaaaa000000000000000000000000000000000001"
    private val b = "0xbbbb000000000000000000000000000000000002"

    @Before fun setUp() {
        mockkObject(SecurePrefs)
        every { SecurePrefs.create(any(), any(), any()) } returns prefs
    }

    @After fun tearDown() = unmockkObject(SecurePrefs)

    private fun store(scope: String?, guest: Boolean = false) =
        PendingCreateStore(mockk<Context>(relaxed = true)).apply { scopeAddress = scope; memoryOnly = guest }

    private fun record(basePath: String, owner: String = a) = PendingCreate(
        basePath = basePath, owner = owner, name = "Group", type = "gated", password = null,
        exposure = "visible", readOnly = true, description = "d", language = "pt", category = "tech",
        members = listOf("0xbob"), storageProvider = "custom",
        customStorageAddress = "0xnode", storageDays = 30, gateMode = 3, gateToken = "0xtoken",
        gateMinBalance = null, gatePrice = "1000000000000000000", gateDuration = 2_592_000L,
        wireIdentity = "sealed",
        publishKey = EpochKeyManager.PubKey("p1.aaaaaa", "0x" + "11".repeat(32), "0x" + "22".repeat(20), 1),
        interactionsKey = EpochKeyManager.PubKey("i1.bbbbbb", "0x" + "33".repeat(32), "0x" + "44".repeat(20), 1),
        startBlock = 123_456_789L, gateAddress = null, startedAt = 42L
    )

    @Test
    fun `a record comes back whole, keys and nulls included`() {
        val written = record("$a/x")
        store(a).put(written)

        val read = store(a).load().single()

        assertEquals(written.copy(publishKey = null, interactionsKey = null), read.copy(publishKey = null, interactionsKey = null))
        assertEquals("0x" + "11".repeat(32), read.publishKey!!.keyHex)
        assertEquals("i1.bbbbbb", read.interactionsKey!!.keyId)
        assertNull(read.password)
        assertNull(read.gateMinBalance)
    }

    @Test
    fun `replaces by basePath and removes by it`() {
        val s = store(a)
        s.put(record("$a/x"))
        s.put(record("$a/y"))
        s.put(record("$a/x").copy(gateAddress = "0xgate"))

        assertEquals(listOf("$a/y" to null, "$a/x" to "0xgate"), s.load().map { it.basePath to it.gateAddress })
        s.remove("$a/y")
        assertEquals(listOf("$a/x"), s.load().map { it.basePath })
    }

    @Test
    fun `each account sees its own, and clearAccount erases only that one`() {
        store(a).put(record("$a/x"))
        store(b).put(record("$b/y", owner = b))

        assertEquals(listOf("$a/x"), store(a).load().map { it.basePath })
        store(a).clearAccount()
        assertTrue(store(a).load().isEmpty())
        assertEquals(listOf("$b/y"), store(b).load().map { it.basePath })
    }

    @Test
    fun `a guest keeps nothing`() {
        store(a, guest = true).put(record("$a/x"))

        assertTrue(prefs.values.isEmpty())
        assertTrue(store(a, guest = true).load().isEmpty())
    }
}
