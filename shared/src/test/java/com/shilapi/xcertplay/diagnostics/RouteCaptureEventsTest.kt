package com.shilapi.xcertplay.diagnostics

import org.junit.Assert.*
import org.junit.Test

class RouteCaptureEventsTest {
    private fun event(kind: String, attempt: String = "a") =
        RouteCaptureEvents.Event(kind, attempt, "ap0", 123, 456)

    @Test fun sinkFailureNeverEscapesIntoConnectionCallback() {
        RouteCaptureEvents.setSink { throw IllegalStateException("writer unavailable") }
        try { RouteCaptureEvents.emit(event("FIRST_TCP_TIMEOUT")) }
        finally { RouteCaptureEvents.setSink(null) }
    }

    @Test fun retainedPeerContextResetsForNextAttemptAndPreservesBothFamilies() {
        val pending = RouteCaptureEvents.Pending()
        pending.offer(RouteCaptureEvents.Event("FIRST_TCP_ACCEPTED", "a", "ap0", 1, 1, "192.168.1.2"))
        pending.offer(RouteCaptureEvents.Event("FIRST_TCP_ACCEPTED", "a", "ap0", 2, 2, "fe80::2"))
        val context = RouteCaptureEvents.Context()
        context.observe(pending.drain())
        context.observe(emptyList()) // Periodic probe keeps this attempt's observed peers.
        assertEquals(listOf("192.168.1.2", "fe80::2"), context.peers())
        context.observe(listOf(event("FIRST_TCP_WAIT", "b")))
        assertTrue(context.peers().isEmpty())
    }

    @Test fun burstCoalescesButRetainsBothWaitAndTimeout() {
        val queue = RouteCaptureEvents.Pending()
        repeat(1000) { queue.offer(event("FIRST_TCP_WAIT")) }
        queue.offer(event("FIRST_TCP_TIMEOUT"))
        val batch = queue.drain()
        assertEquals(listOf("FIRST_TCP_WAIT", "FIRST_TCP_TIMEOUT"), batch.map { it.kind })
        assertEquals(999L, queue.coalesced)
        assertTrue(queue.drain().isEmpty())
    }

    @Test fun boundedQueueReportsLossAndSealingRejectsLateEvents() {
        val queue = RouteCaptureEvents.Pending()
        repeat(1000) { queue.offer(event("FIRST_TCP_WAIT", "a$it")) }
        assertEquals(64, queue.size())
        assertEquals(936L, queue.dropped)
        queue.seal()
        queue.offer(event("FIRST_TCP_TIMEOUT"))
        assertEquals(64, queue.drain().size)
        assertTrue(queue.drain().isEmpty())
    }

    @Test fun wireFormatPreservesOriginTimesAndUnknownTargetWithoutInjection() {
        val original = event("FIRST_TCP_WAIT", "attempt=2/run=4")
        val decoded = RouteCaptureEvents.Event.parse(original.encode())!!
        assertEquals(original.attempt, decoded.attempt)
        assertEquals(123L, decoded.wallMs)
        assertEquals(456L, decoded.elapsedMs)
        assertTrue(decoded.evidence().contains("peer=unknown"))
        assertNull(RouteCaptureEvents.Event.parse("arbitrary\tcommand"))
        assertThrows(IllegalArgumentException::class.java) { event("FIRST_TCP_WAIT", "bad\nvalue") }
    }
}
