package com.shilapi.xcertplay.orchestration

import com.shilapi.xcertplay.ConnectionMilestone
import com.shilapi.xcertplay.airplay.AirPlayListenerIdentity
import com.shilapi.xcertplay.airplay.AirPlayTcpAccepted
import com.shilapi.xcertplay.network.WirelessStartupFailure
import com.shilapi.xcertplay.network.WirelessStartupPolicy

/** Startup deadlines only. A healthy, rendered session has no video-idle deadline. */
internal class WirelessStartupWatchdog(
    val listener: AirPlayListenerIdentity,
    private val schedule: (Long, () -> Unit) -> (() -> Unit),
    private val onTimeout: (WirelessStartupFailure) -> Unit,
    private val nowNanos: () -> Long = System::nanoTime,
    private val timeoutMillis: Long = WirelessStartupPolicy.FIRST_TCP_MILLIS,
    private val log: (String) -> Unit = {},
) {
    private enum class Stage { FIRST_TCP, SESSION, FIRST_FRAME, COMPLETE }
    @Volatile var terminated = false
        private set
    private var stage = Stage.FIRST_TCP
    private var firstStartNanos: Long? = null
    private var stageStartedNanos = 0L
    private var lastMilestone: ConnectionMilestone? = null
    private var timerGeneration = 0
    private var cancelTimer: (() -> Unit)? = null

    @Synchronized fun startSessionSent(sentAtNanos: Long) {
        if (terminated || firstStartNanos != null) return
        firstStartNanos = sentAtNanos
        log("StartSession atNs=$sentAtNanos")
        if (stage == Stage.FIRST_TCP) arm(Stage.FIRST_TCP, sentAtNanos, timeoutMillis)
    }

    @Synchronized fun accepted(event: AirPlayTcpAccepted): Boolean {
        if (terminated || event.listener !== listener || event.internal) return false
        if (stage == Stage.FIRST_TCP) {
            log("first TCP atNs=${event.acceptedAtNanos}")
            arm(Stage.SESSION, event.acceptedAtNanos, WirelessStartupPolicy.SESSION_MILLIS)
        }
        return true
    }

    /** Diagnostic observations never renew a deadline, including repeated protocol requests. */
    @Synchronized fun progress(milestone: ConnectionMilestone) {
        if (!terminated && stage != Stage.COMPLETE) lastMilestone = milestone
    }

    @Synchronized fun sessionEstablished(): Boolean {
        if (terminated) return false
        if (stage == Stage.FIRST_TCP || stage == Stage.SESSION) {
            val atNanos = nowNanos()
            log("session established atNs=$atNanos")
            arm(Stage.FIRST_FRAME, atNanos, WirelessStartupPolicy.FIRST_FRAME_MILLIS)
        }
        return true
    }

    /** Called only for the controller's current session after its primary picture renders. */
    @Synchronized fun frameRendered(): Boolean {
        if (terminated) return false
        if (stage != Stage.COMPLETE) {
            stage = Stage.COMPLETE
            cancel()
            log("first frame rendered atNs=${nowNanos()}; startup deadlines cancelled")
        }
        return true
    }

    @Synchronized fun terminate(): Boolean {
        if (terminated) return false
        terminated = true
        cancel()
        return true
    }

    private fun arm(next: Stage, startedNanos: Long, limitMillis: Long) {
        cancel()
        stage = next
        stageStartedNanos = startedNanos
        val generation = timerGeneration
        val remaining = (limitMillis - (nowNanos() - startedNanos) / 1_000_000).coerceAtLeast(0)
        log("deadline stage=$stage atNs=$startedNanos limitMs=$limitMillis")
        cancelTimer = schedule(remaining) { expire(generation) }
    }

    private fun expire(generation: Int) {
        val reason = synchronized(this) {
            if (terminated || stage == Stage.COMPLETE || generation != timerGeneration) return
            terminated = true
            cancelTimer = null
            val failure = when (stage) {
                Stage.FIRST_TCP -> WirelessStartupFailure.FIRST_TCP_TIMEOUT
                Stage.SESSION -> WirelessStartupFailure.SESSION_TIMEOUT
                Stage.FIRST_FRAME -> WirelessStartupFailure.FIRST_FRAME_TIMEOUT
                Stage.COMPLETE -> return
            }
            val atNanos = nowNanos()
            log("timeout stage=$stage reason=$failure atNs=$atNanos " +
                "elapsedMs=${(atNanos - stageStartedNanos) / 1_000_000} lastMilestone=${lastMilestone ?: "NONE"}")
            failure
        }
        onTimeout(reason)
    }

    private fun cancel() {
        timerGeneration++
        cancelTimer?.invoke()
        cancelTimer = null
    }
}
