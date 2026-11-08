package com.shilapi.xcertplay.transport

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class Iap2LinkEvidenceTest {
    @Test fun cumulativeAckAndWrapRetainPacketIdentityAndCsmFragments() {
        val logs = mutableListOf<String>()
        val engine = ready(logs::add)
        repeat(156) { i ->
            engine.sendSession(10, byteArrayOf(1), i.toLong() + 2, "csm_frame=7 message=0x4301 fragment=${i + 1}/156")
            engine.flushOutput { assertTrue(it.isNotEmpty()) }
            if (i % 2 == 1) engine.feed(packet(0x40, (100 + i) and 255), i.toLong() + 3)
        }
        assertEquals(156, logs.count { "event=peer_link_ack " in it })
        assertEquals(156, logs.count { "event=stream_write_complete " in it })
        assertTrue(logs.any { "event=peer_link_ack " in it && "seq=255 " in it && "fragment=156/156" in it })
        engine.sendSession(10, byteArrayOf(2), 200, "csm_frame=8 message=0x4301 fragment=1/1")
        engine.flushOutput { }
        engine.advanceTime(2200)
        engine.flushOutput { }
        engine.feed(packet(0x40, 0), 2201)
        assertTrue(logs.any { "event=peer_link_ack " in it && "packet=157 " in it && "seq=0 " in it })
        assertTrue(logs.any { "retry=1" in it && "csm_frame=8" in it })
    }

    @Test fun unrelatedAckDoesNotBecomeReceiptEvidenceThroughCleanupHeuristic() {
        val logs = mutableListOf<String>()
        val engine = ready(logs::add)
        engine.sendControl(byteArrayOf(1), 2)
        engine.flushOutput { }
        engine.feed(packet(0x40, 200), 3)
        assertTrue(logs.any { "event=rx_link_ack ack=200" in it })
        assertFalse(logs.any { "event=peer_link_ack " in it })
    }

    @Test fun actualCsmChannelCarriesIdentityAcrossTinyLinkFragments() {
        val logs = java.util.concurrent.CopyOnWriteArrayList<String>()
        val done = java.util.concurrent.CountDownLatch(1)
        val sync = Iap2LinkEngine.SynchronizationPayload(8, 12, 2000, 500, 4, 3, emptyList())
        val responses = java.util.concurrent.LinkedBlockingQueue<ByteArray>().apply { add(packet(0xc0, 99, sync.encode())) }
        val stream = object : BlockingDuplexByteStream {
            override fun send(data: ByteArray) {
                // Stream API accepts a complete buffer; the underlying transport may split it.
            }
            override fun recv(maxBytes: Int, timeoutMillis: Long): ByteArray? = responses.poll(timeoutMillis, java.util.concurrent.TimeUnit.MILLISECONDS)
            override fun close() { }
        }
        val channel = Iap2CsmChannel.open(stream, onDiagnostic = {
            logs.add(it)
            if ("event=stream_write_complete " in it && "fragment=4/4" in it) done.countDown()
            throw AssertionError("diagnostic sink")
        })
        try {
            assertTrue(channel.awaitReady(1000))
            channel.send(com.shilapi.xcertplay.iap2.wire.Iap2Frame(0x4301, byteArrayOf(3, 4)))
            assertTrue(done.await(2, java.util.concurrent.TimeUnit.SECONDS))
            for (fragment in 1..4) {
                for (event in listOf("command_queued", "engine_queued", "stream_write_complete")) {
                    assertTrue(logs.any { "event=$event " in it && "csm_frame=1 message=0x4301 fragment=$fragment/4" in it })
                }
            }
        } finally { channel.close() }
    }

    @Test fun noRetryModeDoesNotInventPeerAcksAndBoundsWriteMetadata() {
        val logs = mutableListOf<String>()
        val engine = ready(logs::add, maxRetries = 0)
        repeat(4100) { engine.sendControl(byteArrayOf(1), 2) }
        var size = 0
        engine.flushOutput { size = it.size }
        assertEquals(4100 * 11, size)
        assertEquals(1, logs.count { "event=write_metadata_truncated " in it })
        assertTrue(logs.count { "event=stream_write_complete " in it } <= 4097)
        engine.feed(packet(0x40, 103), 3)
        assertFalse(logs.any { "event=peer_link_ack " in it })
        assertEquals(Iap2LinkEngine.State.NORMAL, engine.state())
    }

    @Test fun extendedAckRetransmissionKeepsTheOriginalPacketIdentity() {
        val logs = mutableListOf<String>()
        val engine = ready(logs::add)
        engine.sendSession(10, byteArrayOf(1), 2, "csm_frame=1 message=0x4301 fragment=1/1")
        engine.flushOutput { }
        engine.feed(packet(0x60, 99, byteArrayOf(100)), 3)
        engine.flushOutput { }
        assertTrue(logs.any { "event=stream_write_complete " in it && "packet=1 seq=100" in it && "retry=1" in it && "message=0x4301" in it })
        assertFalse(logs.any { "event=peer_link_ack " in it })
    }

    @Test fun failedCoalescedWriteDoesNotClaimCompletion() {
        val logs = mutableListOf<String>()
        val engine = ready(logs::add)
        engine.sendControl(byteArrayOf(1), 2)
        engine.sendControl(byteArrayOf(2), 2)
        try { engine.flushOutput { throw IOException("secret payload") }; fail() } catch (_: IOException) { }
        assertEquals(2, logs.count { "event=stream_write_failed " in it })
        assertFalse(logs.any { "event=stream_write_complete " in it || "secret payload" in it })
    }

    @Test fun throwingDiagnosticSinkCannotBreakTransport() {
        val engine = ready({ throw AssertionError("sink") })
        engine.sendControl(byteArrayOf(1), 2)
        var sent = false
        engine.flushOutput { sent = true }
        engine.feed(packet(0x40, 100), 3)
        assertTrue(sent)
        assertEquals(Iap2LinkEngine.State.NORMAL, engine.state())
    }

    private fun ready(trace: (String) -> Unit, maxRetries: Int = 4): Iap2LinkEngine {
        val engine = Iap2LinkEngine(onDiagnostic = trace)
        engine.start(true, 0)
        engine.takeOutput()
        val sync = Iap2LinkEngine.SynchronizationPayload(8, 4096, 2000, 500, maxRetries, 3, emptyList())
        engine.feed(packet(0xc0, 99, sync.encode()), 1)
        engine.takeOutput()
        return engine
    }

    private fun packet(control: Int, ack: Int, payload: ByteArray? = null): ByteArray {
        val length = 9 + (payload?.size?.plus(1) ?: 0)
        val header = byteArrayOf(0xff.toByte(), 0x5a, (length ushr 8).toByte(), length.toByte(), control.toByte(), 1, ack.toByte(), 0, 0)
        header[8] = checksum(header.copyOf(8))
        return if (payload == null) header else header + payload + checksum(payload)
    }
    private fun checksum(bytes: ByteArray) = (-bytes.sumOf { it.toInt() and 255 }).toByte()
}
