package com.pombo.android.core

/** Home list tabs: DMs in Direct, every other channel type in Channels (web ui/homeTabs.js). */
enum class HomeTab(val id: String) {
    CHANNELS("channels"),
    DIRECT("direct");

    companion object {
        val DEFAULT = CHANNELS

        fun of(channelType: String?): HomeTab = if (channelType == "dm") DIRECT else CHANNELS

        /** A stored id, or the default for none or anything unknown. */
        fun fromId(id: String?): HomeTab = entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}
