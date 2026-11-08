package com.shilapi.xcertplay

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class StartupCaptureHciStatusTest {
    @Test fun hciCoverageAppearsInBothLiveHealthAndExportReport() {
        val root = Files.createTempDirectory("hci-status").toFile()
        val field = StartupCapture::class.java.getDeclaredField("rootDirectory").apply { isAccessible = true }
        val previous = field.get(StartupCapture)
        try {
            field.set(StartupCapture, root)
            root.resolve("bluetooth-status.txt").writeText("HCI header_only; no complete records copied")
            assertTrue(StartupCapture.health().contains("HCI header_only"))
            assertTrue(StartupCapture.report().contains("HCI header_only"))
            root.resolve("bluetooth-status.txt").delete()
            assertTrue(StartupCapture.health().contains("HCI unavailable"))
        } finally { field.set(StartupCapture, previous); root.deleteRecursively() }
    }
}
