package com.shilapi.xcertplay.clock

import org.junit.Assert.*
import org.junit.Test

class PhoneTimeSyncGateTest {
    @Test fun throttlesAttemptsUsingMonotonicTimeEvenWhenSystemDateIsWrong() {
        val gate = PhoneTimeSyncGate()
        assertTrue(gate.claim(100))
        assertFalse(gate.claim(100))
        assertFalse(gate.claim(60_099))
        assertTrue(gate.claim(60_100))
    }
    @Test fun vendorRootIsRestrictedToTheVerifiedCarModelAndHardware() {
        assertTrue(PhoneTimeSyncGate.vendorSupported("FF-5000", "ac8227l", 27))
        assertFalse(PhoneTimeSyncGate.vendorSupported("Redmi", "ac8227l", 27))
        assertFalse(PhoneTimeSyncGate.vendorSupported("FF-5000", "qcom", 27))
        assertFalse(PhoneTimeSyncGate.vendorSupported("FF-5000", "ac8227l", 35))
    }
    @Test fun rootCommandsOnlyContainInternallyFormattedUtcAndRtcSync() {
        val command = PhoneTimeSyncGate.rootScript(1_762_923_477_000)
        assertEquals("exec /system/bin/service call alarm 2 i64 1762923477000\n", command)
        assertThrows(IllegalArgumentException::class.java) { PhoneTimeSyncGate.rootScript(-1) }
    }
}
