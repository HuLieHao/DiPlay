package com.shilapi.xcertplay

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class StartupCaptureStopTest {
    private fun set(name: String, value: Any?) {
        StartupCapture::class.java.getDeclaredField(name).apply { isAccessible = true }.set(StartupCapture, value)
    }
    @Test fun stopRetryAllowsExportAfterEarlierWriteFailureAndRootConfirmation() {
        val root = Files.createTempDirectory("capture-cleanup-retry").toFile()
        try {
            (StartupCapture::class.java.getDeclaredField("started").apply { isAccessible = true }.get(StartupCapture) as AtomicBoolean).set(true)
            (StartupCapture::class.java.getDeclaredField("stopRequested").apply { isAccessible = true }.get(StartupCapture) as AtomicBoolean).set(false)
            set("startedAt", 1L); set("stopped", false); set("enabled", true)
            set("worker", null); set("mirrorWorker", null)
            @Suppress("UNCHECKED_CAST")
            val attempts = StartupCapture::class.java.getDeclaredField("attempts").apply { isAccessible = true }.get(StartupCapture) as java.util.concurrent.CopyOnWriteArrayList<java.io.File>
            attempts.add(root)
            root.resolve("root-ready").writeText("ready")
            root.resolve("stop").mkdir() // Reproduce an actual stop-token write failure.
            StartupCapture.stop()
            val stopFlag = StartupCapture::class.java.getDeclaredField("stopRequested").apply { isAccessible = true }.get(StartupCapture) as AtomicBoolean
            val deadline = System.nanoTime() + 5_000_000_000
            while (stopFlag.get() && System.nanoTime() < deadline) Thread.sleep(10)
            assertTrue("unconfirmed root must still block export", StartupCapture.needsStop())
            root.resolve("stop").delete()
            root.resolve("collector-stopped").writeText("all child processes exited")
            StartupCapture.stop()
            val retryDeadline = System.nanoTime() + 5_000_000_000
            while (StartupCapture.needsStop() && System.nanoTime() < retryDeadline) Thread.sleep(10)
            assertFalse("confirmed cleanup must recover after a previous failure", StartupCapture.needsStop())
        } finally { root.deleteRecursively() }
    }
    @Test fun stoppedMarkerCannotApproveALiveProcessButRetryAfterExitSucceeds() {
        val root = Files.createTempDirectory("capture-live-process").toFile()
        val alive = AtomicBoolean(true)
        val process = object : java.lang.Process() {
            override fun getOutputStream() = java.io.ByteArrayOutputStream()
            override fun getInputStream() = java.io.ByteArrayInputStream(byteArrayOf())
            override fun getErrorStream() = java.io.ByteArrayInputStream(byteArrayOf())
            override fun waitFor() = 0
            override fun exitValue(): Int { if (alive.get()) throw IllegalThreadStateException(); return 0 }
            override fun destroy() { }
            override fun isAlive() = alive.get()
        }
        @Suppress("UNCHECKED_CAST")
        val processes = StartupCapture::class.java.getDeclaredField("processes").apply { isAccessible = true }.get(StartupCapture) as java.util.concurrent.ConcurrentHashMap<java.io.File, java.lang.Process>
        try {
            (StartupCapture::class.java.getDeclaredField("started").apply { isAccessible = true }.get(StartupCapture) as AtomicBoolean).set(true)
            val stopFlag = StartupCapture::class.java.getDeclaredField("stopRequested").apply { isAccessible = true }.get(StartupCapture) as AtomicBoolean
            stopFlag.set(false)
            set("startedAt", 1L); set("stopped", false); set("worker", null); set("mirrorWorker", null)
            processes[root] = process
            @Suppress("UNCHECKED_CAST")
            val attempts = StartupCapture::class.java.getDeclaredField("attempts").apply { isAccessible = true }.get(StartupCapture) as java.util.concurrent.CopyOnWriteArrayList<java.io.File>
            attempts.add(root)
            root.resolve("root-ready").writeText("ready")
            root.resolve("collector-stopped").writeText("done")
            StartupCapture.stop()
            val deadline = System.nanoTime() + 5_000_000_000
            while (stopFlag.get() && System.nanoTime() < deadline) Thread.sleep(10)
            assertTrue(StartupCapture.needsStop())
            alive.set(false)
            root.resolve("collector-stopped").delete()
            StartupCapture.stop()
            val confirmationDeadline = System.nanoTime() + 5_000_000_000
            while (stopFlag.get() && System.nanoTime() < confirmationDeadline) Thread.sleep(10)
            assertTrue("dead launcher alone does not confirm root child cleanup", StartupCapture.needsStop())
            root.resolve("collector-stopped").writeText("done")
            StartupCapture.stop()
            val retryDeadline = System.nanoTime() + 5_000_000_000
            while (StartupCapture.needsStop() && System.nanoTime() < retryDeadline) Thread.sleep(10)
            assertFalse(StartupCapture.needsStop())
        } finally { alive.set(false); processes.remove(root); root.deleteRecursively() }
    }
    @Test fun rootFailureEndsDetailedApplicationLogCapture() {
        (StartupCapture::class.java.getDeclaredField("started").apply { isAccessible = true }.get(StartupCapture) as AtomicBoolean).set(true)
        (StartupCapture::class.java.getDeclaredField("stopRequested").apply { isAccessible = true }.get(StartupCapture) as AtomicBoolean).set(false)
        set("startedAt", 1L); set("stopped", false); set("enabled", true)
        set("state", "root_unavailable"); set("worker", null); set("mirrorWorker", null)
        StartupCapture.onCollectorExit()
        val deadline = System.nanoTime() + 5_000_000_000
        while (StartupCapture.needsStop() && System.nanoTime() < deadline) Thread.sleep(10)
        assertFalse(StartupCapture.needsStop())
        assertFalse(StartupCapture::class.java.getDeclaredField("enabled").apply { isAccessible = true }.getBoolean(StartupCapture))
        assertTrue(StartupCapture.health().contains("root_unavailable"))
    }
    @Test fun repeatedStopWaitsForStartupAndFlushBeforeAllowingDownload() {
        val root = Files.createTempDirectory("capture-stop").toFile()
        val worker = Thread {
            while (!root.resolve("stop").exists()) Thread.sleep(10)
            Thread.sleep(100)
            root.resolve("collector-stopped").writeText("done")
        }
        try {
            (StartupCapture::class.java.getDeclaredField("started").apply { isAccessible = true }.get(StartupCapture) as AtomicBoolean).set(true)
            set("startedAt", 1L); set("stopped", false); set("enabled", true)
            set("rootDirectory", root); set("state", "root_starting")
            @Suppress("UNCHECKED_CAST")
            val attempts = StartupCapture::class.java.getDeclaredField("attempts").apply { isAccessible = true }.get(StartupCapture) as java.util.concurrent.CopyOnWriteArrayList<java.io.File>
            attempts.add(root)
            (StartupCapture::class.java.getDeclaredField("stopRequested").apply { isAccessible = true }.get(StartupCapture) as AtomicBoolean).set(false)
            root.resolve("root-ready").writeText("ready")
            worker.start(); set("worker", worker)
            assertTrue(StartupCapture.needsStop())
            StartupCapture.stop(); StartupCapture.stop()
            val deadline = System.nanoTime() + 5_000_000_000
            while (StartupCapture.needsStop() && System.nanoTime() < deadline) Thread.sleep(10)
            assertFalse(StartupCapture.needsStop())
            assertTrue(root.resolve("collector-stopped").exists())
            assertFalse(worker.isAlive)
            StartupCapture.stop()
            assertFalse(StartupCapture.needsStop())
        } finally { root.resolve("stop").writeText("stop"); worker.join(1000); root.deleteRecursively() }
    }
}
