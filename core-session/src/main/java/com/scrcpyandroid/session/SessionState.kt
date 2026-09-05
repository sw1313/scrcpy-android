package com.scrcpyandroid.session

import com.scrcpyandroid.protocol.MirrorOptions

sealed class SessionState {
    data object Idle : SessionState()
    data object Connecting : SessionState()
    data class Streaming(
        val deviceName: String,
        val videoWidth: Int,
        val videoHeight: Int,
        val options: MirrorOptions,
    ) : SessionState()
    data class Reconnecting(val attempt: Int, val message: String) : SessionState()
    data class Error(val message: String, val cause: Throwable? = null) : SessionState()
}
