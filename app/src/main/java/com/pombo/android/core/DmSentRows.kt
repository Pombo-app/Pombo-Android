package com.pombo.android.core

/**
 * Storage rows this session wrote to peers' DM inboxes, with the throwaway
 * keys that signed them. A Pombo node lets a row's signer purge it, and in a
 * DM every row is signed by a key made for it, so a later delete can only
 * reach storage while the session still holds those keys. Memory only, on
 * purpose: the keys die with the session.
 */
class DmSentRows(private val max: Int = 2000) {

    data class Row(
        val streamId: String,
        val partition: Int,
        val timestamp: Long,
        val sequenceNumber: Int,
        val privateKeyHex: String
    )

    private val rows = object : LinkedHashMap<String, MutableList<Row>>(64, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, MutableList<Row>>) = size > max
    }

    @Synchronized
    fun remember(messageId: String, row: Row) {
        val list = rows.remove(messageId) ?: ArrayList()
        list.add(row)
        rows[messageId] = list
    }

    @Synchronized
    fun rowsOf(messageId: String): List<Row> = rows[messageId]?.toList() ?: emptyList()

    @Synchronized
    fun forget(messageId: String) {
        rows.remove(messageId)
    }

    fun holds(streamId: String, messageId: String): Boolean = rowsOf(messageId).any { it.streamId == streamId }

    /** The purge groups of one message, each signed by the key that wrote its rows; chunks before the announce. */
    fun purgeGroups(streamId: String, messageId: String): List<StoragePurge.Group> {
        val byKey = LinkedHashMap<String, Triple<Int, String, MutableList<StoragePurge.Target>>>()
        for (r in rowsOf(messageId)) {
            if (r.streamId != streamId) continue
            byKey.getOrPut("${r.partition}|${r.privateKeyHex}") { Triple(r.partition, r.privateKeyHex, ArrayList()) }
                .third.add(StoragePurge.Target(r.timestamp, r.sequenceNumber))
        }
        return byKey.values.map { (partition, key, targets) -> StoragePurge.Group(partition, targets, key) }
            .sortedBy { if (it.partition == StreamConstants.P_MESSAGES) 1 else 0 }
    }

    @Synchronized
    fun clear() = rows.clear()
}
