package com.shilapi.xcertplay.airplay

import org.junit.Assert.*
import org.junit.Test

class AudioRtpDuplicateFilterTest {
    @Test fun suppressesOnlyAnAlreadyDeliveredIdenticalPacket() {
        val filter = AudioRtpDuplicateFilter()
        assertTrue(filter.accept(packet(58, 18_240), 0))
        assertFalse(filter.accept(packet(58, 18_240), 28_256_000))
        assertTrue(filter.accept(packet(57, 17_920), 29_000_000)) // Unseen late audio survives.
        assertTrue(filter.accept(packet(58, 18_240, 2), 30_000_000))
    }

    @Test fun sequenceAndTimestampWrapDoNotLoseAudio() {
        val filter = AudioRtpDuplicateFilter()
        assertTrue(filter.accept(packet(65535, -320), 0))
        assertTrue(filter.accept(packet(0, 0), 20_000_000))
        assertFalse(filter.accept(packet(65535, -320), 21_000_000))
    }

    @Test fun expiresHistoryAndBoundsMemoryWithoutDelayingDelivery() {
        val filter = AudioRtpDuplicateFilter()
        repeat(17) { assertTrue(filter.accept(packet(it, it * 320), it.toLong())) }
        assertTrue(filter.accept(packet(0, 0), 18)) // Evicted by packet count.
        assertTrue(filter.accept(packet(16, 5120), 200_000_016)) // Expired by time.
        filter.clear()
        assertTrue(filter.accept(packet(16, 5120), 200_000_017))
    }

    private fun packet(sequence: Int, sample: Int, payload: Int = 1): ByteArray =
        ByteArray(13).also {
            it[0] = 0x80.toByte(); it[1] = 101
            it[2] = (sequence ushr 8).toByte(); it[3] = sequence.toByte()
            repeat(4) { byte -> it[4 + byte] = (sample ushr (24 - byte * 8)).toByte() }
            it[12] = payload.toByte()
        }
}
