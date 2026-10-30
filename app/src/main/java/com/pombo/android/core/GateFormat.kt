package com.pombo.android.core

/**
 * Paid-subscription display arithmetic (N-F), shared by the gate entry
 * dialog, the chat header chip, and the expiry strip. Pure functions —
 * parity with the web's SubscriptionBannerUI.formatRemaining.
 */
object GateFormat {

    /** Show the renew warning when less than this remains (§7.14). */
    const val WARNING_MS = 3L * 24 * 60 * 60 * 1000

    private val PERIOD_UNITS = listOf(
        Triple(86_400L, "day", "days"), Triple(3_600L, "hour", "hours"),
        Triple(60L, "minute", "minutes"), Triple(1L, "second", "seconds")
    )

    /**
     * A subscription period in the largest unit it reaches: "day", "30 days",
     * "10 minutes", "1.5 hours" (one decimal, in the device's number format).
     * Web gate.js formatGatePeriod.
     */
    fun period(seconds: Long): String {
        for ((size, one, many) in PERIOD_UNITS) {
            if (seconds < size) continue
            val n = Math.round(seconds.toDouble() / size * 10) / 10.0
            if (n == 1.0) return one
            val label = if (n == Math.floor(n)) n.toLong().toString()
                else String.format(java.util.Locale.getDefault(), "%.1f", n)
            return "$label $many"
        }
        return "0 seconds"
    }

    /** "12 days" / "1 day" / "5h" / "less than an hour" */
    fun formatRemaining(msLeft: Long): String {
        val days = msLeft / 86_400_000L
        if (days >= 1) return "$days " + if (days == 1L) "day" else "days"
        val hours = msLeft / 3_600_000L
        if (hours >= 1) return "${hours}h"
        return "less than an hour"
    }
}
