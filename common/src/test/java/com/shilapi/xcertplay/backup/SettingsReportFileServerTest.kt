package com.shilapi.xcertplay.backup

import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class SettingsReportFileServerTest {
    private fun server(report: () -> File) = SettingsBackupServer(
        page = "page".toByteArray(), export = { ByteArray(0) },
        import = { SettingsImportOutcome(0, 0, invalid = false) }, reportFile = report,
    )

    private fun request(endpoint: SettingsBackupEndpoint, path: String = "/b/${endpoint.token}/report") =
        (URL("http://127.0.0.1:${endpoint.port}$path").openConnection() as HttpURLConnection).apply {
            connectTimeout = 3000; readTimeout = 5000
        }

    private fun awaitDeleted(file: File) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (file.exists() && System.nanoTime() < deadline) Thread.sleep(5)
        assertFalse("temporary report survives completed transfer", file.exists())
    }

    @Test fun streamsWholeUtf8FileWithLengthAndDeletesOnlyItsSnapshot() {
        val directory = Files.createTempDirectory("report-file-test").toFile()
        val file = directory.resolve("BuickPlay-code47-trip-abc.txt")
        val line = "navigation 音乐稳定性 0123456789\n"
        file.bufferedWriter().use { out -> repeat(60_000) { out.write(line) } }
        val size = file.length()
        val original = directory.resolve("original.log").apply { writeText("must remain") }
        val server = server { file }; val endpoint = server.start()!!
        try {
            val connection = request(endpoint)
            assertEquals(200, connection.responseCode)
            assertEquals(size.toString(), connection.getHeaderField("Content-Length"))
            assertEquals("attachment; filename=\"BuickPlay-code47-trip-abc.txt\"", connection.getHeaderField("Content-Disposition"))
            connection.inputStream.bufferedReader().use { input ->
                repeat(60_000) { assertEquals(line.trimEnd(), input.readLine()) }
                assertNull(input.readLine())
            }
            connection.disconnect()
            awaitDeleted(file)
            assertEquals("must remain", original.readText())
        } finally { server.stop(); directory.deleteRecursively() }
    }

    @Test fun unauthorizedOrWrongMethodNeverGeneratesFile() {
        val builds = AtomicInteger()
        val server = server { builds.incrementAndGet(); error("must not generate") }
        val endpoint = server.start()!!
        try {
            for (path in listOf("/report", "/b/wrong/report")) {
                val connection = request(endpoint, path)
                assertEquals(404, connection.responseCode); connection.disconnect()
            }
            val connection = request(endpoint).apply { requestMethod = "POST" }
            assertEquals(404, connection.responseCode); connection.disconnect()
            assertEquals(0, builds.get())
        } finally { server.stop() }
    }

    @Test fun generationFailureReturnsErrorAndReleasesGateForRetry() {
        val directory = Files.createTempDirectory("report-retry").toFile()
        val builds = AtomicInteger()
        val server = server {
            if (builds.incrementAndGet() == 1) error("disk full")
            directory.resolve("retry.txt").apply { writeText("complete") }
        }
        val endpoint = server.start()!!
        try {
            val failed = request(endpoint)
            assertEquals(500, failed.responseCode); failed.disconnect()
            val retry = request(endpoint)
            assertEquals(200, retry.responseCode)
            assertEquals("complete", retry.inputStream.bufferedReader().use { it.readText() }); retry.disconnect()
        } finally { server.stop(); directory.deleteRecursively() }
    }

    @Test fun concurrentReportReturnsBusyWithoutStartingSecondGeneration() {
        val directory = Files.createTempDirectory("report-busy").toFile()
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val builds = AtomicInteger()
        val server = server {
            builds.incrementAndGet(); entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            directory.resolve("one.txt").apply { writeText("complete") }
        }
        val endpoint = server.start()!!
        val first = FutureTask {
            val c = request(endpoint)
            try { c.responseCode to c.inputStream.bufferedReader().use { it.readText() } } finally { c.disconnect() }
        }
        val worker = Thread(first).apply { start() }
        try {
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            val second = request(endpoint)
            assertEquals(503, second.responseCode); second.disconnect()
            assertEquals(1, builds.get())
            release.countDown()
            assertEquals(200 to "complete", first.get(5, TimeUnit.SECONDS))
        } finally { release.countDown(); server.stop(); worker.join(5000); directory.deleteRecursively() }
    }

    @Test fun stoppingDuringGenerationCleansSnapshotWhenProducerFinishes() {
        val directory = Files.createTempDirectory("report-stop").toFile()
        val file = directory.resolve("stopped.txt")
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val produced = CountDownLatch(1)
        val server = server {
            entered.countDown(); check(release.await(5, TimeUnit.SECONDS))
            file.apply { writeText("should not leak") }.also { produced.countDown() }
        }
        val endpoint = server.start()!!
        val worker = Thread { runCatching { request(endpoint).apply { responseCode; disconnect() } } }.apply { start() }
        try {
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            server.stop(); release.countDown(); worker.join(5000)
            assertTrue(produced.await(3, TimeUnit.SECONDS))
            awaitDeleted(file)
        } finally { release.countDown(); server.stop(); worker.join(5000); directory.deleteRecursively() }
    }
    @Test fun clientResetDuringLargeTransferDeletesSnapshotAndAllowsAnotherDownload() {
        val directory = Files.createTempDirectory("report-reset").toFile()
        val firstFile = directory.resolve("large.txt")
        val builds = AtomicInteger()
        val server = server {
            if (builds.incrementAndGet() == 1) {
                java.io.RandomAccessFile(firstFile, "rw").use { it.setLength(64L * 1024 * 1024) }
                firstFile
            } else directory.resolve("retry.txt").apply { writeText("retry succeeded") }
        }
        val endpoint = server.start()!!
        try {
            java.net.Socket("127.0.0.1", endpoint.port).use { socket ->
                socket.soTimeout = 3000
                socket.getOutputStream().write(("GET /b/${endpoint.token}/report HTTP/1.1\r\n" +
                    "Host: localhost\r\nConnection: close\r\n\r\n").toByteArray())
                assertEquals('H'.code, socket.getInputStream().read())
                socket.setSoLinger(true, 0) // A reset while most of the 64MB body is still unsent.
            }
            awaitDeleted(firstFile)
            val retry = request(endpoint)
            assertEquals(200, retry.responseCode)
            assertEquals("retry succeeded", retry.inputStream.bufferedReader().use { it.readText() })
            retry.disconnect()
        } finally { server.stop(); directory.deleteRecursively() }
    }

    @Test fun aDirectoryIsNotASnapshotAndIsNotDeleted() {
        val directory = Files.createTempDirectory("report-not-file").toFile()
        val server = server { directory }; val endpoint = server.start()!!
        try {
            val connection = request(endpoint)
            assertEquals(500, connection.responseCode); connection.disconnect()
            assertTrue(directory.isDirectory)
        } finally { server.stop(); directory.deleteRecursively() }
    }

    @Test fun unsafeSnapshotNameCannotInjectResponseHeaders() {
        val directory = Files.createTempDirectory("report-name").toFile()
        val file = directory.resolve("name\r\nX-Injected: yes.txt").apply { writeText("complete") }
        val server = server { file }; val endpoint = server.start()!!
        try {
            val connection = request(endpoint)
            assertEquals(200, connection.responseCode)
            assertEquals("attachment; filename=\"BuickPlay-report.txt\"", connection.getHeaderField("Content-Disposition"))
            assertNull(connection.getHeaderField("X-Injected"))
            assertEquals("complete", connection.inputStream.bufferedReader().use { it.readText() })
            connection.disconnect(); awaitDeleted(file)
        } finally { server.stop(); directory.deleteRecursively() }
    }

}
