package com.shilapi.xcertplay

import java.nio.file.Files
import java.util.zip.ZipFile
import org.junit.Assert.*
import org.junit.Test

class DiagnosticArchiveTest {
    @Test fun pendingHeaderRetriesAnIncompleteInitialWrite() {
        val root = Files.createTempDirectory("partial-header-retry").toFile()
        try {
            val archive = DiagnosticArchive(root, reserve = 0)
            val id = java.util.UUID.randomUUID().toString()
            val metadata = root.resolve("archive/$id.meta").apply { mkdirs() }
            assertThrows(java.io.IOException::class.java) { archive.start("retryable header", id = id) }
            metadata.delete()
            metadata.writeText("session=interrupted")
            archive.append(id, "original after retry")
            assertTrue(metadata.readText().contains("session=$id "))
            assertFalse(metadata.readText().contains("session=interrupted"))
            archive.export(root.resolve("complete.zip"), id, "metadata")
            ZipFile(root.resolve("complete.zip")).use { zip ->
                assertNotNull(zip.getEntry("logs/$id.meta"))
                assertTrue(zip.entries().asSequence().filter { it.name.endsWith(".log") }
                    .any { zip.getInputStream(it).bufferedReader().readText().contains("original after retry") })
            }
        } finally { root.deleteRecursively() }
    }

    @Test fun pendingSummaryRecoveryPreservesInvalidClockMetadataAcrossReopen() {
        val root = Files.createTempDirectory("pending-clock-recovery").toFile()
        try {
            var now = 1_800_018_000_000L
            var mono = 1000L
            var space = 10_000_000L
            val archive = DiagnosticArchive(root, wall = { now }, monotonic = { mono }, reserve = 1000, usableSpace = { space })
            archive.start("active")
            space = 1250L
            val queued = archive.start("queued", makeCurrent = false)
            val metadata = root.resolve("archive/$queued.meta")
            assertTrue(metadata.exists())
            now -= 10_000L; mono++
            archive.append(queued, "x".repeat(500)) // marker fits; initial summary/detail do not
            assertTrue(metadata.readText().contains("clockValid=false"))
            assertFalse(root.resolve("archive").listFiles()!!.any { it.name.startsWith(queued) && it.extension == "log" })
            space = 10_000_000L
            now += 10_000L; mono++
            archive.append(queued, "original after recovery")
            val reopened = DiagnosticArchive(root, wall = { now }, monotonic = { mono }, reserve = 0)
            reopened.export(root.resolve("today.zip"), "today", "metadata")
            ZipFile(root.resolve("today.zip")).use { zip ->
                assertNull("a persisted invalid-clock marker must survive pending admission retry", zip.getEntry("logs/$queued.meta"))
            }
            reopened.export(root.resolve("selected.zip"), queued, "metadata")
            ZipFile(root.resolve("selected.zip")).use { zip ->
                assertTrue(zip.getInputStream(zip.getEntry("logs/$queued.meta")).bufferedReader().readText().contains("clockValid=false"))
                assertTrue(zip.entries().asSequence().filter { it.name.endsWith(".log") }
                    .any { zip.getInputStream(it).bufferedReader().readText().contains("original after recovery") })
            }
        } finally { root.deleteRecursively() }
    }

    @Test fun nonCurrentStartSurvivesWhenMetadataFitsButInitialSummaryDoesNot() {
        val root = Files.createTempDirectory("header-only-admission").toFile()
        try {
            var space = 10_000_000L
            val archive = DiagnosticArchive(root, reserve = 1000, usableSpace = { space })
            archive.start("current")
            // Metadata is under 400 bytes; the initial summary also includes durability text.
            space = 1250L
            val queued = archive.start("queued", makeCurrent = false)
            space = 10_000_000L
            archive.append(queued, "original recovered queued run")
            val zip = archive.export(root.resolve("recovered.zip"), queued, "metadata")
            ZipFile(zip).use { bundle ->
                assertNotNull(bundle.getEntry("logs/$queued.meta"))
                assertTrue(bundle.entries().asSequence().filter { it.name.endsWith(".log") }
                    .any { bundle.getInputStream(it).bufferedReader().readText().contains("original recovered queued run") })
            }
        } finally { root.deleteRecursively() }
    }

    @Test fun lateSummaryRemainsSelectableWhenItsLastPriorEvidenceIsPruned() {
        val root = Files.createTempDirectory("late-summary-admission").toFile()
        try {
            val archive = DiagnosticArchive(root, reserve = 0, detailBudget = 150)
            val old = archive.start("old")
            archive.append(old, "x".repeat(100))
            val current = archive.start("new")
            archive.append(current, "y".repeat(100)) // prunes old detail, leaves old summary
            val folder = root.resolve("archive")
            val currentSummary = folder.resolve("$current.summary").length()
            folder.resolve("$old.summary").apply {
                writeBytes(ByteArray((4 * 1024 * 1024 - currentSummary - 20).toInt()) { 120 })
                setLastModified(1L)
            }
            archive.export(root.resolve("before.zip"), "all", "metadata")
            assertTrue(folder.resolve("$old.meta").exists())
            archive.append(old, "CONNECTION_DIAGNOSTIC late original tail")
            archive.export(root.resolve("after.zip"), old, "metadata")
            ZipFile(root.resolve("after.zip")).use { bundle ->
                assertNotNull("late original evidence must retain selectable metadata", bundle.getEntry("logs/$old.meta"))
                assertTrue(bundle.entries().asSequence().filter { it.name.endsWith(".summary") }
                    .any { bundle.getInputStream(it).bufferedReader().readText().contains("late original tail") })
            }
        } finally { root.deleteRecursively() }
    }

    @Test fun summaryBudgetCleanupCannotUnlinkMetadataBeingAdmittedForAnOlderQueuedRun() {
        val root = Files.createTempDirectory("pending-summary-prune").toFile()
        try {
            val archive = DiagnosticArchive(root, reserve = 0)
            val old = archive.start("old")
            root.resolve("archive/$old.summary").writeBytes(ByteArray(4 * 1024 * 1024) { 120 })
            archive.export(root.resolve("initial.zip"), "all", "metadata") // refresh budget accounting
            archive.activate(java.util.UUID.randomUUID().toString()) // a newer UI-admitted target
            val queued = archive.start("older queued run", makeCurrent = false)
            archive.append(queued, "original from queued connection")
            archive.export(root.resolve("queued.zip"), queued, "metadata")
            ZipFile(root.resolve("queued.zip")).use { zip ->
                assertNotNull("summary pruning must not orphan the admitted run", zip.getEntry("logs/$queued.meta"))
                assertTrue(zip.entries().asSequence().filter { it.name.endsWith(".log") }
                    .any { zip.getInputStream(it).bufferedReader().readText().contains("original from queued connection") })
            }
        } finally { root.deleteRecursively() }
    }

    @Test fun detachedCaptureCannotRestoreAnObsoleteRunOverNewUiAdmission() {
        val root = Files.createTempDirectory("capture-ui-admission").toFile()
        try {
            var activateDuringWrite = false
            val next = java.util.UUID.randomUUID().toString()
            lateinit var archive: DiagnosticArchive
            archive = DiagnosticArchive(root, reserve = 0, usableSpace = {
                if (activateDuringWrite) { activateDuringWrite = false; archive.activate(next) }
                10_000_000L
            })
            archive.start("previous run")
            activateDuringWrite = true
            assertTrue(archive.capture("original detached capture", "capture", "none"))
            assertTrue("capture completion must preserve the newer admitted connection", archive.correlation().contains("session=$next "))
        } finally { root.deleteRecursively() }
    }

    @Test fun retainedRunBookkeepingRetiresWhenItsEvidenceExpires() {
        val root = Files.createTempDirectory("run-bookkeeping").toFile()
        try {
            var now = 1_800_000_000_000L
            val archive = DiagnosticArchive(root, wall = { now }, reserve = 0)
            val ids = (1..12).map { archive.start("run $it").also { id -> archive.append(id, "CONNECTION_DIAGNOSTIC original") } }
            now += 15 * 86_400_000L
            val current = archive.start("after retention")
            val parts = DiagnosticArchive::class.java.getDeclaredField("parts").apply { isAccessible = true }.get(archive) as Map<*, *>
            assertEquals(setOf(current), parts.keys)
            assertTrue(ids.none { root.resolve("archive/$it.meta").exists() })
        } finally { root.deleteRecursively() }
    }

    @Test fun dateSelectionUsesAdmittedRunDateAndOldTailDoesNotReplaceCurrentAttempt() {
        val root = Files.createTempDirectory("connection-run-dates").toFile()
        val oldZone = java.util.TimeZone.getDefault()
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("UTC"))
        try {
            val now = 1_800_018_000_000L
            val archive = DiagnosticArchive(root, wall = { now }, monotonic = { 1000L }, reserve = 0)
            val outbound = archive.start("outbound", startedWall = now - 86_400_000L, startedMono = 100L)
            archive.append(outbound, "CONNECTION_DIAGNOSTIC attempt=1 outbound original")
            val returned = archive.start("return", startedWall = now, startedMono = 200L)
            archive.append(returned, "CONNECTION_DIAGNOSTIC attempt=2 return original")
            archive.append(outbound, "CONNECTION_DIAGNOSTIC attempt=1 outbound old tail")
            assertTrue(archive.correlation().contains("session=$returned attempt=2"))
            archive.capture("network evidence", "current-capture")
            assertTrue(root.resolve("archive").listFiles()!!.single { it.extension == "network" }.name.startsWith(returned))
            for ((selection, wanted, unwanted) in listOf(Triple("yesterday", outbound, returned), Triple("today", returned, outbound))) {
                val zip = archive.export(root.resolve("$selection.zip"), selection, "metadata")
                ZipFile(zip).use { bundle ->
                    assertNotNull(bundle.getEntry("logs/$wanted.meta"))
                    assertNull(bundle.getEntry("logs/$unwanted.meta"))
                }
            }
        } finally { java.util.TimeZone.setDefault(oldZone); root.deleteRecursively() }
    }

    @Test fun lowSpaceRepeatedStartsHaveBoundedMemoryAndReportLossAfterRecovery() {
        val root = Files.createTempDirectory("pending-starts-bounded").toFile()
        try {
            var space = 0L
            val archive = DiagnosticArchive(root, reserve = 1000, usableSpace = { space })
            val ids = (1..200).map { archive.start("connection run $it") }
            ids.forEach { archive.append(it, "original while full") }
            val pending = DiagnosticArchive::class.java.getDeclaredField("pendingStarts").apply { isAccessible = true }.get(archive) as Map<*, *>
            assertTrue("low space must not retain unbounded start metadata", pending.size <= 64)
            val parts = DiagnosticArchive::class.java.getDeclaredField("parts").apply { isAccessible = true }.get(archive) as Map<*, *>
            val clockMarks = DiagnosticArchive::class.java.getDeclaredField("invalidClockRecorded").apply { isAccessible = true }.get(archive) as Set<*>
            assertTrue(parts.size <= 64)
            assertTrue(clockMarks.size <= 64)
            space = 10_000_000L
            archive.append(ids.last(), "original after recovery")
            val zip = archive.export(root.resolve("recovered.zip"), "all", "metadata")
            ZipFile(zip).use { bundle ->
                val maintenance = bundle.getInputStream(bundle.getEntry("logs/maintenance.txt")).bufferedReader().readText()
                assertTrue(maintenance.contains("pendingStartsDroppedTotal=136"))
                assertTrue(maintenance.contains("metadataUnavailableEntriesDroppedTotal=200"))
                assertTrue(bundle.entries().asSequence().filter { it.name.endsWith(".log") }
                    .any { bundle.getInputStream(it).bufferedReader().readText().contains("original after recovery") })
                assertTrue(bundle.entries().asSequence().count { it.name.endsWith(".meta") } <= 64)
            }
            assertEquals(64, archive.sessions().size)
            assertTrue(pending.isEmpty())
        } finally { root.deleteRecursively() }
    }

    @Test fun sessionStartedBelowReserveRecoversOriginalMetadataAndExportsNewEvidence() {
        val root = Files.createTempDirectory("start-recovery").toFile()
        try {
            var now = 1_800_000_000_000L
            var available = 1000L
            val archive = DiagnosticArchive(root, wall = { now }, reserve = 1000, usableSpace = { available })
            val id = archive.start("original start")
            archive.append(id, "cannot fit")
            available = 1_000_000L
            now += 1000
            archive.append(id, "recovered original evidence")
            val zip = archive.export(root.resolve("recovered.zip"), "all", "metadata")
            ZipFile(zip).use { bundle ->
                val logs = bundle.entries().asSequence().filter { it.name.endsWith(".log") }
                    .map { bundle.getInputStream(it).bufferedReader().readText() }.toList()
                assertTrue(logs.any { it.contains("recovered original evidence") })
                val meta = bundle.getEntry("logs/$id.meta")
                assertNotNull(meta)
                val text = bundle.getInputStream(meta).bufferedReader().readText()
                assertTrue(text.contains("wallMs=1800000000000"))
                assertTrue(text.contains("reason=original start"))
            }
        } finally { root.deleteRecursively() }
    }
    @Test fun partialLegacyMigrationRetriesAfterRecoveryWithoutDuplicateOrTruncatedCopies() {
        val root = Files.createTempDirectory("migration-recovery").toFile()
        try {
            val originals = mapOf("previous.log" to "small original", "previous-2.log" to "large original".repeat(1000))
            originals.forEach { (name, text) -> root.resolve(name).writeText(text) }
            var capacity = root.walkTopDown().filter { it.isFile }.sumOf { it.length() } + 2500
            fun available() = capacity - root.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            DiagnosticArchive(root, reserve = 1000, usableSpace = ::available).start("low space")
            assertEquals(1, root.resolve("archive").listFiles()!!.count { it.name.contains("-legacy-") && it.extension == "log" })
            capacity += 1_000_000
            val recovered = DiagnosticArchive(root, reserve = 1000, usableSpace = ::available)
            val zip = recovered.export(root.resolve("recovered.zip"), "all", "metadata")
            ZipFile(zip).use { bundle ->
                val logs = bundle.entries().asSequence().filter { it.name.contains("-legacy-") && it.name.endsWith(".log") }.toList()
                assertEquals(2, logs.size)
                originals.forEach { (name, text) ->
                    val entry = logs.single { it.name.endsWith("-legacy-$name") }
                    assertEquals(text, bundle.getInputStream(entry).bufferedReader().readText())
                }
            }
            recovered.export(root.resolve("again.zip"), "all", "metadata")
            assertEquals(2, root.resolve("archive").listFiles()!!.count { it.name.contains("-legacy-") && it.extension == "log" })
        } finally { root.deleteRecursively() }
    }
    @Test fun legacyMirrorsCannotBypassReserveAdmission() {
        val root = Files.createTempDirectory("mirror-reserve").toFile()
        try {
            val source = root.resolve("source.log").apply { writeText("x".repeat(512)) }
            var capacity = 1_000_000L
            fun used() = root.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            val archive = DiagnosticArchive(root, reserve = 1000, usableSpace = { capacity - used() })
            archive.start("trip")
            capacity = used() + 1100
            val mirror = root.resolve("mirror.log")
            archive.appendLegacyMirror(mirror, "x".repeat(512))
            archive.rotateLegacyMirror(source, root.resolve("rotated.log"))
            assertFalse(mirror.exists()); assertFalse(root.resolve("rotated.log").exists())
            assertTrue(capacity - used() >= 1000)
        } finally { root.deleteRecursively() }
    }
    @Test fun repeatedInvalidClockDoesNotGrowMetadataPerEntry() {
        val root = Files.createTempDirectory("invalid-clock-bounded").toFile()
        try {
            val archive = DiagnosticArchive(root, wall = { 0L }, reserve = 0)
            val id = archive.start("invalid date")
            val before = root.resolve("archive/$id.meta").length()
            repeat(2000) { archive.append(id, "original event") }
            assertEquals(before, root.resolve("archive/$id.meta").length())
            assertTrue(archive.sessions().single().second.contains("clockValid=false"))
        } finally { root.deleteRecursively() }
    }
    @Test fun criticalSummarySurvivesPinnedDetailBudgetDrop() {
        val root = Files.createTempDirectory("pinned-summary").toFile()
        @Suppress("UNCHECKED_CAST")
        val pins = DiagnosticArchive::class.java.getDeclaredField("pins").apply { isAccessible = true }.get(null) as MutableMap<String, Int>
        try {
            val archive = DiagnosticArchive(root, detailBudget = 300, reserve = 0)
            val id = archive.start("trip"); archive.append(id, "x".repeat(250))
            val detail = root.resolve("archive").listFiles()!!.single { it.extension == "log" }
            pins[detail.absolutePath] = 1
            archive.append(id, "CONNECTION_DIAGNOSTIC retry disconnect reason=link_lost")
            assertTrue(root.resolve("archive/$id.summary").readText().contains("reason=link_lost"))
            assertEquals(251, detail.length())
        } finally { pins.keys.removeAll { it.startsWith(root.absolutePath) }; root.deleteRecursively() }
    }
    @Test fun reserveAdmissionIncludesIncomingDetailAndCaptureHeaders() {
        val root = Files.createTempDirectory("reserve-admission").toFile()
        try {
            var capacity = 1_000_000L
            fun used() = root.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            val archive = DiagnosticArchive(root, reserve = 1000, usableSpace = { capacity - used() })
            val id = archive.start("trip")
            capacity = used() + 1512
            archive.append(id, "x".repeat(512))
            assertFalse(root.resolve("archive").listFiles()!!.any { it.extension == "log" })
            capacity = used() + 1512
            archive.capture("x".repeat(512), "capture", id)
            assertFalse(root.resolve("archive").listFiles()!!.any { it.extension == "network" })
            assertTrue(capacity - used() >= 1000)
        } finally { root.deleteRecursively() }
    }
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
            assertFalse(root.resolve("archive").listFiles().orEmpty().any { it.extension == "log" })
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
