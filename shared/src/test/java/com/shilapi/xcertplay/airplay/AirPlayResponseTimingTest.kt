package com.shilapi.xcertplay.airplay

import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], manifest = Config.NONE)
class AirPlayResponseTimingTest {
    @Test fun infoResponseRetainsPayloadAndSeparatesWriteFromFlushTiming() {
        val logs = CopyOnWriteArrayList<String>()
        val ended = CountDownLatch(1)
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            Socket(server.inetAddress, server.localPort).use { phone ->
                phone.soTimeout = 5000
                val session = AirPlaySession(server.accept(),
                    AirPlayConfig("test", "02:00:00:00:00:02", "02:00:00:00:00:01", "1.0",
                        main = AirPlayDisplayConfig(800, 480)),
                    AirPlayIdentity.generate(), PairingStore(), null,
                    object : AirPlaySessionListener {
                        override fun onDebugLog(message: String) { logs.add(message) }
                        override fun onSessionEnded(session: AirPlaySession) { ended.countDown() }
                    }, object : AirPlayMediaHandler {})
                try {
                    session.start()
                    phone.getOutputStream().write("GET /info RTSP/1.0\r\nCSeq: 42\r\n\r\n".toByteArray())
                    phone.shutdownOutput()
                    val response = phone.getInputStream().readBytes()
                    assertTrue(ended.await(5, TimeUnit.SECONDS))
                    val text = response.toString(Charsets.ISO_8859_1)
                    assertTrue(text.startsWith("RTSP/1.0 200"))
                    val body = response.copyOfRange(text.indexOf("\r\n\r\n") + 4, response.size)
                    val info = BplistCodec.decode(body) as Map<*, *>
                    assertEquals("test", info["name"])
                    val buffered = logs.single { it.contains("response-buffered cseq=42 ") }
                    for (field in listOf("handleMs", "encodeTraceMs", "encryptMs", "writeMs")) {
                        assertTrue("missing $field: $buffered", Regex("\\b$field=\\d+\\b").containsMatchIn(buffered))
                    }
                    val flushed = logs.single { it.contains("responses-flushed lastCseq=42 ") }
                    assertTrue(Regex("\\bflushMs=\\d+\\b").containsMatchIn(flushed))
                    assertTrue(logs.indexOf(buffered) < logs.indexOf(flushed))
                    val firstClose = logs.single { it.contains("airplay-first-close ") }
                    assertTrue(firstClose.contains("source=control"))
                    assertTrue(firstClose.contains("reason=peer EOF"))
                    session.close()
                    assertEquals(1, logs.count { it.contains("airplay-first-close ") })
                } finally { session.close() }
            }
        }
    }
}
