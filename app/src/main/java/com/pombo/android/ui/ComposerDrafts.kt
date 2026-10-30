package com.pombo.android.ui

import com.pombo.android.UiMessage

/**
 * Unsent composer state per account and conversation: text, reply and edit.
 * Memory only: drafts of sealed conversations must never reach storage.
 */
class ComposerDrafts {
    data class Draft(val text: String, val replyTo: UiMessage? = null, val editing: UiMessage? = null)

    private val drafts = mutableMapOf<String, Draft>()

    private fun key(account: String, streamId: String) = "${account.lowercase()}\n$streamId"

    /** Keeps a conversation's composer state; an empty one removes it. */
    fun stash(account: String?, streamId: String?, draft: Draft) {
        if (account == null || streamId == null) return
        val k = key(account, streamId)
        if (draft.text.isBlank() && draft.replyTo == null && draft.editing == null) drafts.remove(k)
        else drafts[k] = draft
    }

    /**
     * Returns and removes a conversation's draft. A reply or edit whose
     * message has since been deleted is dropped; the text stays.
     */
    fun take(account: String?, streamId: String?, isDeleted: (String) -> Boolean = { false }): Draft? {
        if (account == null || streamId == null) return null
        val draft = drafts.remove(key(account, streamId)) ?: return null
        return draft.copy(
            replyTo = draft.replyTo?.takeUnless { isDeleted(it.id) },
            editing = draft.editing?.takeUnless { isDeleted(it.id) }
        )
    }

    fun forgetAccount(account: String?) {
        if (account == null) return
        val prefix = "${account.lowercase()}\n"
        drafts.keys.removeAll { it.startsWith(prefix) }
    }
}
