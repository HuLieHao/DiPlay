package com.shilapi.xcertplay.media

import org.junit.Assert.*
import org.junit.Test
import com.shilapi.xcertplay.diagnostics.AudioCaptureEvents

class AudioEvidenceTest {
    @Test fun correlationUsesOutputTimestampAndBoundsMetadata() {
        val evidence = AudioEvidence()
        val a = evidence.packet(1, 10, 100)
        val b = evidence.packet(2, 20, 200)
        evidence.dequeued(a, 200)
        evidence.input(10, a, 300)
        evidence.dequeued(b, 350)
        evidence.input(20, b, 400)
        val output = evidence.output(10, 500)
        assertEquals(a.copy(inputNs = 300, outputNs = 500), output)
        evidence.decoded(output, 500)
        evidence.wrote(80, output, 600, 680, 100)
        assertEquals(150L, evidence.maxQueueNs)
        assertEquals(100L, evidence.maxInputWaitNs)
        assertEquals(200L, evidence.maxDecoderNs)
        assertTrue(evidence.summary().contains("sequence=1 sample=10"))
        assertTrue(evidence.summary().contains("writeStartNs=600 writeEndNs=680"))
        assertNull(evidence.output(99))
        repeat(600) { evidence.input(it.toLong(), a, 500) }
        assertTrue(evidence.pending <= 256)
    }
    @Test fun duplicateTimestampIsNotGuessed() {
        val evidence = AudioEvidence()
        evidence.input(10, evidence.packet(1, 10, 100), 200)
        evidence.input(10, evidence.packet(2, 10, 110), 210)
        assertNull(evidence.output(10))
        evidence.input(10, evidence.packet(3, 10, 300), 400)
        repeat(3) { assertNull(evidence.output(10)) }
        evidence.input(10, evidence.packet(4, 10, 500), 600)
        assertNull(evidence.output(10))
    }
    @Test fun completedTimestampCannotIdentifyReusedPacket() {
        val evidence = AudioEvidence()
        evidence.input(10, evidence.packet(1, 1, 100), 200)
        assertEquals(1, evidence.output(10)?.sequence)
        evidence.input(10, evidence.packet(2, 2, 300), 400)
        assertNull(evidence.output(10))
    }
    @Test fun longUniqueStreamRetainsCorrelationAndRejectsBackwardPts() {
        val evidence = AudioEvidence()
        repeat(2000) {
            evidence.input(it.toLong(), evidence.packet(it, it, 100), 200)
            assertEquals(it, evidence.output(it.toLong())?.sequence)
        }
        evidence.input(0, evidence.packet(999, 0, 300), 400)
        assertNull(evidence.output(0))
        evidence.input(2000, evidence.packet(2000, 0, 300), 400)
        assertEquals(2000, evidence.output(2000)?.sequence)
    }
    @Test fun windowPeaksResetWithOccurrenceTimesButLifetimeSurvives() {
        val evidence = AudioEvidence()
        val packet = evidence.packet(1, 1, 100)
        evidence.dequeued(packet, 300)
        evidence.input(1, packet, 500)
        evidence.decoded(evidence.output(1), 900)
        evidence.wrote(600, end = 1600)
        val first = evidence.windowSummary()
        assertTrue(first.contains("maxQueueNs=200 maxQueueAtNs=300"))
        assertTrue(first.contains("maxInputWaitNs=200 maxInputWaitAtNs=500"))
        assertTrue(first.contains("maxDecoderNs=400 maxDecoderAtNs=900"))
        assertTrue(first.contains("maxWriteNs=600 maxWriteAtNs=1600"))
        evidence.wrote(20, end = 2000)
        val second = evidence.windowSummary()
        assertTrue(second.contains("maxWriteNs=20 maxWriteAtNs=2000"))
        assertTrue(second.contains("queueSamples=0"))
        assertTrue(evidence.summary().contains("maxWriteNs=600"))
        assertTrue(evidence.windowSummary().contains("writeSamples=0"))
    }
    @Test fun evictedInputTimestampCannotBeReusedForDelayedOutput() {
        val evidence = AudioEvidence()
        repeat(257) { evidence.input(it.toLong(), evidence.packet(it, it, 100), 200) }
        evidence.input(0, evidence.packet(999, 0, 300), 400)
        assertNull(evidence.output(0))
        assertNull(evidence.output(0))
    }
    @Test fun diagnosticSinkFailuresAreIsolated() {
        AudioCaptureEvents.setSink { throw IllegalStateException("diagnostic failure") }
        try { AudioCaptureEvents.emit("UNDERRUN\trenderer-1\t100\t200\tcount=1") }
        finally { AudioCaptureEvents.setSink(null) }
    }
    @Test fun uniqueIdentityAndUnavailableTimestamp() {
        assertNotEquals(AudioEvidence().id, AudioEvidence().id)
        assertTrue(AudioEvidence.timestamp(false, 10, 20, 3).contains("available=false"))
    }
    @Test fun journalValidationAndBoundedLoss() {
        val base = java.nio.file.Files.createTempDirectory("audio-evidence").toFile()
        try {
            val app = java.io.File(base, "capture").apply { mkdir() }
            val root = java.io.File(base, "capture-root-0").apply { mkdir() }
            assertEquals(java.io.File(app, "audio-events.log").canonicalFile, AudioCaptureEvents.journal(root, app))
            assertTrue(AudioCaptureEvents.valid("UNDERRUN\trenderer-1\t100\t200\tobservation=periodic"))
            assertFalse(AudioCaptureEvents.valid("UNDERRUN\trenderer-1\tbad\t200\tbad"))
            java.nio.file.Files.createSymbolicLink(java.io.File(app, "audio-events.log").toPath(), java.io.File(base, "outside").toPath())
            try { AudioCaptureEvents.journal(root, app); fail("symlink escape") } catch (_: java.io.IOException) { }
            val pending = AudioCaptureEvents.Pending()
            repeat(600) { pending.offer("event$it") }
            assertEquals(512, pending.size())
            assertEquals(88L, pending.dropped)
            pending.seal()
            pending.offer("late")
            assertEquals(89L, pending.dropped)
            assertEquals(512, pending.drain().size)
        } finally { base.deleteRecursively() }
    }
    @Test fun urgentBeforeSlowAndCooldownPreserveEventTimes() {
        var now = 0L
        val snapshots = AudioCaptureEvents.Snapshots { now }
        snapshots.offer("UNDERRUN\trenderer-1\t100\t200\ta=1")
        assertTrue(snapshots.take()!!.startsWith("UNDERRUN"))
        snapshots.offer("REBUFFER_PAUSE\trenderer-1\t101\t201\ta=1")
        snapshots.offer("WRITE_ANOMALY\trenderer-1\t102\t202\ta=1")
        assertNull(snapshots.take())
        now = 10000
        val batch = snapshots.take()!!
        assertTrue(batch.startsWith("REBUFFER_PAUSE"))
        assertTrue(batch.contains("snapshotBatchFirstDetectedNs=201 snapshotBatchLastDetectedNs=202"))
        assertTrue(batch.contains("snapshotBatchFirstWallMs=101 snapshotBatchLastWallMs=102"))
        snapshots.offer("WRITE_ANOMALY\trenderer-1\t103\t203\ta=1")
        assertNotNull(snapshots.take()) // Immediate first ordinary snapshot, independent of urgent.
        repeat(59) {
            now += 1000
            snapshots.offer("WRITE_ANOMALY\trenderer-1\t104\t204\ta=1")
            assertNull(snapshots.take())
        }
        now += 1000
        assertTrue(snapshots.take()!!.contains("snapshotBatchCount=59"))
        snapshots.cancelledBeforeProbe()
        assertEquals(1L, snapshots.cancelledBeforeProbe)
        snapshots.offer("DECODER_DROP\trenderer-1\t104\t204\ta=1")
        assertEquals(1L, snapshots.suppressed)
    }
    @Test fun allEventsSurviveBeyondFiveAndSnapshotBurstCoalesces() {
        val pending = AudioCaptureEvents.Pending()
        repeat(20) { pending.offer("UNDERRUN\trenderer-1\t100\t200\tcount=$it") }
        assertEquals(20, pending.drain().size)
        var now = 0L
        val snapshots = AudioCaptureEvents.Snapshots { now }
        fun event(kind: String) = "$kind\trenderer-1\t100\t200\tcount=1"
        repeat(20) { snapshots.offer(event("WRITE_ANOMALY")) }
        assertTrue(snapshots.take()!!.contains("snapshotBatchCount=20"))
        repeat(10) { now += 1000; snapshots.offer(event("WRITE_ANOMALY")); assertNull(snapshots.take()) }
        snapshots.offer(event("UNDERRUN"))
        assertTrue(snapshots.take()!!.startsWith("UNDERRUN"))
        snapshots.offer(event("WRITE_ANOMALY"))
        snapshots.offer(event("REBUFFER"))
        assertNull(snapshots.take())
        now += 10000
        val urgent = snapshots.take()!!
        assertTrue(urgent.startsWith("REBUFFER"))
        assertTrue(urgent.contains("snapshotBatchTypes={REBUFFER=1, WRITE_ANOMALY=1}"))
        snapshots.offer(event("WRITE_ANOMALY"))
        now = 60000
        assertNotNull(snapshots.take())
        snapshots.offer(event("UNDERRUN"))
        snapshots.seal()
        assertEquals(1L, snapshots.discarded)
        snapshots.offer(event("UNDERRUN"))
        assertNull(snapshots.take())
        assertEquals(1L, snapshots.dropped)
    }
}
