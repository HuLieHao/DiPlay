package com.shilapi.xcertplay

/** Legacy call-site name: only bounds entry size; all captured content is retained without redaction. */
internal object DiagnosticRedactor {
    fun redact(line: String): String? = line.take(700)
}
