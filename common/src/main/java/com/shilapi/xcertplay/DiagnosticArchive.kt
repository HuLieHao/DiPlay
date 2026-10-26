package com.shilapi.xcertplay

import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.Calendar
import java.util.TimeZone
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Original evidence, bounded on disk. No network calls and no content filtering. */
internal class DiagnosticArchive(
    private val root: File,
    private val wall: () -> Long = System::currentTimeMillis,
    private val monotonic: () -> Long = { System.nanoTime() / 1_000_000 },
    private val detailBudget: Long = 128L * 1024 * 1024,
    private val reserve: Long = 32L * 1024 * 1024,
    private val partBytes: Long = 512L * 1024,
) {
    private val directory = File(root, "archive")
    private var current: String? = null
    private val parts = mutableMapOf<String, Int>()
    private var lastWall = wall()
    private var lastMono = monotonic()
    private var clockValid = validWall(lastWall)
    private var lastException = Long.MIN_VALUE
    private var lastCleanup = Long.MIN_VALUE
    private var attempt = "unknown"
    private var detailBytes = 0L
    private var summaryBytes = 0L

    fun start(reason: String): String = synchronized(lock) {
        directory.mkdirs()
        migrateLegacy()
        val id = UUID.randomUUID().toString()
        current = id; parts[id] = 0
        val metadata = "session=$id process=$processId wallMs=${wall()} timezone=${TimeZone.getDefault().id} monotonicMs=${monotonic()} clockValid=$clockValid reason=$reason\n"
        File(directory, "$id.meta").writeText(metadata)
        File(directory, "$id.summary").writeText(metadata + "Tail durability: asynchronous queue; sudden process/power loss may lose queued or filesystem-buffered tail.\n")
        cleanup()
        id
    }

    fun append(session: String, original: String) = synchronized(lock) {
        require(session.matches(sessionPattern))
        Regex("attempt(?:Id)?=([^ \n]+)").find(original)?.groupValues?.get(1)?.let { attempt = it }
        val now = wall(); val mono = monotonic()
        val delta = now - lastWall - (mono - lastMono)
        if (!validWall(now) || kotlin.math.abs(delta) > 300_000 || now < lastWall) {
            clockValid = false
            File(directory, "$session.meta").appendText("clockValid=false clock_jump=true\n")
            summary(session, "clock_jump wallMs=$now previousWallMs=$lastWall monotonicMs=$mono dateSelectionUnreliable=true")
        }
        lastWall = now; lastMono = mono
        if (directory.usableSpace < reserve) {
            cleanup(force = true)
            if (directory.usableSpace < reserve) {
                event("detail_dropped low_space session=$session")
                return@synchronized
            }
        }
        val text = original.take(MAX_LINE_CHARS) + if (original.length > MAX_LINE_CHARS) " [truncated oversized line]\n" else "\n"
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (detailBytes + bytes.size > detailBudget) {
            cleanup(detailLimit = (detailBudget - bytes.size).coerceAtLeast(0))
            if (detailBytes + bytes.size > detailBudget) {
                event("detail_dropped archive_budget_pinned_or_entry_too_large=true session=$session")
                return@synchronized
            }
        }
        var file = detail(session)
        val rotated = file.length() + bytes.size > partBytes && file.length() > 0
        if (rotated) { parts[session] = (parts[session] ?: 0) + 1; file = detail(session) }
        try {
            file.appendBytes(bytes)
            detailBytes += bytes.size
            if (isSummary(original)) summary(session, "wallMs=$now monotonicMs=$mono $text")
            if (isException(original) && (lastException == Long.MIN_VALUE || mono - lastException >= 60_000)) {
                lastException = mono
                val context = File(directory, "$session-${UUID.randomUUID()}.exception")
                context.outputStream().use { output ->
                    output.write("session=$session process=$processId wallMs=$now monotonicMs=$mono exceptionContext=last64KiB\n".toByteArray())
                    file.inputStream().use { input -> input.skip((file.length() - 64 * 1024).coerceAtLeast(0)); input.copyTo(output, 8192) }
                }
            }
            if (rotated || lastCleanup == Long.MIN_VALUE || mono - lastCleanup > 60_000) { cleanup(); lastCleanup = mono }
        } catch (failure: IOException) { event("write_failed session=$session type=${failure.javaClass.simpleName} partial_tail_possible=true") }
    }

    fun correlation(): String = synchronized(lock) { "process=$processId session=${current ?: "none"} attempt=$attempt wallMs=${wall()} monotonicMs=${monotonic()} timezone=${TimeZone.getDefault().id} clockValid=$clockValid" }

    fun capture(text: String, captureId: String, startingSession: String? = null) = synchronized(lock) {
        directory.mkdirs()
        val id = when {
            startingSession?.matches(sessionPattern) == true -> startingSession
            startingSession != null -> {
                val previous = current
                val detached = start("manual_network_capture_without_live_session")
                current = previous
                detached
            }
            else -> current ?: start("manual_network_capture")
        }
        if (!File(directory, "$id.meta").isFile)
            File(directory, "$id.meta").writeText("session=$id wallMs=0 clockValid=false reason=capture_start_metadata_unavailable\n")
        val file = File(directory, "$id-${UUID.randomUUID()}.network")
        file.writeText("captureId=$captureId captureSession=$id captureEnd ${correlation()}\n" + text.take(640 * 1024))
        cleanup()
    }

    /** Pins a fixed byte prefix of each selected file; cleanup cannot unlink it until ZIP completion. */
    fun export(destination: File, selection: String = "all", metadata: String): File {
        val selected: List<Pair<File, Long>>
        val cutoff: Long
        val valid: Boolean
        synchronized(lock) {
            require(selection in ranges || selection.matches(sessionPattern)) { "Invalid archive selection" }
            directory.mkdirs(); migrateLegacy(); cleanup()
            cutoff = wall(); valid = clockValid && validWall(cutoff)
            require(valid || selection == "all" || selection.matches(sessionPattern)) { "Device date is unreliable; choose all or a session" }
            val sessions = directory.listFiles().orEmpty().filter { it.extension == "meta" }.filter { meta ->
                when {
                    selection == "all" -> true
                    selection.matches(sessionPattern) -> meta.nameWithoutExtension == selection
                    else -> {
                        val time = Regex("wallMs=(\\d+)").find(meta.readText())?.groupValues?.get(1)?.toLongOrNull()
                        !meta.readText().contains("clockValid=false") && time != null && inRange(time, selection, cutoff)
                    }
                }
            }.map { it.nameWithoutExtension }.toSet()
            selected = directory.listFiles().orEmpty().filter { file ->
                file.isFile && (file.name == "maintenance.txt" || sessions.any { file.name.startsWith(it) })
            }.sortedBy { it.name }.map { it to it.length() }
            selected.forEach { (file, _) -> pins[file.absolutePath] = (pins[file.absolutePath] ?: 0) + 1 }
            // Subsequent writes use another detail part, never the snapshotted part.
            parts.keys.toList().forEach { id -> parts[id] = (parts[id] ?: 0) + 1 }
        }
        try {
            destination.parentFile?.mkdirs()
            val required = selected.sumOf { it.second } + metadata.length.toLong() * 3 + 128 * 1024
            if ((destination.parentFile?.usableSpace ?: root.usableSpace) - required < reserve)
                throw IOException("Insufficient space for export while preserving the diagnostic reserve")
            ZipOutputStream(destination.outputStream().buffered(32 * 1024)).use { zip ->
                val manifest = StringBuilder("format=1 selection=$selection cutoffWallMs=$cutoff clockValid=$valid clockValidity=local_plausibility_only_no_external_time_validation process=$processId timezone=${TimeZone.getDefault().id}\n" +
                    "detailBudgetBytes=$detailBudget detailDays=7 summaryBudgetBytes=4194304 summaryDays=14 exceptionCount=8 networkCount=3 networkMaxChars=655360 reserveBytes=$reserve\n" +
                    "rangeBasis=session_start_wall_time; for sessions spanning midnight choose all/session.\nConsistency: fixed byte prefixes; partial writes are preserved; queued tail may be missing. Unknown-date sessions are excluded from date ranges; use all/session selection.\n")
                for ((file, length) in selected) {
                    zip.putNextEntry(ZipEntry("logs/${file.name}"))
                    val digest = MessageDigest.getInstance("SHA-256")
                    var remaining = length; var copied = 0L; var error: String? = null
                    try {
                        file.inputStream().use { input ->
                            val buffer = ByteArray(32 * 1024)
                            while (remaining > 0) {
                                val count = input.read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt())
                                if (count < 0) throw IOException("short_read")
                                zip.write(buffer, 0, count); digest.update(buffer, 0, count)
                                remaining -= count; copied += count
                            }
                        }
                    } catch (failure: IOException) { error = failure.javaClass.simpleName }
                    zip.closeEntry()
                    manifest.append("file=logs/${file.name} selectedBytes=$length bytes=$copied sha256=${digest.digest().joinToString("") { "%02x".format(it) }} status=${error ?: "complete"}\n")
                }
                val diagnosticBytes = metadata.toByteArray(Charsets.UTF_8)
                zip.putNextEntry(ZipEntry("diagnostics.txt")); zip.write(diagnosticBytes); zip.closeEntry()
                manifest.append("file=diagnostics.txt bytes=${diagnosticBytes.size} sha256=${MessageDigest.getInstance("SHA-256").digest(diagnosticBytes).joinToString("") { "%02x".format(it) }} status=complete\n")
                zip.putNextEntry(ZipEntry("manifest.txt")); zip.write(manifest.toString().toByteArray(Charsets.UTF_8)); zip.closeEntry()
            }
            return destination
        } catch (failure: Exception) { destination.delete(); throw failure }
        finally { synchronized(lock) { selected.forEach { (file, _) -> val count = (pins[file.absolutePath] ?: 1) - 1; if (count == 0) pins.remove(file.absolutePath) else pins[file.absolutePath] = count }; cleanup() } }
    }

    fun sessions(): List<Pair<String, String>> = synchronized(lock) {
        directory.listFiles().orEmpty().filter { it.extension == "meta" }.sortedByDescending { it.lastModified() }
            .map { it.nameWithoutExtension to it.readText().lineSequence().first().take(512) }
    }

    private fun migrateLegacy() {
        val marker = File(directory, "legacy-migrated")
        if (marker.exists()) return
        val legacy = SessionLogFile.REPORT_NAMES.map { File(root, it) }.filter { it.isFile && it.length() > 0 }
        val networks = File(root.parentFile, "hotspot-network-diagnostics").listFiles().orEmpty()
            .filter { it.isFile && it.name in setOf("latest.txt", "previous-1.txt", "previous-2.txt") }
        if (legacy.isNotEmpty() || networks.isNotEmpty()) {
            val id = UUID.randomUUID().toString()
            File(directory, "$id.meta").writeText("session=$id process=legacy wallMs=0 clockValid=false reason=legacy_migration unknown_original_dates=true\n")
            File(directory, "$id.summary").writeText("Legacy retained logs migrated unchanged; dates and original session boundaries unknown; choose all or session.\n")
            legacy.forEach { it.copyTo(File(directory, "$id-legacy-${it.name}")) }
            networks.forEach { it.copyTo(File(directory, "$id-legacy-${it.nameWithoutExtension}.network")) }
        }
        marker.writeText("Legacy migration completed; original legacy files retained.\n")
    }

    private fun detail(session: String) = File(directory, "$session-$processId-${(parts[session] ?: 0).toString().padStart(6, '0')}.log")
    private fun summary(session: String, text: String) {
        var file = File(directory, "$session.summary")
        var index = 1
        while (file.length() >= 256 * 1024) file = File(directory, "$session-summary-${index++}.summary")
        val value = if (text.length <= 8192) "$text\n" else text.take(8192) + " [truncated summary excerpt; original in details]\n"
        val bytes = value.toByteArray(Charsets.UTF_8)
        if (summaryBytes + bytes.size > 4L * 1024 * 1024) cleanup(summaryLimit = 4L * 1024 * 1024 - bytes.size)
        if (summaryBytes + bytes.size > 4L * 1024 * 1024) { event("summary_dropped pinned_budget session=$session"); return }
        file.appendBytes(bytes)
        summaryBytes += bytes.size
    }
    private fun event(text: String) { runCatching {
        val file = File(directory, "maintenance.txt")
        if (file.length() > 64 * 1024 && file.absolutePath in pins) return@runCatching
        if (file.length() > 64 * 1024) file.writeText("maintenance_rotated oldest_events_removed=true\n")
        file.appendText("wallMs=${wall()} $text\n")
    } }
    private fun cleanup(force: Boolean = false, detailLimit: Long = detailBudget, summaryLimit: Long = 4L * 1024 * 1024) {
        val all = directory.listFiles().orEmpty().filter { it.isFile }.sortedBy { it.lastModified() }
        fun prune(extension: String, budget: Long, days: Int, count: Int = Int.MAX_VALUE) {
            val files = all.filter { it.extension == extension }; var bytes = files.sumOf { it.length() }; var size = files.size
            for (file in files) {
                val meta = File(directory, file.name.take(36) + ".meta")
                val metadata = if (meta.isFile) meta.readText() else ""
                val created = Regex("wallMs=(\\d+)").find(metadata)?.groupValues?.get(1)?.toLongOrNull()
                val expired = clockValid && !metadata.contains("clockValid=false") && validWall(wall()) && created != null && wall() >= created && wall() - created > days * DAY
                if ((force || expired || bytes > budget || size > count) && file.absolutePath !in pins) {
                    val length = file.length()
                    if (file.delete()) {
                        if (extension == "log") {
                            val id = file.name.take(36)
                            if (parts.containsKey(id) && detail(id) == file) parts[id] = (parts[id] ?: 0) + 1
                        }
                        bytes -= length; size--; event("cleanup file=${file.name} bytes=$length reason=${if (expired) "age" else "budget_or_space"}") }
                    else event("cleanup_failed file=${file.name}")
                }
            }
            if (extension == "log") detailBytes = bytes
            if (extension == "summary") summaryBytes = bytes
        }
        prune("log", detailLimit, 7)
        prune("summary", summaryLimit, 14)
        prune("exception", 8L * 66 * 1024, 14, 8)
        prune("network", 3L * 3 * 640 * 1024, 7, 3)
        // Metadata lives exactly as long as its evidence; interrupted starts do not accumulate forever.
        all.filter { it.extension == "meta" && it.nameWithoutExtension != current }.forEach { meta ->
            if (directory.listFiles().orEmpty().none { it != meta && it.name.startsWith(meta.nameWithoutExtension) }) meta.delete()
        }
        parts.keys.removeAll { it != current && !File(directory, "$it.meta").isFile }
    }
    private fun inRange(time: Long, selection: String, now: Long): Boolean {
        val calendar = Calendar.getInstance().apply { timeInMillis = now; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
        val end = if (selection == "yesterday") calendar.timeInMillis else now + 1
        calendar.add(Calendar.DAY_OF_YEAR, when (selection) { "yesterday" -> -1; "7days" -> -6; else -> 0 })
        return time >= calendar.timeInMillis && time < end
    }
    companion object {
        private val lock = Any()
        private val pins = mutableMapOf<String, Int>()
        private val processId = UUID.randomUUID().toString()
        private val sessionPattern = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        private val ranges = setOf("today", "yesterday", "7days", "all")
        private const val DAY = 86_400_000L
        private const val MAX_LINE_CHARS = 64 * 1024
        private fun validWall(time: Long) = time in 1_577_836_800_000L..4_102_444_800_000L
        private fun isSummary(text: String) = text.contains("DIAGNOSTIC_QUEUE") || text.contains("CONNECTION_DIAGNOSTIC") || text.contains("session", true) || isException(text) || text.contains("first", true) || text.contains("retry", true) || text.contains("stat", true)
        private val exceptionPattern = Regex("(?i)\\bERROR\\b(?!=0\\b)|\\bfailed\\b|\\btimeout\\b|\\btimed_out\\b|\\babnormal\\b|\\bunexpected\\b|\\b[A-Za-z]+Exception\\b|underruns?=\\+?[1-9][0-9]*")
        private fun isException(text: String): Boolean = !text.contains("TRACE ") && exceptionPattern.containsMatchIn(text)
        private val initializedExportDirectories = mutableSetOf<String>()
        fun newExportFile(directory: File, versionCode: Long): File = synchronized(lock) {
            directory.mkdirs()
            if (initializedExportDirectories.add(directory.absolutePath)) {
                directory.listFiles().orEmpty().filter { it.isFile && it.name.startsWith("BuickPlay-code") && it.extension == "zip" }.forEach { it.delete() }
            }
            File.createTempFile("BuickPlay-code$versionCode-", ".zip", directory)
        }
        private val instances = mutableMapOf<String, DiagnosticArchive>()
        fun forLogs(directory: File): DiagnosticArchive = synchronized(lock) { instances.getOrPut(directory.absolutePath) { DiagnosticArchive(directory) } }
    }
}
