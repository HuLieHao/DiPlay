package com.shilapi.xcertplay.network

import com.shilapi.xcertplay.ConnectionMilestone
import com.shilapi.xcertplay.airplay.*
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], manifest = Config.NONE)
class AirPlayConnectionMilestoneForwardingTest {
    @Test fun internalProbeRequestsDoNotAdvanceTheWirelessPhoneConnection() {
        val service = Robolectric.buildService(CarPlayVpnService::class.java).create().get()
        val events = LinkedBlockingQueue<ConnectionMilestone>()
        val address = InetAddress.getByName("127.0.0.1")
        val config = AirPlayConfig("test", "test", "", "1", AirPlayDisplayConfig(800, 480), port = 0)
        try {
            assertEquals(CarPlayVpnService.AttachResult.Started, service.attachWireless(address, config,
                AirPlayIdentity.generate(), PairingStore(), null,
                object : AirPlaySessionListener {
                    override fun onConnectionMilestone(session: AirPlaySession, milestone: ConnectionMilestone) {
                        events.add(milestone)
                    }
                }, object : AirPlayMediaHandler {}, listenerIdentity = AirPlayListenerIdentity(1)))
            Socket(address, service.boundPort()!!).use { probe ->
                probe.soTimeout = 3_000
                probe.getOutputStream().write("GET /info RTSP/1.0\r\nCSeq: 1\r\nContent-Length: 0\r\n\r\n".toByteArray())
                assertTrue(probe.getInputStream().bufferedReader().readLine().startsWith("RTSP/1.0 200"))
                assertTrue(events.isEmpty())
            }
        } finally { service.detach() }
    }

    @Test fun realServiceRequestsReachTheConnectionObserverOncePerMilestone() {
        val service = Robolectric.buildService(CarPlayVpnService::class.java).create().get()
        val events = LinkedBlockingQueue<ConnectionMilestone>()
        val ended = CountDownLatch(1)
        val address = InetAddress.getByName("127.0.0.1")
        val config = AirPlayConfig("test", "02:00:00:00:00:02", "02:00:00:00:00:01", "1.0",
            AirPlayDisplayConfig(800, 480), port = 0)
        try {
            assertEquals(CarPlayVpnService.AttachResult.Started, service.attachWireless(address, config,
                AirPlayIdentity.generate(), PairingStore(), null,
                object : AirPlaySessionListener {
                    override fun onConnectionMilestone(session: AirPlaySession, milestone: ConnectionMilestone) {
                        events.add(milestone)
                    }
                    override fun onSessionEnded(session: AirPlaySession) { ended.countDown() }
                }, object : AirPlayMediaHandler {}))
            Socket(address, service.boundPort()!!).use { phone ->
                phone.getOutputStream().write(listOf("GET /info", "GET /info", "POST /pair-verify").mapIndexed { i, request ->
                    "$request RTSP/1.0\r\nCSeq: ${i + 1}\r\nContent-Length: 0\r\n\r\n"
                }.joinToString("").toByteArray())
                phone.shutdownOutput()
                assertTrue("The real control reader must finish", ended.await(5, TimeUnit.SECONDS))
                assertEquals(listOf(ConnectionMilestone.AIRPLAY_INFO, ConnectionMilestone.AIRPLAY_PAIR_VERIFY), events.toList())
            }
        } finally { service.detach() }
    }
}
