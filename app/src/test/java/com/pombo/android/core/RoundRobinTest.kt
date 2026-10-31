package com.pombo.android.core

import org.junit.Assert.assertEquals
import org.junit.Test

class RoundRobinTest {

    private val channels = listOf("a", "b", "c", "d", "e")

    @Test
    fun `takes three at a time and wraps around the list, as the web poller does`() {
        var index = 0
        val turns = List(3) {
            val (batch, next) = roundRobin(channels, index, 3)
            index = next
            batch
        }

        assertEquals(listOf(listOf("a", "b", "c"), listOf("d", "e", "a"), listOf("b", "c", "d")), turns)
    }

    @Test
    fun `a list shorter than the batch is read whole, each channel once`() {
        assertEquals(listOf("a", "b") to 0, roundRobin(listOf("a", "b"), 0, 3))
    }

    @Test
    fun `an index left past the end by a shrunken list still wraps`() {
        assertEquals(listOf("b", "a") to 1, roundRobin(listOf("a", "b"), 3, 3))
    }

    @Test
    fun `nothing to read keeps the turn where it was`() {
        assertEquals(emptyList<String>() to 4, roundRobin(emptyList<String>(), 4, 3))
    }
}
