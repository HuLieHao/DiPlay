package com.shilapi.xcertplay

import com.shilapi.xcertplay.orchestration.CarPlayStatus
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], qualifiers = "zh-rCN", manifest = Config.NONE)
class ConnectionWaitingProgressTest {
    private var time = 0L
    private val progress = ConnectionWaitingProgress { time }
    private fun text() = progress.text(RuntimeEnvironment.getApplication().resources)

    @Test fun duplicateNotificationsDoNotPretendTheConnectionIsMakingProgress() {
        progress.status(CarPlayStatus.WaitingForAirPlay, true)
        time = 4_000
        progress.status(CarPlayStatus.WaitingForAirPlay, true)
        time = 7_000
        assertTrue(text(), text().contains("距最近进展：7 秒"))
        progress.status(CarPlayStatus.EstablishingAirPlay, true)
        time = 9_000
        assertTrue(text(), text().contains("距最近进展：2 秒"))
        assertTrue(text(), text().contains("+7.0 秒"))
    }

    @Test fun networkWaitDoesNotClaimToObservePhoneWifiOrConfirmationDialog() {
        progress.status(CarPlayStatus.WaitingForAirPlay, true)
        val details = text()
        assertTrue(details, details.contains("手机 Wi-Fi 入网/IP：无法单独确认"))
        assertTrue(details, details.contains("iPhone 确认弹窗：无法检测"))
        assertFalse(details.contains("iPhone 已加入热点"))
    }

    @Test fun receivedAirPlayRequestProvesTcpButBluetoothAuthenticationDoesNot() {
        progress.status(CarPlayStatus.ConnectionProgress(ConnectionMilestone.AUTHENTICATED), true)
        assertFalse(progress.snapshot().tcpAccepted)
        progress.status(CarPlayStatus.ConnectionProgress(ConnectionMilestone.AIRPLAY_INFO), false)
        assertTrue(progress.snapshot().tcpAccepted)
        assertFalse(progress.text(RuntimeEnvironment.getApplication().resources, false).contains("手机 Wi-Fi 入网"))
    }

    @Test fun hotspotBecomingReadyCountsAsProgressWithinTheSamePreparationStep() {
        progress.status(CarPlayStatus.StartingHotspot, true)
        time = 3_000
        progress.status(CarPlayStatus.HotspotReady("test", "Auto", 0, "", "192.168.43.1", "manual"), true)
        time = 6_000
        assertEquals(3L, progress.snapshot().sinceProgressSeconds)
        assertEquals(6_000L, progress.snapshot().phases.first().elapsedMillis)
    }

    @Test fun handshakeEventsKeepTheirTimelineWithoutRestartingThePhaseOrAcceptingLateBootstrap() {
        progress.status(CarPlayStatus.EstablishingAirPlay, true)
        time = 1_000
        assertTrue(progress.status(CarPlayStatus.ConnectionProgress(ConnectionMilestone.AIRPLAY_PAIR_VERIFY), true))
        time = 4_000
        assertFalse(progress.status(CarPlayStatus.ConnectionProgress(ConnectionMilestone.AIRPLAY_PAIR_VERIFY), true))
        assertFalse(progress.status(CarPlayStatus.ConnectionProgress(ConnectionMilestone.WIFI_CONFIG_SENT), true))
        assertTrue(text(), text().contains("距最近进展：3 秒"))
        assertTrue(text(), text().contains("+1.0 秒 · 正在处理 CarPlay 配对验证"))
        assertEquals(3L, progress.snapshot().stageSeconds)
        assertEquals(4_000L, progress.snapshot().phases.single { it.phase == WaitingPhase.HANDSHAKE }.elapsedMillis)
        progress.sessionActive()
        time = 5_000
        progress.status(CarPlayStatus.ConnectionProgress(ConnectionMilestone.VIDEO_RECEIVED), true)
        assertTrue(text(), text().contains("视频数据已收到，等待首帧显示"))
        assertEquals(WaitingPhaseState.CURRENT, progress.snapshot().phases.last().state)
        progress.firstFrame()
        assertTrue(progress.snapshot().phases.all { it.state == WaitingPhaseState.DONE })
        time = 10_000
        assertEquals(5L, progress.snapshot().totalSeconds)
    }

    @Test fun retryClearsEventTimelineAndRetainsRedactedFailureAsContext() {
        progress.status(CarPlayStatus.ConnectionProgress(ConnectionMilestone.WIFI_CONFIG_SENT), true)
        progress.retry(1_000, "network timeout 192.168.43.2")
        time = 1_000
        progress.beginAttempt()
        assertFalse(text().contains("Wi-Fi 参数已发送"))
        assertFalse(text().contains("192.168.43.2"))
        assertTrue(text(), text().contains("network timeout"))
        assertTrue(text(), text().contains("距最近进展：0 秒"))
    }

    @Test fun repeatedStageAndLateBootstrapDoNotRestartTheStageTimer() {
        progress.status(CarPlayStatus.EstablishingAirPlay, true)
        time = 3_000
        progress.status(CarPlayStatus.EstablishingAirPlay, true)
        assertFalse(progress.status(CarPlayStatus.RunningWireless, true))
        time = 5_000
        assertTrue(text(), text().contains("当前阶段：5 秒"))
        assertTrue(text(), text().contains("CarPlay TCP：已接入"))
        val phase = progress.snapshot().phases.single { it.phase == WaitingPhase.HANDSHAKE }
        assertEquals(WaitingPhaseState.CURRENT, phase.state)
        assertEquals(5_000L, phase.elapsedMillis)
    }

    @Test fun retryKeepsTotalTimeButClearsPreviousAttemptMilestonesAndNetwork() {
        progress.status(CarPlayStatus.HotspotReady("test", "2.4GHz", 6, "", "192.168.43.1", "manual"), true)
        time = 3_000
        progress.status(CarPlayStatus.WaitingForAirPlay, true)
        time = 10_000
        progress.retry(2_000, "timeout")
        time = 10_500
        assertTrue(text(), text().contains("2 秒后重试"))
        time = 12_000
        progress.beginAttempt()
        assertTrue(text(), text().contains("总耗时：12 秒"))
        assertTrue(text(), text().contains("第 2 次尝试"))
        assertFalse(text().contains("192.168.43.1"))
        assertFalse(text().contains("本次已完成"))
        assertEquals(WaitingPhaseState.CURRENT, progress.snapshot().phases.first().state)
        assertTrue(progress.snapshot().phases.drop(1).all { it.state == WaitingPhaseState.PENDING && it.elapsedMillis == null })
    }

    @Test fun stoppedTimerFreezesAndSensitiveFailureIsNotDisplayed() {
        time = 4_000
        progress.stop("token=private-token")
        time = 40_000
        assertTrue(text(), text().contains("总耗时：4 秒"))
        assertTrue(text(), text().contains("自动重试已停止"))
        assertFalse(text().contains("private-token"))
        assertEquals(WaitingPhaseState.INTERRUPTED, progress.snapshot().phases.first().state)
    }

    @Test fun reconnectAfterSuccessfulPlaybackDoesNotCountPlaybackTimeAsConnectionTime() {
        progress.sessionActive()
        time = 5_000
        progress.firstFrame()
        time = 3_600_000
        progress.retry(2_000, "disconnected")
        time += 2_000
        progress.beginAttempt()
        assertTrue(text(), text().contains("总耗时：2 秒"))
        assertTrue(text(), text().contains("第 1 次尝试"))
    }
    @Test fun aRecoveredSessionClearsThePendingRetryBeforeItsFirstFrame() {
        progress.retry(2_000, "transport error")
        time = 3_000
        progress.sessionActive()
        assertNull(progress.snapshot().retrySeconds)
        assertEquals(WaitingPhaseState.CURRENT, progress.snapshot().phases.last().state)
        assertTrue(progress.isWaiting)
    }

    @Test fun wiredControlRecoveryReplacesRetryStateWithoutAcceptingLateBootstrap() {
        progress.status(CarPlayStatus.ConnectingControl, false)
        progress.retry(2_000, "configuration switch failed")
        time = 1_000
        assertTrue(progress.status(CarPlayStatus.RunningControl, false))
        assertNull(progress.snapshot().retrySeconds)
        assertEquals(WaitingPhaseState.CURRENT, progress.snapshot().phases.single { it.phase == WaitingPhase.NETWORK }.state)
        progress.status(CarPlayStatus.EstablishingAirPlay, false)
        assertFalse(progress.status(CarPlayStatus.RunningControl, false))
    }

}
