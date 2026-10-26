package com.shilapi.xcertplay

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Queued entries retain only their exact file target and bounded original diagnostic text. */
internal object AsyncDiagnosticLog {
    private data class Entry(val target: SessionLogFile, val line: String?)
    private val writer = BoundedDiagnosticWriter<Entry> { writeEntry(it) }
    private var observedDrops = 0L
    private fun writeEntry(entry: Entry) {
        val dropped = writer.droppedCount
        if (dropped != observedDrops) {
            entry.target.append("DIAGNOSTIC_QUEUE droppedTotal=$dropped droppedSinceLast=${dropped - observedDrops}; oldest_pending_entries_lost=true")
            observedDrops = dropped
        }
        if (entry.line == null) entry.target.close() else entry.target.append(entry.line)
    }

    fun append(target: SessionLogFile?, message: String, nowMillis: Long = System.currentTimeMillis()) {
        if (target == null) return
        val safe = DiagnosticRedactor.original(message)
        val timestamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date(nowMillis))
        runCatching { writer.enqueue(Entry(target, "$timestamp  $safe")) }
    }

    fun close(target: SessionLogFile?) { if (target != null) writer.enqueue(Entry(target, null)) }
    fun status(): String = "queueCapacity=64 pending=${writer.pendingCount} dropped=${writer.droppedCount} oversizedEntries=explicitly_marked"

    internal fun awaitIdle(timeoutMillis: Long): Boolean = writer.awaitIdle(timeoutMillis)
}
