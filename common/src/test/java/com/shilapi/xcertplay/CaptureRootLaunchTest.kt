package com.shilapi.xcertplay

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class CaptureRootLaunchTest {
    @Test fun rootSiblingReadsExplicitAppJournal() {
        val base = Files.createTempDirectory("route-journal-launch").toFile()
        try {
            val app = base.resolve("run").apply { mkdirs() }
            val journal = app.resolve("route-events.log")
            journal.writeText("FIRST_TCP_WAIT\tattempt=1\tap0\t123\t456\tunknown\n")
            for (attempt in 0..1) {
                val root = CaptureRootLaunch.attemptDirectory(app, attempt).apply { mkdirs() }
                val resolved = com.shilapi.xcertplay.diagnostics.RouteCaptureEvents.journal(root, journal.path)
                assertEquals(journal.canonicalFile, resolved)
                assertNotNull(com.shilapi.xcertplay.diagnostics.RouteCaptureEvents.Event.parse(resolved.readText().trim()))
                assertThrows(java.io.IOException::class.java) {
                    com.shilapi.xcertplay.diagnostics.RouteCaptureEvents.journal(root, base.resolve("route-events.log").path)
                }
                val unrelated = base.resolve("other").apply { mkdirs() }.resolve("route-events.log").apply { writeText("wrong") }
                val link = app.resolve("symlink-$attempt")
                Files.createSymbolicLink(link.toPath(), unrelated.toPath())
                assertThrows(java.io.IOException::class.java) {
                    com.shilapi.xcertplay.diagnostics.RouteCaptureEvents.journal(root, link.path)
                }
            }
        } finally { base.deleteRecursively() }
    }

    @Test fun staleReadinessFromFailedAttemptCannotApproveAHangingFallback() {
        val base = Files.createTempDirectory("capture-launch").toFile()
        val child = ProcessBuilder("/bin/sleep", "5").start()
        try {
            val parent = base.resolve("run").apply { mkdirs() }
            val first = CaptureRootLaunch.attemptDirectory(parent, 0).apply { mkdirs() }
            first.resolve("root-ready").writeText("old")
            first.resolve("stop").writeText("old")
            val second = CaptureRootLaunch.attemptDirectory(parent, 1).apply { mkdirs() }
            val start = System.nanoTime()
            assertFalse(CaptureRootLaunch.awaitReady(child, second, 100))
            assertTrue((System.nanoTime() - start) / 1_000_000 < 2000)
            assertFalse(second.resolve("stop").exists())
            second.resolve("root-ready").writeText("new")
            assertTrue(CaptureRootLaunch.awaitReady(child, second, 100))
        } finally { child.destroyForcibly(); base.deleteRecursively() }
    }
}
