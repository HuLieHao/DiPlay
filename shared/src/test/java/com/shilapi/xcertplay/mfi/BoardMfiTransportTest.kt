package com.shilapi.xcertplay.mfi

import com.shilapi.xcertplay.transport.I2cTransport
import com.shilapi.xcertplay.transport.I2cTransportException
import java.io.InterruptedIOException
import org.junit.Assert.*
import org.junit.Test

class BoardMfiTransportTest {
    private class TimingBus : I2cTransport {
        var waited = false
        var register = -1
        var failSelect = false
        var reads = 0
        val selections = mutableListOf<Int>()
        override fun transaction(address7Bit: Int, writeData: ByteArray, readLength: Int): ByteArray {
            if (writeData.isNotEmpty()) {
                if (failSelect) throw I2cTransportException.DeviceUnavailable("errno=6")
                if (writeData.size == 1) {
                    register = writeData[0].toInt() and 255
                    selections += register
                    waited = false
                }
                return byteArrayOf()
            }
            reads++
            if (!waited) throw I2cTransportException.Nack("register not ready")
            return when (register) {
                0 -> byteArrayOf(5)
                2 -> byteArrayOf(2)
                0x30 -> byteArrayOf(1, 44)
                0x31, 0x32, 0x33 -> ByteArray(readLength) { 42 }
                0x10 -> byteArrayOf(0x10)
                0x11 -> byteArrayOf(0, 128.toByte())
                0x12 -> ByteArray(readLength) { 17 }
                else -> error("unexpected register")
            }
        }
    }

    @Test fun discoveryCertificateAndSignatureWaitAfterEverySuccessfulSelect() {
        val bus = TimingBus()
        var waits = 0
        val transport = BoardMfiTransport(bus, waitForRead = { millis ->
            assertEquals(5L, millis)
            bus.waited = true
            waits++
        })
        assertEquals(0x10, MfiDeviceScanner(transport).scan().chip?.address7Bit)
        val client = MfiAuthenticationClient(transport, 0x10)
        assertEquals(2, client.protocolMajor())
        assertArrayEquals(ByteArray(300) { 42 }, client.readCertificate())
        assertArrayEquals(ByteArray(128) { 17 }, client.signChallenge(ByteArray(20)))
        assertEquals(listOf(0, 2, 0x30, 0x31, 0x32, 0x33, 0x10, 0x11, 0x12), bus.selections)
        assertEquals(bus.reads, waits)
    }

    @Test fun failedSelectionDoesNotWaitAndRetryReselects() {
        val bus = TimingBus().apply { failSelect = true }
        var waits = 0
        val transport = BoardMfiTransport(bus, waitForRead = { waits++; bus.waited = true })
        try { transport.transaction(0x10, byteArrayOf(2), 0); fail("expected failure") }
        catch (_: I2cTransportException.DeviceUnavailable) { }
        assertEquals(0, waits)
        bus.failSelect = false
        transport.transaction(0x10, byteArrayOf(2), 0)
        assertArrayEquals(byteArrayOf(2), transport.transaction(0x10, byteArrayOf(), 1))
        assertEquals(1, waits)
    }

    @Test fun interruptedWaitStopsBeforeReadAndPreservesInterrupt() {
        val bus = TimingBus()
        val transport = BoardMfiTransport(bus, waitForRead = { throw InterruptedException() })
        try {
            try { MfiAuthenticationClient(transport, 0x10).protocolMajor(); fail("expected interruption") }
            catch (_: InterruptedIOException) { assertTrue(Thread.currentThread().isInterrupted) }
            assertEquals(0, bus.reads)
        } finally { Thread.interrupted() }
    }

    @Test fun diagnosticsIdentifyReadStageWithoutPayload() {
        val logs = mutableListOf<String>()
        val bus = TimingBus()
        val transport = BoardMfiTransport(bus, diagnostic = logs::add, waitForRead = {})
        transport.transaction(0x10, byteArrayOf(0x30), 0)
        try { transport.transaction(0x10, byteArrayOf(), 2); fail("expected NACK") }
        catch (_: I2cTransportException.Nack) { }
        assertTrue(logs.single().contains("stage=read"))
        assertTrue(logs.single().contains("register=0x30"))
        assertTrue(logs.single().contains("address=0x10"))
        assertTrue(logs.single().contains("readBytes=2"))
        bus.waited = true
        transport.transaction(0x10, byteArrayOf(), 2)
        assertTrue(logs.last().contains("recovered"))
    }

    @Test fun policyOnlySelectsVerifiedModelAndBus() {
        assertTrue(BoardMfiTransport.isSupported("FF_5000", "/dev/i2c-2"))
        assertTrue(BoardMfiTransport.isSupported("FF-5000", "/dev/i2c-2"))
        assertFalse(BoardMfiTransport.isSupported("other", "/dev/i2c-2"))
        assertFalse(BoardMfiTransport.isSupported("FF_5000", "/dev/i2c-1"))
    }
}
