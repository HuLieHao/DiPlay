package com.shilapi.xcertplay.orchestration

import com.shilapi.xcertplay.ConnectionMilestone
import com.shilapi.xcertplay.airplay.*
import com.shilapi.xcertplay.network.WirelessStartupFailure
import com.shilapi.xcertplay.transport.Iap2IdentificationConfig
import java.net.Socket
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], manifest = Config.NONE)
class WirelessStartupListenerTest {
    @Test fun staleWirelessRunRetainsDiagnosticsWithoutForwardingOrdinaryLogs() {
        Fixture().use { f ->
            (f.controller.javaClass.getDeclaredField("wirelessGeneration").apply { isAccessible = true }
                .get(f.controller) as java.util.concurrent.atomic.AtomicInteger).incrementAndGet()
            f.listener.onDebugLog("CONNECTION_DIAGNOSTIC airplay-first-close source=video")
            f.listener.onDebugLog("TRACE airplay control response-buffered cseq=7 writeMs=1700")
            f.listener.onDebugLog("TRACE airplay control responses-flushed lastCseq=7 flushMs=1")
            f.listener.onDebugLog("old ordinary state")
            assertTrue(f.logs.any { it.contains("airplay-first-close source=video") })
            assertTrue(f.logs.any { it.contains("writeMs=1700") })
            assertTrue(f.logs.any { it.contains("lastCseq=7") })
            assertFalse(f.logs.any { it.contains("old ordinary state") })
        }
    }
    @Test fun primaryPictureRenderedBeforeRecordIsRecognizedOnceSessionBecomesActive() {
        Fixture().use { f ->
            f.connected()
            val session = f.session()
            session.javaClass.getDeclaredField("mainScreenToken").apply { isAccessible = true }.set(session, Any())
            session.videoFrameRendered() // SETUP media can arrive before RECORD on the control socket.
            f.listener.onSessionActive(session)
            session.videoFrameRendered()
            f.timer!!()
            assertTrue("An already displayed picture must not time out after RECORD", f.failures.isEmpty())
            assertEquals("Live delivery and replay notify the host only once", 1, f.renderedPictures)
        }
    }

    @Test fun replacingTheScreenTokenInvalidatesAPictureRenderedBeforeRecord() {
        Fixture().use { f ->
            f.connected()
            val session = f.session()
            val token = session.javaClass.getDeclaredField("mainScreenToken").apply { isAccessible = true }
            token.set(session, Any())
            session.videoFrameRendered()
            token.set(session, Any())
            f.listener.onSessionActive(session)
            f.timer!!()
            assertEquals(listOf(WirelessStartupFailure.FIRST_FRAME_TIMEOUT), f.failures)
        }
    }
    /** Exercises real controller listeners: diagnostic and auxiliary callbacks must not bypass startup protection. */
    private class Fixture : AutoCloseable {
        val logs = mutableListOf<String>()
        var renderedPictures = 0
        val config = AirPlayConfig("test", "test", "", "1", AirPlayDisplayConfig(800, 480))
        val identity = AirPlayIdentity.generate()
        val media = object : AirPlayMediaHandler {}
        val controller = CarPlayController(RuntimeEnvironment.getApplication(),
            CarPlayRuntimeConfig(mfiTarget = MfiTarget.LOCAL,
                identification = Iap2IdentificationConfig("test", "test", "test", "test", "1", "1",
                    carPlayUsbInterfaceNumber = 0)), config, identity, PairingStore(),
            object : AirPlaySessionListener {
                override fun onDebugLog(message: String) { logs += message }
                override fun onVideoFrameRendered(session: AirPlaySession) { renderedPictures++ }
            },
            media, {})
        val owner = AirPlayListenerIdentity(0)
        var timer: (() -> Unit)? = null
        var cancelled = 0
        val failures = mutableListOf<WirelessStartupFailure>()
        val watchdog = WirelessStartupWatchdog(owner, { _, action ->
            timer = action; { cancelled++ }
        }, failures::add, log = logs::add)
        val listener: AirPlaySessionListener
        val sessions = mutableListOf<AirPlaySession>()
        init {
            val phase = controller.javaClass.getDeclaredField("phase").apply { isAccessible = true }
            phase.set(controller, phase.type.enumConstants.first { it.toString() == "WIRELESS" })
            listener = controller.javaClass.getDeclaredMethod("wirelessSessionListener", Int::class.javaPrimitiveType,
                WirelessStartupWatchdog::class.java).apply { isAccessible = true }
                .invoke(controller, 0, watchdog) as AirPlaySessionListener
        }
        fun session() = AirPlaySession(Socket(), config, identity, PairingStore(), null, listener, media)
            .also { sessions += it }
        fun connected() = listener.onTcpAccepted(AirPlayTcpAccepted(owner, false, System.nanoTime()))
        override fun close() {
            watchdog.terminate()
            sessions.forEach { it.close() }
            controller.close()
            assertTrue(controller.awaitClosed(5_000))
        }
    }

    @Test fun renderedPictureOfCurrentPrimarySessionCancelsDeadlineAndLeavesStaticSessionAlive() {
        Fixture().use { f ->
            f.connected()
            val current = f.session()
            val other = f.session()
            f.listener.onSessionActive(current)
            val pictureTimer = f.timer!!
            val before = f.cancelled
            f.listener.onVideoFrameRendered(other)
            f.listener.onVideoFrameRendered(current) // No primary screen negotiated yet.
            assertEquals(before, f.cancelled)
            current.javaClass.getDeclaredField("mainScreenToken").apply { isAccessible = true }.set(current, Any())
            f.listener.onVideoFrameRendered(current)
            assertTrue(f.cancelled > before)
            pictureTimer()
            assertTrue(f.failures.isEmpty())
            assertSame(current, f.controller.activeAirPlaySession())
        }
    }

    @Test fun detailedMilestoneStillReachesLogWithoutPreventingHandshakeTimeout() {
        Fixture().use { f ->
            f.connected()
            val session = f.session()
            f.listener.onConnectionMilestone(session, ConnectionMilestone.AIRPLAY_PAIR_VERIFY)
            f.timer!!()
            assertEquals(listOf(WirelessStartupFailure.SESSION_TIMEOUT), f.failures)
            assertTrue(f.logs.any { it.contains("lastMilestone=AIRPLAY_PAIR_VERIFY") })
        }
    }

    @Test fun internalAndForeignTcpDoNotArmStartupDeadline() {
        Fixture().use { f ->
            f.listener.onTcpAccepted(AirPlayTcpAccepted(f.owner, true, 0))
            f.listener.onTcpAccepted(AirPlayTcpAccepted(AirPlayListenerIdentity(0), false, 0))
            assertNull(f.timer)
        }
    }
}
