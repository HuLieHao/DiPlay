package com.shilapi.xcertplay

import java.nio.file.Files
import java.util.zip.ZipFile
import org.junit.Assert.*
import org.junit.Test

class DiagnosticArchiveTest {
    @Test fun captureRemainsAttachedToItsStartingSessionAcrossReconnect() {
        val root = Files.createTempDirectory("capture-reconnect").toFile()
        try {
            val archive = DiagnosticArchive(root)
            val outbound = archive.start("outbound")
            archive.start("return")
            archive.capture("read-only evidence", "capture", outbound)
            assertTrue(root.resolve("archive").listFiles()!!.single { it.extension == "network" }.name.startsWith(outbound))
        } finally { root.deleteRecursively() }
    }
    @Test fun detailBudgetAppliesBeforePartRotation() {
        val root = Files.createTempDirectory("strict-budget").toFile()
        try {
            val archive = DiagnosticArchive(root, detailBudget = 3000, partBytes = 10000, reserve = 0)
            val id = archive.start("trip")
            repeat(25) { archive.append(id, "original=" + "x".repeat(200)) }
            assertTrue(root.resolve("archive").listFiles()!!.filter { it.extension == "log" }.sumOf { it.length() } <= 3000)
        } finally { root.deleteRecursively() }
    }
    @Test fun connectionCloseSummaryContinuesAfterSummaryPartFills() {
        val root = Files.createTempDirectory("summary-parts").toFile()
        try {
            val archive = DiagnosticArchive(root)
            val id = archive.start("trip")
            repeat(40) { archive.append(id, "CONNECTION_DIAGNOSTIC stage=" + "x".repeat(8000)) }
            archive.append(id, "session_closed reason=user_requested")
            assertTrue(root.resolve("archive").listFiles()!!.filter { it.extension == "summary" }.any { it.readText().contains("reason=user_requested") })
        } finally { root.deleteRecursively() }
    }
    @Test fun noisyProtocolErrorFieldsDoNotCreateExceptionSnapshots() {
        val root = Files.createTempDirectory("false-errors").toFile()
        try {
            val archive = DiagnosticArchive(root)
            val id = archive.start("trip")
            archive.append(id, "TRACE payload={error:0, timeoutMs:1000}")
            archive.append(id, "receiveCounters rxErrorsDelta=0 readTimeouts=0")
            assertFalse(root.resolve("archive").listFiles()!!.any { it.extension == "exception" })
        } finally { root.deleteRecursively() }
    }
    @Test fun ageCleanupUsesRecordedSessionDateAndRetainsIndependentSummaries() {
        val root = Files.createTempDirectory("age-archive").toFile()
        try {
            var now = 1_800_000_000_000L
            val first = DiagnosticArchive(root, wall = { now })
            val id = first.start("outbound"); first.append(id, "CONNECTION_DIAGNOSTIC first_frame")
            now += 8 * 86_400_000L
            DiagnosticArchive(root, wall = { now }).start("return")
            assertFalse(root.resolve("archive").listFiles()!!.any { it.name.startsWith(id) && it.extension == "log" })
            assertTrue(root.resolve("archive/$id.summary").readText().contains("first_frame"))
            now += 7 * 86_400_000L
            DiagnosticArchive(root, wall = { now }).start("later")
            assertFalse(root.resolve("archive/$id.summary").exists())
        } finally { root.deleteRecursively() }
    }
    @Test fun lowSpaceAndExceptionRateLimitsAreExplicitAndBounded() {
        val root = Files.createTempDirectory("limits-archive").toFile()
        try {
            val low = DiagnosticArchive(root, reserve = Long.MAX_VALUE)
            val lowId = low.start("low space"); low.append(lowId, "evidence cannot fit")
            assertTrue(root.resolve("archive/maintenance.txt").readText().contains("detail_dropped low_space"))
            var mono = 0L
            val archive = DiagnosticArchive(root, monotonic = { mono }, reserve = 0)
            val id = archive.start("failures")
            repeat(20) { archive.append(id, "ERROR actual decoder failure") }
            assertEquals(1, root.resolve("archive").listFiles()!!.count { it.extension == "exception" })
            repeat(12) { mono += 60_001; archive.append(id, "ERROR decoder failure") }
            assertEquals(8, root.resolve("archive").listFiles()!!.count { it.extension == "exception" })
        } finally { root.deleteRecursively() }
    }
    @Test fun zipChecksumsDescribeExactOriginalBytesIncludingInterruptedTail() {
        val root = Files.createTempDirectory("hash-archive").toFile()
        try {
            val archive = DiagnosticArchive(root)
            val id = archive.start("trip"); archive.append(id, "original token=secret")
            val detail = root.resolve("archive").listFiles()!!.single { it.extension == "log" }
            detail.appendText("partial-unflushed-line")
            val zip = archive.export(root.resolve("export.zip"), id, "metadata")
            ZipFile(zip).use { bundle ->
                val manifest = bundle.getInputStream(bundle.getEntry("manifest.txt")).bufferedReader().readText()
                bundle.entries().asSequence().filter { it.name.startsWith("logs/") }.forEach { entry ->
                    val bytes = bundle.getInputStream(entry).readBytes()
                    val digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
                    assertTrue(manifest.contains("file=${entry.name} selectedBytes=${bytes.size} bytes=${bytes.size} sha256=$digest status=complete"))
                }
                assertTrue(bundle.getInputStream(bundle.getEntry("logs/${detail.name}")).bufferedReader().readText().endsWith("partial-unflushed-line"))
            }
        } finally { root.deleteRecursively() }
    }
    @Test fun networkCapturesRetainCorrelationAndThreeSnapshotBudget() {
        val root = Files.createTempDirectory("capture-archive").toFile()
        try {
            val archive = DiagnosticArchive(root)
            val id = archive.start("trip"); archive.append(id, "CONNECTION_DIAGNOSTIC attempt=42 ready")
            repeat(6) { archive.capture("command status=permission_denied", "capture-$it") }
            val captures = root.resolve("archive").listFiles()!!.filter { it.extension == "network" }
            assertEquals(3, captures.size)
            assertTrue(captures.all { it.readText().contains("session=$id attempt=42") })
            assertTrue(captures.all { it.readText().contains("permission_denied") })
        } finally { root.deleteRecursively() }
    }
    @Test fun staleExportFilesFromPreviousProcessAreRemovedButCurrentExportsAreUnique() {
        val root = Files.createTempDirectory("orphan-export").toFile()
        try {
            val old = root.resolve("BuickPlay-code47-old.zip").apply { writeText("partial") }
            val unrelated = root.resolve("keep.txt").apply { writeText("keep") }
            val first = DiagnosticArchive.newExportFile(root, 47)
            val second = DiagnosticArchive.newExportFile(root, 47)
            assertFalse(old.exists()); assertTrue(first.exists()); assertTrue(second.exists()); assertTrue(unrelated.exists())
            assertNotEquals(first, second)
        } finally { root.deleteRecursively() }
    }
    @Test fun legacyLogsMigrateWithoutInventingSessionDates() {
        val root = Files.createTempDirectory("legacy").toFile()
        try {
            root.resolve("previous.log").writeText("original outbound legacy")
            val archive = DiagnosticArchive(root)
            archive.start("new")
            assertTrue(root.resolve("archive").listFiles()!!.any { it.extension == "log" && it.readText().contains("original outbound legacy") })
            assertTrue(archive.sessions().any { it.second.contains("clockValid=false") })
        } finally { root.deleteRecursively() }
    }
    @Test fun sessionsSurviveReopenAndExportOriginalBytesWithChecksums() {
        val root = Files.createTempDirectory("archive").toFile()
        try {
            val first = DiagnosticArchive(root)
            val session = first.start("outbound")
            first.append(session, "CONNECTION_DIAGNOSTIC attempt=1 first_frame token=original")
            val second = DiagnosticArchive(root)
            val returned = second.start("return")
            second.append(returned, "return journey")
            val zip = second.export(root.resolve("bundle.zip"), "all", "metadata")
            ZipFile(zip).use { archive ->
                val manifest = archive.getInputStream(archive.getEntry("manifest.txt")).bufferedReader().readText()
                assertTrue(manifest.contains("sha256="))
                assertTrue(manifest.contains(session))
                val bodies = archive.entries().asSequence().filter { it.name.endsWith(".log") }
                    .map { archive.getInputStream(it).bufferedReader().readText() }.joinToString()
                assertTrue(bodies.contains("token=original")); assertTrue(bodies.contains("return journey"))
            }
        } finally { root.deleteRecursively() }
    }
    @Test fun invalidSelectionCannotEscapeArchiveAndClockRollbackIsExplicit() {
        val root = Files.createTempDirectory("archive").toFile()
        try {
            var time = 1_800_000_000_000L
            val archive = DiagnosticArchive(root, wall = { time })
            val id = archive.start("start")
            time -= 100_000
            archive.append(id, "retry")
            assertTrue(root.walkTopDown().filter { it.extension == "summary" }.single().readText().contains("clock_jump"))
            try { archive.export(root.resolve("bad.zip"), "../secret", ""); fail("selection accepted") }
            catch (_: IllegalArgumentException) { }
        } finally { root.deleteRecursively() }
    }
    @Test fun budgetsKeepSummaryAndExceptionContextAfterDetailRotation() {
        val root = Files.createTempDirectory("archive").toFile()
        try {
            val archive = DiagnosticArchive(root, detailBudget = 1500, reserve = 0, partBytes = 300)
            val id = archive.start("start")
            repeat(20) { archive.append(id, "CONNECTION_DIAGNOSTIC attempt=$it " + "x".repeat(150)) }
            archive.append(id, "ERROR audio underrun")
            assertTrue(root.walkTopDown().filter { it.extension == "log" }.sumOf { it.length() } <= 1500)
            assertTrue(root.walkTopDown().any { it.extension == "summary" && it.readText().contains("attempt=0") })
            assertTrue(root.walkTopDown().any { it.extension == "exception" })
        } finally { root.deleteRecursively() }
    }
}
