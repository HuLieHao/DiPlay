package com.shilapi.xcertplay

import android.content.Context
import android.os.Build
import android.os.Process
import android.os.SystemClock
import com.shilapi.xcertplay.diagnostics.RouteCaptureEvents
import com.shilapi.xcertplay.diagnostics.AudioCaptureEvents
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipFile

/** Private FF5000 diagnostic capture. No permission, process or disk waits on the caller. */
internal object StartupCapture {
    private val started = AtomicBoolean()
    private val entries = CaptureLogQueue()
    private val audioEvents = AudioCaptureEvents.Pending()
    private val routeEvents = RouteCaptureEvents.Pending()
    private val stopRequested = AtomicBoolean()
    private val processes = java.util.concurrent.ConcurrentHashMap<File, java.lang.Process>()
    private val attempts = java.util.concurrent.CopyOnWriteArrayList<File>()
    @Volatile private var stopped = false
    @Volatile private var startedAt = 0L
    @Volatile private var endedAt = 0L
    @Volatile private var worker: Thread? = null
    @Volatile private var mirrorWorker: Thread? = null
    @Volatile private var enabled = false
    @Volatile private var directory: File? = null
    @Volatile private var rootDirectory: File? = null
    @Volatile private var state = "not_started"
    @Volatile private var mirrorState = "not_started"

    private val audioModeLock = Any()
    private val audioModeScheduleLock = Any()
    @Volatile private var audioModeWritesClosed = false
    private var audioModeRevision = 0L
    private var audioModeWorker: Thread? = null
    @Volatile private var heavyAudioRequested = true
    @Volatile private var audioModeWriteState = "not_written"

    fun heavyAudioSnapshots(context: Context): Boolean =
        context.getSharedPreferences("startup-capture", Context.MODE_PRIVATE).getBoolean("heavy-audio", true)

    fun setHeavyAudioSnapshots(context: Context, value: Boolean) {
        context.getSharedPreferences("startup-capture", Context.MODE_PRIVATE).edit().putBoolean("heavy-audio", value).apply()
        synchronized(audioModeScheduleLock) {
            heavyAudioRequested = value
            if (audioModeWritesClosed || stopped || stopRequested.get()) {
                audioModeWriteState = "next_capture"
                return
            }
            audioModeWriteState = "pending"
            audioModeRevision++
            if (audioModeWorker != null) return
            // One coalescing writer, with no queue of per-toggle threads. The scheduling lock
            // never covers disk I/O; stop closes admission and joins this writer with a deadline.
            audioModeWorker = Thread({
                while (true) {
                    val revision = synchronized(audioModeScheduleLock) { audioModeRevision }
                    synchronized(audioModeLock) {
                        if (!audioModeWritesClosed && !stopped && !stopRequested.get()) {
                            directory?.let { folder -> runCatching { writeAudioMode(folder) }
                                .onFailure { audioModeWriteState = "failed ${it.javaClass.simpleName}" } }
                                ?: run { audioModeWriteState = "next_capture" }
                        }
                    }
                    synchronized(audioModeScheduleLock) {
                        if (audioModeWritesClosed || revision == audioModeRevision) {
                            audioModeWorker = null
                            return@Thread
                        }
                    }
                }
            }, "capture-audio-mode").apply { isDaemon = true; start() }
        }
    }

    private fun writeAudioMode(folder: File) {
        val value = if (heavyAudioRequested) "on" else "off"
        val temporary = File(folder, "audio-heavy-mode.tmp")
        temporary.writeText("$value\n")
        check(temporary.renameTo(File(folder, "audio-heavy-mode.txt"))) { "Cannot publish audio capture mode" }
        File(folder, "audio-mode-changes.log").appendText(
            "wallMs=${System.currentTimeMillis()} elapsedMs=${SystemClock.elapsedRealtime()} heavyAudioRequested=$value\n")
        audioModeWriteState = "published"
    }

    private fun audioModeStatus(): String {
        val observed = runCatching {
            rootDirectory?.resolve("audio-heavy-status.txt")?.inputStream()?.use { input ->
                val bytes = ByteArray(1024)
                val count = input.read(bytes)
                if (count > 0) String(bytes, 0, count).trim() else "heavyAudioObserved=pending"
            }
        }.getOrNull() ?: "heavyAudioObserved=unconfirmed"
        return "heavyAudioRequested=${if (heavyAudioRequested) "on" else "off"} modeWrite=$audioModeWriteState $observed"
    }

    fun start(context: Context) {
        if (!started.compareAndSet(false, true)) return
        heavyAudioRequested = heavyAudioSnapshots(context)
        if (!Build.MODEL.equals("FF-5000", true) || !Build.HARDWARE.equals("ac8227l", true) || Build.VERSION.SDK_INT != 27) {
            state = "disabled_non_ff5000_profile"; return
        }
        startedAt = SystemClock.elapsedRealtime()
        enabled = true
        RouteCaptureEvents.setSink { event -> if (enabled) routeEvents.offer(event) }
        AudioCaptureEvents.setSink { event -> if (enabled) audioEvents.offer(event) }
        val app = context.applicationContext
        worker = Thread({
            var mirrorLaunched = false
            try {
                val folder = File(app.filesDir, "startup-captures/${UUID.randomUUID()}")
                check(folder.mkdirs()) { "Cannot create capture directory" }
                synchronized(audioModeLock) {
                    directory = folder
                    if (!audioModeWritesClosed && !stopRequested.get()) writeAudioMode(folder)
                }
                File(folder, "app-metadata.txt").writeText(DiagnosticBuildInfo.report(app) +
                    "pid=${Process.myPid()} uid=${Process.myUid()} wallMs=${System.currentTimeMillis()} elapsedMs=${SystemClock.elapsedRealtime()}\n" +
                    "mode=app_start all_retained=true root_collection_does_not_block_connection=true ${audioModeStatus()}\n")
                mirrorWorker = Thread({ mirror(folder) }, "capture-app-writer").apply { isDaemon = true; start() }
                mirrorLaunched = true
                launch(app, folder)
            } catch (error: Exception) {
                state = "startup_failed ${error.javaClass.simpleName}: ${error.message}"
                if (!mirrorLaunched) { enabled = false; entries.disable(); AudioCaptureEvents.setSink(null); audioEvents.seal() }
            } finally { onCollectorExit() }
        }, "capture-startup").apply { isDaemon = true; start() }
    }

    fun append(message: String, wall: Long) {
        if (enabled) entries.offer("wallMs=$wall elapsedMs=${SystemClock.elapsedRealtime()} $message\n")
    }

    private fun mirror(folder: File) {
        try {
            File(folder, "app-full.log").outputStream().buffered(64 * 1024).use { output ->
                mirrorState = "writing"
                var lastDropped = 0L
                while (enabled || entries.pendingChars > 0 || routeEvents.size() > 0 || audioEvents.size() > 0) {
                    val audioBatch = audioEvents.drain()
                    if (audioBatch.isNotEmpty()) {
                        File(folder, "audio-events.log").appendText(audioBatch.joinToString("\n", postfix = "\n"))
                        audioBatch.forEach { output.write(("audio_event $it\n").toByteArray()) }
                        output.write(("audio_event_queue ${audioEvents.status()}\n").toByteArray())
                    }
                    val routeBatch = routeEvents.drain()
                    if (routeBatch.isNotEmpty()) {
                        // Separate journal stays in the private capture folder; root tails it independently.
                        File(folder, "route-events.log").appendText(
                            routeBatch.joinToString("\n", postfix = "\n") { it.encode() })
                        output.write(("route_event_queue ${routeEvents.status()}\n").toByteArray())
                    }
                    val item = entries.poll()
                    val lost = entries.dropped
                    if (lost != lastDropped) {
                        output.write("capture_queue_dropped_total=$lost\n".toByteArray()); lastDropped = lost
                    }
                    if (item != null) output.write(item.toByteArray())
                    // Flush each batch, not each protocol line. Export may omit at most this queued tail.
                    if (item == null) { output.flush(); Thread.sleep(100) }
                }
            }
            mirrorState = "flushed"
        } catch (error: Exception) {
            mirrorState = "failed ${error.javaClass.simpleName}: ${error.message}"
            enabled = false; entries.disable(); AudioCaptureEvents.setSink(null); audioEvents.seal()
        }
    }

    private fun library(context: Context, folder: File): File {
        val name = "libstartup_packet_capture.so"
        val installed = File(context.applicationInfo.nativeLibraryDir, name)
        if (installed.isFile) return installed
        // Some APKs map uncompressed libraries directly instead of extracting nativeLibraryDir.
        val abis = if (Process.is64Bit()) Build.SUPPORTED_64_BIT_ABIS else Build.SUPPORTED_32_BIT_ABIS
        ZipFile(context.applicationInfo.sourceDir).use { zip ->
            val entry = abis.firstNotNullOfOrNull { zip.getEntry("lib/$it/$name") }
                ?: error("No compatible bundled packet capture library")
            val target = File(folder, name)
            zip.getInputStream(entry).use { input -> target.outputStream().use { input.copyTo(it) } }
            return target
        }
    }

    private fun launch(context: Context, folder: File) {
        val native = library(context, folder)
        val pid = Process.myPid()
        val identity = StartupCaptureFiles.processStart(File("/proc/$pid/stat").readText())
        val appProcess = if (Process.is64Bit()) "/system/bin/app_process64" else "/system/bin/app_process32"
        val executable = if (File(appProcess).canExecute()) appProcess else "/system/bin/app_process"
        val methods = listOf(listOf("su", "@#zxcvbnmasdfghjklqwertyuiop1234567890,."))
        for ((index, args) in methods.withIndex()) {
            if (stopRequested.get()) return
            val attempt = CaptureRootLaunch.attemptDirectory(folder, index)
            check(attempt.mkdirs()) { "Cannot create root attempt directory" }
            attempts.add(attempt)
            rootDirectory = attempt
            if (stopRequested.get()) return
            val command = "CLASSPATH=${StartupCaptureFiles.quote(context.applicationInfo.sourceDir)} exec " +
                listOf(executable, "/system/bin", "com.shilapi.xcertplay.diagnostics.RootCaptureMain", attempt.path,
                    Process.myUid().toString(), pid.toString(), identity, native.path, File(folder, "route-events.log").path).joinToString(" ", transform = StartupCaptureFiles::quote) + "\n"
            val output = File(attempt, "root-launch.log")
            var child: java.lang.Process? = null
            try {
                state = "root_starting method=$index"
                child = ProcessBuilder(args).redirectErrorStream(true).redirectOutput(output).start()
                processes[attempt] = child
                child.outputStream.bufferedWriter().use { it.write(command) }
                if (stopRequested.get()) File(attempt, "stop").writeText("user requested stop\n")
                if (CaptureRootLaunch.awaitReady(child, attempt, 5000)) {
                    state = "root_running method=$index; packet health is recorded separately"
                    // Parent-bound collector owns subprocess cleanup. Keep launch process supervised.
                    val exit = child.waitFor()
                    state = "root_collector_exited code=$exit"
                    return
                }
                File(folder, "launch-status.log").appendText("method=$index no_root_ready exit=${if (child.isAlive) "timeout" else child.exitValue()}\n")
            } catch (error: Exception) {
                File(folder, "launch-status.log").appendText("method=$index failed=${error.javaClass.simpleName}: ${error.message}\n")
            } finally {
                // Even if su forks before a timeout, its late helper sees this cancellation.
                runCatching { File(attempt, "stop").writeText("launcher finished\n") }
                if (child != null && child.isAlive) {
                    if (!child.waitFor(8, java.util.concurrent.TimeUnit.SECONDS)) {
                        child.destroyForcibly()
                        state = "collector_stop_unconfirmed" // Export remains blocked until cleanup is confirmed.
                    }
                }
            }
        }
        state = "root_unavailable; detailed collection stopped; inspect root-launch logs"
    }

    fun report(): String {
        val folder = directory
        val marker = rootDirectory?.resolve("network.pcap.complete")
        val complete = runCatching { marker?.takeIf { it.isFile && it.length() <= 40 }?.readText()?.trim()?.toLongOrNull() }.getOrNull()
        return "Startup capture: $state; mirror=$mirrorState ${entries.status()} " +
            "directory=${folder?.name ?: "none"} pcapCompleteBytes=${complete ?: 0} " +
            "rootDirectory=${rootDirectory?.name ?: "none"} loopbackSelftest=${rootDirectory?.resolve("network.pcap.selftest")?.length()?.let { it > 0 } ?: false} " +
            "packetObserved=${complete != null && complete > 24} (may_include_loopback_selftest; not_hotspot_proof)\n" +
            "${bluetoothStatus()}\n${audioModeStatus()}\n" +
            "Capture export: all retained process directories, independent of session/date selection; full payloads; queued app tail may be absent.\n"
    }

    internal fun onCollectorExit() {
        if (!stopRequested.get()) stop()
    }

    fun needsStop(): Boolean = started.get() && startedAt != 0L && !stopped

    fun durationSeconds(): Long = if (startedAt == 0L) 0 else
        ((if (stopped) endedAt else SystemClock.elapsedRealtime()) - startedAt).coerceAtLeast(0) / 1000

    fun stop() {
        if (!needsStop() || !stopRequested.compareAndSet(false, true)) return
        val modeWriter = synchronized(audioModeScheduleLock) {
            audioModeWritesClosed = true
            audioModeWorker
        }
        Thread({
            val previousState = state
            state = "stopping"
            entries.seal()
            routeEvents.seal()
            audioEvents.seal()
            AudioCaptureEvents.setSink(null)
            RouteCaptureEvents.setSink(null)
            enabled = false
            try {
                // The launcher checks cancellation before every attempt and after spawning su.
                attempts.forEach { attempt ->
                    runCatching { attempt.resolve("stop").writeText("user requested stop\n") }
                        .onFailure { state = "stop token write failed: $it; checking actual cleanup" }
                }
                worker?.join(20_000)
                entries.seal()
                enabled = false
                mirrorWorker?.join(5000)
                if (mirrorWorker?.isAlive == true) { state = "stop_failed: application log flush not confirmed"; return@Thread }
                if (worker?.isAlive == true || processes.values.any { it.isAlive }) {
                    state = "stop_failed: collector shutdown not confirmed; application log capture ended"; return@Thread
                }
                if (attempts.any { it.resolve("root-ready").exists() && !it.resolve("collector-stopped").exists() }) {
                    state = "stop_failed: root cleanup not confirmed; application log capture ended"; return@Thread
                }
                modeWriter?.join(3000)
                if (modeWriter?.isAlive == true) {
                    state = "stop_failed: audio mode writer shutdown not confirmed"
                    return@Thread
                }
                // Root may stop before the app journal writer flushes. Preserve both boundaries.
                val finalJournalLength = directory?.resolve("audio-events.log")?.length() ?: 0L
                attempts.forEach { attempt ->
                    val cursor = runCatching {
                        attempt.resolve("audio-reader-stop.txt").useLines { it.firstOrNull()?.toLongOrNull() }
                    }.getOrNull() ?: -1L
                    directory?.resolve("audio-stop-final.txt")?.appendText(
                        "collector=${attempt.name} " + AudioCaptureEvents.boundary(cursor, finalJournalLength, "app_writer_and_root_stopped") + "\n")
                }
                endedAt = SystemClock.elapsedRealtime()
                stopped = true
                state = "stopped; files flushed; previous=$previousState"
            } catch (error: Exception) { state = "stop_failed: $error" }
            finally { if (!stopped) stopRequested.set(false) }
        }, "capture-stop").apply { isDaemon = true; start() }
    }

    private fun bluetoothStatus(): String = runCatching {
            rootDirectory?.resolve("bluetooth-status.txt")?.inputStream()?.use { input ->
                val bytes = ByteArray(2048)
                val count = input.read(bytes)
                if (count > 0) String(bytes, 0, count).trim() else "HCI pending"
            } ?: "HCI unavailable; root status not available"
        }.getOrDefault("HCI unavailable; root status not readable")

    fun health(): String {
        val bluetooth = bluetoothStatus() + "\n" + audioModeStatus()
        if (stopped) return "$state; appLog=$mirrorState\n$bluetooth"
        val file = rootDirectory?.resolve("root-launch.log")
        val tail = runCatching {
            if (file?.isFile != true) return@runCatching ""
            java.io.RandomAccessFile(file, "r").use {
                it.seek((it.length() - 4000).coerceAtLeast(0)); val bytes = ByteArray((it.length() - it.filePointer).toInt())
                it.readFully(bytes); String(bytes).lineSequence().filter { line ->
                    line.contains("health ") || line.contains("native_capture") || line.contains("unavailable") || line.contains("packet stopped")
                }.joinToString("\n")
            }
        }.getOrDefault("")
        val components = tail.lineSequence().lastOrNull { it.contains("health packetThread=") }?.substringAfter("health ")?.substringBefore(" freeBytes=") ?: "components=pending"
        val failure = tail.lineSequence().lastOrNull { it.contains("native_capture_unavailable") || it.contains("native_capture_exit") } ?: ""
        return "$state; appLog=$mirrorState\npcapReady=${rootDirectory?.resolve("network.pcap.complete")?.isFile == true} loopbackSelftest=${rootDirectory?.resolve("network.pcap.selftest")?.isFile == true}\n$components\n$bluetooth\n$failure"
    }
}
