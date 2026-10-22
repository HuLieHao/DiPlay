package com.shilapi.xcertplay.airplay

import com.shilapi.xcertplay.ConnectionMilestone
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], manifest = Config.NONE)
class AirPlayConnectionProgressTest {
    @Test fun receivedProtocolRequestsReportProgressOnceWithoutClaimingSuccessfulVerification() {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            Socket(server.inetAddress, server.localPort).use { phone ->
                val events = Collections.synchronizedList(mutableListOf<ConnectionMilestone>())
                val ended = CountDownLatch(1)
                val session = AirPlaySession(
                    server.accept(),
                    AirPlayConfig("test", "02:00:00:00:00:02", "02:00:00:00:00:01", "1.0",
                        main = AirPlayDisplayConfig(800, 480)),
                    AirPlayIdentity.generate(), PairingStore(), null,
                    object : AirPlaySessionListener {
                        override fun onConnectionMilestone(session: AirPlaySession, milestone: ConnectionMilestone) {
                            events += milestone
                        }
                        override fun onSessionEnded(session: AirPlaySession) { ended.countDown() }
                    }, object : AirPlayMediaHandler {},
                )
                try {
                    session.start()
                    val requests = listOf("GET /info", "GET /info", "POST /pair-verify", "POST /pair-verify", "OPTIONS *")
                    phone.getOutputStream().write(requests.mapIndexed { i, request ->
                        "$request RTSP/1.0\r\nCSeq: ${i + 1}\r\nContent-Length: 0\r\n\r\n"
                    }.joinToString("").toByteArray())
                    phone.shutdownOutput()
                    assertTrue("Control reader must finish after peer EOF", ended.await(5, TimeUnit.SECONDS))
                    assertEquals(listOf(ConnectionMilestone.AIRPLAY_INFO, ConnectionMilestone.AIRPLAY_PAIR_VERIFY), events)
                    assertFalse(session.pairVerify.isVerified)
                    session.connectionMilestone(ConnectionMilestone.VIDEO_RECEIVED)
                    assertEquals(2, events.size)
                } finally { session.close() }
            }
        }
    }
}
