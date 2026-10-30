package com.pombo.android.core

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The Blocked Users erase: only the blocked senders' rows go, messages last,
 * and the toast reads as the web's describeBlockedErase.
 */
class BlockedEraseTest {

    private val blocked = "0xb10cked1111111111111111111111111111111"
    private val other = "0xpeer22222222222222222222222222222222222"

    private fun outcome(providers: Int, erasedOn: Int) =
        StoragePurge.Outcome(providers, erasedOn, 0, 0, emptyList())

    @Test
    fun `keeps only the blocked senders' rows, whatever the case of the address`() {
        val rows = listOf(
            BlockedErase.Row(0, blocked.uppercase().replace("0X", "0x"), 200, 0),
            BlockedErase.Row(0, other, 201, 0),
            BlockedErase.Row(3, blocked, 100, 0)
        )
        assertEquals(listOf(200L, 100L), BlockedErase.theirs(rows, setOf(blocked)).map { it.timestamp })
    }

    @Test
    fun `groups their rows per partition with messages last`() {
        val theirs = listOf(
            BlockedErase.Row(0, blocked, 200, 0),
            BlockedErase.Row(3, blocked, 100, 0),
            BlockedErase.Row(0, blocked, 201, 1)
        )
        assertEquals(
            listOf(
                StoragePurge.Group(3, listOf(StoragePurge.Target(100, 0))),
                StoragePurge.Group(0, listOf(StoragePurge.Target(200, 0), StoragePurge.Target(201, 1)))
            ),
            BlockedErase.groups(theirs)
        )
    }

    @Test
    fun `describes the outcome as the web does`() {
        assertEquals("Nothing from blocked users in your inbox storage" to BlockedErase.Tone.INFO, BlockedErase.describe(null))
        assertEquals(
            "Erased 3 messages and 1 file on 2 of 2 storage providers" to BlockedErase.Tone.SUCCESS,
            BlockedErase.describe(BlockedErase.Result(outcome(2, 2), 3, 1, 0))
        )
        assertEquals(
            "Erased 1 message on 1 of 2 storage providers" to BlockedErase.Tone.WARNING,
            BlockedErase.describe(BlockedErase.Result(outcome(2, 1), 1, 0, 0))
        )
        assertEquals(
            "Erased 3 messages on 2 of 2 storage providers; 2 files could not be checked" to BlockedErase.Tone.WARNING,
            BlockedErase.describe(BlockedErase.Result(outcome(2, 2), 3, 0, 2))
        )
        assertEquals(
            "Erased 2 files on 2 of 2 storage providers" to BlockedErase.Tone.SUCCESS,
            BlockedErase.describe(BlockedErase.Result(outcome(2, 2), 0, 2, 0))
        )
        assertEquals(
            "No storage provider of your inbox can erase 3 messages" to BlockedErase.Tone.WARNING,
            BlockedErase.describe(BlockedErase.Result(outcome(0, 0), 3, 0, 0))
        )
        assertEquals("Erasing 1 message and 2 files", BlockedErase.erasing(1, 2))
        assertEquals("Erasing 2 files", BlockedErase.erasing(0, 2))
    }
}
