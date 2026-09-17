package com.pombo.android.core

import java.math.BigInteger

/**
 * Token amounts for display, matching the web's `formatTokenAmount`.
 *
 * Money on screen: the decimals come from the token's own contract, the value
 * is never rounded up, and dust reads as "< 0.0001" rather than as zero.
 */
object TokenAmount {

    fun format(raw: BigInteger?, decimals: Int): String? {
        if (raw == null || decimals < 0) return null
        if (raw.signum() == 0) return "0"

        val base = BigInteger.TEN.pow(decimals)
        val whole = raw.divide(base)
        val frac = raw.mod(base)

        val scale = if (decimals <= 4) decimals else 4
        val shown = if (decimals <= 4) frac else frac.divide(BigInteger.TEN.pow(decimals - 4))

        if (whole.signum() == 0) {
            if (shown.signum() == 0) return "< 0.0001"
            return "0." + shown.toString().padStart(scale, '0').trimEnd('0')
        }
        if (frac.signum() == 0 || shown.signum() == 0) return whole.toString()
        val padded = shown.toString().padStart(scale, '0').trimEnd('0')
        return if (padded.isEmpty()) whole.toString() else "$whole.$padded"
    }
}
