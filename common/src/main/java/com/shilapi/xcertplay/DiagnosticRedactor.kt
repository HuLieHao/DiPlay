package com.shilapi.xcertplay

/** Legacy call-site name: only bounds entry size; all captured content is retained without redaction. */
internal object DiagnosticRedactor {
    fun redact(line: String): String? {
        val marker = " [truncated bounded metadata line]"
        return if (line.length <= 700) line else line.take(700 - marker.length) + marker
    }

    fun original(line: String): String {
        val marker = " [truncated: original diagnostic entry exceeds 64KiB characters]"
        return if (line.length <= 64 * 1024) line else line.take(64 * 1024 - marker.length) + marker
    }
}
