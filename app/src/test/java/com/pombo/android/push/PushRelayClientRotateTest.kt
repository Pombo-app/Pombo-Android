package com.pombo.android.push

import android.content.Context
import com.pombo.android.core.PushRegistry
import com.pombo.android.data.FakePrefs
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Deleting an account retires the install's delivery token: the relay rows
 * cannot be removed per account, so they die with the token.
 */
class PushRelayClientRotateTest {

    private val prefs = FakePrefs()
    private val context: Context = mockk<Context>(relaxed = true).also { ctx ->
        every { ctx.applicationContext } returns ctx
        every { ctx.getSharedPreferences(any(), any()) } returns prefs
    }
    private var deletions = 0
    private val client = PushRelayClient(
        context = context,
        bridge = mockk(relaxed = true),
        registry = PushRegistry(context),
        deleteFcmToken = { deletions++ }
    )

    @Test
    fun `a held token is deleted and the next connect re-registers`() = runBlocking {
        prefs.edit().putString("fcm_token", "token-x").apply()

        client.rotateToken()

        assertEquals(1, deletions)
        assertNull(prefs.getString("fcm_token", null))
        assertTrue(prefs.getBoolean("needs_reregister", false))
    }

    @Test
    fun `without a token there is nothing to retire`() = runBlocking {
        client.rotateToken()

        assertEquals(0, deletions)
        assertFalse(prefs.getBoolean("needs_reregister", false))
    }
}
