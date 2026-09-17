package com.pombo.android

import com.pombo.android.core.TokenAmount
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.math.BigInteger

/**
 * The same vectors the web runs against `formatTokenAmount`: a balance shown
 * here and there must read identically, and neither client may round a balance
 * up or call dust zero.
 */
class TokenAmountTest {

    private fun n(value: String) = BigInteger(value)

    @Test
    fun `whole units carry no fractional noise`() {
        assertEquals("0", TokenAmount.format(BigInteger.ZERO, 18))
        assertEquals("1", TokenAmount.format(n("1000000000000000000"), 18))
        assertEquals("42", TokenAmount.format(n("42000000000000000000"), 18))
    }

    @Test
    fun `decimals come from the token, not from a fixed 18`() {
        assertEquals("3.2", TokenAmount.format(n("3200000"), 6))
        assertEquals("< 0.0001", TokenAmount.format(n("1"), 6))
        assertEquals("1", TokenAmount.format(n("100000000"), 8))
    }

    @Test
    fun `four decimals at most, trailing zeros dropped`() {
        assertEquals("1.697", TokenAmount.format(n("1697000000000000000"), 18))
        assertEquals("0.1697", TokenAmount.format(n("169712345678901234"), 18))
        assertEquals("0.1", TokenAmount.format(n("100000000000000000"), 18))
    }

    @Test
    fun `a balance is never rounded up`() {
        assertEquals("0.9999", TokenAmount.format(n("999990000000000000"), 18))
    }

    @Test
    fun `dust says it is dust instead of zero`() {
        assertEquals("< 0.0001", TokenAmount.format(n("1"), 18))
        assertEquals("< 0.0001", TokenAmount.format(n("99999999999999"), 18))
    }

    @Test
    fun `no amount without a value`() {
        assertNull(TokenAmount.format(null, 18))
        assertNull(TokenAmount.format(BigInteger.ONE, -1))
    }
}
