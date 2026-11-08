package com.shilapi.xcertplay

import com.shilapi.xcertplay.network.WirelessStartupPolicy

internal class WirelessStartupRetryBudget {
    var retries = 0
        private set
    private var frameSession: Any? = null
    private var firstFrameMillis = 0L

    fun nextDelayMillis(
        fastWireless: Boolean = false,
        failure: com.shilapi.xcertplay.network.WirelessStartupFailure? = null,
    ): Long? {
        if (retries >= WirelessStartupPolicy.MAX_STARTUP_RETRIES) return null
        val retry = retries++
        if (fastWireless && failure == com.shilapi.xcertplay.network.WirelessStartupFailure.FIRST_TCP_TIMEOUT) {
            val nominal = if (retry == 0) 2_000L else 4_000L
            return nominal - WirelessStartupPolicy.FIRST_TCP_GRACE_MILLIS
        }
        return (2_000L * (1L shl retry)).coerceAtMost(30_000L)
    }

    fun firstFrame(session: Any, nowMillis: Long): Boolean {
        if (frameSession === session) return false
        frameSession = session
        firstFrameMillis = nowMillis
        return true
    }

    fun resetIfStable(session: Any, nowMillis: Long): Boolean {
        if (frameSession !== session || nowMillis - firstFrameMillis < WirelessStartupPolicy.STABLE_SESSION_MILLIS) return false
        retries = 0
        return true
    }

    fun disconnected() { frameSession = null }
    fun manualRetry() { retries = 0; disconnected() }
}
