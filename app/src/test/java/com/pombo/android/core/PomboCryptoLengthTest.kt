package com.pombo.android.core

import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * The size of a password-sealed payload is computed, not measured, so that
 * sizing does not pay a PBKDF2 per chunk. What pins the arithmetic is that it
 * matches what encryptString actually returns.
 */
class PomboCryptoLengthTest {

    @Before fun setUp() {
        // android.util.Base64 is a stub on the JVM; the real codec stands in.
        mockkStatic(android.util.Base64::class)
        every { android.util.Base64.encodeToString(any<ByteArray>(), any()) } answers {
            java.util.Base64.getEncoder().encodeToString(firstArg<ByteArray>())
        }
    }

    @After fun tearDown() = unmockkStatic(android.util.Base64::class)

    @Test
    fun `is the length encryptString returns`() {
        for (text in listOf("", "a", "ab", "abc", "olá 🐦 \"x\"", "y".repeat(4097))) {
            val out = PomboCrypto.encryptString(text, "pw")
            assertEquals(text, out.length, PomboCrypto.encryptedLength(text.toByteArray(Charsets.UTF_8).size))
        }
    }
}
