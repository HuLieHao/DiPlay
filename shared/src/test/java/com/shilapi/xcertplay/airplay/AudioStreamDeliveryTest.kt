package com.shilapi.xcertplay.airplay

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class AudioStreamDeliveryTest {
    private val key = ByteArray(32) { it.toByte() }

    @Test fun navigationCapturesDuplicatesButDeliversEachOnceAndKeepsUnseenLateAudio() {
        val delivered = CopyOnWriteArrayList<Int>()
        val captured = CopyOnWriteArrayList<Int>()
        val end = CountDownLatch(1)
        val stream = AudioStream(key, 101)
        try {
            val port = stream.listen(object : AudioStream.Listener {
                override fun onPacket(wire: ByteArray, rtp: ByteArray?, sample: Int?, error: Throwable?) {
                    assertNull(error)
                    captured.add(sample!!)
                }
                override fun onRtp(rtp: ByteArray, sample: Int) {
                    delivered.add(sample)
                    if (sample == 960) end.countDown()
                }
            }).first
            DatagramSocket().use { sender ->
                listOf(1, 2, 1, 0, 3).forEach { sequence ->
                    val wire = seal(sequence)
                    sender.send(DatagramPacket(wire, wire.size, InetAddress.getByName("127.0.0.1"), port))
                }
            }
            assertTrue(end.await(3, TimeUnit.SECONDS))
            assertEquals(listOf(320, 640, 0, 960), delivered.toList())
            assertEquals(listOf(320, 640, 320, 0, 960), captured.toList())
        } finally { stream.close() }
        // A replacement stream owns new history, even with the same SSRC=0, seq and timestamp.
        val restarted = AudioStream(key, 101)
        val first = CountDownLatch(1)
        try {
            val port = restarted.listen(object : AudioStream.Listener {
                override fun onRtp(rtp: ByteArray, sample: Int) { first.countDown() }
            }).first
            DatagramSocket().use { sender ->
                val wire = seal(1)
                sender.send(DatagramPacket(wire, wire.size, InetAddress.getByName("127.0.0.1"), port))
            }
            assertTrue(first.await(3, TimeUnit.SECONDS))
        } finally { restarted.close() }
    }

    private fun seal(sequence: Int): ByteArray {
        val header = ByteArray(12).also {
            it[0] = 0x80.toByte(); it[1] = 101
            it[2] = (sequence ushr 8).toByte(); it[3] = sequence.toByte()
            val timestamp = sequence * 320
            repeat(4) { byte -> it[4 + byte] = (timestamp ushr (24 - byte * 8)).toByte() }
        }
        val shortNonce = ByteArray(8).also { it[0] = sequence.toByte() }
        val nonce = ByteArray(12).also { shortNonce.copyInto(it, 4) }
        return header + AirPlayCrypto.chachaSeal(key, nonce, byteArrayOf(1, 2, 3, 4), header.copyOfRange(4, 12)) + shortNonce
    }
}
