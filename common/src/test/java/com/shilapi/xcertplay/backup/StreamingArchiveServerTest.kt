package com.shilapi.xcertplay.backup

import com.shilapi.xcertplay.DiagnosticArchive
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.util.zip.ZipInputStream
import org.junit.Assert.*
import org.junit.Test

class StreamingArchiveServerTest {
    @Test fun nonReadingPhoneTimesOutAndReleasesPauseForTheNextDownload() {
        val root = Files.createTempDirectory("phone-stall").toFile()
        val entered = java.util.concurrent.CountDownLatch(1)
        val released = java.util.concurrent.CountDownLatch(1)
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val server = SettingsBackupServer(page = byteArrayOf(), export = { byteArrayOf() },
            import = { SettingsImportOutcome(0, 0, false) }, archiveStream = { _, output ->
                if (calls.incrementAndGet() == 1) {
                    try {
                        run {
                            entered.countDown()
                            val block = ByteArray(32 * 1024)
                            repeat(4096) { output.write(block); output.flush() }
                        }
                    } finally { released.countDown() }
                } else output.write("ok".toByteArray())
            })
        server.reportWriteTimeoutMillis = 250
        try {
            val endpoint = checkNotNull(server.start())
            java.net.Socket().use { socket ->
                socket.receiveBufferSize = 1024
                socket.connect(java.net.InetSocketAddress("127.0.0.1", endpoint.port))
                socket.getOutputStream().write("GET /b/${endpoint.token}/report HTTP/1.1\r\nHost: localhost\r\n\r\n".toByteArray())
                assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
                assertTrue("stalled write must abort", released.await(8, java.util.concurrent.TimeUnit.SECONDS))
            }
            val connection = URL("http://127.0.0.1:${endpoint.port}/b/${endpoint.token}/report").openConnection() as HttpURLConnection
            connection.readTimeout = 5000
            assertEquals(200, connection.responseCode)
            assertEquals("ok", connection.inputStream.reader().readText())
            connection.disconnect()
        } finally { server.stop(); root.deleteRecursively() }
    }
    @Test fun phoneDownloadStreamsCompleteZipWithoutStagingAndSurvivesIdleDeadline() {
        val root = Files.createTempDirectory("phone-stream").toFile()
        var called = 0
        val logs = root.resolve("logs").apply { mkdirs() }
        val capture = root.resolve("startup-captures/run").apply { mkdirs() }
        val evidence = ByteArray(512 * 1024) { (it % 241).toByte() }
        capture.resolve("evidence.log").writeBytes(evidence)
        val server = SettingsBackupServer(page = byteArrayOf(), export = { byteArrayOf() },
            import = { SettingsImportOutcome(0, 0, false) },
            archiveReport = { error("file staging must not be called") },
            archiveStream = { selection, out ->
                called++
                Thread.sleep(1500) // Longer than idle timeout, while the authenticated download is active.
                DiagnosticArchive(logs, reserve = Long.MAX_VALUE, usableSpace = { 0L }).exportTo(out, selection, "stream")
            })
        server.idleTimeoutMillis = 1000
        try {
            val endpoint = checkNotNull(server.start())
            val bad = URL("http://127.0.0.1:${endpoint.port}/b/wrong/report").openConnection() as HttpURLConnection
            assertEquals(404, bad.responseCode); bad.disconnect()
            assertEquals(0, called)
            val connection = URL("http://127.0.0.1:${endpoint.port}/b/${endpoint.token}/report?selection=all").openConnection() as HttpURLConnection
            connection.readTimeout = 5000
            assertEquals(200, connection.responseCode)
            assertEquals("chunked", connection.getHeaderField("Transfer-Encoding"))
            var found = false
            ZipInputStream(connection.inputStream).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.name.endsWith("evidence.log")) { assertArrayEquals(evidence, zip.readBytes()); found = true }
                }
            }
            connection.disconnect()
            assertTrue(found)
            assertTrue(root.walkTopDown().none { it.extension == "zip" })
            assertArrayEquals(evidence, capture.resolve("evidence.log").readBytes())
        } finally { server.stop(); root.deleteRecursively() }
    }
}
