package com.pombo.android

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source guard: AppViewModel cannot be instantiated in a JVM test.
 *
 * Every store is cleared while the scope still points at the deleted account,
 * and only then does disconnect() move the scope to the account that remains;
 * the other way round erases the remaining account's data. A guest session
 * keeps the last real account stored as current, so neither deleting nor
 * exporting the key may run there.
 */
class DeleteAccountWiringTest {

    private val vm = File("src/main/java/com/pombo/android/AppViewModel.kt").readText()
    private val settings = File("src/main/java/com/pombo/android/ui/screens/SettingsScreen.kt").readText()

    private fun body(source: String, signature: String): String {
        val start = source.indexOf(signature)
        assertTrue("$signature is gone", start >= 0)
        val end = source.indexOf("\n    fun ", start + 1).let { if (it < 0) source.length else it }
        return source.substring(start, end)
    }

    @Test
    fun `every store is cleared before disconnect`() {
        val body = body(vm, "fun deleteAccount()")
        val disconnect = body.lastIndexOf("disconnect()")
        listOf(
            "channelStore", "contactsStore", "inviteStore", "sentDmStore", "sentReactionsStore",
            "failedOutbox", "epochKeyStore", "unreadStore", "settingsStore", "syncStore",
            "pushRegistry", "walletTokenStore", "blobStore"
        ).forEach { store ->
            val clear = body.indexOf("$store.clearAccount(")
            assertTrue("deleteAccount no longer clears $store", clear >= 0)
            assertTrue("$store must be cleared before disconnect()", clear < disconnect)
        }
    }

    @Test
    fun `disconnect moves the scope only after the account is dropped, before reconnecting`() {
        val body = body(vm, "fun disconnect()")
        val drop = body.indexOf("store.clear()")
        val scope = body.indexOf("applyStorageScope(store.address, guest = false)")
        val reconnect = body.indexOf("bridge.reconnect()")
        assertTrue("disconnect no longer re-scopes the stores", scope >= 0)
        assertTrue("the scope must move after store.clear()", drop in 0 until scope)
        assertTrue("the scope must move before reconnecting", scope < reconnect)
    }

    @Test
    fun `a guest can neither delete nor export the stored account`() {
        val delete = body(vm, "fun deleteAccount()")
        val guard = delete.indexOf("if (_isGuest.value) return")
        assertTrue("deleteAccount runs in a guest session", guard in 0 until delete.indexOf("viewModelScope.launch"))
        assertTrue(
            "exportPrivateKey hands a guest the stored account's key",
            Regex("""fun exportPrivateKey\(\): String\? = if \(_isGuest\.value\) null""").containsMatchIn(vm)
        )
        assertTrue("Unlock Key is enabled for a guest", settings.contains("""DangerButton("Unlock Key", enabled = !noDeviceLock && !isGuest)"""))
        assertTrue("Delete's Verify is enabled for a guest", settings.contains("""DangerButton("Verify", enabled = !noDeviceLock && !isGuest)"""))
    }
}
