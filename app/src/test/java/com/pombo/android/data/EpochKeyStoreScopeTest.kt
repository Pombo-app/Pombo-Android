package com.pombo.android.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The scoping rules of the epoch key store, as pure key arithmetic (the class
 * itself needs a Context). Two accounts on one device must never resolve to
 * the same storage key, and an entry written before scoping existed must not
 * answer for anybody.
 */
class EpochKeyStoreScopeTest {

    private val stream = "0x7ea24eb97d400a76f8d96be92c4e7fce576aedb9/b9c47a9f0ef40637-1"
    private val alice = "0x03E2b4661E51e2Cf0D5Bc0D5b9D2A2d84eF7D819"
    private val bob = "0x7ea24Eb97D400A76f8d96Be92C4e7fce576AedB9"

    /** Mirror of EpochKeyStore.key. */
    private fun key(scope: String?, messageStreamId: String): String =
        if (scope.isNullOrEmpty()) messageStreamId
        else "${scope.lowercase()}_$messageStreamId"

    @Test
    fun `two accounts never share a key`() {
        assertNotEquals(key(alice, stream), key(bob, stream))
    }

    @Test
    fun `the scoped key is case insensitive on the address`() {
        assertEquals(key(alice.lowercase(), stream), key(alice.uppercase(), stream))
    }

    @Test
    fun `an unscoped entry is not what any scoped account reads`() {
        val legacy = key(null, stream)
        assertEquals(stream, legacy)
        assertNotEquals(legacy, key(alice, stream))
        assertNotEquals(legacy, key(bob, stream))
    }
}
