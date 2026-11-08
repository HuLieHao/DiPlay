package com.shilapi.xcertplay.diagnostics

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class RouteCaptureWorkerTest {
    @Test fun blockedServiceSnapshotsCannotDelayEventWorkerAndStopRejectsPendingWork() {
        val serviceRelease = CountDownLatch(1)
        val service = Thread { serviceRelease.await() }.apply { start() }
        val stopped = AtomicBoolean(false)
        val probed = CountDownLatch(1)
        val pending = RouteCaptureEvents.Pending()
        pending.offer(RouteCaptureEvents.Event("FIRST_TCP_WAIT", "a", "ap0", 1, 1))
        val worker = Thread(RouteCaptureWorker(
            { pending.drain() }, { probed.countDown(); stopped.set(true) },
            { stopped.get() }, { 1L }, { Thread.sleep(1) },
        ))
        try {
            worker.start()
            assertTrue(probed.await(2, TimeUnit.SECONDS))
            assertTrue("slow snapshot remains blocked while route event completes", service.isAlive)
            worker.join(2000)
            assertFalse(worker.isAlive)
            pending.offer(RouteCaptureEvents.Event("FIRST_TCP_TIMEOUT", "a", "ap0", 2, 2))
            RouteCaptureWorker({ pending.drain() }, { fail("probe after stop") }, { true }, { 2L }, {}).run()
            assertEquals(1, pending.size())
        } finally { stopped.set(true); serviceRelease.countDown(); service.join(2000); worker.join(2000) }
    }

    @Test fun stopDuringPollingPreventsLaunchingCommands() {
        var stopped = false
        RouteCaptureWorker({ stopped = true; listOf(RouteCaptureEvents.Event("FIRST_TCP_WAIT", "a", "ap0", 1, 1)) },
            { fail("must not start probe after cancellation") }, { stopped }, { 1L }, {}).run()
    }

    @Test fun periodicProbeAndBurstUseOneSerializedWorker() {
        var now = 0L
        var count = 0
        val batches = mutableListOf<Int>()
        val pending = RouteCaptureEvents.Pending()
        RouteCaptureWorker({ pending.drain() }, { batches.add(it.size); count++ }, { count == 2 }, { now }, {
            now += 30000
            if (count == 1) {
                repeat(100) { pending.offer(RouteCaptureEvents.Event("FIRST_TCP_WAIT", "a", "ap0", now, now)) }
                pending.offer(RouteCaptureEvents.Event("FIRST_TCP_TIMEOUT", "a", "ap0", now, now))
            }
        }).run()
        assertEquals(listOf(0, 2), batches)
    }
}
