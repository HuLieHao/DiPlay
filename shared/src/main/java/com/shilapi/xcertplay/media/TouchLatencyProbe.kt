package com.shilapi.xcertplay.media

/** Diagnostic link between the touch uplink and the next video frame, read by [VideoStats]. */
internal object TouchLatencyProbe {
    data class Window(val maxSendNs: Long, val expired: Long)
    private var streamGeneration = 0L
    private var pendingTouchNs: Long? = null
    private var maxSendNs = 0L
    private var expired = 0L

    /** A replacement main screen starts a fresh measurement scope. Old decoders retain their token. */
    @Synchronized fun beginStream(): Long {
        streamGeneration++
        pendingTouchNs = null
        maxSendNs = 0L
        expired = 0L
        return streamGeneration
    }

    @Synchronized fun currentGeneration(): Long = streamGeneration

    @Synchronized fun onTouchSent(sentAtNs: Long, sendDurationNs: Long, generation: Long = currentGeneration()) {
        if (generation != streamGeneration) return
        expirePending(sentAtNs)
        if (pendingTouchNs == null) pendingTouchNs = sentAtNs
        maxSendNs = maxOf(maxSendNs, sendDurationNs.coerceAtLeast(0))
    }

    /** Returns touch-to-frame latency for the first frame after a touch, or -1. */
    @Synchronized fun onFrame(nowNs: Long, generation: Long = currentGeneration()): Long {
        if (generation != streamGeneration) return -1
        expirePending(nowNs)
        val touch = pendingTouchNs ?: return -1
        pendingTouchNs = null
        return nowNs - touch
    }

    /** Only the owning main-screen counters may consume this window; auxiliary streams cannot. */
    @Synchronized fun finishWindow(generation: Long, nowNs: Long): Window {
        if (generation != streamGeneration) return Window(0, 0)
        expirePending(nowNs)
        return Window(maxSendNs, expired).also { maxSendNs = 0; expired = 0 }
    }

    private fun expirePending(nowNs: Long) {
        val age = nowNs - (pendingTouchNs ?: return)
        if (age < 0 || age >= MAX_TOUCH_AGE_NS) {
            pendingTouchNs = null
            expired++
        }
    }

    // A much later frame cannot reliably be attributed to that touch. Report it as unmatched,
    // rather than clamping the measurement or declaring minutes of input latency.
    private const val MAX_TOUCH_AGE_NS = 5_000_000_000L
}
