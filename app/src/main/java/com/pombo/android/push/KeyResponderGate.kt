package com.pombo.android.push

/**
 * Process-wide handoff for key-request wakes, mirroring [ForegroundGate]:
 * while the app is up, the ViewModel installs a sweep trigger here so an FCM
 * 'keys' wake runs through the LIVE bridge instead of booting a headless one.
 */
object KeyResponderGate {
    @Volatile
    var sweepNow: (() -> Unit)? = null
}
