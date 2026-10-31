package com.pombo.android.data

import android.content.Context
import com.pombo.android.core.EpochKeyManager
import org.json.JSONArray
import org.json.JSONObject

/** A channel creation as stored before its first transaction: what finishing it needs. */
data class PendingCreate(
    /** `owner/random`: the stream ids are this plus -1 … -5. */
    val basePath: String,
    val owner: String,
    val name: String,
    val type: String,
    val password: String?,
    val exposure: String,
    val readOnly: Boolean,
    val description: String,
    val language: String,
    val category: String,
    val members: List<String>,
    val storageProvider: String,
    val customStorageAddress: String?,
    val storageDays: Int,
    val gateMode: Int,
    val gateToken: String?,
    val gateMinBalance: String?,
    val gatePrice: String?,
    val gateDuration: Long?,
    val wireIdentity: String,
    val publishKey: EpochKeyManager.PubKey?,
    val interactionsKey: EpochKeyManager.PubKey?,
    /** Read before the gate deploy is sent (gated only). */
    val startBlock: Long?,
    val gateAddress: String?,
    val startedAt: Long
) {
    fun toJson(): JSONObject = JSONObject()
        .put("basePath", basePath).put("owner", owner).put("name", name).put("type", type)
        .put("password", password ?: JSONObject.NULL)
        .put("exposure", exposure).put("readOnly", readOnly)
        .put("description", description).put("language", language).put("category", category)
        .put("members", JSONArray(members))
        .put("storageProvider", storageProvider)
        .put("customStorageAddress", customStorageAddress ?: JSONObject.NULL)
        .put("storageDays", storageDays)
        .put("gateMode", gateMode)
        .put("gateToken", gateToken ?: JSONObject.NULL)
        .put("gateMinBalance", gateMinBalance ?: JSONObject.NULL)
        .put("gatePrice", gatePrice ?: JSONObject.NULL)
        .put("gateDuration", gateDuration ?: JSONObject.NULL)
        .put("wireIdentity", wireIdentity)
        .put("publishKey", publishKey?.let(::keyJson) ?: JSONObject.NULL)
        .put("interactionsKey", interactionsKey?.let(::keyJson) ?: JSONObject.NULL)
        .put("startBlock", startBlock ?: JSONObject.NULL)
        .put("gateAddress", gateAddress ?: JSONObject.NULL)
        .put("startedAt", startedAt)

    companion object {
        private fun keyJson(key: EpochKeyManager.PubKey) = JSONObject()
            .put("keyId", key.keyId).put("keyHex", key.keyHex).put("address", key.address).put("rev", key.rev)

        private fun JSONObject.text(name: String): String? = if (isNull(name)) null else optString(name)

        private fun JSONObject.key(name: String): EpochKeyManager.PubKey? =
            optJSONObject(name)?.let {
                EpochKeyManager.PubKey(it.getString("keyId"), it.getString("keyHex"), it.getString("address"), it.getInt("rev"))
            }

        fun fromJson(o: JSONObject): PendingCreate = PendingCreate(
            basePath = o.getString("basePath"),
            owner = o.getString("owner"),
            name = o.getString("name"),
            type = o.getString("type"),
            password = o.text("password"),
            exposure = o.optString("exposure", "hidden"),
            readOnly = o.optBoolean("readOnly"),
            description = o.optString("description"),
            language = o.optString("language", "en"),
            category = o.optString("category", "general"),
            members = o.optJSONArray("members")?.let { arr -> (0 until arr.length()).map { arr.getString(it) } } ?: emptyList(),
            storageProvider = o.optString("storageProvider", "streamr"),
            customStorageAddress = o.text("customStorageAddress"),
            storageDays = o.optInt("storageDays", 180),
            gateMode = o.optInt("gateMode"),
            gateToken = o.text("gateToken"),
            gateMinBalance = o.text("gateMinBalance"),
            gatePrice = o.text("gatePrice"),
            gateDuration = if (o.isNull("gateDuration")) null else o.optLong("gateDuration"),
            wireIdentity = o.optString("wireIdentity", "sealed"),
            publishKey = o.key("publishKey"),
            interactionsKey = o.key("interactionsKey"),
            startBlock = if (o.isNull("startBlock")) null else o.optLong("startBlock"),
            gateAddress = o.text("gateAddress"),
            startedAt = o.optLong("startedAt")
        )
    }
}

/**
 * Channel creations started on this device and not finished, per account.
 *
 * Device-local on purpose: never in the sync or the backup, or two devices
 * would each pay for the transactions still missing.
 *
 * Encrypted at rest because it holds the channel password and the private
 * halves of the shared keys.
 */
class PendingCreateStore(context: Context) {

    private val prefs by lazy {
        com.pombo.android.core.SecurePrefs.create(context, "pombo_pending_creates")
    }

    @Volatile var scopeAddress: String? = null
    @Volatile var memoryOnly: Boolean = false

    private fun key(): String? = scopeAddress?.lowercase()?.ifEmpty { null }?.let { "pending_$it" }

    fun load(): List<PendingCreate> {
        if (memoryOnly) return emptyList()
        val raw = key()?.let { prefs.getString(it, null) } ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i -> runCatching { PendingCreate.fromJson(arr.getJSONObject(i)) }.getOrNull() }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Adds or replaces the record with the same basePath. */
    @Synchronized
    fun put(record: PendingCreate) {
        if (memoryOnly) return
        val key = key() ?: return
        val arr = JSONArray()
        (load().filterNot { it.basePath == record.basePath } + record).forEach { arr.put(it.toJson()) }
        // commit, not apply: the record must be on disk before the transaction it covers is sent.
        prefs.edit().putString(key, arr.toString()).commit()
    }

    @Synchronized
    fun remove(basePath: String) {
        if (memoryOnly) return
        val key = key() ?: return
        val kept = load().filterNot { it.basePath == basePath }
        val edit = prefs.edit()
        if (kept.isEmpty()) edit.remove(key)
        else edit.putString(key, JSONArray().apply { kept.forEach { put(it.toJson()) } }.toString())
        // apply, not commit: it then reaches disk after the channel and key saves applied before it.
        edit.apply()
    }

    fun clearAccount() {
        if (memoryOnly) return
        val key = key() ?: return
        prefs.edit().remove(key).apply()
    }
}
