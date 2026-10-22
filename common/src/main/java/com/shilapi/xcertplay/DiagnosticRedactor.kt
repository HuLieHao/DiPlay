package com.shilapi.xcertplay

/** Keep original network/device identifiers; exclude credential and protocol-payload lines. */
internal object DiagnosticRedactor {
    private val secret = Regex("""(?i)(?:^|[\s,;{\[])(?:pass(?:word|phrase)?|pre.?shared.?key|psk|wep.?key|token|private.?key|certificate|pair.?record|authorization|cookie|body|payload|hex)\s*[:=]|\bcertificate\s+(?:bytes|data)\s*[:=]""")
    fun containsCredential(line: String): Boolean = secret.containsMatchIn(line)
    fun redact(line: String): String? {
        if (line.contains("TRACE ") || line.contains("PHONE ") || line.contains('\n') || line.contains('\r')) return null
        if (containsCredential(line)) return null
        return line.take(700)
    }
}
