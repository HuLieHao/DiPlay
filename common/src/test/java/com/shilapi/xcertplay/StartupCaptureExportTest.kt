package com.shilapi.xcertplay

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.util.zip.ZipFile

class StartupCaptureExportTest {
    @Test fun failedStreamingOutputReleasesArchivePins() {
        val base = Files.createTempDirectory("stream-pin-release").toFile()
        try {
            val logs = base.resolve("logs").apply { mkdirs() }
            val archive = DiagnosticArchive(logs, detailBudget = 5000, reserve = 0)
            val id = archive.start("stream")
            archive.append(id, "x".repeat(800))
            val old = logs.resolve("archive").listFiles()!!.single { it.extension == "log" }
            assertThrows(java.io.IOException::class.java) {
                archive.exportTo(object : java.io.OutputStream() {
                    override fun write(value: Int): Unit = throw java.io.IOException("phone disconnected")
                }, metadata = "test")
            }
            archive.append(id, "z".repeat(4800))
            assertFalse("failed download must not pin expired log parts", old.exists())
            assertTrue(logs.resolve("archive").listFiles()!!.any { it.extension == "log" && it.readText().contains("zzz") })
        } finally { base.deleteRecursively() }
    }
    @Test fun streamExportNeedsNoFreeSpaceForAnIntermediateZip() {
        val base = Files.createTempDirectory("startup-stream").toFile()
        try {
            val logs = base.resolve("logs").apply { mkdirs() }
            val capture = base.resolve("startup-captures/run").apply { mkdirs() }
            val bytes = ByteArray(1024 * 1024) { (it % 251).toByte() }
            capture.resolve("network.pcap").writeBytes(bytes)
            capture.resolve("network.pcap.complete").writeText(bytes.size.toString())
            val before = base.walkTopDown().filter { it.isFile }.map { it.relativeTo(base).path }.toSet()
            val output = java.io.ByteArrayOutputStream()
            DiagnosticArchive(logs, reserve = Long.MAX_VALUE, usableSpace = { 0L })
                .exportTo(output, metadata = "zero free export staging space")
            java.util.zip.ZipInputStream(output.toByteArray().inputStream()).use { zip ->
                var found = false
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.name.endsWith("network.pcap")) { assertArrayEquals(bytes, zip.readBytes()); found = true }
                }
                assertTrue(found)
            }
            assertTrue(base.walkTopDown().none { it.extension == "zip" })
            assertTrue(before.all { base.resolve(it).isFile })
        } finally { base.deleteRecursively() }
    }

    @Test fun timeCorrelationSidecarSurvivesFailedDownloadAndRetry() {
        val base = Files.createTempDirectory("capture-clock-export").toFile()
        try {
            val logs = base.resolve("logs").apply { mkdirs() }
            val capture = base.resolve("startup-captures/run-clock").apply { mkdirs() }
            val pcap = ByteArray(80) { it.toByte() }
            val clocks = "kind\tpacketIndex\tpcapEnd\tkernelWallNs\npacket\t1\t80\t1000000000\n"
            capture.resolve("network.pcap").writeBytes(pcap)
            capture.resolve("network.pcap.complete").writeText("80")
            val sidecar = capture.resolve("network.pcap.clock.tsv").apply { writeText(clocks) }
            val archive = DiagnosticArchive(logs, reserve = 0)
            assertThrows(java.io.IOException::class.java) {
                archive.exportTo(object : java.io.OutputStream() {
                    override fun write(value: Int): Unit = throw java.io.IOException("download interrupted")
                }, metadata = "clock test")
            }
            assertEquals(clocks, sidecar.readText())
            val output = java.io.ByteArrayOutputStream()
            archive.exportTo(output, metadata = "retry")
            val entries = mutableMapOf<String, ByteArray>()
            java.util.zip.ZipInputStream(output.toByteArray().inputStream()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    entries[entry.name] = zip.readBytes()
                }
            }
            assertArrayEquals(pcap, entries["startup-captures/run-clock/network.pcap"])
            assertArrayEquals(clocks.toByteArray(), entries["startup-captures/run-clock/network.pcap.clock.tsv"])
            assertEquals(clocks, sidecar.readText())
        } finally { base.deleteRecursively() }
    }

    @Test fun replaceableCompletionMarkersAreNotTreatedAsAppendOnlyFiles() {
        val base = Files.createTempDirectory("startup-marker").toFile()
        try {
            val root = base.resolve("startup-captures").apply { mkdirs() }
            val run = root.resolve("run").apply { mkdirs() }
            run.resolve("network.pcap").writeBytes(ByteArray(200))
            run.resolve("network.pcap.complete").writeText("80")
            val snapshot = StartupCaptureFiles.snapshot(root)
            run.resolve("network.pcap.complete").writeText("160")
            assertFalse(snapshot.any { it.first.name.endsWith(".complete") })
            assertEquals(80L, snapshot.single().second)
        } finally { base.deleteRecursively() }
    }

    @Test fun symlinksCannotIncludeFilesOutsideCaptureDirectory() {
        val base = Files.createTempDirectory("startup-symlink").toFile()
        try {
            val root = base.resolve("startup-captures").apply { mkdirs() }
            val run = root.resolve("run").apply { mkdirs() }
            val secret = base.resolve("outside").apply { writeText("outside") }
            Files.createSymbolicLink(run.resolve("escape.log").toPath(), secret.toPath())
            Files.createSymbolicLink(root.resolve("escape-dir").toPath(), base.toPath())
            assertTrue(StartupCaptureFiles.snapshot(root).isEmpty())
        } finally { base.deleteRecursively() }
    }

    @Test fun shellArgumentsRemainLiteralAndProcessNamesCanContainParentheses() {
        val value = "x' y; \$(bad)"
        val process = ProcessBuilder("/bin/sh", "-c", "printf %s " + StartupCaptureFiles.quote(value)).start()
        assertEquals(value, process.inputStream.reader().readText())
        assertEquals(0, process.waitFor())
        val fields = (3..52).map { if (it == 22) "987654" else "0" }.joinToString(" ")
        assertEquals("987654", StartupCaptureFiles.processStart("123 (name (with spaces)) $fields"))
    }

    @Test fun fullBinaryCaptureIsIncludedOutsideOrdinaryArchiveBudget() {
        val base = Files.createTempDirectory("startup-export").toFile()
        try {
            val logs = base.resolve("logs").apply { mkdirs() }
            val capture = base.resolve("startup-captures/run-a").apply { mkdirs() }
            val bytes = ByteArray(1024 * 1024 + 7) { (it % 251).toByte() }
            capture.resolve("network.pcap").writeBytes(bytes)
            capture.resolve("network.pcap.complete").writeText(bytes.size.toString())
            capture.resolve("system.log").writeText("original system evidence")
            val archive = DiagnosticArchive(logs, detailBudget = 512, reserve = 0)
            archive.start("test")
            val zip = archive.export(base.resolve("report.zip"), metadata = "test")
            ZipFile(zip).use {
                val entry = it.getEntry("startup-captures/run-a/network.pcap")
                assertNotNull("root packet evidence must accompany the ordinary report", entry)
                assertArrayEquals(bytes, it.getInputStream(entry).readBytes())
                assertTrue(it.getInputStream(it.getEntry("manifest.txt")).reader().readText().contains("captureScope=all_retained_processes"))
            }
        } finally { base.deleteRecursively() }
    }

    @Test fun activePcapExportsOnlyPublishedCompleteRecords() {
        val base = Files.createTempDirectory("startup-prefix").toFile()
        try {
            val logs = base.resolve("logs").apply { mkdirs() }
            val capture = base.resolve("startup-captures/run-b").apply { mkdirs() }
            capture.resolve("network.pcap").writeBytes(ByteArray(100) { it.toByte() })
            capture.resolve("network.pcap.complete").writeText("80")
            val zip = DiagnosticArchive(logs, reserve = 0).export(base.resolve("report.zip"), metadata = "test")
            ZipFile(zip).use { assertEquals(80L, it.getEntry("startup-captures/run-b/network.pcap")?.size) }
        } finally { base.deleteRecursively() }
    }
}
