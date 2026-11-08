package com.shilapi.xcertplay

import com.shilapi.xcertplay.diagnostics.AudioCaptureEvents
import com.shilapi.xcertplay.diagnostics.RouteCaptureEvents
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipInputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class StartupCaptureAudioBoundaryTest {
    private fun field(name: String) = StartupCapture::class.java.getDeclaredField(name).apply { isAccessible = true }
    private fun get(name: String) = field(name).get(StartupCapture)
    private fun set(name: String, value: Any?) = field(name).set(StartupCapture, value)

    @Test fun rootStopsBeforeRealMirrorFlushAndStreamingExportKeepsFinalTailAndBoundaries() {
        val base = Files.createTempDirectory("audio-stop-integration").toFile()
        val folder = base.resolve("startup-captures/run").apply { mkdirs() }
        val root = base.resolve("startup-captures/run-root-0").apply { mkdirs() }
        val releaseWriter = CountDownLatch(1)
        val rootStopped = CountDownLatch(1)
        val writerFinished = CountDownLatch(1)
        val failures = java.util.concurrent.ConcurrentLinkedQueue<Throwable>()
        val mutable = listOf("startedAt", "endedAt", "stopped", "enabled", "directory", "rootDirectory", "state", "mirrorState", "worker", "mirrorWorker")
        val saved = mutable.associateWith { get(it) }
        val started = get("started") as AtomicBoolean
        val stopRequested = get("stopRequested") as AtomicBoolean
        val oldStarted = started.get()
        val oldStop = stopRequested.get()
        @Suppress("UNCHECKED_CAST")
        val attempts = get("attempts") as java.util.concurrent.CopyOnWriteArrayList<File>
        val oldAttempts = attempts.toList()
        val pending = get("audioEvents") as AudioCaptureEvents.Pending
        val entries = get("entries") as CaptureLogQueue
        val routes = get("routeEvents") as RouteCaptureEvents.Pending
        // Existing stop tests can leave these singleton admission flags sealed.
        fun flag(target: Any, name: String, value: Boolean): Boolean {
            val f = target.javaClass.getDeclaredField(name).apply { isAccessible = true }
            val before = f.getBoolean(target); f.setBoolean(target, value); return before
        }
        val sinks = listOf(AudioCaptureEvents::class.java, RouteCaptureEvents::class.java).associateWith {
            it.getDeclaredField("sink").apply { isAccessible = true }.get(null)
        }
        // Snapshot queue state as well as admission flags so this singleton fixture is reversible.
        val queueStates = listOf(pending, routes, entries).associateWith { queue ->
            queue.javaClass.declaredFields.filter { !java.lang.reflect.Modifier.isStatic(it.modifiers) }.associateWith { f ->
                f.isAccessible = true
                val value = f.get(queue)
                if (value is java.util.ArrayDeque<*>) java.util.ArrayDeque(value) else value
            }
        }
        pending.drain(); routes.drain(); while (entries.poll() != null) { }
        val oldAudioSealed = flag(pending, "sealed", false)
        val oldRouteSealed = flag(routes, "sealed", false)
        val oldEntryEnabled = flag(entries, "enabled", true)
        val prefix = "UNDERRUN\trenderer-1\t100\t200\tfirst=1\n"
        val tail = "WRITE_ANOMALY\trenderer-1\t101\t201\ttail=queued-after-root-boundary"
        val appTail = "application queued tail"
        folder.resolve("audio-events.log").writeText(prefix)
        root.resolve("root-ready").writeText("ready")
        val rootWorker = Thread {
            try {
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                while (!root.resolve("stop").exists() && System.nanoTime() < deadline) Thread.sleep(5)
                check(root.resolve("stop").exists())
                root.resolve("audio-reader-stop.txt").writeText("${prefix.toByteArray().size}\n")
                root.resolve("collector-stopped").writeText("fake root process stopped")
                rootStopped.countDown()
            } catch (t: Throwable) { failures.add(t); rootStopped.countDown() }
        }
        val writer = Thread {
            try {
                check(releaseWriter.await(5, TimeUnit.SECONDS))
                StartupCapture::class.java.getDeclaredMethod("mirror", File::class.java)
                    .apply { isAccessible = true }.invoke(StartupCapture, folder)
            } catch (t: Throwable) { failures.add(t) }
            finally { writerFinished.countDown() }
        }
        try {
            attempts.clear(); attempts.add(root)
            started.set(true); stopRequested.set(false)
            set("startedAt", 1L); set("stopped", false); set("enabled", true)
            set("directory", folder); set("rootDirectory", root)
            set("worker", rootWorker); set("mirrorWorker", writer)
            AudioCaptureEvents.setSink { pending.offer(it) }
            AudioCaptureEvents.emit(tail)
            StartupCapture.append(appTail, 102)
            rootWorker.start(); writer.start()
            StartupCapture.stop()
            assertTrue("root boundary missing", rootStopped.await(3, TimeUnit.SECONDS))
            rootWorker.join(1000)
            assertFalse(rootWorker.isAlive)
            assertTrue("download gate must stay closed until app flush", StartupCapture.needsStop())
            assertEquals(prefix, folder.resolve("audio-events.log").readText())
            assertFalse(folder.resolve("audio-stop-final.txt").exists())
            releaseWriter.countDown()
            assertTrue(writerFinished.await(3, TimeUnit.SECONDS))
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (StartupCapture.needsStop() && System.nanoTime() < deadline) Thread.sleep(5)
            assertFalse("stop must open download gate after flush", StartupCapture.needsStop())
            assertTrue(failures.toString(), failures.isEmpty())
            val journal = prefix + tail + "\n"
            assertEquals(journal, folder.resolve("audio-events.log").readText())
            val appLog = folder.resolve("app-full.log").readText()
            assertTrue(appLog.contains("audio_event $tail\n"))
            assertTrue(appLog.contains(appTail))
            val expected = "collector=${root.name} " + AudioCaptureEvents.boundary(
                prefix.toByteArray().size.toLong(), journal.toByteArray().size.toLong(), "app_writer_and_root_stopped") + "\n"
            assertEquals(expected, folder.resolve("audio-stop-final.txt").readText())
            val originals = base.walkTopDown().filter { it.isFile }.associate { it.relativeTo(base).path to it.readBytes() }
            val output = ByteArrayOutputStream()
            DiagnosticArchive(base.resolve("logs").apply { mkdirs() }, reserve = 0).exportTo(output, metadata = "audio boundary integration")
            val zipped = mutableMapOf<String, ByteArray>()
            ZipInputStream(output.toByteArray().inputStream()).use { zip ->
                while (true) { val entry = zip.nextEntry ?: break; zipped[entry.name] = zip.readBytes() }
            }
            for (name in listOf("audio-events.log", "app-full.log", "audio-stop-final.txt")) {
                assertArrayEquals(originals["startup-captures/run/$name"], zipped["startup-captures/run/$name"])
            }
            assertArrayEquals(prefix.toByteArray().size.toString().plus("\n").toByteArray(), zipped["startup-captures/run-root-0/audio-reader-stop.txt"])
            originals.forEach { (name, bytes) -> assertArrayEquals("original retained: $name", bytes, base.resolve(name).readBytes()) }
        } finally {
            releaseWriter.countDown()
            root.resolve("stop").writeText("cleanup")
            rootWorker.join(3000); writer.join(3000)
            Thread.getAllStackTraces().keys.filter { it.name == "capture-stop" }.forEach { it.join(6000) }
            assertFalse(rootWorker.isAlive); assertFalse(writer.isAlive)
            sinks.forEach { (type, sink) -> type.getDeclaredField("sink").apply { isAccessible = true }.set(null, sink) }
            pending.drain(); routes.drain(); while (entries.poll() != null) { }
            flag(pending, "sealed", oldAudioSealed); flag(routes, "sealed", oldRouteSealed); flag(entries, "enabled", oldEntryEnabled)
            queueStates.forEach { (queue, fields) -> fields.forEach { (f, value) ->
                if (value is java.util.ArrayDeque<*>) {
                    @Suppress("UNCHECKED_CAST")
                    val target = f.get(queue) as java.util.ArrayDeque<Any>
                    target.clear(); target.addAll(value)
                } else f.set(queue, value)
            } }
            attempts.clear(); attempts.addAll(oldAttempts)
            saved.forEach { (name, value) -> set(name, value) }
            started.set(oldStarted); stopRequested.set(oldStop)
            base.deleteRecursively()
        }
    }
}
