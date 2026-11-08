package com.shilapi.xcertplay

import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class AsyncDiagnosticLogTest {
    @Test fun startupBurstSurvivesTemporaryArchiveWriteDelay() {
        val folder = Files.createTempDirectory("startup-burst").toFile()
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val target = SessionLogFile(folder.resolve("diplay.log"), "startup", lazy {
            entered.countDown()
            check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
            "versionCode=test"
        })
        try {
            AsyncDiagnosticLog.append(target, "first")
            assertTrue(entered.await(2, java.util.concurrent.TimeUnit.SECONDS))
            repeat(300) { AsyncDiagnosticLog.append(target, "burst-entry-$it-END") }
            release.countDown()
            assertTrue(AsyncDiagnosticLog.awaitIdle(5000))
            val text = folder.resolve("diplay.log").readText()
            repeat(300) { assertTrue("missing burst entry $it", text.contains("burst-entry-$it-END")) }
        } finally {
            release.countDown()
            AsyncDiagnosticLog.awaitIdle(5000)
            folder.deleteRecursively()
        }
    }

    @Test fun admittingConnectionTargetsDoesNoDiskOrBuildProbeAndRetainsRecordingBuild() {
        val folder = Files.createTempDirectory("lazy-run-target").toFile()
        try {
            var buildReads = 0
            val root = SessionLogFile(folder.resolve("diplay.log"), "kind=host_setup", lazy {
                buildReads++; "versionCode=47 sourceRevision=recording-revision"
            })
            val first = root.connectionRun(1)
            val second = first.connectionRun(2)
            assertEquals(0, buildReads)
            assertTrue(folder.listFiles()!!.isEmpty())
            AsyncDiagnosticLog.append(first, "old original")
            AsyncDiagnosticLog.append(second, "new original")
            assertTrue(AsyncDiagnosticLog.awaitIdle(2000))
            assertEquals(1, buildReads)
            val metadata = folder.resolve("archive").listFiles()!!.filter { it.extension == "meta" }
            assertEquals(2, metadata.size)
            assertTrue(metadata.all { it.readText().contains("versionCode=47 sourceRevision=recording-revision") })
            val parents = metadata.map { Regex("startup=([^ ]+)").find(it.readText())!!.groupValues[1] }
            assertEquals(1, parents.distinct().size)
        } finally { folder.deleteRecursively() }
    }

    @Test fun orderlyCloseDrainsItsQueuedTailBeforeClosingFile() {
        val folder = Files.createTempDirectory("close-tail").toFile()
        val target = SessionLogFile(folder.resolve("diplay.log"))
        try {
            target.reset("session")
            AsyncDiagnosticLog.append(target, "last event")
            AsyncDiagnosticLog.close(target)
            assertTrue(AsyncDiagnosticLog.awaitIdle(2000))
            assertTrue(target.file.readText().contains("last event"))
            target.append("late event")
            assertFalse(target.file.readText().contains("late event"))
        } finally { target.close(); folder.deleteRecursively() }
    }
    @Test fun legacyWriterAlsoRetainsIndependentJourneyArchives() {
        val folder = Files.createTempDirectory("journeys").toFile()
        try {
            SessionLogFile(folder.resolve("diplay.log")).use { it.reset("outbound"); it.append("outbound original") }
            SessionLogFile(folder.resolve("diplay.log")).use { it.reset("return"); it.append("return original") }
            val archived = folder.resolve("archive").walkTopDown().filter { it.extension == "log" }.map { it.readText() }.toList()
            assertEquals(2, archived.size)
            assertTrue(archived.any { it.contains("outbound original") })
            assertTrue(archived.any { it.contains("return original") })
        } finally { folder.deleteRecursively() }
    }
    @Test fun queuedEvidenceRetainsNetworkAddressAndTimestampBeforeUsingItsCapturedFileTarget() {
        val folder = Files.createTempDirectory("diplay-async-log").toFile()
        val old = SessionLogFile(folder.resolve("old.log"))
        val current = SessionLogFile(folder.resolve("current.log"))
        try {
            old.reset("old session"); current.reset("current session")
            AsyncDiagnosticLog.append(old, "CONNECTION_DIAGNOSTIC attempt=1 teardown end elapsedMs=117", 0)
            AsyncDiagnosticLog.append(old, "Microphone: start type=telephony codec=OPUS peer=192.168.49.1", 0)
            AsyncDiagnosticLog.append(old, "CONNECTION_DIAGNOSTIC token=private-token", 0)
            AsyncDiagnosticLog.append(old, "Microphone: payload=private-audio", 0)
            assertTrue(AsyncDiagnosticLog.awaitIdle(2_000))
            val evidence = old.file.readText()
            assertTrue(evidence.contains("teardown end elapsedMs=117"))
            assertTrue(evidence.contains("Microphone: start type=telephony codec=OPUS peer=192.168.49.1"))
            assertTrue(evidence.contains("192.168.49.1"))
            assertTrue(evidence.contains("private-token")); assertTrue(evidence.contains("private-audio"))
            assertFalse(current.file.readText().contains("teardown end"))
            assertEquals(6, evidence.lineSequence().count())
        } finally {
            old.close(); current.close(); folder.deleteRecursively()
        }
    }
}
