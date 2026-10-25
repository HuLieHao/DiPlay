package com.shilapi.xcertplay.airplay

import org.junit.Assert.assertEquals
import org.junit.Test

class ControlHexTest {
    @Test fun emptyPayload() { assertEquals("", ByteArray(0).controlHex()) }
    @Test fun allUnsignedBytesAndLargePayloadPreserved() {
        val bytes = ByteArray(37518) { it.toByte() }
        val expected = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
        assertEquals(expected, bytes.controlHex())
        assertEquals("000f107f80ff", byteArrayOf(0, 15, 16, 127, -128, -1).controlHex())
    }
}
