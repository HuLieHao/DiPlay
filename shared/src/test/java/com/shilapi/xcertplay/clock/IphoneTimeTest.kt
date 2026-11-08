package com.shilapi.xcertplay.clock

import com.shilapi.xcertplay.iap2.body.Iap2BodyReader
import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import com.shilapi.xcertplay.iap2.wire.Iap2Parameter
import com.shilapi.xcertplay.iap2.wire.Iap2ParameterList
import com.shilapi.xcertplay.iap2.wire.Iap2WireCodec
import com.shilapi.xcertplay.transport.Iap2IdentificationClient
import com.shilapi.xcertplay.transport.Iap2IdentificationConfig
import org.junit.Assert.*
import org.junit.Test

class IphoneTimeTest {
    private fun frame(seconds: Long = 1_762_923_477, zone: Int = 420, dst: Int = 0) = Iap2Frame(
        0x4e0b, Iap2ParameterList.of(
            Iap2Parameter(0, ByteArray(8) { (seconds ushr (56 - it * 8)).toByte() }),
            Iap2Parameter(1, byteArrayOf((zone shr 8).toByte(), zone.toByte())),
            Iap2Parameter(2, byteArrayOf(dst.toByte())),
        ).encode(),
    )
    @Test fun decodesPhoneUtcWithoutAddingTimezoneOrAppleEpoch() {
        val time = IphoneTimeUpdate.decode(frame())
        assertEquals(1_762_923_477_000, time.unixMillis)
        assertEquals(420, time.offsetMinutes)
    }
    @Test fun supportsNegativeTimezoneAndDaylightSaving() {
        assertEquals(-240, IphoneTimeUpdate.decode(frame(zone = -300, dst = 60)).offsetMinutes)
    }
    @Test fun rejectsWrongEpochUnsignedOverflowBadZoneAndTruncatedFrame() {
        for (bad in listOf(frame(seconds = 800_000_000), frame(seconds = -1),
            frame(seconds = Long.MAX_VALUE), frame(zone = 2000), frame(dst = 127),
            Iap2Frame(0x4e0b, byteArrayOf(0)), Iap2Frame(0x4e0a, frame().payload))) {
            assertThrows(Exception::class.java) { IphoneTimeUpdate.decode(bad) }
        }
    }
    @Test fun rejectsDuplicateTimeRatherThanChoosingAnAmbiguousValue() {
        val fields = Iap2ParameterList.of(Iap2Parameter(0, Iap2WireCodec.u64(1_762_923_477)),
            Iap2Parameter(0, Iap2WireCodec.u64(1_762_923_478)))
        assertThrows(Exception::class.java) { IphoneTimeUpdate.decode(Iap2Frame(0x4e0b, fields.encode())) }
    }
    @Test fun anchorTracksElapsedTimeRegardlessOfBrokenSystemDateAndExpires() {
        val anchor = PhoneClockAnchor(IphoneTimeUpdate.decode(frame()), 5_000)
        assertEquals(1_762_923_479_000, anchor.at(7_000)?.unixMillis)
        assertNull(anchor.at(4_999))
        assertNull(anchor.at(5_000 + 86_400_001))
    }
    @Test fun identificationDeclaresTimeNotification() {
        val fields = Iap2BodyReader.of(Iap2IdentificationClient.identificationInformation(Iap2IdentificationConfig("BuickPlay", "test", "test", "test", "1", "1", 0)))
        assertTrue(fields.u16List(7).contains(0x4e0b))
    }
}
