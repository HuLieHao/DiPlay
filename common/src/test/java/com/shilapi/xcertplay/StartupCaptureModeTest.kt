package com.shilapi.xcertplay

import android.content.Context
import org.robolectric.RuntimeEnvironment
import com.shilapi.xcertplay.diagnostics.AudioCaptureMode
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class StartupCaptureModeTest {
    private fun field(name: String) = StartupCapture::class.java.getDeclaredField(name).apply { isAccessible = true }

    @Test fun stoppedCaptureRemainsByteFrozenWhenComparisonPreferenceChanges() {
        val context = RuntimeEnvironment.getApplication()
        val folder = Files.createTempDirectory("capture-mode-stopped").toFile()
        val saved = listOf("directory", "stopped", "heavyAudioRequested", "audioModeWriteState", "audioModeWritesClosed").associateWith { field(it).get(StartupCapture) }
        try {
            folder.resolve("audio-heavy-mode.txt").writeText("on\n")
            folder.resolve("audio-mode-changes.log").writeText("capture finalized\n")
            field("directory").set(StartupCapture, folder)
            field("stopped").set(StartupCapture, true)
            val before = folder.listFiles()!!.associate { it.name to it.readText() }
            StartupCapture.setHeavyAudioSnapshots(context, false)
            Thread.getAllStackTraces().keys.filter { it.name == "capture-audio-mode" }.forEach { it.join(3000) }
            assertFalse(StartupCapture.heavyAudioSnapshots(context))
            assertEquals("finalized export inputs must remain unchanged", before, folder.listFiles()!!.associate { it.name to it.readText() })
        } finally {
            saved.forEach { (name, value) -> field(name).set(StartupCapture, value) }
            context.getSharedPreferences("startup-capture", 0).edit().clear().commit()
            folder.deleteRecursively()
        }
    }

    @Test fun stopCannotPublishCompletionWhileAnAdmittedModeWriterIsPending() {
        val context = RuntimeEnvironment.getApplication()
        val folder = Files.createTempDirectory("capture-mode-stop-race").toFile()
        val saved = listOf("directory", "stopped", "startedAt", "worker", "mirrorWorker", "enabled", "state", "heavyAudioRequested", "audioModeWriteState", "audioModeWritesClosed").associateWith { field(it).get(StartupCapture) }
        val started = field("started").get(StartupCapture) as java.util.concurrent.atomic.AtomicBoolean
        val stopping = field("stopRequested").get(StartupCapture) as java.util.concurrent.atomic.AtomicBoolean
        val beforeStarted = started.get(); val beforeStopping = stopping.get()
        val lock = requireNotNull(field("audioModeLock").get(StartupCapture))
        try {
            field("audioModeWritesClosed").set(StartupCapture, false)
            field("directory").set(StartupCapture, folder); field("stopped").set(StartupCapture, false)
            field("startedAt").set(StartupCapture, 1L); field("worker").set(StartupCapture, null); field("mirrorWorker").set(StartupCapture, null)
            started.set(true); stopping.set(false)
            synchronized(lock) {
                StartupCapture.setHeavyAudioSnapshots(context, false)
                val deadline = System.nanoTime() + 1_000_000_000
                while (Thread.getAllStackTraces().keys.none { it.name == "capture-audio-mode" && it.state == Thread.State.BLOCKED } && System.nanoTime() < deadline) Thread.sleep(5)
                StartupCapture.stop()
                Thread.sleep(150)
                assertTrue("export must stay blocked while an admitted writer has not finished", StartupCapture.needsStop())
            }
            val deadline = System.nanoTime() + 4_000_000_000
            while (StartupCapture.needsStop() && System.nanoTime() < deadline) Thread.sleep(5)
            assertFalse(StartupCapture.needsStop())
            val frozen = folder.listFiles()!!.associate { it.name to it.readText() }
            StartupCapture.setHeavyAudioSnapshots(context, true)
            Thread.getAllStackTraces().keys.filter { it.name == "capture-audio-mode" }.forEach { it.join(3000) }
            assertEquals(frozen, folder.listFiles()!!.associate { it.name to it.readText() })
        } finally {
            Thread.getAllStackTraces().keys.filter { it.name == "capture-audio-mode" || it.name == "capture-stop" }.forEach { it.join(5000) }
            saved.forEach { (name, value) -> field(name).set(StartupCapture, value) }
            started.set(beforeStarted); stopping.set(beforeStopping)
            context.getSharedPreferences("startup-capture", 0).edit().clear().commit()
            folder.deleteRecursively()
        }
    }

    @Test fun persistedOffPropagatesLiveAndReportDistinguishesRequestedFromRootObserved() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("startup-capture", Context.MODE_PRIVATE).edit().clear().commit()
        val folder = Files.createTempDirectory("capture-mode").toFile()
        val directory = StartupCapture::class.java.getDeclaredField("directory").apply { isAccessible = true }
        val original = directory.get(StartupCapture)
        val originalStopped = field("stopped").get(StartupCapture)
        val originalClosed = field("audioModeWritesClosed").get(StartupCapture)
        try {
            directory.set(StartupCapture, folder)
            field("stopped").set(StartupCapture, false)
            field("audioModeWritesClosed").set(StartupCapture, false)
            assertTrue(StartupCapture.heavyAudioSnapshots(context))
            StartupCapture.setHeavyAudioSnapshots(context, false)
            assertFalse(StartupCapture.heavyAudioSnapshots(context))
            val deadline = System.nanoTime() + 3_000_000_000
            while (AudioCaptureMode.enabled(folder.resolve("audio-heavy-mode.txt")) && System.nanoTime() < deadline) Thread.sleep(10)
            assertFalse(AudioCaptureMode.enabled(folder.resolve("audio-heavy-mode.txt")))
            assertTrue(StartupCapture.report().contains("heavyAudioRequested=off"))
            assertTrue(folder.resolve("audio-mode-changes.log").readText().contains("heavyAudioRequested=off"))
            StartupCapture.setHeavyAudioSnapshots(context, true)
            val onDeadline = System.nanoTime() + 3_000_000_000
            while (folder.resolve("audio-heavy-mode.txt").readText() != "on\n" && System.nanoTime() < onDeadline) Thread.sleep(10)
            assertTrue(AudioCaptureMode.enabled(folder.resolve("audio-heavy-mode.txt")))
        } finally {
            directory.set(StartupCapture, original)
            field("stopped").set(StartupCapture, originalStopped)
            field("audioModeWritesClosed").set(StartupCapture, originalClosed)
            context.getSharedPreferences("startup-capture", Context.MODE_PRIVATE).edit().clear().commit()
            folder.deleteRecursively()
        }
    }
}
