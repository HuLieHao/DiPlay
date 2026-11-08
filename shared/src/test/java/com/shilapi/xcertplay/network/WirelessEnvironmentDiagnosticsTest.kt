package com.shilapi.xcertplay.network

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27, 33], manifest = Config.NONE)
class WirelessEnvironmentDiagnosticsTest {
    @Test fun boundedFileEvidenceRetainsOriginalAndReportsTruncationOrDenial() {
        val file = java.io.File.createTempFile("network-evidence", ".txt")
        try {
            file.writeText("phone=胡烈豪\n192.168.245.71")
            val full = WirelessEnvironmentDiagnostics.fileEvidence(file.path, 128)
            assertTrue(full, full.contains("phone=胡烈豪;192.168.245.71"))
            assertTrue(full, full.contains("truncated=false"))
            assertTrue(WirelessEnvironmentDiagnostics.fileEvidence(file.path, 5).contains("truncated=true"))
            file.delete()
            assertTrue(WirelessEnvironmentDiagnostics.fileEvidence(file.path).contains("unavailable"))
        } finally { file.delete() }
    }

    @Test fun missingNetworkEvidenceIsExplicitAndCannotBeMistakenForIphoneOrHotspotRadioState() {
        val result = WirelessEnvironmentDiagnostics.snapshot(RuntimeEnvironment.getApplication(), "ap0")
        assertTrue(result, result.contains("apInterface=ap0"))
        assertTrue(result, result.contains("source=WifiInfo_station_only not_hotspot_frequency=true"))
        assertTrue(result, result.contains("networks count="))
        assertTrue(result, result.contains("file=/proc/net/arp "))
        assertFalse(result, result.contains("iphoneInternet=true"))
        assertTrue(result, result.lines().size <= 24)
    }

    @Test fun deniedEvidenceDoesNotAbortOtherDiagnosticsAndPreservesFailureReason() {
        val result = WirelessEnvironmentDiagnostics.observe("station") { throw SecurityException("permission denied") }
        assertEquals("station unavailable failureClass=SecurityException message=permission denied", result)
        assertEquals("station ssid=redmi-hu", WirelessEnvironmentDiagnostics.observe("station") { "ssid=redmi-hu" })
    }
}
