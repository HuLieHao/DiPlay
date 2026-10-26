package com.shilapi.xcertplay.backup

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import org.junit.Assert.*
import org.junit.Test

class FileReportResponseTest {
    @Test fun archiveDownloadUsesZipMimeType() {
        val output = ByteArrayOutputStream()
        FileReportResponse.write(ByteArrayInputStream(byteArrayOf(1)), output, 1, "report.zip") {}
        assertTrue(output.toString("UTF-8").contains("Content-Type: application/zip"))
    }
    @Test fun firstReadFailureDoesNotCommitSuccessHeaders() {
        val input = object : InputStream() { override fun read(): Int = throw IOException("read failed") }
        val output = ByteArrayOutputStream()
        var committed = false
        val failure = runCatching {
            FileReportResponse.write(input, output, 10, "report.txt") { committed = true }
        }.exceptionOrNull()
        assertTrue(failure is IOException)
        assertFalse(committed)
        assertEquals(0, output.size())
    }

    @Test fun truncatedSnapshotThrowsInsteadOfCompletingTheAdvertisedBody() {
        val output = ByteArrayOutputStream()
        var committed = false
        val failure = runCatching {
            FileReportResponse.write(ByteArrayInputStream("abc".toByteArray()), output, 9, "report.txt") { committed = true }
        }.exceptionOrNull()
        assertTrue(failure is IOException)
        assertTrue(committed)
    }

    @Test fun bytesAppendedAfterSnapshotBoundaryAreNotIncluded() {
        val output = ByteArrayOutputStream()
        FileReportResponse.write(ByteArrayInputStream("abc-new-data".toByteArray()), output, 3, "report.txt") {}
        val response = output.toString("UTF-8")
        assertTrue(response.contains("Content-Length: 3\r\n"))
        assertEquals("abc", response.substringAfter("\r\n\r\n"))
    }
}
