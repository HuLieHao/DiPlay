package com.shilapi.xcertplay

import java.io.Closeable
import java.io.File

/** Bounded, private diagnostics. Captured text is stored without content filtering. */
internal class SessionLogFile(
    val file: File,
    private val initialHeader: String? = null,
    private val recordingBuild: Lazy<String> = lazy { "" },
    private val startupId: String? = null,
) : Closeable {
    private val lock = Any()
    private var closed = false
    private val archive = DiagnosticArchive.forLogs(file.parentFile!!)
    private var session: String? = null
    private val identity = java.util.UUID.randomUUID().toString()
    private val admittedWall = System.currentTimeMillis()
    private val admittedMono = System.nanoTime() / 1_000_000
    val isConnectionRun: Boolean get() = startupId != null

    /** No disk operations: immutable targets remain owned by their original callbacks. */
    fun connectionRun(generation: Int): SessionLogFile = SessionLogFile(file,
        "kind=controller_connection_run controllerGeneration=$generation startup=${startupId ?: identity}",
        recordingBuild, startupId ?: identity).also { it.activate() }

    fun activate() { archive.activate(session ?: identity) }

    fun reset(header: String) = synchronized(lock) {
        if (!closed) {
            file.parentFile?.mkdirs()
            session = archive.start(header)
            rotate()
            file.writeText("")
            append(header)
        }
    }
    fun append(line: String) = synchronized(lock) {
        if (closed) return@synchronized
        val safe = DiagnosticRedactor.original(line)
        runCatching {
            val id = session ?: archive.start(
                (initialHeader ?: "append_without_reset") + " " + recordingBuild.value.replace('\n', ' '),
                id = identity, startedWall = admittedWall, startedMono = admittedMono,
                makeCurrent = initialHeader == null,
            ).also { session = it }
            runCatching { archive.append(id, safe) }
            if (file.length() > MAX_BYTES) {
                rotate()
                file.writeText("")
            }
            val mirror = if (safe.length <= 700) safe else safe.take(655) + " [truncated legacy mirror; see session archive]"
            archive.appendLegacyMirror(file, mirror + "\n")
        }
        Unit
    }
    private fun rotate() {
        if (!file.exists() || file.length() == 0L) return
        for (index in ARCHIVE_NAMES.lastIndex downTo 1) {
            val source = File(file.parentFile, ARCHIVE_NAMES[index - 1])
            val destination = File(file.parentFile, ARCHIVE_NAMES[index])
            if (source.exists()) archive.rotateLegacyMirror(source, destination)
        }
        archive.rotateLegacyMirror(file, File(file.parentFile, ARCHIVE_NAMES.first()))
    }
    override fun close() = synchronized(lock) {
        if (!closed) session?.let { archive.append(it, "session_closed reason=writer_closed") }
        closed = true
    }
    companion object {
        const val MAX_BYTES = 512 * 1024L
        private val ARCHIVE_NAMES = listOf("previous.log") + (2..7).map { "previous-$it.log" }
        val REPORT_NAMES = ARCHIVE_NAMES.reversed() + "diplay.log"
    }
}
