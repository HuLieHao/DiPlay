package com.shilapi.xcertplay

import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class DiagnosticReportFileTest {
    @Test fun createsClosedUtf8SnapshotsWithDistinctBuildIdentifiableNames() {
        val directory = Files.createTempDirectory("diagnostic-file").toFile().resolve("reports")
        try {
            val first = DiagnosticReportFile.create(directory, 47) { it.write("去程\n原始信息 token=secret\n") }
            val second = DiagnosticReportFile.create(directory, 47) { it.write("返程\n") }
            assertEquals("去程\n原始信息 token=secret\n", first.readText())
            assertEquals("返程\n", second.readText())
            assertNotEquals(first, second)
            assertTrue(first.name.startsWith("BuickPlay-code47-"))
            assertTrue(first.name.endsWith(".txt"))
        } finally { directory.parentFile.deleteRecursively() }
    }

    @Test fun failedGenerationRemovesPartialFileWithoutDeletingAnEarlierReport() {
        val directory = Files.createTempDirectory("diagnostic-partial").toFile()
        val previous = directory.resolve("previous.txt").apply { writeText("retained") }
        try {
            val failure = runCatching {
                DiagnosticReportFile.create(directory, 47) { it.write("partial"); error("disk failure") }
            }.exceptionOrNull()
            assertEquals("disk failure", failure?.message)
            assertEquals(listOf(previous), directory.listFiles()!!.toList())
            assertEquals("retained", previous.readText())
        } finally { directory.deleteRecursively() }
    }
}
