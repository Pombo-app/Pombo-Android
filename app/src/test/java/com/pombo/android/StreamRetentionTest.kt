package com.pombo.android

import com.pombo.android.data.Channel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Retention is a property of each stream, not of the channel: -1, -3 and -4
 * are configured by separate transactions and any of them can fail alone.
 * Deciding about one of them with another's value is guessing.
 *
 * Mirrors the web's streamRetention.test.js so the two clients cannot drift.
 */
class StreamRetentionTest {

    private fun channel(
        storageDays: Int? = null,
        adminStorageDays: Int? = null,
        keysStorageDays: Int? = null,
        type: String = "gated"
    ) = Channel(
        messageStreamId = "0xowner/test-1",
        ephemeralStreamId = "0xowner/test-2",
        adminStreamId = "0xowner/test-3",
        keysStreamId = "0xowner/test-4",
        name = "T",
        type = type,
        storageDays = storageDays,
        adminStorageDays = adminStorageDays,
        keysStorageDays = keysStorageDays
    )

    // ===== pickRetention =====

    @Test
    fun `pickRetention takes the first usable candidate in order`() {
        assertEquals(7, ChannelManager.pickRetention(null, 7, 30))
    }

    @Test
    fun `pickRetention skips zero and negative candidates`() {
        assertEquals(5, ChannelManager.pickRetention(0, -1, 5))
    }

    @Test
    fun `pickRetention falls back to the default`() {
        assertEquals(
            ChannelManager.DEFAULT_RETENTION_DAYS,
            ChannelManager.pickRetention(null, null)
        )
    }

    // ===== adminRetentionDays: the -3 artifacts =====

    @Test
    fun `admin retention prefers the admin stream over the message one`() {
        assertEquals(3, ChannelManager.adminRetentionDays(
            channel(storageDays = 180, adminStorageDays = 3)))
    }

    @Test
    fun `admin retention falls back to the message stream when unresolved`() {
        assertEquals(30, ChannelManager.adminRetentionDays(channel(storageDays = 30)))
    }

    @Test
    fun `admin retention falls back to the default when nothing is known`() {
        assertEquals(
            ChannelManager.DEFAULT_RETENTION_DAYS,
            ChannelManager.adminRetentionDays(channel())
        )
    }

    // ===== keysRetentionDays: the -4 announces =====

    @Test
    fun `keys retention prefers the keys stream over every other`() {
        assertEquals(3, ChannelManager.keysRetentionDays(
            channel(storageDays = 180, adminStorageDays = 30, keysStorageDays = 3)))
    }

    @Test
    fun `keys retention falls back to the admin stream before the message one`() {
        assertEquals(30, ChannelManager.keysRetentionDays(
            channel(storageDays = 180, adminStorageDays = 30)))
    }

    @Test
    fun `keys retention falls back to the message stream when nothing else is known`() {
        assertEquals(90, ChannelManager.keysRetentionDays(channel(storageDays = 90)))
    }

    @Test
    fun `keys retention falls back to the default on an empty record`() {
        assertEquals(
            ChannelManager.DEFAULT_RETENTION_DAYS,
            ChannelManager.keysRetentionDays(channel())
        )
    }

    // ===== retentionInSync: what the panel warns about =====

    @Test
    fun `streams holding the same retention agree`() {
        assertTrue(ChannelManager.retentionInSync(180, 180, 180))
    }

    @Test
    fun `one stream holding a different retention disagrees`() {
        assertFalse(ChannelManager.retentionInSync(180, 180, 3))
        assertFalse(ChannelManager.retentionInSync(3, 180, 180))
    }

    /**
     * Unknown values are streams the channel does not have, or lookups that
     * failed. Counting them as a mismatch would badge every channel with no
     * keys stream.
     */
    @Test
    fun `unknown values are skipped rather than counted as a mismatch`() {
        assertTrue(ChannelManager.retentionInSync(180, 180, null))
        assertTrue(ChannelManager.retentionInSync(180, null, null))
        assertTrue(ChannelManager.retentionInSync(180, 0, -1))
    }

    @Test
    fun `nothing known at all agrees`() {
        assertTrue(ChannelManager.retentionInSync(null, null, null))
        assertTrue(ChannelManager.retentionInSync())
    }
}
