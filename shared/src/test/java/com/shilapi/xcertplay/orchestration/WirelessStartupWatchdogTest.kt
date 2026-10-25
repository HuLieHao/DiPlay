package com.shilapi.xcertplay.orchestration

import com.shilapi.xcertplay.airplay.AirPlayListenerIdentity
import com.shilapi.xcertplay.airplay.AirPlayTcpAccepted
import com.shilapi.xcertplay.ConnectionMilestone
import com.shilapi.xcertplay.network.WirelessStartupFailure
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class WirelessStartupWatchdogTest {
    @Test fun acceptedTcpStillTimesOutWhenHandshakeNeverCompletes() {
        val watch = watchdog()
        watch.startSessionSent(0)
        now = 1_000
        watch.accepted(event())
        assertNotNull("TCP arrival must arm a handshake deadline", deadline)
        now = 31_000
        timer!!()
        assertEquals(1, failures)
    }

    @Test fun activeSessionStillTimesOutWithoutARenderedPicture() {
        val watch = watchdog()
        watch.startSessionSent(0)
        watch.accepted(event())
        now = 2_000
        watch.sessionEstablished()
        assertNotNull("Session establishment must arm a first-picture deadline", deadline)
        now = 32_000
        timer!!()
        assertEquals(1, failures)
    }
    @Test fun successfulAuthenticationAllowsSessionSetupWithoutRepeatedRenewal() {
        val watch = watchdog()
        watch.accepted(event())
        val originalTimer = timer!!
        now = 29_000
        watch.authenticationResponseSent()
        assertEquals(59_000L, deadline)
        originalTimer()
        assertEquals(0, failures)
        now = 40_000
        watch.authenticationResponseSent()
        assertEquals(59_000L, deadline)
        now = 59_000
        timer!!()
        assertEquals(1, failures)
    }

    @Test fun authenticationCannotReviveExpiredSession() {
        val watch = watchdog()
        watch.accepted(event())
        now = 30_000
        timer!!()
        watch.authenticationResponseSent()
        assertEquals(1, failures)
        assertTrue(watch.terminated)
    }

    @Test fun authenticationCannotChangeFirstFrameDeadline() {
        val watch = watchdog()
        watch.accepted(event())
        now = 2_000
        watch.sessionEstablished()
        now = 20_000
        watch.authenticationResponseSent()
        assertEquals(32_000L, deadline)
    }

    @Test fun delayedTimerDoesNotPermitLateAuthenticationToExtendDeadline() {
        val watch = watchdog()
        watch.accepted(event())
        now = 30_001
        watch.authenticationResponseSent()
        assertEquals(30_000L, deadline)
        timer!!()
        assertEquals(1, failures)
    }

    private var now = 0L
    private var deadline: Long? = null
    private var timer: (() -> Unit)? = null
    private var failures = 0
    private val identity = AirPlayListenerIdentity(7)
    private fun watchdog() = WirelessStartupWatchdog(identity, { delay, action ->
        deadline = now + delay
        timer = action
        val cancel: () -> Unit = { deadline = null }
        cancel
    }, { failures++ }, { now * 1_000_000 })
    private fun event(owner: AirPlayListenerIdentity = identity, internal: Boolean = false) =
        AirPlayTcpAccepted(owner, internal, now * 1_000_000)

    @Test fun noSuccessfulStartSessionDoesNotArmTimer() {
        watchdog()
        now = 60_000
        assertNull(timer)
        assertEquals(0, failures)
    }

    @Test fun repeatedStartDoesNotExtendDeadline() {
        val watch = watchdog()
        watch.startSessionSent(0)
        now = 20_000
        watch.startSessionSent(now * 1_000_000)
        assertEquals(30_000L, deadline)
        now = 30_000
        timer!!()
        assertEquals(1, failures)
        assertTrue(watch.terminated)
        timer!!()
        assertEquals(1, failures)
    }

    @Test fun earlyAcceptAvoidsTimerAndDoesNotDeclareSessionActive() {
        val logs = mutableListOf<String>()
        val watch = WirelessStartupWatchdog(identity, { delay, action ->
            deadline = now + delay; timer = action; { deadline = null }
        }, {}, { now * 1_000_000 }, log = logs::add)
        assertTrue(watch.accepted(event()))
        assertEquals(30_000L, deadline)
        watch.startSessionSent(1)
        assertFalse(logs.any { it.contains("session established") })
        assertFalse(watch.terminated)
    }

    @Test fun localProbeAndOtherListenerCannotCancelTimer() {
        val watch = watchdog()
        watch.startSessionSent(0)
        assertFalse(watch.accepted(event(internal = true)))
        assertFalse(watch.accepted(event(AirPlayListenerIdentity(6))))
        assertFalse(watch.accepted(event(AirPlayListenerIdentity(7))))
        now = 30_000
        timer!!()
        assertEquals(1, failures)
    }

    @Test fun acceptWinsAgainstAlreadyQueuedTimeout() {
        val watch = watchdog()
        watch.startSessionSent(0)
        now = 30_000
        val oldTimer = timer!!
        assertTrue(watch.accepted(event()))
        oldTimer()
        assertEquals(0, failures)
        assertFalse(watch.terminated)
    }

    @Test fun timeoutWinsAndLateAcceptCannotRevive() {
        val watch = watchdog()
        watch.startSessionSent(0)
        now = 30_000
        timer!!()
        assertFalse(watch.accepted(event()))
        assertFalse(watch.sessionEstablished())
        assertFalse(watch.terminate())
        assertEquals(1, failures)
    }

    @Test fun sessionSuccessAndUserCancellationInvalidateQueuedTimer() {
        for (success in listOf(true, false)) {
            val watch = watchdog()
            watch.startSessionSent(0)
            val queued = timer!!
            if (success) { watch.sessionEstablished(); watch.frameRendered() } else watch.terminate()
            now = 60_000
            queued()
            assertEquals(0, failures)
        }
    }

    @Test fun oldTimerCannotEndNewAttempt() {
        val old = watchdog()
        old.startSessionSent(0)
        val queued = timer!!
        old.terminate()
        val current = watchdog()
        current.startSessionSent(0)
        queued()
        assertFalse(current.terminated)
        assertEquals(0, failures)
    }

    @Test fun concurrentAcceptAndTimeoutHaveExactlyOneOutcome() {
        repeat(50) {
            lateinit var expire: () -> Unit
            val outcomes = AtomicInteger()
            val watch = WirelessStartupWatchdog(identity, { _, action -> expire = action; {} }, { outcomes.incrementAndGet() })
            watch.startSessionSent(0)
            val start = CountDownLatch(1)
            val queued = expire
            val tcp = thread { start.await(); if (watch.accepted(event())) outcomes.incrementAndGet() }
            val timeout = thread { start.await(); queued() }
            start.countDown()
            tcp.join(2_000); timeout.join(2_000)
            assertFalse(tcp.isAlive); assertFalse(timeout.isAlive)
            assertEquals(1, outcomes.get())
        }
    }
    @Test fun repeatedTcpAndDetailedObservationsNeverExtendHandshakeDeadline() {
        val watch = watchdog()
        watch.accepted(event())
        val queued = timer!!
        now = 29_000
        watch.accepted(event())
        repeat(10) { watch.progress(ConnectionMilestone.AIRPLAY_PAIR_VERIFY) }
        watch.startSessionSent(now * 1_000_000)
        assertEquals(30_000L, deadline)
        now = 30_000
        queued()
        assertEquals(1, failures)
    }

    @Test fun repeatedRecordAndVideoReceivedCannotExtendOrSatisfyFirstPictureDeadline() {
        val watch = watchdog()
        watch.accepted(event())
        now = 2_000
        watch.sessionEstablished()
        val queued = timer!!
        now = 31_000
        watch.sessionEstablished()
        watch.progress(ConnectionMilestone.VIDEO_RECEIVED)
        assertEquals(32_000L, deadline)
        now = 32_000
        queued()
        assertEquals(1, failures)
        assertFalse(watch.frameRendered())
    }

    @Test fun handshakeTimerCannotKillSessionThatHasMovedToWaitingForPicture() {
        val watch = watchdog()
        watch.accepted(event())
        val handshakeTimer = timer!!
        now = 29_000
        watch.sessionEstablished()
        handshakeTimer()
        assertEquals(0, failures)
        assertEquals(59_000L, deadline)
        timer!!()
        assertEquals(1, failures)
    }

    @Test fun renderedStaticPictureCancelsAllStartupTimersPermanently() {
        val watch = watchdog()
        watch.accepted(event())
        val handshakeTimer = timer!!
        watch.sessionEstablished()
        val pictureTimer = timer!!
        assertTrue(watch.frameRendered())
        now = 3_600_000
        handshakeTimer(); pictureTimer()
        watch.sessionEstablished()
        watch.accepted(event())
        watch.progress(ConnectionMilestone.AIRPLAY_STREAM_SETUP)
        assertNull(deadline)
        assertEquals(0, failures)
    }

    @Test fun timeoutLogsKeepLastDiagnosticStepAndReportDistinctRetryableFailure() {
        for (picture in listOf(false, true)) {
            val logs = mutableListOf<String>()
            val reasons = mutableListOf<WirelessStartupFailure>()
            val watch = WirelessStartupWatchdog(identity, { delay, action ->
                deadline = now + delay; timer = action; { deadline = null }
            }, reasons::add, { now * 1_000_000 }, log = logs::add)
            watch.accepted(event())
            if (picture) watch.sessionEstablished()
            watch.progress(ConnectionMilestone.AIRPLAY_PAIR_VERIFY)
            now += 30_000
            timer!!()
            assertEquals(listOf(if (picture) WirelessStartupFailure.FIRST_FRAME_TIMEOUT else
                WirelessStartupFailure.SESSION_TIMEOUT), reasons)
            assertTrue(reasons.single().retryable)
            assertTrue(logs.any { it.contains("elapsedMs=30000") &&
                it.contains("lastMilestone=AIRPLAY_PAIR_VERIFY") && it.contains("atNs=") })
        }
    }

    @Test fun userStopInvalidatesQueuedHandshakeAndPictureTimeouts() {
        for (picture in listOf(false, true)) {
            val watch = watchdog()
            watch.accepted(event())
            if (picture) watch.sessionEstablished()
            val queued = timer!!
            watch.terminate()
            now += 60_000
            queued()
            assertEquals(0, failures)
        }
    }

}
