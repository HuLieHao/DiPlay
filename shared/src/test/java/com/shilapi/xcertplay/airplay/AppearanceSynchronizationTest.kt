package com.shilapi.xcertplay.airplay

import java.io.ByteArrayOutputStream
import java.net.Socket
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class AppearanceSynchronizationTest {
    private fun session(listener: AirPlaySessionListener = object : AirPlaySessionListener {}) = AirPlaySession(Socket(),
        AirPlayConfig(deviceName = "test", deviceId = "02:00:00:00:00:02", btMac = "02:00:00:00:00:01",
            sourceVersion = "1", main = AirPlayDisplayConfig(widthPixels = 800, heightPixels = 480)),
        AirPlayIdentity.generate(), PairingStore(), null, listener, object : AirPlayMediaHandler {})

    private fun display(session: AirPlaySession): Map<*, *> {
        val request = RtspMessage.Request("GET", "/info", "RTSP/1.0", emptyMap(), byteArrayOf())
        val response = session.javaClass.getDeclaredMethod("handle", RtspMessage.Request::class.java)
            .apply { isAccessible = true }.invoke(session, request) as RtspMessage.Response
        val info = BplistCodec.decode(response.body) as Map<*, *>
        return (info["displays"] as List<*>).first() as Map<*, *>
    }

    @Test fun initialAppearanceUsesLatestDecisionBeforeEventChannelExists() {
        session().use { session ->
            assertFalse(session.setNightMode(true))
            assertEquals(1L, (display(session)["uiAppearanceMode"] as Number).toLong())
            assertEquals(1L, (display(session)["uiAppearanceSetting"] as Number).toLong())
            assertFalse(session.setNightMode(false))
            assertEquals(0L, (display(session)["uiAppearanceMode"] as Number).toLong())
        }
    }

    @Test fun newHandshakeReadsLiveHostDecisionRatherThanOldControllerStartState() {
        var night = true
        val listener = object : AirPlaySessionListener {
            override fun currentAppearanceNight() = night
        }
        session(listener).use { first ->
            assertEquals(1L, (display(first)["uiAppearanceMode"] as Number).toLong())
            night = false
            assertEquals(0L, (display(first)["uiAppearanceMode"] as Number).toLong())
        }
        night = true
        session(listener).use { second ->
            assertEquals(1L, (display(second)["uiAppearanceMode"] as Number).toLong())
        }
    }

    @Test fun partialDeliveryClosesSessionAndFreshSessionUsesCurrentHostDecision() {
        session().use { session ->
            var writes = 0
            val failing = object : Socket() {
                override fun getOutputStream() = object : java.io.OutputStream() {
                    override fun write(value: Int) {}
                    override fun write(bytes: ByteArray) {
                        if (++writes == 2) throw java.io.IOException("appearance write failed")
                    }
                }
            }
            for ((name, value) in listOf("eventSocket" to failing, "eventCipher" to ControlCipher(ByteArray(32), ByteArray(32)))) {
                session.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(session, value)
            }
            assertFalse(session.setNightMode(true))
            assertTrue("An event write failure ends the session", session.isClosed)
            val listener = object : AirPlaySessionListener {
                override fun currentAppearanceNight() = true
            }
            session(listener).use { replacement ->
                assertEquals(1L, (display(replacement)["uiAppearanceMode"] as Number).toLong())
            }
        }
    }

    @Test fun eventChannelFlushesOnlyLatestDecisionAndUpdatesWithoutReconnect() {
        session().use { session ->
            session.setNightMode(true)
            session.setNightMode(false)
            val output = ByteArrayOutputStream()
            val socket = object : Socket() { override fun getOutputStream() = output }
            for ((name, value) in listOf("eventSocket" to socket, "eventCipher" to ControlCipher(ByteArray(32), ByteArray(32)))) {
                session.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(session, value)
            }
            val flush = session.javaClass.getDeclaredMethod("sendPendingNightModeLocked").apply { isAccessible = true }
            assertEquals(true, flush.invoke(session))
            assertTrue(session.setNightMode(true))
            assertTrue(session.setNightMode(false))
            val plaintext = ControlCipher(ByteArray(32), ByteArray(32)).decrypt(output.toByteArray()).data
            val commands = RtspMessage.parseMessages(plaintext).messages.map { BplistCodec.decode(it.body) as Map<*, *> }
            assertEquals(listOf("setNightMode", "uiAppearanceUpdate", "setNightMode", "uiAppearanceUpdate", "setNightMode", "uiAppearanceUpdate"), commands.map { it["type"] })
            val modes = commands.filter { it["type"] == "uiAppearanceUpdate" }.map { it["params"] as Map<*, *> }
            assertEquals(listOf(0L, 1L, 0L), modes.map { (it["appearanceMode"] as Number).toLong() })
            modes.forEach {
                assertEquals("b7e6c5a0-1111-4000-8000-000000000001", it["uuid"])
                assertEquals(1L, (it["appearanceSetting"] as Number).toLong())
            }
            val size = output.size()
            assertEquals(true, flush.invoke(session))
            assertEquals("Successful delivery must clear the pending state", size, output.size())
        }
    }
}
