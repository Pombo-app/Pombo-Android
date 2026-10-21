package com.pombo.android.core

import android.content.Context
import java.io.File

/**
 * Where file transfers keep their bytes: received files, partial downloads,
 * storage staging and the seed registry. One directory per account, so one
 * account's files never list or serve under another; a guest, or no account
 * at all, gets a throwaway directory per session.
 */
object TransferDirs {

    private fun accounts(context: Context) = File(context.filesDir, "transfers-v2")
    private fun guests(context: Context) = File(context.filesDir, "transfers-guest")

    fun forScope(context: Context, address: String?, guest: Boolean, session: String?): File =
        if (!guest && !address.isNullOrEmpty()) File(accounts(context), address.lowercase())
        else File(guests(context), session?.lowercase()?.ifEmpty { null } ?: "none")

    /**
     * Deletes the device-wide layout, whose files belong to no account, and
     * every guest session's directory except [current].
     */
    fun sweep(context: Context, current: File) {
        runCatching { File(context.filesDir, "transfers").deleteRecursively() }
        guests(context).listFiles()?.filter { it != current }?.forEach { runCatching { it.deleteRecursively() } }
    }
}
