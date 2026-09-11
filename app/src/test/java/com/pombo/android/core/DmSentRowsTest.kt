package com.pombo.android.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rows a session can still purge from a peer's inbox, and how they turn
 * into purge groups: one per (partition, key), chunks before the announce.
 */
class DmSentRowsTest {

    private val inbox = "0xpeer/Pombo-DM-1"
    private val announceKey = "0x" + "11".repeat(32)
    private val chunkKey = "0x" + "22".repeat(32)

    @Test
    fun `groups the rows of a message by partition and key, chunks first`() {
        val rows = DmSentRows()
        rows.remember("m1", DmSentRows.Row(inbox, 0, 500L, 0, announceKey))
        rows.remember("m1", DmSentRows.Row(inbox, 4, 100L, 0, chunkKey))
        rows.remember("m1", DmSentRows.Row(inbox, 4, 101L, 0, chunkKey))
        rows.remember("m1", DmSentRows.Row(inbox, 5, 102L, 0, chunkKey))
        rows.remember("m1", DmSentRows.Row("0xother/Pombo-DM-1", 0, 999L, 0, announceKey))

        val groups = rows.purgeGroups(inbox, "m1")
        assertEquals(listOf(4, 5, 0), groups.map { it.partition })
        assertEquals(listOf(chunkKey, chunkKey, announceKey), groups.map { it.privateKeyHex })
        assertEquals(listOf(StoragePurge.Target(100L, 0), StoragePurge.Target(101L, 0)), groups[0].targets)
        assertEquals(listOf(StoragePurge.Target(500L, 0)), groups[2].targets)
        assertTrue(rows.holds(inbox, "m1"))
        assertFalse(rows.holds(inbox, "m2"))
    }

    @Test
    fun `a republished announce keeps both rows, each under its own key`() {
        val rows = DmSentRows()
        rows.remember("m1", DmSentRows.Row(inbox, 0, 500L, 0, announceKey))
        rows.remember("m1", DmSentRows.Row(inbox, 0, 700L, 0, "0x" + "33".repeat(32)))
        val groups = rows.purgeGroups(inbox, "m1")
        assertEquals(2, groups.size)
        assertEquals(setOf(announceKey, "0x" + "33".repeat(32)), groups.map { it.privateKeyHex }.toSet())
    }

    @Test
    fun `forgets a message and evicts the oldest past the cap`() {
        val rows = DmSentRows(max = 2)
        rows.remember("m1", DmSentRows.Row(inbox, 0, 1L, 0, announceKey))
        rows.remember("m2", DmSentRows.Row(inbox, 0, 2L, 0, announceKey))
        rows.remember("m3", DmSentRows.Row(inbox, 0, 3L, 0, announceKey))
        assertTrue(rows.rowsOf("m1").isEmpty())
        assertEquals(1, rows.rowsOf("m2").size)
        rows.forget("m2")
        assertTrue(rows.rowsOf("m2").isEmpty())
        assertTrue(rows.purgeGroups(inbox, "m2").isEmpty())
    }
}
