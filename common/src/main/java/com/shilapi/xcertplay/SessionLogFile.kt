package com.shilapi.xcertplay

import java.io.Closeable
import java.io.File

/** Bounded, private diagnostics. Captured text is stored without content filtering. */
internal class SessionLogFile(val file: File) : Closeable {
    private val lock = Any()
    private var closed = false
    private val archive = DiagnosticArchive.forLogs(file.parentFile!!)
    private var session: String? = null
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
            val id = session ?: archive.start("append_without_reset").also { session = it }
            runCatching { archive.append(id, safe) }
            if (file.length() > MAX_BYTES) {
                rotate()
                file.writeText("")
            }
            val mirror = if (safe.length <= 700) safe else safe.take(655) + " [truncated legacy mirror; see session archive]"
            file.appendText(mirror + "\n")
        }
        Unit
    }
    private fun rotate() {
        if (!file.exists() || file.length() == 0L) return
        for (index in ARCHIVE_NAMES.lastIndex downTo 1) {
            val source = File(file.parentFile, ARCHIVE_NAMES[index - 1])
            val destination = File(file.parentFile, ARCHIVE_NAMES[index])
            if (source.exists()) source.copyTo(destination, overwrite = true)
        }
        file.copyTo(File(file.parentFile, ARCHIVE_NAMES.first()), overwrite = true)
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
