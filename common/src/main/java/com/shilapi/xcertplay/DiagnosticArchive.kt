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
    private val usableSpace: () -> Long = { root.usableSpace },
) {
    private val directory = File(root, "archive")
    private data class ActiveRun(val session: String?, val attempt: String = "unknown")
    private val activeRun = java.util.concurrent.atomic.AtomicReference(ActiveRun(null))
    private var current: String?
        get() = activeRun.get().session
        set(value) { activeRun.set(ActiveRun(value)) }
    private val parts = mutableMapOf<String, Int>()
    private var lastWall = wall()
    private var lastMono = monotonic()
    private var clockValid = validWall(lastWall)
    private var lastException = Long.MIN_VALUE
    private var lastCleanup = Long.MIN_VALUE
    private var detailBytes = 0L
    private var summaryBytes = 0L
    private val invalidClockRecorded = mutableSetOf<String>()
    private val pendingStarts = linkedMapOf<String, ByteArray>()
    private var pendingStartsDropped = 0L
    private var metadataEntriesDropped = 0L
    private var reportedStartDrops = 0L
    private var reportedEntryDrops = 0L

    // Assignment only: callers may admit a run on the UI thread while the writer is busy.
    fun activate(session: String) {
        activeRun.updateAndGet { if (it.session == session) it else ActiveRun(session) }
    }

    fun start(reason: String, id: String = UUID.randomUUID().toString(), startedWall: Long = wall(),
        startedMono: Long = monotonic(), makeCurrent: Boolean = true): String = synchronized(lock) {
        directory.mkdirs()
        migrateLegacy()
        require(id.matches(sessionPattern))
        if (makeCurrent) current = id
        parts[id] = 0
        val metadata = "session=$id process=$processId wallMs=$startedWall timezone=${TimeZone.getDefault().id} monotonicMs=$startedMono clockValid=${clockValid && validWall(startedWall)} reason=${reason.take(1024)}\n"
        pendingStarts[id] = metadata.toByteArray()
        if (pendingStarts.size > 64) {
            pendingStarts.remove(pendingStarts.keys.first())
            pendingStartsDropped++
        }
        ensureMetadata(id)
        if (!clockValid) invalidClockRecorded.add(id)
        cleanup()
        id
    }

    fun append(session: String, original: String) = synchronized(lock) {
        require(session.matches(sessionPattern))
        if (!ensureMetadata(session)) {
            metadataEntriesDropped++
            event("detail_dropped session_metadata_unavailable session=$session")
            return@synchronized
        }
        reportPendingLoss()
        Regex("attempt(?:Id)?=([^ \n]+)").find(original)?.groupValues?.get(1)?.let { attempt ->
            activeRun.updateAndGet { if (it.session == session) it.copy(attempt = attempt) else it }
        }
        val now = wall(); val mono = monotonic()
        val delta = now - lastWall - (mono - lastMono)
        if (!validWall(now) || kotlin.math.abs(delta) > 300_000 || now < lastWall) {
            clockValid = false
            if (invalidClockRecorded.add(session)) {
                val meta = File(directory, "$session.meta")
                if (meta.length() < MAX_METADATA_BYTES)
                    writeAdmitted(meta, "clockValid=false clock_jump=true\n".toByteArray(), append = true)
                summary(session, "clock_jump wallMs=$now previousWallMs=$lastWall monotonicMs=$mono dateSelectionUnreliable=true")
            }
        }
        lastWall = now; lastMono = mono
        val text = original.take(MAX_LINE_CHARS) + if (original.length > MAX_LINE_CHARS) " [truncated oversized line]\n" else "\n"
        val bytes = text.toByteArray(Charsets.UTF_8)
        // Summary admission is independent: detail pins/budget must never suppress critical events.
        if (isSummary(original)) summary(session, "wallMs=$now monotonicMs=$mono $text")
        if (!hasSpace(bytes.size.toLong())) cleanup(spaceNeeded = bytes.size.toLong())
        if (!hasSpace(bytes.size.toLong())) {
            event("detail_dropped low_space session=$session")
            return@synchronized
        }
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
            if (!writeAdmitted(file, bytes, append = true)) { event("detail_dropped low_space session=$session"); return@synchronized }
            detailBytes += bytes.size
            if (isException(original) && (lastException == Long.MIN_VALUE || mono - lastException >= 60_000)) {
                val header = "session=$session process=$processId wallMs=$now monotonicMs=$mono exceptionContext=last64KiB\n".toByteArray()
                val contextBytes = minOf(file.length(), 64L * 1024)
                if (hasSpace(header.size + contextBytes)) {
                    lastException = mono
                    val context = File(directory, "$session-${UUID.randomUUID()}.exception")
                    context.outputStream().use { output ->
                        output.write(header)
                        file.inputStream().use { input -> input.skip(file.length() - contextBytes); input.copyTo(output, 8192) }
                    }
                } else event("exception_dropped low_space session=$session")
            }
            if (rotated || lastCleanup == Long.MIN_VALUE || mono - lastCleanup > 60_000) { cleanup(); lastCleanup = mono }
        } catch (failure: IOException) { event("write_failed session=$session type=${failure.javaClass.simpleName} partial_tail_possible=true") }
    }

    fun appendLegacyMirror(file: File, text: String) = synchronized(lock) {
        if (!writeAdmitted(file, text.toByteArray(Charsets.UTF_8), append = true))
            event("legacy_mirror_dropped low_space file=${file.name}")
    }
    fun rotateLegacyMirror(source: File, destination: File) = synchronized(lock) {
        if (hasSpace(source.length())) source.copyTo(destination, overwrite = true)
        else event("legacy_rotation_dropped low_space file=${source.name}")
    }

    fun correlation(): String = synchronized(lock) {
        val run = activeRun.get()
        "process=$processId session=${run.session ?: "none"} attempt=${run.attempt} wallMs=${wall()} monotonicMs=${monotonic()} timezone=${TimeZone.getDefault().id} clockValid=$clockValid"
    }

    fun capture(text: String, captureId: String, startingSession: String? = null) = synchronized(lock) {
        directory.mkdirs()
        val id = when {
            startingSession?.matches(sessionPattern) == true -> startingSession
            startingSession != null -> start("manual_network_capture_without_live_session", makeCurrent = false)
            else -> current ?: start("manual_network_capture", makeCurrent = false)
        }
        if (!ensureMetadata(id)) {
            if (id in pendingStarts || !writeAdmitted(File(directory, "$id.meta"), "session=$id wallMs=0 clockValid=false reason=capture_start_metadata_unavailable\n".toByteArray()))
                return@synchronized false
        }
        val file = File(directory, "$id-${UUID.randomUUID()}.network")
        val bytes = ("captureId=$captureId captureSession=$id captureEnd ${correlation()}\n" + text.take(640 * 1024)).toByteArray(Charsets.UTF_8)
        if (!writeAdmitted(file, bytes)) { event("capture_dropped low_space captureId=$captureId session=$id"); return@synchronized false }
        cleanup()
        true
    }

    /** Pins a fixed byte prefix of each selected file; cleanup cannot unlink it until ZIP completion. */
    fun export(destination: File, selection: String = "all", metadata: String): File {
        val selected: List<Pair<File, Long>>
        val cutoff: Long
        val valid: Boolean
        synchronized(lock) {
            require(selection in ranges || selection.matches(sessionPattern)) { "Invalid archive selection" }
            directory.mkdirs(); migrateLegacy(); pendingStarts.keys.toList().forEach { ensureMetadata(it) }; reportPendingLoss(); cleanup()
            cutoff = wall(); valid = clockValid && validWall(cutoff)
            require(valid || selection == "all" || selection.matches(sessionPattern)) { "Device date is unreliable; choose all or a session" }
            val sessions = directory.listFiles().orEmpty().filter { it.extension == "meta" }.filter { meta ->
                when {
                    selection == "all" -> true
                    selection.matches(sessionPattern) -> meta.nameWithoutExtension == selection
                    else -> {
                        val time = Regex("wallMs=(\\d+)").find(readMetadata(meta))?.groupValues?.get(1)?.toLongOrNull()
                        !readMetadata(meta).contains("clockValid=false") && time != null && inRange(time, selection, cutoff)
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
                    "sessionUnit=controller_connection_run_or_host_setup; controller retries retain protocol attempt IDs; not an ACC/trip detector. Original recording build/startup identity is in session metadata.\nrangeBasis=session_start_wall_time; for sessions spanning midnight choose all/session.\nConsistency: fixed byte prefixes; partial writes are preserved; queued tail may be missing. Unknown-date sessions are excluded from date ranges; use all/session selection.\n")
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
            .map { it.nameWithoutExtension to readMetadata(it).lineSequence().first().take(512) }
    }

    private fun migrateLegacy() {
        val marker = File(directory, "legacy-migrated")
        if (marker.exists()) return
        val legacy = SessionLogFile.REPORT_NAMES.map { File(root, it) }.filter { it.isFile && it.length() > 0 }
        val networks = File(root.parentFile, "hotspot-network-diagnostics").listFiles().orEmpty()
            .filter { it.isFile && it.name in setOf("latest.txt", "previous-1.txt", "previous-2.txt") }
        if (legacy.isNotEmpty() || networks.isNotEmpty()) {
            // Reuse the incomplete migration's identity, including after process recreation.
            val id = directory.listFiles().orEmpty().firstOrNull {
                it.extension == "meta" && readMetadata(it).contains("reason=legacy_migration")
            }?.nameWithoutExtension ?: UUID.randomUUID().toString()
            val meta = File(directory, "$id.meta")
            if (!meta.isFile && !writeTextAdmitted(meta, "session=$id process=legacy wallMs=0 clockValid=false reason=legacy_migration unknown_original_dates=true\n")) return
            val summary = File(directory, "$id.summary")
            if (!summary.isFile && !writeTextAdmitted(summary, "Legacy retained logs migrated unchanged; dates and original session boundaries unknown; choose all or session.\n")) return
            var complete = true
            val copies = legacy.map { it to File(directory, "$id-legacy-${it.name}") } +
                networks.map { it to File(directory, "$id-legacy-${it.nameWithoutExtension}.network") }
            copies.forEach { (source, destination) ->
                if (!destination.isFile || destination.length() != source.length()) {
                    if (hasSpace(source.length())) {
                        try { source.copyTo(destination, overwrite = true) }
                        catch (failure: IOException) { destination.delete(); throw failure }
                    } else {
                        complete = false
                        event("legacy_migration_dropped low_space file=${source.name}")
                    }
                }
            }
            if (!complete) return
        }
        writeTextAdmitted(marker, "Legacy migration completed; original legacy files retained.\n")
    }

    private fun ensureMetadata(session: String): Boolean {
        val file = File(directory, "$session.meta")
        val metadata = pendingStarts[session]
        if (metadata != null) {
            if (!writeAdmitted(file, metadata)) return false
            summary(session, String(metadata, Charsets.UTF_8) +
                "Tail durability: asynchronous queue; sudden process/power loss may lose queued or filesystem-buffered tail. Metadata may be recovered after low space; missing entries are counted in maintenance.")
            pendingStarts.remove(session)
            reportPendingLoss()
        }
        return file.isFile
    }

    private fun hasSpace(bytes: Long): Boolean = bytes >= 0 && usableSpace() - bytes >= reserve
    private fun writeAdmitted(file: File, bytes: ByteArray, append: Boolean = false): Boolean {
        if (!hasSpace(bytes.size.toLong())) return false
        file.outputStreamIf(append).use { it.write(bytes) }
        return true
    }
    private fun File.outputStreamIf(append: Boolean) = java.io.FileOutputStream(this, append)
    private fun writeTextAdmitted(file: File, text: String) = writeAdmitted(file, text.toByteArray(Charsets.UTF_8))
    private fun readMetadata(file: File): String {
        val bytes = ByteArray(MAX_METADATA_BYTES)
        val count = file.inputStream().use { input -> var total = 0; while (total < bytes.size) { val n = input.read(bytes, total, bytes.size - total); if (n < 0) break; total += n }; total }
        return String(bytes, 0, count, Charsets.UTF_8) + if (file.length() > MAX_METADATA_BYTES) "\nclockValid=false oversized_legacy_metadata=true" else ""
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
        if (!writeAdmitted(file, bytes, append = true)) { event("summary_dropped low_space session=$session"); return }
        summaryBytes += bytes.size
    }
    private fun reportPendingLoss() {
        if (pendingStartsDropped == reportedStartDrops && metadataEntriesDropped == reportedEntryDrops) return
        if (event("metadata_loss_recovery pendingStartsDroppedTotal=$pendingStartsDropped " +
                "metadataUnavailableEntriesDroppedTotal=$metadataEntriesDropped " +
                "pendingStartCapacity=64 oldest_start_metadata_lost=true countsProcess=$processId")) {
            reportedStartDrops = pendingStartsDropped
            reportedEntryDrops = metadataEntriesDropped
        }
    }
    private fun event(text: String): Boolean = runCatching {
        val file = File(directory, "maintenance.txt")
        if (file.length() > 64 * 1024 && file.absolutePath in pins) return@runCatching false
        val value = if (file.length() > 64 * 1024) "maintenance_rotated oldest_events_removed=true\nwallMs=${wall()} $text\n" else "wallMs=${wall()} $text\n"
        writeAdmitted(file, value.toByteArray(), append = file.length() <= 64 * 1024)
    }.getOrDefault(false)
    private fun cleanup(detailLimit: Long = detailBudget, summaryLimit: Long = 4L * 1024 * 1024, spaceNeeded: Long = 0) {
        val all = directory.listFiles().orEmpty().filter { it.isFile }.sortedBy { it.lastModified() }
        fun prune(extension: String, budget: Long, days: Int, count: Int = Int.MAX_VALUE) {
            val files = all.filter { it.extension == extension }; var bytes = files.sumOf { it.length() }; var size = files.size
            for (file in files) {
                val meta = File(directory, file.name.take(36) + ".meta")
                val metadata = if (meta.isFile) readMetadata(meta) else ""
                val created = Regex("wallMs=(\\d+)").find(metadata)?.groupValues?.get(1)?.toLongOrNull()
                val expired = clockValid && !metadata.contains("clockValid=false") && validWall(wall()) && created != null && wall() >= created && wall() - created > days * DAY
                if ((expired || bytes > budget || size > count || extension == "log" && spaceNeeded > 0 && !hasSpace(spaceNeeded)) && file.absolutePath !in pins) {
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
        all.filter { it.extension == "meta" && it.nameWithoutExtension != current && it.nameWithoutExtension !in pendingStarts }.forEach { meta ->
            if (directory.listFiles().orEmpty().none { it != meta && it.name.startsWith(meta.nameWithoutExtension) }) meta.delete()
        }
        parts.keys.removeAll { it != current && it !in pendingStarts && !File(directory, "$it.meta").isFile }
        invalidClockRecorded.removeAll { it != current && !File(directory, "$it.meta").isFile }
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
        private const val MAX_METADATA_BYTES = 4096
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
        fun forLogs(directory: File): DiagnosticArchive = synchronized(instances) { instances.getOrPut(directory.absolutePath) { DiagnosticArchive(directory) } }
    }
}
