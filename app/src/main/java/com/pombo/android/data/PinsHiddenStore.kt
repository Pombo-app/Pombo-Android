package com.pombo.android.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/**
 * Channels whose pinned banner the reader closed (web `pombo_pins_hidden_<addr>`):
 * stream id → target id of the newest pin at that moment. The banner stays
 * closed until a newer pin arrives. Per account; guests keep it in memory.
 */
class PinsHiddenStore(context: Context) {

    private val prefs = context.getSharedPreferences("pombo_pins_hidden", Context.MODE_PRIVATE)

    @Volatile
    var memoryOnly: Boolean = false

    @Volatile
    var scopeAddress: String? = null
        set(value) {
            field = value
            _hidden.value = load()
        }

    private val _hidden = MutableStateFlow<Map<String, String>>(emptyMap())
    val hidden: StateFlow<Map<String, String>> = _hidden.asStateFlow()

    private fun key() = scopeAddress?.lowercase()?.ifEmpty { null }

    private fun load(): Map<String, String> {
        val k = key() ?: return emptyMap()
        if (memoryOnly) return emptyMap()
        return try {
            val o = JSONObject(prefs.getString(k, null) ?: "{}")
            o.keys().asSequence().associateWith { o.optString(it) }
        } catch (e: Exception) {
            emptyMap()
        }
    }

    @Synchronized
    private fun write(next: Map<String, String>) {
        _hidden.value = next
        val k = key() ?: return
        if (memoryOnly) return
        prefs.edit().putString(k, JSONObject(next).toString()).apply()
    }

    fun hide(streamId: String, newestTargetId: String) = write(_hidden.value + (streamId to newestTargetId))

    fun show(streamId: String) {
        if (streamId in _hidden.value) write(_hidden.value - streamId)
    }

    fun clearAccount() {
        val k = key() ?: return
        prefs.edit().remove(k).apply()
        _hidden.value = emptyMap()
    }
}
