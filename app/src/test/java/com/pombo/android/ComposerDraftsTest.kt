package com.pombo.android

import com.pombo.android.ui.ComposerDrafts
import com.pombo.android.ui.ComposerDrafts.Draft
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ComposerDraftsTest {
    private val alice = "0xAA"
    private val bob = "0xbb"
    private fun msg(id: String) = UiMessage(id = id, text = "t-$id", sender = "0x1", senderName = null, timestamp = 1L, mine = false)

    @Test
    fun `a conversation gets back its own draft and the others stay empty`() {
        val drafts = ComposerDrafts()
        drafts.stash(alice, "chan-1", Draft("half a thought"))
        assertNull(drafts.take(alice, "chan-2"))
        assertEquals(Draft("half a thought"), drafts.take(alice, "chan-1"))
    }

    @Test
    fun `a draft is handed out once`() {
        val drafts = ComposerDrafts()
        drafts.stash(alice, "chan-1", Draft("once"))
        drafts.take(alice, "chan-1")
        assertNull(drafts.take(alice, "chan-1"))
    }

    @Test
    fun `the reply and the edit travel with the text`() {
        val drafts = ComposerDrafts()
        drafts.stash(alice, "chan-1", Draft("answer", replyTo = msg("m1")))
        drafts.stash(alice, "chan-2", Draft("new", editing = msg("m2")))
        assertEquals(msg("m1"), drafts.take(alice, "chan-1")?.replyTo)
        assertEquals(msg("m2"), drafts.take(alice, "chan-2")?.editing)
    }

    @Test
    fun `a reply with no text yet is kept`() {
        val drafts = ComposerDrafts()
        drafts.stash(alice, "chan-1", Draft("", replyTo = msg("m1")))
        assertEquals(msg("m1"), drafts.take(alice, "chan-1")?.replyTo)
    }

    @Test
    fun `an empty composer stores nothing and clears an older draft`() {
        val drafts = ComposerDrafts()
        drafts.stash(alice, "chan-1", Draft("   "))
        assertNull(drafts.take(alice, "chan-1"))
        drafts.stash(alice, "chan-1", Draft("x"))
        drafts.stash(alice, "chan-1", Draft(""))
        assertNull(drafts.take(alice, "chan-1"))
    }

    @Test
    fun `a deleted target drops the bar and keeps the text`() {
        val drafts = ComposerDrafts()
        drafts.stash(alice, "chan-1", Draft("still mine", replyTo = msg("gone"), editing = msg("gone")))
        assertEquals(Draft("still mine"), drafts.take(alice, "chan-1") { it == "gone" })
    }

    @Test
    fun `accounts are kept apart, case-insensitively`() {
        val drafts = ComposerDrafts()
        drafts.stash(alice, "chan-1", Draft("alice"))
        assertNull(drafts.take(bob, "chan-1"))
        assertEquals("alice", drafts.take("0xaa", "chan-1")?.text)
    }

    @Test
    fun `forgetting an account leaves the others alone`() {
        val drafts = ComposerDrafts()
        drafts.stash(alice, "chan-1", Draft("a"))
        drafts.stash(alice, "chan-2", Draft("b"))
        drafts.stash(bob, "chan-1", Draft("c"))
        drafts.forgetAccount("0xaa")
        assertNull(drafts.take(alice, "chan-1"))
        assertNull(drafts.take(alice, "chan-2"))
        assertEquals("c", drafts.take(bob, "chan-1")?.text)
    }

    @Test
    fun `no account or conversation means no draft`() {
        val drafts = ComposerDrafts()
        drafts.stash(null, "chan-1", Draft("x"))
        drafts.stash(alice, null, Draft("x"))
        assertNull(drafts.take(null, "chan-1"))
        assertNull(drafts.take(alice, null))
    }
}
