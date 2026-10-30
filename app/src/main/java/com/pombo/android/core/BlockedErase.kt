package com.pombo.android.core

/**
 * Erasing what blocked accounts left in the own DM inbox (web
 * dmManager.eraseBlockedData): which rows go, in what order, and how the
 * result reads.
 */
object BlockedErase {

    /** One opened inbox row: who sealed it and where it sits. */
    data class Row(val partition: Int, val sender: String, val timestamp: Long, val sequenceNumber: Int)

    data class Result(
        val outcome: StoragePurge.Outcome?,
        val messages: Int,
        val files: Int,
        val skippedFiles: Int
    )

    /** The blocked senders' rows. */
    fun theirs(rows: List<Row>, blocked: Set<String>): List<Row> {
        val lower = blocked.map { it.lowercase() }.toSet()
        return rows.filter { it.sender.lowercase() in lower }
    }

    /** Their rows per partition, messages last: a pass cut short leaves the announces, never orphaned chunks. */
    fun groups(theirs: List<Row>): List<StoragePurge.Group> =
        theirs.groupBy { it.partition }
            .map { (p, rows) -> StoragePurge.Group(p, rows.map { StoragePurge.Target(it.timestamp, it.sequenceNumber) }) }
            .sortedBy { if (it.partition == StreamConstants.P_MESSAGES) 1 else 0 }

    enum class Tone { INFO, SUCCESS, WARNING }

    /** The toast for a finished erase (web describeBlockedErase). */
    fun describe(result: Result?): Pair<String, Tone> {
        if (result == null || (result.messages == 0 && result.files == 0)) return "Nothing from blocked users in your inbox storage" to Tone.INFO
        val what = listOfNotNull(
            plural(result.messages, "message").takeIf { result.messages > 0 },
            plural(result.files, "file").takeIf { result.files > 0 }
        ).joinToString(" and ")
        val o = result.outcome
        if (o == null || o.providers == 0) return "No storage provider of your inbox can erase $what" to Tone.WARNING
        val missed = if (result.skippedFiles > 0) "; ${plural(result.skippedFiles, "file")} could not be checked" else ""
        val tone = if (o.erasedOn == o.providers && result.skippedFiles == 0) Tone.SUCCESS else Tone.WARNING
        return "Erased $what on ${o.erasedOn} of ${plural(o.providers, "storage provider")}$missed" to tone
    }

    /** The loading toast's line once the rows are located. */
    fun erasing(messages: Int, files: Int): String =
        "Erasing " + listOfNotNull(
            plural(messages, "message").takeIf { messages > 0 },
            plural(files, "file").takeIf { files > 0 }
        ).joinToString(" and ")

    private fun plural(n: Int, word: String) = "$n $word${if (n == 1) "" else "s"}"
}
