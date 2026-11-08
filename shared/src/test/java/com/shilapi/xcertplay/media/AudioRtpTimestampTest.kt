package com.shilapi.xcertplay.media

import com.shilapi.xcertplay.airplay.AudioStreamCodec
import org.junit.Assert.assertEquals
import org.junit.Test

class AudioRtpTimestampTest {
    @Test fun navigationOpusPtsUsesTheNegotiated16kClock() {
        val navigation = AudioStreamCodec.fromFormatBits(0x10000000, 101, "default")
        assertEquals(20_000L, audioRtpTimestampUs(320, navigation))
        assertEquals(40_000L, audioRtpTimestampUs(640, navigation))
    }
    @Test fun mediaPtsAndUnsignedTimestampKeepTheirTimebase() {
        val media = AudioStreamCodec.fromFormatBits(0x800000, 96)
        assertEquals(20_000L, audioRtpTimestampUs(960, media))
        assertEquals(4_294_967_295L * 1_000_000 / 48_000, audioRtpTimestampUs(-1, media))
    }
}
