package com.pombo.android.core

/** Home list tabs: DMs in Direct, every other channel type in Channels (web ui/homeTabs.js). */
enum class HomeTab(val id: String) {
    DIRECT("direct"),
    CHANNELS("channels");

    companion object {
        fun of(channelType: String?): HomeTab = if (channelType == "dm") DIRECT else CHANNELS

        /** A stored id, or Direct for none or anything unknown. */
        fun fromId(id: String?): HomeTab = entries.firstOrNull { it.id == id } ?: DIRECT
    }
}
