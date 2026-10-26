package com.shilapi.xcertplay

import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class AsyncDiagnosticLogTest {
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
