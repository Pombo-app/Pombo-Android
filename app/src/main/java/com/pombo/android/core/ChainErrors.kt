package com.pombo.android.core

/**
 * Chain error classifier — port of the web's utils/chainErrors.js.
 *
 * Ethers surfaces on-chain failures as long technical strings; showing them
 * raw ("could not coalesce error …") tells the user nothing. Regex patterns
 * rather than exact matches, for resilience against library updates — same
 * choice the web made.
 */
object ChainErrors {

    private class Kind(val transient: Boolean, val message: String, vararg patterns: String) {
        val regexes = patterns.map { Regex(it, RegexOption.IGNORE_CASE) }
        fun matches(text: String) = regexes.any { it.containsMatchIn(text) }
    }

    private val KINDS = listOf(
        Kind(
            false,
            "Not enough POL for the network fee",
            "INSUFFICIENT_FUNDS", "insufficient\\s*funds", "not\\s*enough\\s*balance",
            "balance\\s*too\\s*low", "sender\\s*doesn.*have\\s*enough\\s*funds"
        ),
        Kind(
            false,
            "The transaction was rejected on chain",
            "CALL_EXCEPTION", "execution\\s*reverted", "transaction\\s*reverted", "revert"
        ),
        // NONCE before NETWORK: "nonce too low" must not fall through.
        Kind(
            true,
            "Transaction conflict, try again",
            "nonce\\s*too\\s*(low|high)", "NONCE_EXPIRED", "REPLACEMENT_UNDERPRICED", "already\\s*known"
        ),
        Kind(
            true,
            "Network error, try again",
            "NETWORK_ERROR", "SERVER_ERROR", "network\\s*error", "timeout", "timed?\\s*out",
            "ECONNREFUSED", "ETIMEDOUT", "fetch\\s*failed", "connection\\s*refused",
            // What the WebView itself says when a request cannot leave.
            "failed\\s*to\\s*fetch", "load\\s*failed",
            // Bridge-specific transient states the ethers list cannot know.
            "client\\s*not\\s*connected", "bridge\\s*reloaded", "Could not reach"
        ),
        Kind(
            false,
            "The transaction ran out of gas",
            "gas\\s*limit", "out\\s*of\\s*gas", "intrinsic\\s*gas\\s*too\\s*low"
        )
    )

    /** User-facing message; falls back to the raw one when nothing matches. */
    fun friendly(e: Throwable): String {
        val raw = e.message ?: return "Unexpected error"
        return KINDS.firstOrNull { it.matches(raw) }?.message ?: raw
    }

    /**
     * Whether retrying can possibly change the outcome (web utils/retry.js
     * `shouldRetry`: NETWORK and NONCE only). A deterministic revert or an
     * empty wallet fails identically every time — retrying just burns time.
     * Unmatched errors are NOT transient: fail fast and show the message.
     */
    fun isTransient(e: Throwable): Boolean {
        val raw = e.message ?: return false
        return KINDS.firstOrNull { it.matches(raw) }?.transient ?: false
    }
}
