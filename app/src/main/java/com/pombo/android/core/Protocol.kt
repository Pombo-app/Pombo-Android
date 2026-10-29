package com.pombo.android.core

import java.security.SecureRandom

object Protocol {

    private val random = SecureRandom()

    /** 16 random bytes in hex — same as identityManager.generateMessageId(). */
    fun generateMessageId(): String {
        val bytes = ByteArray(16)
        random.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
