package com.shilapi.xcertplay.airplay

import java.net.Socket
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class NightModeResponseTest {
    @Test fun readerExitClosesSocketBeforeWaitingForBlockedThemeWriter() {
        val logs = java.util.Collections.synchronizedList(mutableListOf<String>())
        val session = AirPlaySession(Socket(),
            AirPlayConfig(deviceName = "test", deviceId = "02:00:00:00:00:02", btMac = "02:00:00:00:00:01",
                sourceVersion = "1", main = AirPlayDisplayConfig(widthPixels = 800, heightPixels = 480)),
            AirPlayIdentity.generate(), PairingStore(), null,
            object : AirPlaySessionListener { override fun onDebugLog(message: String) { logs += message } },
            object : AirPlayMediaHandler {})
        val writing = java.util.concurrent.CountDownLatch(1)
        val socketClosed = java.util.concurrent.CountDownLatch(1)
        val event = object : Socket() {
            override fun getInputStream() = ByteArrayInputStream(byteArrayOf())
            override fun getOutputStream() = object : java.io.OutputStream() {
                override fun write(value: Int) {
                    writing.countDown()
                    socketClosed.await()
                    throw java.io.IOException("socket closed")
                }
            }
            override fun close() { socketClosed.countDown() }
        }
        for ((name, value) in listOf("eventSocket" to event, "eventCipher" to ControlCipher(ByteArray(32), ByteArray(32)))) {
            session.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(session, value)
        }
        val writer = Thread { session.setNightMode(true) }.apply { isDaemon = true }
        val reader = Thread {
            session.javaClass.getDeclaredMethod("runEventRead", Socket::class.java).apply { isAccessible = true }.invoke(session, event)
        }.apply { isDaemon = true }
        try {
            writer.start()
            assertTrue(writing.await(2, java.util.concurrent.TimeUnit.SECONDS))
            reader.start()
            assertTrue("read EOF must close socket without waiting for eventWriteLock", socketClosed.await(2, java.util.concurrent.TimeUnit.SECONDS))
        } finally {
            event.close(); writer.join(2000); reader.join(2000); session.close()
        }
    }

    @Test fun recordsRejectedNightCommandResponseInsteadOfSilentlyDroppingIt() {
        val logs = mutableListOf<String>()
        val session = AirPlaySession(Socket(),
            AirPlayConfig(deviceName = "test", deviceId = "02:00:00:00:00:02", btMac = "02:00:00:00:00:01",
                sourceVersion = "1", main = AirPlayDisplayConfig(widthPixels = 800, heightPixels = 480)),
            AirPlayIdentity.generate(), PairingStore(), null,
            object : AirPlaySessionListener { override fun onDebugLog(message: String) { logs += message } },
            object : AirPlayMediaHandler {})
        val response = ("RTSP/1.0 400 Bad Request\r\nCSeq: 1\r\nContent-Length: 0\r\n\r\n" +
            "RTSP/1.0 200 OK\r\nCSeq: 2\r\nContent-Length: 0\r\n\r\n").toByteArray()
        val output = ByteArrayOutputStream()
        val event = object : Socket() {
            override fun getOutputStream() = output
            override fun getInputStream() = ByteArrayInputStream(ControlCipher(ByteArray(32), ByteArray(32)).encrypt(response))
        }
        try {
            for ((name, value) in listOf("eventSocket" to event, "eventCipher" to ControlCipher(ByteArray(32), ByteArray(32)))) {
                session.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(session, value)
            }
            assertTrue(session.setNightMode(true))
            assertTrue(logs.toString(), logs.any { it.contains("THEME_DELIVERY") && it.contains("written=true") && it.contains("actualAppearance=unknown") })
            session.javaClass.getDeclaredMethod("runEventRead", Socket::class.java).apply { isAccessible = true }.invoke(session, event)
            assertTrue(logs.toString(), logs.any { it.contains("THEME_RESPONSE") && it.contains("night=true") && it.contains("status=400") })
            assertTrue(logs.toString(), logs.any { it.contains("THEME_RESPONSE") && it.contains("type=uiAppearanceUpdate") && it.contains("status=200") })
            assertTrue(logs.toString(), logs.any { it.contains("THEME_RESPONSE") && it.contains("responseAfterMs=") && it.contains("actualAppearance=unknown") })
        } finally { session.close() }
    }
}
