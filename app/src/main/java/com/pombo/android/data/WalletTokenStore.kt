package com.pombo.android.data

import android.content.Context
import org.json.JSONArray

/**
 * Extra ERC-20 addresses the wallet panel lists, per account.
 *
 * Addresses only: balances are read from the chain every time the panel opens,
 * so nothing here is a number anyone could act on. Local by decision (web
 * parity), which is why it lives in plain prefs and not in the device sync.
 */
class WalletTokenStore(context: Context) {

    private val prefs = context.getSharedPreferences("pombo_wallet_tokens", Context.MODE_PRIVATE)

    private fun key(address: String) = "tokens_${address.lowercase()}"

    fun list(address: String?): List<String> {
        if (address.isNullOrEmpty()) return emptyList()
        val raw = prefs.getString(key(address), null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { array.optString(it).takeIf { s -> isAddress(s) } }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun add(address: String?, token: String): Boolean {
        if (address.isNullOrEmpty() || !isAddress(token)) return false
        val current = list(address)
        if (current.any { it.equals(token, ignoreCase = true) }) return false
        save(address, current + token)
        return true
    }

    fun remove(address: String?, token: String) {
        if (address.isNullOrEmpty()) return
        save(address, list(address).filterNot { it.equals(token, ignoreCase = true) })
    }

    private fun save(address: String, tokens: List<String>) {
        prefs.edit().putString(key(address), JSONArray(tokens).toString()).apply()
    }

    companion object {
        private val ADDRESS = Regex("^0x[0-9a-fA-F]{40}$")
        fun isAddress(value: String?) = value != null && ADDRESS.matches(value)
    }
}
