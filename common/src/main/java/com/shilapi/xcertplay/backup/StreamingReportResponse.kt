package com.shilapi.xcertplay.backup

import java.io.OutputStream

/** No staging file. A failed producer never emits the terminal HTTP chunk. */
internal object StreamingReportResponse {
    fun write(output: OutputStream, onHeadersStarted: () -> Unit, produce: (OutputStream) -> Unit) {
        var started = false
        fun start() {
            if (started) return
            started = true; onHeadersStarted()
            output.write(("HTTP/1.1 200 OK\r\nContent-Type: application/zip\r\n" +
                "Content-Disposition: attachment; filename=\"BuickPlay-report.zip\"\r\n" +
                "Transfer-Encoding: chunked\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n").toByteArray(Charsets.US_ASCII))
        }
        val chunks = object : OutputStream() {
            override fun write(value: Int) = write(byteArrayOf(value.toByte()), 0, 1)
            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                if (length == 0) return
                start()
                output.write((length.toString(16) + "\r\n").toByteArray(Charsets.US_ASCII))
                output.write(bytes, offset, length); output.write(byteArrayOf(13, 10))
            }
            override fun flush() = output.flush()
            override fun close() = flush()
        }.buffered(32 * 1024)
        produce(chunks)
        chunks.flush()
        start()
        output.write("0\r\n\r\n".toByteArray(Charsets.US_ASCII)); output.flush()
    }
}
