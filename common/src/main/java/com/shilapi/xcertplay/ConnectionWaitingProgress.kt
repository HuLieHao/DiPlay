package com.shilapi.xcertplay

import android.content.res.Resources
import android.os.SystemClock
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.orchestration.CarPlayStatus

internal enum class WaitingPhase(val title: Int) {
    PREPARE(R.string.waiting_phase_prepare), PHONE(R.string.waiting_phase_phone),
    NETWORK(R.string.waiting_phase_network), HANDSHAKE(R.string.waiting_phase_handshake),
    PICTURE(R.string.waiting_phase_picture),
}
internal enum class WaitingPhaseState { DONE, CURRENT, PENDING, INTERRUPTED }
internal data class WaitingPhaseProgress(val phase: WaitingPhase, val state: WaitingPhaseState, val elapsedMillis: Long?)
internal data class ConnectionWaitingSnapshot(
    val totalSeconds: Long, val stageSeconds: Long, val attempt: Int,
    val phases: List<WaitingPhaseProgress>, val hotspot: CarPlayStatus.HotspotReady?,
    val tcpAccepted: Boolean, val retrySeconds: Long?, val stopped: Boolean, val failure: String?,
    val sinceProgressSeconds: Long, val lastProgressTitle: Int,
)

/** UI-only observations. No deadlines, network probes or connection/retry decisions live here. */
internal class ConnectionWaitingProgress(private val now: () -> Long = SystemClock::elapsedRealtime) {
    private enum class Step(val title: Int, val order: Int) {
        PREPARING(R.string.waiting_step_preparing, 0),
        MFI(R.string.waiting_step_mfi, 1),
        HOTSPOT(R.string.waiting_step_hotspot, 2),
        SERVICE(R.string.waiting_step_service, 3),
        BLUETOOTH(R.string.waiting_step_bluetooth, 4),
        USB(R.string.waiting_step_usb, 4),
        AUTH(R.string.waiting_step_auth, 5),
        NETWORK(R.string.waiting_step_network, 6),
        HANDSHAKE(R.string.waiting_step_handshake, 7),
        FRAME(R.string.waiting_step_frame, 8),
        CONNECTED(R.string.carplay_connected, 9),
        RETRY(R.string.waiting_retry, -1),
        STOPPED(R.string.waiting_stopped, -1),
        FAILED(R.string.error, -1),
    }

    private var started = now()
    private var stageStarted = started
    private var ended: Long? = null
    private var step = Step.PREPARING
    private var lastActiveStep = step
    private val completed = linkedMapOf<Step, Long>()
    private var attempt = 1
    private var retryAt: Long? = null
    private var failure: String? = null
    private var hotspot: CarPlayStatus.HotspotReady? = null
    private var tcpAccepted = false
    private var attemptStarted = started
    private var lastProgressAt = started
    private var lastProgressTitle = Step.PREPARING.title
    private val observations = linkedMapOf<String, Pair<Int, Long>>()
    val isWaiting: Boolean get() = ended == null

    fun beginAttempt() {
        if (step == Step.CONNECTED) reset()
        attempt++
        step = Step.PREPARING
        lastActiveStep = step
        stageStarted = now()
        ended = null
        completed.clear()
        retryAt = null
        hotspot = null
        tcpAccepted = false
        resetObservations()
    }

    fun reset() {
        started = now()
        stageStarted = started
        attempt = 0
        step = Step.PREPARING
        lastActiveStep = step
        ended = null
        completed.clear()
        retryAt = null
        failure = null
        hotspot = null
        tcpAccepted = false
        resetObservations()
    }

    private fun resetObservations() {
        attemptStarted = now()
        lastProgressAt = attemptStarted
        lastProgressTitle = Step.PREPARING.title
        observations.clear()
        observe("step:PREPARING", lastProgressTitle)
    }

    private fun observe(key: String, title: Int): Boolean {
        if (observations.containsKey(key)) return false
        val time = now()
        observations[key] = title to (time - attemptStarted).coerceAtLeast(0)
        lastProgressAt = time
        lastProgressTitle = title
        return true
    }

    fun status(status: CarPlayStatus, wireless: Boolean): Boolean {
        val next = when (status) {
            is CarPlayStatus.ConnectionProgress -> when (status.milestone) {
                ConnectionMilestone.IDENTIFYING, ConnectionMilestone.IDENTIFIED,
                ConnectionMilestone.AUTHENTICATING, ConnectionMilestone.AUTHENTICATED,
                ConnectionMilestone.WIFI_CONFIG_SENT -> Step.AUTH
                ConnectionMilestone.VIDEO_RECEIVED -> Step.FRAME
                else -> Step.HANDSHAKE
            }
            CarPlayStatus.DiscoveringMfi, CarPlayStatus.WaitingForMfi,
            CarPlayStatus.RequestingMfiPermission, CarPlayStatus.MfiReady -> Step.MFI
            CarPlayStatus.StartingHotspot, is CarPlayStatus.HotspotReady -> Step.HOTSPOT
            CarPlayStatus.AttachingNetwork -> if (wireless) Step.SERVICE else Step.NETWORK
            CarPlayStatus.WaitingForPairedIphone, CarPlayStatus.ConnectingBluetooth -> Step.BLUETOOTH
            CarPlayStatus.RunningWireless, CarPlayStatus.ConnectingControl -> Step.AUTH
            CarPlayStatus.WaitingForAirPlay, CarPlayStatus.RunningControl -> Step.NETWORK
            CarPlayStatus.EstablishingAirPlay -> Step.HANDSHAKE
            CarPlayStatus.WirelessActive, CarPlayStatus.WirelessActiveFallback -> return false
            CarPlayStatus.DiscoveringIphone, CarPlayStatus.WaitingForIphone,
            CarPlayStatus.RequestingIphonePermission, CarPlayStatus.WaitingForReenumeration,
            CarPlayStatus.SelectingConfiguration, CarPlayStatus.OpeningDataPaths,
            CarPlayStatus.Pairing -> Step.USB
            CarPlayStatus.ControlEnded -> Step.FAILED
            is CarPlayStatus.Failed -> Step.FAILED
        }
        // Late bootstrap notifications must not rewind the TCP/session/first-frame milestones.
        val recoveredWiredControl = !wireless && step == Step.RETRY && status == CarPlayStatus.RunningControl
        if (ended != null || (step.order < 0 && next.order >= 0 && !recoveredWiredControl) ||
            (next.order >= 0 && next.order < step.order)) return false
        if (recoveredWiredControl) retryAt = null
        if (status is CarPlayStatus.HotspotReady) hotspot = status
        if (status is CarPlayStatus.EstablishingAirPlay ||
            (status is CarPlayStatus.ConnectionProgress && next.order >= Step.HANDSHAKE.order)) tcpAccepted = true
        if (status is CarPlayStatus.Failed) failure = DiagnosticRedactor.redact(status.message)?.take(240)
        move(next)
        if (status is CarPlayStatus.ConnectionProgress) {
            return observe("milestone:${status.milestone}", status.milestone.waitingTitle())
        }
        if (status == CarPlayStatus.WaitingForAirPlay) observe("start-sent", R.string.waiting_start_sent)
        val observationTitle = when (status) {
            CarPlayStatus.MfiReady -> R.string.mfi_authentication_ready
            CarPlayStatus.WaitingForMfi -> R.string.waiting_for_mfi_coprocessor
            CarPlayStatus.RequestingMfiPermission -> R.string.requesting_mfi_usb_permission
            is CarPlayStatus.HotspotReady -> R.string.waiting_hotspot_ready
            CarPlayStatus.WaitingForPairedIphone -> R.string.waiting_for_paired_iphone
            CarPlayStatus.ConnectingBluetooth -> R.string.connecting_bluetooth
            CarPlayStatus.RequestingIphonePermission -> R.string.requesting_iphone_usb_permission
            CarPlayStatus.WaitingForReenumeration -> R.string.status_waiting_reenumeration
            CarPlayStatus.SelectingConfiguration -> R.string.selecting_carplay_configuration
            CarPlayStatus.OpeningDataPaths -> R.string.opening_usb_data_paths
            CarPlayStatus.Pairing -> R.string.pairing_with_iphone
            else -> null
        }
        if (observationTitle != null) observe("status:${status.javaClass.simpleName}", observationTitle)
        return true
    }

    fun sessionActive() {
        if (step == Step.CONNECTED) return
        retryAt = null
        ended = null
        tcpAccepted = true
        move(Step.FRAME)
    }
    fun firstFrame() { move(Step.CONNECTED); ended = now(); retryAt = null }

    fun retry(delayMillis: Long, reason: String) {
        if (step == Step.CONNECTED) reset()
        ended = null
        failure = DiagnosticRedactor.redact(reason)?.take(240)
        move(Step.RETRY)
        retryAt = now() + delayMillis
    }

    fun stop(reason: String) {
        if (step == Step.CONNECTED) reset()
        failure = DiagnosticRedactor.redact(reason)?.take(240)
        move(Step.STOPPED)
        retryAt = null
        ended = now()
    }

    private fun move(next: Step) {
        if (step == next) return
        val time = now()
        if (step.order >= 0) completed[step] = (time - stageStarted).coerceAtLeast(0)
        step = next
        if (next.order >= 0) lastActiveStep = next
        stageStarted = time
        if (next.order >= 0) observe("step:$next", next.title)
    }

    fun snapshot(): ConnectionWaitingSnapshot {
        val time = ended ?: now()
        fun Step.phase(): WaitingPhase = when (order) {
            in 0..3 -> WaitingPhase.PREPARE
            in 4..5 -> WaitingPhase.PHONE
            6 -> WaitingPhase.NETWORK
            7 -> WaitingPhase.HANDSHAKE
            else -> WaitingPhase.PICTURE
        }
        val current = lastActiveStep.phase()
        val phases = WaitingPhase.entries.map { phase ->
            val samples = completed.filterKeys { it.phase() == phase }.values
            val active = step.order >= 0 && step.phase() == phase
            val duration = if (samples.isNotEmpty() || active)
                samples.sum() + if (active) (time - stageStarted).coerceAtLeast(0) else 0L else null
            WaitingPhaseProgress(phase, when {
                step == Step.CONNECTED || phase.ordinal < current.ordinal -> WaitingPhaseState.DONE
                phase.ordinal > current.ordinal -> WaitingPhaseState.PENDING
                step.order < 0 -> WaitingPhaseState.INTERRUPTED
                else -> WaitingPhaseState.CURRENT
            }, duration)
        }
        return ConnectionWaitingSnapshot(
            (time - started).coerceAtLeast(0) / 1_000, (time - lastProgressAt).coerceAtLeast(0) / 1_000,
            attempt, phases, hotspot, tcpAccepted,
            retryAt?.let { ((it - time).coerceAtLeast(0) + 999) / 1_000 }, step == Step.STOPPED, failure,
            (time - lastProgressAt).coerceAtLeast(0) / 1_000, lastProgressTitle,
        )
    }

    fun text(resources: Resources, wireless: Boolean = true): String {
        val time = ended ?: now()
        fun seconds(millis: Long) = millis.coerceAtLeast(0) / 1_000
        return buildList {
            add(resources.getString(R.string.waiting_timing, seconds(time - started), seconds(time - lastProgressAt), attempt))
            add(resources.getString(R.string.waiting_last_progress, resources.getString(lastProgressTitle)))
            add(resources.getString(R.string.waiting_since_progress, seconds(time - lastProgressAt)))
            retryAt?.let { add(resources.getString(R.string.waiting_retry_countdown, ((it - time).coerceAtLeast(0) + 999) / 1_000)) }
            if (step == Step.STOPPED) add(resources.getString(R.string.waiting_stopped))
            hotspot?.let {
                val knownBand = it.band.contains("2.4") || it.band.contains("5") || it.band.contains("6")
                add(resources.getString(R.string.waiting_network_details,
                    if (knownBand) it.band else resources.getString(R.string.waiting_unknown),
                    if (it.channel <= 0) resources.getString(R.string.waiting_unknown) else it.channel.toString(), it.address))
            }
            add(resources.getString(if (tcpAccepted) R.string.waiting_tcp_connected else R.string.waiting_tcp_unconfirmed))
            add(resources.getString(R.string.waiting_tcp_note))
            add(resources.getString(R.string.waiting_internet_unknown))
            if (wireless) add(resources.getString(R.string.waiting_phone_wifi_unknown))
            add(resources.getString(R.string.waiting_phone_prompt_unknown))
            if (observations.isNotEmpty()) {
                add(resources.getString(R.string.waiting_observations))
                add(resources.getString(R.string.waiting_observations_note))
                observations.values.forEach { (title, millis) ->
                    add(resources.getString(R.string.waiting_observation, millis / 1_000.0, resources.getString(title)))
                }
            }
            if (completed.isNotEmpty()) {
                add(resources.getString(R.string.waiting_completed))
                completed.forEach { (phase, duration) ->
                    add(resources.getString(R.string.waiting_completed_stage, resources.getString(phase.title), seconds(duration)))
                }
            }
            failure?.let { add(resources.getString(R.string.waiting_last_error, it)) }
        }.joinToString("\n")
    }
}

internal fun ConnectionMilestone.waitingTitle(): Int = when (this) {
    ConnectionMilestone.IDENTIFYING -> R.string.waiting_identifying
    ConnectionMilestone.IDENTIFIED -> R.string.waiting_identified
    ConnectionMilestone.AUTHENTICATING -> R.string.waiting_authenticating
    ConnectionMilestone.AUTHENTICATED -> R.string.waiting_authenticated
    ConnectionMilestone.WIFI_CONFIG_SENT -> R.string.waiting_wifi_sent
    ConnectionMilestone.AIRPLAY_INFO -> R.string.waiting_airplay_info
    ConnectionMilestone.AIRPLAY_PAIR_SETUP -> R.string.waiting_airplay_pair_setup
    ConnectionMilestone.AIRPLAY_PAIR_VERIFY -> R.string.waiting_airplay_pair_verify
    ConnectionMilestone.AIRPLAY_MFI -> R.string.waiting_airplay_mfi
    ConnectionMilestone.AIRPLAY_STREAM_SETUP -> R.string.waiting_airplay_stream_setup
    ConnectionMilestone.VIDEO_RECEIVED -> R.string.waiting_video_received
}
