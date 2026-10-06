package com.pombo.android.data

import android.content.Context
import com.pombo.android.core.PushRegistry
import com.pombo.android.core.SecurePrefs
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Deleting an account erases what every store keeps for it and nothing else,
 * least of all another account's epoch key for a channel the deleted one owns.
 */
class AccountClearTest {

    private val files = HashMap<String, FakePrefs>()
    private fun file(name: String) = files.getOrPut(name) { FakePrefs() }

    private val context: Context = mockk<Context>(relaxed = true).also { ctx ->
        every { ctx.applicationContext } returns ctx
        every { ctx.getSharedPreferences(any(), any()) } answers { file(firstArg()) }
    }

    private val aMixed = "0xAaAa000000000000000000000000000000000001"
    private val a = aMixed.lowercase()
    private val b = "0xbbbb000000000000000000000000000000000002"
    private val channelOfA = "$a/0123456789abcdef-1"
    private val channelOfB = "$b/fedcba9876543210-1"

    @Before fun setUp() {
        mockkObject(SecurePrefs)
        every { SecurePrefs.create(any(), any(), any()) } answers { file(secondArg()) }
    }

    @After fun tearDown() = unmockkObject(SecurePrefs)

    private inner class Stores(private val scope: String?, guest: Boolean = false) {
        val sentDm = SentDmStore(context).apply { scopeAddress = scope; memoryOnly = guest }
        val failed = FailedOutboxStore(context).apply { scopeAddress = scope; memoryOnly = guest }
        val epoch = EpochKeyStore(context).apply { scopeAddress = scope; memoryOnly = guest }
        val reactions = SentReactionsStore(context).apply { scopeAddress = scope; memoryOnly = guest }
        val channels = ChannelStore(context).apply { scopeAddress = scope; memoryOnly = guest }
        val contacts = ContactsStore(context).apply { scopeAddress = scope; memoryOnly = guest }
        val unread = UnreadStore(context).apply { scopeAddress = scope }
        val invites = InviteStore(context).apply { scopeAddress = scope }
        val sync = SyncStore(context).apply { scopeAddress = scope }
        val settings = SettingsStore(context).apply { scopeAddress = scope }
        val push = PushRegistry(context).apply { scopeAddress = scope }
        val tokens = WalletTokenStore(context)

        fun fill(own: String, peer: String) {
            sentDm.add(channelOfB, JSONObject().put("id", "m-$own").put("timestamp", 1L).put("text", "hi"))
            sentDm.delete(channelOfB, "gone-$own")
            failed.put(channelOfB, JSONObject().put("id", "f-$own").put("timestamp", 2L).put("text", "retry"))
            epoch.save(channelOfA, JSONObject().put("currentEpoch", 1))
            epoch.save(channelOfB, JSONObject().put("currentEpoch", 2))
            reactions.record(channelOfB, "msg", "fire", own, add = true)
            channels.save(emptyList())
            channels.markLeft(channelOfB)
            channels.saveOrder(listOf(channelOfB))
            contacts.save(emptyList())
            unread.add(channelOfB, 2)
            unread.setWatermark(channelOfB, 5L)
            val invite = InviteStore.StoredInvite("i-$own", peer, channelOfB, "room", "public", null)
            invites.save(listOf(invite))
            invites.markDismissed("d-$own")
            invites.recordDismissed(invite)
            sync.dirty = true
            sync.lastSyncTs = 3L
            sync.confirmedHash = "hash-$own"
            sync.confirmedAt = 4L
            sync.recordApplied(listOf(5L))
            settings.graphApiKey = "graph-$own"
            settings.syncBase = "{}"
            settings.blockedPeers = setOf(peer)
            settings.nsfwEnabled = true
            settings.syncMode = SyncMode.MANUAL_ONLY
            push.add(PushRegistry.Entry(channelOfB, "ab", "public", "room", 0L))
            push.rememberProviders(channelOfB, listOf("https://1.storage.example"))
            tokens.add(own, "0x" + "1".repeat(40))
        }

        fun clearAll() {
            sentDm.clearAccount()
            failed.clearAccount()
            epoch.clearAccount()
            reactions.clearAccount()
            channels.clearAccount()
            contacts.clearAccount()
            unread.clearAccount()
            invites.clearAccount()
            sync.clearAccount()
            settings.clearAccount()
            push.clearAccount()
            tokens.clearAccount(scope)
        }
    }

    private fun ownedBy(address: String, key: String) =
        key.endsWith("_$address") || key.startsWith("${address}_") || key.contains("_${address}_")

    private fun snapshot(): Map<String, Map<String, Any>> = files.mapValues { LinkedHashMap(it.value.values) }

    private fun fillBoth() {
        Stores(aMixed).fill(a, b)
        Stores(b).fill(b, a)
        file("pombo_settings").values["rpc_selection"] = "device-wide"
    }

    @Test
    fun `deleting an account erases its keys in every store and leaves the other account's`() {
        fillBoth()
        val before = snapshot()
        files.forEach { (name, prefs) ->
            assertTrue("nothing of the account was written to $name", prefs.values.keys.any { ownedBy(a, it) })
        }

        Stores(aMixed).clearAll()

        val after = snapshot()
        before.forEach { (name, keys) ->
            assertEquals(name, keys.filterKeys { !ownedBy(a, it) }, after[name])
        }
        assertEquals(1, Stores(b).epoch.load(channelOfA)?.optInt("currentEpoch"))
        assertEquals("device-wide", file("pombo_settings").values["rpc_selection"])
    }

    @Test
    fun `without an account in scope nothing is erased`() {
        fillBoth()
        val before = snapshot()

        Stores(null).clearAll()
        Stores(null, guest = true).clearAll()

        assertEquals(before, snapshot())
    }

    @Test
    fun `the deleted account's unread badges leave memory too`() {
        val unread = Stores(aMixed).unread
        unread.add(channelOfB, 3)

        unread.clearAccount()

        assertTrue(unread.counts.value.isEmpty())
    }
}
