package com.shilapi.xcertplay.backup

import java.io.ByteArrayOutputStream
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class StreamingReportResponseTest {
    @Test fun successfulResponseHasChunkedFramingAndExplicitCompletion() {
        val output = ByteArrayOutputStream()
        StreamingReportResponse.write(output, {}) { it.write("hello".toByteArray()) }
        val response = output.toString("UTF-8")
        assertTrue(response.contains("Transfer-Encoding: chunked\r\n"))
        assertFalse(response.contains("Content-Length:"))
        assertTrue(response.endsWith("5\r\nhello\r\n0\r\n\r\n"))
    }
    @Test fun producerFailureDoesNotMarkPartialZipAsACompleteDownload() {
        val output = ByteArrayOutputStream()
        assertThrows(IOException::class.java) {
            StreamingReportResponse.write(output, {}) {
                it.write("partial".toByteArray()); it.flush(); throw IOException("source failed")
            }
        }
        assertFalse(output.toString("UTF-8").endsWith("0\r\n\r\n"))
    }
    @Test fun failureBeforeOutputAllowsServerToSendAnErrorStatus() {
        var started = false
        val output = ByteArrayOutputStream()
        assertThrows(IOException::class.java) {
            StreamingReportResponse.write(output, { started = true }) { throw IOException("prepare failed") }
        }
        assertFalse(started)
        assertEquals(0, output.size())
    }
}
