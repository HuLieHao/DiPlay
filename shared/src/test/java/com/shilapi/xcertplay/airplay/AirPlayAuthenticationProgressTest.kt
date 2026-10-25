package com.shilapi.xcertplay.airplay

import com.shilapi.xcertplay.mfi.MfiAuthenticator
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], manifest = Config.NONE)
class AirPlayAuthenticationProgressTest {
    @Test fun onlySuccessfulMfiResponseReportsAuthentication() {
        assertEquals(0, exchange(ByteArray(0), false))
        val request = byteArrayOf(1) + AirPlayCrypto.x25519Generate().publicKey
        assertEquals(1, exchange(request, false))
        assertEquals(0, exchange(request, true))
    }

    @Test fun unrelatedSuccessfulMethodCannotClaimAuthentication() {
        assertEquals(0, exchange(ByteArray(0), false, "SET_PARAMETER"))
    }

    private fun exchange(body: ByteArray, signingFails: Boolean, method: String = "POST"): Int {
        val callbacks = AtomicInteger()
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            Socket(server.inetAddress, server.localPort).use { phone ->
                phone.soTimeout = 5000
                val ended = CountDownLatch(1)
                val auth = object : MfiAuthenticator {
                    override fun protocolMajor() = 2
                    override fun readCertificate(maximumOutputLength: Int) = byteArrayOf(1, 2)
                    override fun signChallenge(challenge: ByteArray): ByteArray {
                        check(!signingFails) { "test signer failure" }
                        return byteArrayOf(3, 4)
                    }
                }
                val session = AirPlaySession(server.accept(),
                    AirPlayConfig("test", "02:00:00:00:00:02", "02:00:00:00:00:01", "1.0",
                        main = AirPlayDisplayConfig(800, 480)),
                    AirPlayIdentity.generate(), PairingStore(), auth,
                    object : AirPlaySessionListener {
                        override fun onAuthenticationResponseSent(session: AirPlaySession) { callbacks.incrementAndGet() }
                        override fun onSessionEnded(session: AirPlaySession) { ended.countDown() }
                    }, object : AirPlayMediaHandler {})
                try {
                    session.start()
                    phone.getOutputStream().write(
                        "$method /auth-setup RTSP/1.0\r\nCSeq: 1\r\nContent-Length: ${body.size}\r\n\r\n".toByteArray() + body)
                    phone.shutdownOutput()
                    val reply = phone.getInputStream().readBytes().toString(Charsets.ISO_8859_1)
                    assertTrue(reply.startsWith("RTSP/1.0 " + when {
                        method == "SET_PARAMETER" -> "200"
                        signingFails -> "500"
                        body.isEmpty() -> "400"
                        else -> "200"
                    }))
                    assertTrue(ended.await(5, TimeUnit.SECONDS))
                } finally { session.close() }
            }
        }
        return callbacks.get()
    }
}
