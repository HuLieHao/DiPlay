package com.shilapi.xcertplay.backup

import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream

/** Writes one owned report snapshot without retaining its body in memory. */
internal object FileReportResponse {
    fun write(input: InputStream, output: OutputStream, length: Long, name: String, onHeadersStarted: () -> Unit) {
        val filename = name.takeIf { it.matches(Regex("[A-Za-z0-9._-]{1,160}")) } ?: "BuickPlay-report.txt"
        val headers = "HTTP/1.1 200 OK\r\nContent-Type: text/plain; charset=utf-8\r\n" +
            "Content-Disposition: attachment; filename=\"$filename\"\r\n" +
            "Content-Length: $length\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n"
        require(length >= 0)
        val buffer = ByteArray(32 * 1024)
        var remaining = length
        // Read once before committing a 200: an immediate storage failure can still become HTTP500.
        var count = if (remaining > 0) input.read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt()) else -1
        if (remaining > 0 && count <= 0) throw EOFException("Report snapshot ended before its declared length")
        onHeadersStarted()
        output.write(headers.toByteArray(Charsets.US_ASCII))
        while (remaining > 0) {
            if (count <= 0) throw EOFException("Report snapshot ended before its declared length")
            output.write(buffer, 0, count)
            remaining -= count
            if (remaining > 0) count = input.read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt())
        }
        output.flush()
    }
}
