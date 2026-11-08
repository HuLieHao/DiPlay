package com.shilapi.xcertplay

import org.junit.Assert.*
import org.junit.Test

class CaptureLogQueueTest {
    @org.junit.Test fun sealPreservesPendingEntriesAndRejectsLateProducer() {
        val queue = CaptureLogQueue(100)
        assertTrue(queue.offer("before stop"))
        queue.seal()
        assertFalse(queue.offer("late producer"))
        assertEquals("before stop", queue.poll())
        assertNull(queue.poll())
    }

    @Test fun hugeEntriesCannotExhaustTheConnectionProcessHeap() {
        val queue = CaptureLogQueue(maxChars = 10)
        assertTrue(queue.offer("123456"))
        assertFalse(queue.offer("12345"))
        assertEquals(6, queue.pendingChars)
        assertEquals(1L, queue.dropped)
        assertEquals("123456", queue.poll())
        assertEquals(0, queue.pendingChars)
        assertTrue(queue.offer("1234567890"))
    }

    @Test fun failedWriterReleasesPendingEntriesAndRejectsFurtherLogs() {
        val queue = CaptureLogQueue(maxChars = 10)
        queue.offer("pending")
        queue.disable()
        assertEquals(0, queue.pendingChars)
        assertNull(queue.poll())
        assertFalse(queue.offer("next"))
        assertEquals(2L, queue.dropped)
    }
}
